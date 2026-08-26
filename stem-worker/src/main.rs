use std::{
    fs,
    io::{self, Write},
    path::{Path, PathBuf},
    time::Instant,
};

use anyhow::{anyhow, bail, Context, Result};
use hound::{SampleFormat, WavReader, WavSpec, WavWriter};
use ort::{ep, inputs, session::Session, value::Tensor};
use realfft::{num_complex::Complex32, RealFftPlanner};
use serde_json::json;

mod playback;

const SAMPLE_RATE: usize = 44_100;
const CHANNELS: usize = 2;
const SOURCES: usize = 4;
const NFFT: usize = 4096;
const HOP: usize = 1024;
const FREQ_BINS: usize = 2048;
const FRAMES: usize = 44;
const SEGMENT: usize = SAMPLE_RATE;
const OVERLAP: f32 = 0.10;
const STRIDE: usize = (SEGMENT as f32 * (1.0 - OVERLAP)) as usize;
const SHIFT_CONTEXT: usize = SAMPLE_RATE / 4;

fn main() {
    if let Err(error) = run() {
        emit(json!({ "type": "error", "message": format!("{error:#}") }));
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    if std::env::args_os().nth(1).as_deref() == Some(std::ffi::OsStr::new("play")) {
        return playback::run(std::env::args_os().skip(2));
    }
    run_separation()
}

fn run_separation() -> Result<()> {
    let args = Args::parse()?;
    fs::create_dir_all(&args.output).with_context(|| format!("create {}", args.output.display()))?;

    let ort_dll = std::env::var_os("ARCHIVETUNE_ONNXRUNTIME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            std::env::current_exe()
                .ok()
                .and_then(|path| path.parent().map(Path::to_path_buf))
                .unwrap_or_else(|| PathBuf::from("."))
                .join("onnxruntime.dll")
        });
    ort::init_from(&ort_dll)
        .with_context(|| format!("load ONNX Runtime from {}", ort_dll.display()))?
        .commit();

    emit(json!({ "type": "loading", "backend": "directml" }));
    let load_started = Instant::now();
    let mut session = Session::builder()?
        .with_execution_providers([
            ep::DirectML::default()
                .with_device_id(0)
                .build(),
        ])
        .map_err(|error| anyhow!(error.to_string()))?
        .commit_from_file(&args.model)
        .with_context(|| format!("load model {}", args.model.display()))?;
    emit(json!({ "type": "loaded", "milliseconds": load_started.elapsed().as_millis() }));

    let audio = read_stereo_wav(&args.input)?;
    let duration_seconds = audio[0].len() as f64 / SAMPLE_RATE as f64;
    emit(json!({ "type": "audio", "samples": audio[0].len(), "duration_seconds": duration_seconds }));

    let started = Instant::now();
    let stems = separate(&mut session, audio)?;
    write_stems(&args.output, &stems)?;
    emit(json!({
        "type": "done",
        "milliseconds": started.elapsed().as_millis(),
        "output": args.output,
    }));
    Ok(())
}

#[derive(Debug)]
struct Args {
    model: PathBuf,
    input: PathBuf,
    output: PathBuf,
}

impl Args {
    fn parse() -> Result<Self> {
        let mut model = None;
        let mut input = None;
        let mut output = None;
        let mut args = std::env::args_os().skip(1);
        while let Some(arg) = args.next() {
            match arg.to_string_lossy().as_ref() {
                "--model" => model = args.next().map(PathBuf::from),
                "--input" => input = args.next().map(PathBuf::from),
                "--output" => output = args.next().map(PathBuf::from),
                "--help" | "-h" => {
                    println!("archivetune-stems --model <htdemucs.onnx> --input <44.1k-stereo.wav> --output <dir>");
                    std::process::exit(0);
                }
                other => bail!("unknown argument {other}"),
            }
        }
        Ok(Self {
            model: model.context("missing --model")?,
            input: input.context("missing --input")?,
            output: output.context("missing --output")?,
        })
    }
}

fn emit(value: serde_json::Value) {
    println!("{value}");
    let _ = io::stdout().flush();
}

