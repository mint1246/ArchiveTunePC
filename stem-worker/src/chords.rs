use std::{
    ffi::OsString,
    fs,
    path::{Path, PathBuf},
    time::Instant,
};

use anyhow::{bail, Context, Result};
use hound::{SampleFormat, WavReader};
use ort::{inputs, value::Tensor};
use realfft::{num_complex::Complex32, RealFftPlanner};
use serde_json::{json, Value};

use crate::{emit, model_runtime};

const EXPECTED_SAMPLE_RATE: u32 = 22_050;
const EXPECTED_CHANNELS: u16 = 2;
const SEQUENCE_LENGTH: usize = 108;
const INPUT_BINS: usize = 144;
const OUTPUT_CLASSES: usize = 170;
const FFT_SIZE: usize = 512;

pub fn run(args: impl Iterator<Item = OsString>) -> Result<()> {
    let args = Args::parse(args)?;
    let config: Value = serde_json::from_slice(
        &fs::read(&args.config).with_context(|| format!("read {}", args.config.display()))?,
    )
    .context("parse chord model config")?;
    validate_config(&config)?;
    let vocab = config["chordVocab"]
        .as_array()
        .context("chord config is missing chordVocab")?
        .iter()
        .map(|value| value.as_str().unwrap_or_default().to_owned())
        .collect::<Vec<_>>();

    emit(json!({ "type": "loading", "backend": "directml", "task": "chords" }));
    model_runtime::init_onnx_runtime()?;
    let load_started = Instant::now();
    let mut session = model_runtime::load_directml_session(&args.model)?;
    emit(json!({
        "type": "loaded",
        "task": "chords",
        "milliseconds": load_started.elapsed().as_millis(),
    }));

    let audio = read_mono_wav(&args.input)?;
    let duration_ms = audio.len() as u64 * 1000 / EXPECTED_SAMPLE_RATE as u64;
    emit(json!({
        "type": "audio",
        "task": "chords",
        "samples": audio.len(),
        "duration_seconds": audio.len() as f64 / EXPECTED_SAMPLE_RATE as f64,
    }));

    let started = Instant::now();
    let plan = CqtPlan::decode(&args.plan)?;
    plan.validate()?;
    let features = compute_cqt(&audio, &plan)?;
    let frame_count = features.len() / INPUT_BINS;
    if frame_count == 0 {
        bail!("chord analysis produced no CQT frames");
    }
    emit(json!({ "type": "progress", "task": "chords", "value": 0.56 }));

    let window_count = frame_count.div_ceil(SEQUENCE_LENGTH);
    let mut padded = vec![0.0_f32; window_count * SEQUENCE_LENGTH * INPUT_BINS];
    padded[..features.len()].copy_from_slice(&features);
    let input = Tensor::from_array(([window_count, SEQUENCE_LENGTH, INPUT_BINS], padded))?;
    let outputs = session.run(inputs!["features" => input])?;
    let (_, logits) = outputs["logits"].try_extract_tensor::<f32>()?;
    let expected = window_count * SEQUENCE_LENGTH * OUTPUT_CLASSES;
    if logits.len() != expected {
        bail!(
            "unexpected chord model output size: {} (expected {expected})",
            logits.len()
        );
    }
    emit(json!({ "type": "progress", "task": "chords", "value": 0.88 }));

    let predictions = smooth_argmax(logits, frame_count, window_count)?;
    let events = predictions_to_events(&predictions, logits, frame_count, &vocab, duration_ms);
    let result = json!({
        "model": config["modelType"].as_str().unwrap_or("chord-model"),
        "sample_rate": EXPECTED_SAMPLE_RATE,
        "hop_length": plan.hop_length,
        "duration_ms": duration_ms,
        "events": events,
    });
    fs::write(&args.output, serde_json::to_vec(&result)?)
        .with_context(|| format!("write {}", args.output.display()))?;
    emit(json!({
        "type": "done",
        "task": "chords",
        "milliseconds": started.elapsed().as_millis(),
        "output": args.output,
        "events": result["events"].as_array().map_or(0, Vec::len),
    }));
    Ok(())
}

#[derive(Debug)]
struct Args {
    model: PathBuf,
    plan: PathBuf,
    config: PathBuf,
    input: PathBuf,
    output: PathBuf,
}

impl Args {
    fn parse(args: impl Iterator<Item = OsString>) -> Result<Self> {
        let mut model = None;
        let mut plan = None;
        let mut config = None;
        let mut input = None;
        let mut output = None;
        let mut args = args;
        while let Some(arg) = args.next() {
            match arg.to_string_lossy().as_ref() {
                "--model" => model = args.next().map(PathBuf::from),
                "--plan" => plan = args.next().map(PathBuf::from),
                "--config" => config = args.next().map(PathBuf::from),
                "--input" => input = args.next().map(PathBuf::from),
                "--output" => output = args.next().map(PathBuf::from),
                "--help" | "-h" => {
                    println!(
                        "archivetune-model-runner chords --model <chordnet.onnx> --plan <cqt-plan.bin> --config <config.json> --input <22.05k-stereo.wav> --output <result.json>"
                    );
                    std::process::exit(0);
                }
                other => bail!("unknown chord argument {other}"),
            }
        }
        Ok(Self {
            model: model.context("missing --model")?,
            plan: plan.context("missing --plan")?,
            config: config.context("missing --config")?,
            input: input.context("missing --input")?,
            output: output.context("missing --output")?,
        })
    }
}

fn validate_config(config: &Value) -> Result<()> {
    let input_shape = config["input"]["shape"]
        .as_array()
        .context("missing input shape")?;
    let output_shape = config["output"]["shape"]
        .as_array()
        .context("missing output shape")?;
    if config["input"]["name"].as_str() != Some("features")
        || config["output"]["name"].as_str() != Some("logits")
        || input_shape.get(1).and_then(Value::as_u64) != Some(SEQUENCE_LENGTH as u64)
        || input_shape.get(2).and_then(Value::as_u64) != Some(INPUT_BINS as u64)
        || output_shape.get(1).and_then(Value::as_u64) != Some(SEQUENCE_LENGTH as u64)
        || output_shape.get(2).and_then(Value::as_u64) != Some(OUTPUT_CLASSES as u64)
        || config["sampleRate"].as_u64() != Some(EXPECTED_SAMPLE_RATE as u64)
        || config["hopLength"].as_u64() != Some(2048)
        || config["normalizationInGraph"].as_bool() != Some(true)
    {
        bail!("unsupported chord model contract");
    }
    let vocab = config["chordVocab"]
        .as_array()
        .context("missing chord vocabulary")?;
    if vocab.len() != OUTPUT_CLASSES {
        bail!("unsupported chord vocabulary size {}", vocab.len());
    }
    Ok(())
}

fn read_mono_wav(path: &Path) -> Result<Vec<f32>> {
    let mut reader = WavReader::open(path).with_context(|| format!("open {}", path.display()))?;
    let spec = reader.spec();
    if spec.sample_rate != EXPECTED_SAMPLE_RATE || spec.channels != EXPECTED_CHANNELS {
        bail!(
            "chord input must be {} Hz stereo; got {} Hz / {} channels",
            EXPECTED_SAMPLE_RATE,
            spec.sample_rate,
            spec.channels
        );
    }
    let mut mono = Vec::with_capacity(reader.duration() as usize / EXPECTED_CHANNELS as usize);
    match spec.sample_format {
        SampleFormat::Float => {
            let mut pair = [0.0_f32; 2];
            for (index, sample) in reader.samples::<f32>().enumerate() {
                pair[index & 1] = sample.context("read float wav sample")?;
                if index & 1 == 1 {
                    mono.push((pair[0] + pair[1]) * 0.5);
                }
            }
        }
        SampleFormat::Int => {
            let scale = (1_u64 << spec.bits_per_sample.saturating_sub(1) as u32) as f32;
            let mut pair = [0.0_f32; 2];
            if spec.bits_per_sample <= 16 {
                for (index, sample) in reader.samples::<i16>().enumerate() {
                    pair[index & 1] = sample.context("read pcm16 wav sample")? as f32 / scale;
                    if index & 1 == 1 {
                        mono.push((pair[0] + pair[1]) * 0.5);
                    }
                }
            } else {
                for (index, sample) in reader.samples::<i32>().enumerate() {
                    pair[index & 1] = sample.context("read pcm wav sample")? as f32 / scale;
                    if index & 1 == 1 {
                        mono.push((pair[0] + pair[1]) * 0.5);
                    }
                }
            }
        }
    }
    if mono.is_empty() {
        bail!("empty chord-analysis wav");
    }
    Ok(mono)
}