fn read_stereo_wav(path: &Path) -> Result<[Vec<f32>; CHANNELS]> {
    let mut reader = WavReader::open(path).with_context(|| format!("open {}", path.display()))?;
    let spec = reader.spec();
    if spec.sample_rate as usize != SAMPLE_RATE || spec.channels as usize != CHANNELS {
        bail!(
            "worker input must be 44.1 kHz stereo; got {} Hz / {} channels",
            spec.sample_rate,
            spec.channels
        );
    }
    let mut left = Vec::with_capacity(reader.duration() as usize / 2);
    let mut right = Vec::with_capacity(reader.duration() as usize / 2);
    match spec.sample_format {
        SampleFormat::Float => {
            for (index, sample) in reader.samples::<f32>().enumerate() {
                let sample = sample.context("read float wav sample")?;
                if index & 1 == 0 { left.push(sample) } else { right.push(sample) }
            }
        }
        SampleFormat::Int => {
            let scale = (1_u64 << (spec.bits_per_sample.saturating_sub(1) as u32)) as f32;
            if spec.bits_per_sample <= 16 {
                for (index, sample) in reader.samples::<i16>().enumerate() {
                    let sample = sample.context("read pcm16 wav sample")? as f32 / scale;
                    if index & 1 == 0 { left.push(sample) } else { right.push(sample) }
                }
            } else {
                for (index, sample) in reader.samples::<i32>().enumerate() {
                    let sample = sample.context("read pcm wav sample")? as f32 / scale;
                    if index & 1 == 0 { left.push(sample) } else { right.push(sample) }
                }
            }
        }
    }
    if left.len() != right.len() || left.is_empty() {
        bail!("invalid stereo wav sample count");
    }
    Ok([left, right])
}

fn separate(session: &mut Session, mut audio: [Vec<f32>; CHANNELS]) -> Result<Vec<Vec<Vec<f32>>>> {
    let original_len = audio[0].len();
    let (outer_mean, outer_std) = outer_normalization(&audio);
    for channel in &mut audio {
        for sample in channel.iter_mut() {
            *sample = (*sample - outer_mean) / outer_std;
        }
    }

    // Demucs' shifts=1 path amounts to one shifted, zero-padded prediction. Keep a fixed
    // quarter-second offset so cached stems are deterministic across runs.
    let shifted_len = original_len + SHIFT_CONTEXT * 2;
    let mut shifted = [vec![0.0_f32; shifted_len], vec![0.0_f32; shifted_len]];
    for channel in 0..CHANNELS {
        shifted[channel][SHIFT_CONTEXT..SHIFT_CONTEXT + original_len].copy_from_slice(&audio[channel]);
    }

    let mut output = vec![vec![vec![0.0_f32; shifted_len]; CHANNELS]; SOURCES];
    let mut weight_sum = vec![0.0_f32; shifted_len];
    let weight = triangular_weight();
    let offsets: Vec<usize> = (0..shifted_len).step_by(STRIDE).collect();
    let total = offsets.len();
    let mut dsp = DemucsDsp::new();

    for (chunk_index, offset) in offsets.into_iter().enumerate() {
        let chunk_len = SEGMENT.min(shifted_len - offset);
        let mut chunk = [vec![0.0_f32; SEGMENT], vec![0.0_f32; SEGMENT]];
        let pad_left = (SEGMENT - chunk_len) / 2;
        for channel in 0..CHANNELS {
            chunk[channel][pad_left..pad_left + chunk_len]
                .copy_from_slice(&shifted[channel][offset..offset + chunk_len]);
        }

        let mag = dsp.stft_mag(&chunk)?;
        let mix = interleaved_model_mix(&chunk);
        let mag_tensor = Tensor::from_array(([1, 4, FREQ_BINS, FRAMES], mag))?;
        let mix_tensor = Tensor::from_array(([1, 2, SEGMENT], mix))?;
        let outputs = session.run(inputs!["mag" => mag_tensor, "mix" => mix_tensor])?;
        let (_, freq) = outputs["freq_stems"].try_extract_tensor::<f32>()?;
        let (_, time) = outputs["time_stems"].try_extract_tensor::<f32>()?;
        let chunk_stems = dsp.reconstruct(freq, time)?;

        let trim_start = pad_left;
        for source in 0..SOURCES {
            for channel in 0..CHANNELS {
                for local in 0..chunk_len {
                    output[source][channel][offset + local] +=
                        chunk_stems[source][channel][trim_start + local] * weight[local];
                }
            }
        }
        for local in 0..chunk_len {
            weight_sum[offset + local] += weight[local];
        }
        emit(json!({ "type": "progress", "value": (chunk_index + 1) as f64 / total as f64 }));
    }

    for index in 0..shifted_len {
        let divisor = weight_sum[index];
        if divisor > 0.0 {
            for source in 0..SOURCES {
                for channel in 0..CHANNELS {
                    output[source][channel][index] /= divisor;
                }
            }
        }
    }

    let mut cropped = vec![vec![vec![0.0_f32; original_len]; CHANNELS]; SOURCES];
    for source in 0..SOURCES {
        for channel in 0..CHANNELS {
            for i in 0..original_len {
                cropped[source][channel][i] =
                    output[source][channel][SHIFT_CONTEXT + i] * outer_std + outer_mean;
            }
        }
    }
    Ok(cropped)
}