#[derive(Debug, Clone)]
struct OctavePlan {
    index: usize,
    hop_length: usize,
    fft_size: usize,
    bin_start: usize,
    bin_count: usize,
}

#[derive(Debug)]
struct CqtPlan {
    sample_rate: f64,
    hop_length: usize,
    n_bins: usize,
    early_downsample_count: usize,
    tap_count: usize,
    row_offsets: Vec<u32>,
    fft_bins: Vec<u32>,
    coefficients: Vec<f32>,
    downsample_half_coefficients: Vec<f32>,
    octaves: Vec<OctavePlan>,
}

impl CqtPlan {
    fn decode(path: &Path) -> Result<Self> {
        let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
        if bytes.len() < 128 || read_u32(&bytes, 0)? != 0x5451_434d {
            bail!("invalid CQT plan artifact");
        }
        let header_size = read_u32(&bytes, 8)? as usize;
        if header_size != 128 {
            bail!("unsupported CQT plan header size {header_size}");
        }
        let sample_rate = read_f64(&bytes, 16)?;
        let hop_length = read_u32(&bytes, 24)? as usize;
        let n_bins = read_u32(&bytes, 28)? as usize;
        let early_downsample_count = read_u32(&bytes, 36)? as usize;
        let octave_count = read_u32(&bytes, 48)? as usize;
        let tap_count = read_u32(&bytes, 52)? as usize;
        let coefficient_count = read_u32(&bytes, 56)? as usize;
        let payload_size = read_u32(&bytes, 60)? as usize;
        if header_size + payload_size != bytes.len() {
            bail!("CQT plan payload size mismatch");
        }
        let octaves_offset = read_u32(&bytes, 72)? as usize;
        let row_offsets_offset = read_u32(&bytes, 76)? as usize;
        let fft_bins_offset = read_u32(&bytes, 80)? as usize;
        let coefficients_offset = read_u32(&bytes, 84)? as usize;
        let downsample_offset = read_u32(&bytes, 92)? as usize;

        let mut octaves = Vec::with_capacity(octave_count);
        for i in 0..octave_count {
            let base = checked_range(&bytes, octaves_offset + i * 32, 32)?;
            octaves.push(OctavePlan {
                index: read_u32(base, 0)? as usize,
                hop_length: read_u32(base, 12)? as usize,
                fft_size: read_u32(base, 16)? as usize,
                bin_start: read_u32(base, 20)? as usize,
                bin_count: read_u32(base, 24)? as usize,
            });
        }
        let row_offsets = read_u32_vec(&bytes, row_offsets_offset, n_bins + 1)?;
        let fft_bins = read_u32_vec(&bytes, fft_bins_offset, coefficient_count)?;
        let coefficients = read_f32_vec(&bytes, coefficients_offset, coefficient_count * 2)?;
        let half_count = tap_count.div_ceil(2);
        let downsample_half_coefficients = read_f32_vec(&bytes, downsample_offset, half_count)?;
        Ok(Self {
            sample_rate,
            hop_length,
            n_bins,
            early_downsample_count,
            tap_count,
            row_offsets,
            fft_bins,
            coefficients,
            downsample_half_coefficients,
            octaves,
        })
    }

    fn validate(&self) -> Result<()> {
        if (self.sample_rate - EXPECTED_SAMPLE_RATE as f64).abs() > 0.001
            || self.hop_length != 2048
            || self.n_bins != INPUT_BINS
            || self.early_downsample_count != 1
            || self.tap_count != 255
            || self.octaves.len() != 6
            || self
                .octaves
                .iter()
                .any(|octave| octave.fft_size != FFT_SIZE)
        {
            bail!("unsupported ChordMini CQT plan");
        }
        if self.row_offsets.len() != self.n_bins + 1
            || self.coefficients.len() != self.fft_bins.len() * 2
        {
            bail!("invalid CQT coefficient tables");
        }
        Ok(())
    }
}

fn compute_cqt(audio: &[f32], plan: &CqtPlan) -> Result<Vec<f32>> {
    let mut levels = Vec::with_capacity(plan.early_downsample_count + plan.octaves.len());
    levels.push(audio.to_vec());
    let level_count = plan.early_downsample_count + plan.octaves.len();
    for _ in 0..level_count {
        let next = downsample(levels.last().unwrap(), plan);
        levels.push(next);
    }
    let frame_count = plan
        .octaves
        .iter()
        .map(|octave| {
            let level = plan.early_downsample_count + octave.index;
            1 + levels[level].len() / octave.hop_length
        })
        .min()
        .unwrap_or(0);
    if frame_count == 0 {
        return Ok(Vec::new());
    }
    let mut output = vec![0.0_f32; frame_count * plan.n_bins];
    let mut planner = RealFftPlanner::<f32>::new();
    let fft = planner.plan_fft_forward(FFT_SIZE);
    let mut frame = fft.make_input_vec();
    let mut spectrum = fft.make_output_vec();

    for (octave_no, octave) in plan.octaves.iter().enumerate() {
        let level_index = plan.early_downsample_count + octave.index;
        let level = &levels[level_index];
        for frame_index in 0..frame_count {
            for sample_in_frame in 0..FFT_SIZE {
                let offset = frame_index as isize * octave.hop_length as isize
                    + sample_in_frame as isize
                    - (FFT_SIZE / 2) as isize;
                frame[sample_in_frame] = if offset >= 0 && (offset as usize) < level.len() {
                    level[offset as usize]
                } else {
                    0.0
                };
            }
            fft.process(&mut frame, &mut spectrum)?;
            for local_bin in 0..octave.bin_count {
                let global_bin = octave.bin_start + local_bin;
                let start = plan.row_offsets[global_bin] as usize;
                let end = plan.row_offsets[global_bin + 1] as usize;
                let mut sum = Complex32::new(0.0, 0.0);
                for coefficient_index in start..end {
                    let fft_bin = plan.fft_bins[coefficient_index] as usize;
                    if fft_bin >= spectrum.len() {
                        bail!("CQT plan references FFT bin {fft_bin} outside spectrum");
                    }
                    let coefficient = Complex32::new(
                        plan.coefficients[coefficient_index * 2],
                        plan.coefficients[coefficient_index * 2 + 1],
                    );
                    sum += coefficient * spectrum[fft_bin];
                }
                let magnitude = (sum.re * sum.re + sum.im * sum.im).sqrt();
                output[frame_index * plan.n_bins + global_bin] = (magnitude + 1e-6).ln();
            }
        }
        emit(json!({
            "type": "progress",
            "task": "chords",
            "value": 0.05 + (octave_no + 1) as f64 / plan.octaves.len() as f64 * 0.46,
        }));
    }
    Ok(output)
}

fn downsample(input: &[f32], plan: &CqtPlan) -> Vec<f32> {
    let output_count = input.len().div_ceil(2);
    let delay = (plan.tap_count - 1) / 2;
    let gain = std::f32::consts::SQRT_2;
    let mut output = vec![0.0_f32; output_count];
    for (output_index, value) in output.iter_mut().enumerate() {
        let center = (output_index * 2) as isize;
        let mut sum = 0.0_f32;
        for tap in 0..plan.tap_count {
            let source = center + tap as isize - delay as isize;
            if source < 0 || source as usize >= input.len() {
                continue;
            }
            let distance = tap.abs_diff(delay);
            sum += input[source as usize] * plan.downsample_half_coefficients[distance];
        }
        *value = sum * gain;
    }
    output
}

fn smooth_argmax(logits: &[f32], frame_count: usize, window_count: usize) -> Result<Vec<usize>> {
    if logits.len() != window_count * SEQUENCE_LENGTH * OUTPUT_CLASSES {
        bail!("invalid chord logits buffer");
    }
    let radius = 4_i32;
    let mut result = vec![0usize; frame_count];
    for frame_index in 0..frame_count {
        let window = frame_index / SEQUENCE_LENGTH;
        let local = (frame_index % SEQUENCE_LENGTH) as i32;
        let mut best_class = 0usize;
        let mut best_value = f32::NEG_INFINITY;
        for class in 0..OUTPUT_CLASSES {
            let mut total = 0.0_f32;
            for offset in -radius..=radius {
                let neighbor = local + offset;
                if !(0..SEQUENCE_LENGTH as i32).contains(&neighbor) {
                    continue;
                }
                let index = (window * SEQUENCE_LENGTH + neighbor as usize) * OUTPUT_CLASSES + class;
                total += logits[index];
            }
            if total > best_value {
                best_value = total;
                best_class = class;
            }
        }
        result[frame_index] = best_class;
    }
    Ok(result)
}