fn outer_normalization(audio: &[Vec<f32>; CHANNELS]) -> (f32, f32) {
    let len = audio[0].len();
    let mut mean = 0.0_f64;
    for i in 0..len {
        mean += ((audio[0][i] + audio[1][i]) * 0.5) as f64;
    }
    mean /= len as f64;
    let mut variance = 0.0_f64;
    for i in 0..len {
        let mono = ((audio[0][i] + audio[1][i]) * 0.5) as f64;
        let delta = mono - mean;
        variance += delta * delta;
    }
    // torch.std defaults to Bessel correction (N-1).
    let std = (variance / (len.saturating_sub(1).max(1) as f64)).sqrt().max(1e-12);
    (mean as f32, std as f32)
}

fn triangular_weight() -> Vec<f32> {
    let half = SEGMENT / 2;
    let mut weight = Vec::with_capacity(SEGMENT);
    for value in 1..=half { weight.push(value as f32 / half as f32); }
    for value in (1..=half).rev() { weight.push(value as f32 / half as f32); }
    weight
}

fn interleaved_model_mix(chunk: &[Vec<f32>; CHANNELS]) -> Vec<f32> {
    let mut out = Vec::with_capacity(CHANNELS * SEGMENT);
    out.extend_from_slice(&chunk[0]);
    out.extend_from_slice(&chunk[1]);
    out
}

struct DemucsDsp {
    forward: std::sync::Arc<dyn realfft::RealToComplex<f32>>,
    inverse: std::sync::Arc<dyn realfft::ComplexToReal<f32>>,
    hann: Vec<f32>,
}

impl DemucsDsp {
    fn new() -> Self {
        let mut planner = RealFftPlanner::<f32>::new();
        let forward = planner.plan_fft_forward(NFFT);
        let inverse = planner.plan_fft_inverse(NFFT);
        let hann = (0..NFFT)
            .map(|i| 0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / NFFT as f32).cos())
            .collect();
        Self { forward, inverse, hann }
    }

    fn stft_mag(&mut self, mix: &[Vec<f32>; CHANNELS]) -> Result<Vec<f32>> {
        let le = SEGMENT.div_ceil(HOP);
        debug_assert_eq!(le, FRAMES);
        let manual_left = HOP / 2 * 3;
        let manual_right = manual_left + le * HOP - SEGMENT;
        let mut mag = vec![0.0_f32; 4 * FREQ_BINS * FRAMES];
        let fft_scale = (NFFT as f32).sqrt();

        for channel in 0..CHANNELS {
            let manual = reflect_pad(&mix[channel], manual_left, manual_right);
            for frame in 0..FRAMES {
                // _spec keeps STFT frames [2, 2 + le). torch.stft(center=true) adds NFFT/2 reflect padding.
                let stft_frame = frame + 2;
                let start = stft_frame * HOP;
                let mut input = self.forward.make_input_vec();
                for n in 0..NFFT {
                    let centered_index = start as isize + n as isize - (NFFT / 2) as isize;
                    input[n] = reflected_sample(&manual, centered_index) * self.hann[n];
                }
                let mut spectrum = self.forward.make_output_vec();
                self.forward.process(&mut input, &mut spectrum)?;
                for freq in 0..FREQ_BINS {
                    let value = spectrum[freq] / fft_scale;
                    let real_channel = channel * 2;
                    mag[(real_channel * FREQ_BINS + freq) * FRAMES + frame] = value.re;
                    mag[((real_channel + 1) * FREQ_BINS + freq) * FRAMES + frame] = value.im;
                }
            }
        }
        Ok(mag)
    }

    fn reconstruct(&mut self, freq: &[f32], time: &[f32]) -> Result<Vec<Vec<Vec<f32>>>> {
        let expected_freq = SOURCES * 4 * FREQ_BINS * FRAMES;
        let expected_time = SOURCES * CHANNELS * SEGMENT;
        if freq.len() != expected_freq || time.len() != expected_time {
            bail!("unexpected model output sizes: freq={} time={}", freq.len(), time.len());
        }
        let padded_length = HOP * FRAMES + 2 * (HOP / 2 * 3); // 48,128
        let centered_length = padded_length + NFFT; // 52,224
        let crop_start = NFFT / 2 + HOP / 2 * 3;
        let fft_scale = (NFFT as f32).sqrt();
        let mut result = vec![vec![vec![0.0_f32; SEGMENT]; CHANNELS]; SOURCES];

        for source in 0..SOURCES {
            for channel in 0..CHANNELS {
                let mut ola = vec![0.0_f32; centered_length];
                let mut envelope = vec![0.0_f32; centered_length];
                for frame48 in 0..(FRAMES + 4) {
                    let mut spectrum = vec![Complex32::new(0.0, 0.0); NFFT / 2 + 1];
                    if (2..FRAMES + 2).contains(&frame48) {
                        let frame = frame48 - 2;
                        let complex_channel = channel * 2;
                        for f in 0..FREQ_BINS {
                            let re = freq[(((source * 4 + complex_channel) * FREQ_BINS + f) * FRAMES) + frame];
                            let im = freq[(((source * 4 + complex_channel + 1) * FREQ_BINS + f) * FRAMES) + frame];
                            spectrum[f] = Complex32::new(re, im);
                        }
                        // A real-valued signal cannot have an imaginary DC component. PyTorch's
                        // istft effectively discards it; realfft validates the Hermitian boundary
                        // bins and rejects it, so make that convention explicit here. Demucs drops
                        // the Nyquist row before the network and restores it as zero during _ispec.
                        spectrum[0].im = 0.0;
                        spectrum[NFFT / 2] = Complex32::new(0.0, 0.0);
                    }
                    let mut frame_time = self.inverse.make_output_vec();
                    self.inverse.process(&mut spectrum, &mut frame_time)?;
                    let start = frame48 * HOP;
                    for n in 0..NFFT {
                        let synthesized = frame_time[n] / fft_scale;
                        let window = self.hann[n];
                        ola[start + n] += synthesized * window;
                        envelope[start + n] += window * window;
                    }
                }
                for i in 0..SEGMENT {
                    let full_index = crop_start + i;
                    let freq_sample = if envelope[full_index] > 1e-11 {
                        ola[full_index] / envelope[full_index]
                    } else {
                        0.0
                    };
                    let time_sample = time[(source * CHANNELS + channel) * SEGMENT + i];
                    result[source][channel][i] = freq_sample + time_sample;
                }
            }
        }
        Ok(result)
    }
}