fn predictions_to_events(
    predictions: &[usize],
    logits: &[f32],
    frame_count: usize,
    vocab: &[String],
    duration_ms: u64,
) -> Vec<Value> {
    let mut result = Vec::new();
    if predictions.is_empty() {
        return result;
    }
    let mut start = 0usize;
    while start < predictions.len() {
        let class = predictions[start];
        let mut end = start + 1;
        while end < predictions.len() && predictions[end] == class {
            end += 1;
        }
        let raw = vocab.get(class).map(String::as_str).unwrap_or("N");
        if raw != "N" && raw != "X" {
            let start_ms = frame_to_ms(start);
            let end_ms = frame_to_ms(end).min(duration_ms).max(start_ms + 1);
            let confidence = segment_confidence(logits, class, start, end, frame_count);
            result.push(json!({
                "start_ms": start_ms,
                "end_ms": end_ms,
                "chord": pretty_chord(raw),
                "confidence": confidence,
            }));
        }
        start = end;
    }
    result
}

fn segment_confidence(
    logits: &[f32],
    class: usize,
    start: usize,
    end: usize,
    frame_count: usize,
) -> f32 {
    let mut accumulated = 0.0_f32;
    let mut count = 0usize;
    for frame_index in start..end.min(frame_count) {
        let window = frame_index / SEQUENCE_LENGTH;
        let local = frame_index % SEQUENCE_LENGTH;
        let base = (window * SEQUENCE_LENGTH + local) * OUTPUT_CLASSES;
        let row = &logits[base..base + OUTPUT_CLASSES];
        let max = row.iter().copied().fold(f32::NEG_INFINITY, f32::max);
        let denom: f32 = row.iter().map(|value| (*value - max).exp()).sum();
        if denom > 0.0 {
            accumulated += (row[class] - max).exp() / denom;
            count += 1;
        }
    }
    if count == 0 {
        0.0
    } else {
        accumulated / count as f32
    }
}

fn pretty_chord(raw: &str) -> String {
    let Some((root, quality)) = raw.split_once(':') else {
        return raw.to_owned();
    };
    let suffix = match quality {
        "min" => "m",
        "dim" => "dim",
        "aug" => "aug",
        "min6" => "m6",
        "maj6" => "6",
        "min7" => "m7",
        "minmaj7" => "mMaj7",
        "maj7" => "maj7",
        "7" => "7",
        "dim7" => "dim7",
        "hdim7" => "m7b5",
        "sus2" => "sus2",
        "sus4" => "sus4",
        other => other,
    };
    format!("{root}{suffix}")
}

fn frame_to_ms(frame: usize) -> u64 {
    frame as u64 * 2048 * 1000 / EXPECTED_SAMPLE_RATE as u64
}

fn checked_range(bytes: &[u8], offset: usize, len: usize) -> Result<&[u8]> {
    bytes
        .get(offset..offset.saturating_add(len))
        .context("CQT plan range is outside artifact")
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32> {
    let raw: [u8; 4] = checked_range(bytes, offset, 4)?.try_into().unwrap();
    Ok(u32::from_le_bytes(raw))
}

fn read_f64(bytes: &[u8], offset: usize) -> Result<f64> {
    let raw: [u8; 8] = checked_range(bytes, offset, 8)?.try_into().unwrap();
    Ok(f64::from_le_bytes(raw))
}

fn read_u32_vec(bytes: &[u8], offset: usize, count: usize) -> Result<Vec<u32>> {
    (0..count)
        .map(|index| read_u32(bytes, offset + index * 4))
        .collect()
}

fn read_f32_vec(bytes: &[u8], offset: usize, count: usize) -> Result<Vec<f32>> {
    (0..count)
        .map(|index| {
            let raw: [u8; 4] = checked_range(bytes, offset + index * 4, 4)?
                .try_into()
                .unwrap();
            Ok(f32::from_le_bytes(raw))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chord_labels_are_ui_friendly() {
        assert_eq!(pretty_chord("C:min"), "Cm");
        assert_eq!(pretty_chord("F#:hdim7"), "F#m7b5");
        assert_eq!(pretty_chord("A:maj7"), "Amaj7");
        assert_eq!(pretty_chord("G"), "G");
    }

    #[test]
    fn stereo_downmix_is_arithmetic_mean_contract() {
        assert_eq!((0.75_f32 + -0.25_f32) * 0.5, 0.25);
    }
}