fn reflect_pad(input: &[f32], left: usize, right: usize) -> Vec<f32> {
    let mut out = Vec::with_capacity(left + input.len() + right);
    for index in -(left as isize)..(input.len() + right) as isize {
        out.push(reflected_sample(input, index));
    }
    out
}

fn reflected_sample(input: &[f32], mut index: isize) -> f32 {
    let len = input.len() as isize;
    while index < 0 || index >= len {
        if index < 0 { index = -index; }
        if index >= len { index = 2 * len - 2 - index; }
    }
    input[index as usize]
}

fn write_stems(directory: &Path, stems: &[Vec<Vec<f32>>]) -> Result<()> {
    // Raw model source order is drums, bass, other, vocals. Keep each stem stereo on disk; an
    // 8-channel audio container would assign surround-speaker semantics (including an LFE channel)
    // and can therefore alter arbitrary stem channels during encode/decode.
    let names = ["drums", "bass", "other", "vocals"];
    for (source, name) in names.into_iter().enumerate() {
        let path = directory.join(format!("{name}.wav"));
        let spec = WavSpec {
            channels: CHANNELS as u16,
            sample_rate: SAMPLE_RATE as u32,
            bits_per_sample: 32,
            sample_format: SampleFormat::Float,
        };
        let mut writer = WavWriter::create(&path, spec).with_context(|| format!("create {}", path.display()))?;
        for i in 0..stems[source][0].len() {
            writer.write_sample(stems[source][0][i])?;
            writer.write_sample(stems[source][1][i])?;
        }
        writer.finalize()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reflection_matches_torch_style_edges() {
        let x = [0.0, 1.0, 2.0, 3.0];
        assert_eq!(reflected_sample(&x, -1), 1.0);
        assert_eq!(reflected_sample(&x, -2), 2.0);
        assert_eq!(reflected_sample(&x, 4), 2.0);
        assert_eq!(reflected_sample(&x, 5), 1.0);
    }

    #[test]
    fn overlap_weight_is_symmetric_and_peaks_at_one() {
        let weight = triangular_weight();
        assert_eq!(weight.len(), SEGMENT);
        assert_eq!(weight[SEGMENT / 2 - 1], 1.0);
        assert_eq!(weight[SEGMENT / 2], 1.0);
        assert!((weight[0] - weight[SEGMENT - 1]).abs() < f32::EPSILON);
    }
}
