use std::{
    fs,
    io::{self, Write},
    path::{Path, PathBuf},
    time::Instant,
};

use anyhow::{bail, Context, Result};
use hound::{SampleFormat, WavReader, WavSpec, WavWriter};
use ort::{inputs, session::Session, value::Tensor};
use realfft::{num_complex::Complex32, RealFftPlanner};
use serde_json::json;

mod chords;
mod model_runtime;
mod playback;

const SAMPLE_RATE: usize = 44_100;
const CHANNELS: usize = 2;
const SOURCES: usize = 6;
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
    let mut args = std::env::args_os();
    let _exe = args.next();
    let first = args.next();
    match first.as_deref().and_then(|arg| arg.to_str()) {
        Some("play") => playback::run(args),
        Some("chords") => chords::run(args),
        Some("stems") => run_separation(args),
        Some("--help") | Some("-h") => {
            println!("archivetune-model-runner <stems|chords|play> [options]");
            Ok(())
        }
        // Compatibility with older desktop builds that launched the helper without a subcommand.
        Some(_) => run_separation(std::env::args_os().skip(1)),
        None => bail!("missing command (stems, chords, or play)"),
    }
}

fn run_separation(args: impl Iterator<Item = std::ffi::OsString>) -> Result<()> {
    let args = Args::parse(args)?;
    fs::create_dir_all(&args.output)
        .with_context(|| format!("create {}", args.output.display()))?;

    model_runtime::init_onnx_runtime()?;

    emit(json!({ "type": "loading", "backend": "directml" }));
    let load_started = Instant::now();
    let mut session = model_runtime::load_directml_session(&args.model)?;
    emit(json!({ "type": "loaded", "milliseconds": load_started.elapsed().as_millis() }));

    let (original_len, outer_mean, outer_std) = stereo_wav_normalization(&args.input)?;
    let duration_seconds = original_len as f64 / SAMPLE_RATE as f64;
    emit(json!({ "type": "audio", "samples": original_len, "duration_seconds": duration_seconds }));

    let started = Instant::now();
    separate_to_wavs(
        &mut session,
        StereoWavInput::open(&args.input)?,
        original_len,
        outer_mean,
        outer_std,
        &args.output,
    )?;
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
    fn parse(args: impl Iterator<Item = std::ffi::OsString>) -> Result<Self> {
        let mut model = None;
        let mut input = None;
        let mut output = None;
        let mut args = args;
        while let Some(arg) = args.next() {
            match arg.to_string_lossy().as_ref() {
                "--model" => model = args.next().map(PathBuf::from),
                "--input" => input = args.next().map(PathBuf::from),
                "--output" => output = args.next().map(PathBuf::from),
                "--help" | "-h" => {
                    println!("archivetune-model-runner stems --model <htdemucs.onnx> --input <44.1k-stereo.wav> --output <dir>");
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

struct StereoWavInput {
    reader: WavReader<std::io::BufReader<fs::File>>,
    spec: WavSpec,
}

impl StereoWavInput {
    fn open(path: &Path) -> Result<Self> {
        let reader = WavReader::open(path).with_context(|| format!("open {}", path.display()))?;
        let spec = reader.spec();
        if spec.sample_rate as usize != SAMPLE_RATE || spec.channels as usize != CHANNELS {
            bail!(
                "worker input must be 44.1 kHz stereo; got {} Hz / {} channels",
                spec.sample_rate,
                spec.channels
            );
        }
        Ok(Self { reader, spec })
    }

    fn frames(&self) -> usize {
        self.reader.duration() as usize
    }

    fn fill_normalized(
        &mut self,
        start_frame: usize,
        end_frame: usize,
        mean: f32,
        std: f32,
        destination: &mut [Vec<f32>; CHANNELS],
        destination_start: usize,
    ) -> Result<()> {
        self.reader.seek(start_frame as u32)?;
        let frame_count = end_frame - start_frame;
        match self.spec.sample_format {
            SampleFormat::Float => {
                for (index, sample) in self
                    .reader
                    .samples::<f32>()
                    .take(frame_count * CHANNELS)
                    .enumerate()
                {
                    destination[index & 1][destination_start + index / CHANNELS] =
                        (sample.context("read float wav sample")? - mean) / std;
                }
            }
            SampleFormat::Int if self.spec.bits_per_sample <= 16 => {
                let scale = (1_u64 << (self.spec.bits_per_sample.saturating_sub(1) as u32)) as f32;
                for (index, sample) in self
                    .reader
                    .samples::<i16>()
                    .take(frame_count * CHANNELS)
                    .enumerate()
                {
                    destination[index & 1][destination_start + index / CHANNELS] =
                        (sample.context("read pcm16 wav sample")? as f32 / scale - mean) / std;
                }
            }
            SampleFormat::Int => {
                let scale = (1_u64 << (self.spec.bits_per_sample.saturating_sub(1) as u32)) as f32;
                for (index, sample) in self
                    .reader
                    .samples::<i32>()
                    .take(frame_count * CHANNELS)
                    .enumerate()
                {
                    destination[index & 1][destination_start + index / CHANNELS] =
                        (sample.context("read pcm wav sample")? as f32 / scale - mean) / std;
                }
            }
        }
        Ok(())
    }
}

fn stereo_wav_normalization(path: &Path) -> Result<(usize, f32, f32)> {
    let mut reader = WavReader::open(path).with_context(|| format!("open {}", path.display()))?;
    let spec = reader.spec();
    if spec.sample_rate as usize != SAMPLE_RATE || spec.channels as usize != CHANNELS {
        bail!(
            "worker input must be 44.1 kHz stereo; got {} Hz / {} channels",
            spec.sample_rate,
            spec.channels
        );
    }
    let frames = reader.duration() as usize;
    if frames == 0 {
        bail!("invalid stereo wav sample count");
    }
    let mut mean = 0.0_f64;
    match spec.sample_format {
        SampleFormat::Float => {
            let mut samples = reader.samples::<f32>();
            for _ in 0..frames {
                mean += ((samples.next().context("missing left sample")??
                    + samples.next().context("missing right sample")??)
                    * 0.5) as f64;
            }
        }
        SampleFormat::Int => {
            let scale = (1_u64 << (spec.bits_per_sample.saturating_sub(1) as u32)) as f32;
            if spec.bits_per_sample <= 16 {
                let mut samples = reader.samples::<i16>();
                for _ in 0..frames {
                    mean += ((samples.next().context("missing left sample")?? as f32 / scale
                        + samples.next().context("missing right sample")?? as f32 / scale)
                        * 0.5) as f64;
                }
            } else {
                let mut samples = reader.samples::<i32>();
                for _ in 0..frames {
                    mean += ((samples.next().context("missing left sample")?? as f32 / scale
                        + samples.next().context("missing right sample")?? as f32 / scale)
                        * 0.5) as f64;
                }
            }
        }
    }
    mean /= frames as f64;

    let mut reader = StereoWavInput::open(path)?;
    let mut variance = 0.0_f64;
    match reader.spec.sample_format {
        SampleFormat::Float => {
            let mut samples = reader.reader.samples::<f32>();
            for _ in 0..frames {
                let mono = (samples.next().context("missing left sample")??
                    + samples.next().context("missing right sample")??)
                    * 0.5;
                variance += (mono as f64 - mean).powi(2);
            }
        }
        SampleFormat::Int if reader.spec.bits_per_sample <= 16 => {
            let scale = (1_u64 << (reader.spec.bits_per_sample.saturating_sub(1) as u32)) as f32;
            let mut samples = reader.reader.samples::<i16>();
            for _ in 0..frames {
                let mono = (samples.next().context("missing left sample")?? as f32 / scale
                    + samples.next().context("missing right sample")?? as f32 / scale)
                    * 0.5;
                variance += (mono as f64 - mean).powi(2);
            }
        }
        SampleFormat::Int => {
            let scale = (1_u64 << (reader.spec.bits_per_sample.saturating_sub(1) as u32)) as f32;
            let mut samples = reader.reader.samples::<i32>();
            for _ in 0..frames {
                let mono = (samples.next().context("missing left sample")?? as f32 / scale
                    + samples.next().context("missing right sample")?? as f32 / scale)
                    * 0.5;
                variance += (mono as f64 - mean).powi(2);
            }
        }
    }
    let std = (variance / frames.saturating_sub(1).max(1) as f64)
        .sqrt()
        .max(1e-12);
    Ok((frames, mean as f32, std as f32))
}

/// Separates one track without retaining a second full-song copy of every stem.
///
/// A three-minute song previously held both an expanded shifted output and a cropped output
/// (24 full-length float channels in total) before the WAV writer could begin. That alone is
/// more than 700 MiB for a three-minute song. The overlap between adjacent one-second segments is only 100 ms, so a
/// one-segment ring is sufficient: samples are flushed as soon as no later segment can touch
/// them.
fn separate_to_wavs(
    session: &mut Session,
    mut audio: StereoWavInput,
    original_len: usize,
    outer_mean: f32,
    outer_std: f32,
    directory: &Path,
) -> Result<()> {
    // Demucs' shifts=1 path amounts to one shifted, zero-padded prediction. Keep the shift
    // virtual instead of allocating another full-song stereo buffer.
    let shifted_len = original_len + SHIFT_CONTEXT * 2;
    let weight = triangular_weight();
    let total = shifted_len.div_ceil(STRIDE);
    let mut dsp = DemucsDsp::new();
    let mut writer = RollingStemWavWriter::new(directory, original_len, outer_mean, outer_std)?;

    for chunk_index in 0..total {
        let offset = chunk_index * STRIDE;
        let chunk_len = SEGMENT.min(shifted_len - offset);
        let mut chunk = [vec![0.0_f32; SEGMENT], vec![0.0_f32; SEGMENT]];
        let pad_left = (SEGMENT - chunk_len) / 2;
        copy_virtual_shifted_chunk(
            &mut audio, offset, chunk_len, pad_left, outer_mean, outer_std, &mut chunk,
        )?;

        let mag = dsp.stft_mag(&chunk)?;
        let mix = interleaved_model_mix(&chunk);
        let mag_tensor = Tensor::from_array(([1, 4, FREQ_BINS, FRAMES], mag))?;
        let mix_tensor = Tensor::from_array(([1, 2, SEGMENT], mix))?;
        let outputs = session.run(inputs!["mag" => mag_tensor, "mix" => mix_tensor])?;
        let (_, freq) = outputs["freq_stems"].try_extract_tensor::<f32>()?;
        let (_, time) = outputs["time_stems"].try_extract_tensor::<f32>()?;
        let chunk_stems = dsp.reconstruct(freq, time)?;

        writer.add_chunk(offset, chunk_len, pad_left, &chunk_stems, &weight)?;
        writer.flush_until((offset + STRIDE).min(shifted_len))?;
        emit(json!({ "type": "progress", "value": (chunk_index + 1) as f64 / total as f64 }));
    }
    writer.flush_until(shifted_len)?;
    writer.finish()
}

fn copy_virtual_shifted_chunk(
    audio: &mut StereoWavInput,
    offset: usize,
    chunk_len: usize,
    pad_left: usize,
    mean: f32,
    std: f32,
    chunk: &mut [Vec<f32>; CHANNELS],
) -> Result<()> {
    let shifted_end = offset + chunk_len;
    let original_start = offset.saturating_sub(SHIFT_CONTEXT);
    let original_end = shifted_end
        .saturating_sub(SHIFT_CONTEXT)
        .min(audio.frames());
    if original_start >= original_end {
        return Ok(());
    }
    let destination_start = pad_left + original_start + SHIFT_CONTEXT - offset;
    audio.fill_normalized(
        original_start,
        original_end,
        mean,
        std,
        chunk,
        destination_start,
    )
}

struct RollingStemWavWriter {
    writers: Vec<WavWriter<std::io::BufWriter<fs::File>>>,
    sums: Vec<Vec<f32>>,
    weights: Vec<f32>,
    cursor: usize,
    original_len: usize,
    outer_mean: f32,
    outer_std: f32,
}

impl RollingStemWavWriter {
    fn new(directory: &Path, original_len: usize, outer_mean: f32, outer_std: f32) -> Result<Self> {
        let spec = WavSpec {
            channels: CHANNELS as u16,
            sample_rate: SAMPLE_RATE as u32,
            bits_per_sample: 32,
            sample_format: SampleFormat::Float,
        };
        let names = ["drums", "bass", "other", "vocals", "guitar", "piano"];
        let writers = names
            .into_iter()
            .map(|name| {
                let path = directory.join(format!("{name}.wav"));
                WavWriter::create(&path, spec).with_context(|| format!("create {}", path.display()))
            })
            .collect::<Result<Vec<_>>>()?;
        Ok(Self {
            writers,
            sums: (0..SOURCES * CHANNELS)
                .map(|_| vec![0.0; SEGMENT])
                .collect(),
            weights: vec![0.0; SEGMENT],
            cursor: 0,
            original_len,
            outer_mean,
            outer_std,
        })
    }

    fn add_chunk(
        &mut self,
        offset: usize,
        chunk_len: usize,
        trim_start: usize,
        stems: &[Vec<Vec<f32>>],
        blend: &[f32],
    ) -> Result<()> {
        for local in 0..chunk_len {
            let slot = (offset + local) % SEGMENT;
            let weight = blend[local];
            self.weights[slot] += weight;
            for source in 0..SOURCES {
                for channel in 0..CHANNELS {
                    self.sums[source * CHANNELS + channel][slot] +=
                        stems[source][channel][trim_start + local] * weight;
                }
            }
        }
        Ok(())
    }

    fn flush_until(&mut self, end: usize) -> Result<()> {
        while self.cursor < end {
            let slot = self.cursor % SEGMENT;
            let divisor = self.weights[slot];
            if (SHIFT_CONTEXT..SHIFT_CONTEXT + self.original_len).contains(&self.cursor) {
                for source in 0..SOURCES {
                    let left = self.sums[source * CHANNELS][slot] / divisor * self.outer_std
                        + self.outer_mean;
                    let right = self.sums[source * CHANNELS + 1][slot] / divisor * self.outer_std
                        + self.outer_mean;
                    self.writers[source].write_sample(left)?;
                    self.writers[source].write_sample(right)?;
                }
            }
            self.weights[slot] = 0.0;
            for sum in &mut self.sums {
                sum[slot] = 0.0;
            }
            self.cursor += 1;
        }
        Ok(())
    }

    fn finish(self) -> Result<()> {
        for writer in self.writers {
            writer.finalize()?;
        }
        Ok(())
    }
}

fn triangular_weight() -> Vec<f32> {
    let half = SEGMENT / 2;
    let mut weight = Vec::with_capacity(SEGMENT);
    for value in 1..=half {
        weight.push(value as f32 / half as f32);
    }
    for value in (1..=half).rev() {
        weight.push(value as f32 / half as f32);
    }
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
        Self {
            forward,
            inverse,
            hann,
        }
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
            bail!(
                "unexpected model output sizes: freq={} time={}",
                freq.len(),
                time.len()
            );
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
                            let re = freq[(((source * 4 + complex_channel) * FREQ_BINS + f)
                                * FRAMES)
                                + frame];
                            let im = freq[(((source * 4 + complex_channel + 1) * FREQ_BINS + f)
                                * FRAMES)
                                + frame];
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
        if index < 0 {
            index = -index;
        }
        if index >= len {
            index = 2 * len - 2 - index;
        }
    }
    input[index as usize]
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

    #[test]
    fn rolling_writer_flushes_each_stem_without_full_song_buffers() -> Result<()> {
        let directory = std::env::temp_dir().join(format!(
            "archivetune-stem-worker-test-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&directory);
        fs::create_dir_all(&directory)?;

        let result = (|| -> Result<()> {
            let mut writer = RollingStemWavWriter::new(&directory, 3, 0.0, 1.0)?;
            let mut stems = vec![vec![vec![0.0; SEGMENT]; CHANNELS]; SOURCES];
            for source in 0..SOURCES {
                for channel in 0..CHANNELS {
                    for sample in 0..3 {
                        stems[source][channel][sample] =
                            (source * 10 + channel * 3 + sample) as f32;
                    }
                }
            }
            writer.add_chunk(SHIFT_CONTEXT, 3, 0, &stems, &vec![1.0; SEGMENT])?;
            writer.flush_until(SHIFT_CONTEXT + 3)?;
            writer.finish()?;

            let mut reader = WavReader::open(directory.join("vocals.wav"))?;
            let samples = reader.samples::<f32>().collect::<Result<Vec<_>, _>>()?;
            assert_eq!(samples.len(), 6);
            assert_eq!(samples, vec![30.0, 33.0, 31.0, 34.0, 32.0, 35.0]);
            Ok(())
        })();
        let _ = fs::remove_dir_all(&directory);
        result
    }

    #[test]
    fn wav_input_seeks_and_normalizes_only_the_requested_segment() -> Result<()> {
        let directory = std::env::temp_dir().join(format!(
            "archivetune-stem-input-test-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&directory);
        fs::create_dir_all(&directory)?;
        let input = directory.join("input.wav");
        let result = (|| -> Result<()> {
            let spec = WavSpec {
                channels: CHANNELS as u16,
                sample_rate: SAMPLE_RATE as u32,
                bits_per_sample: 16,
                sample_format: SampleFormat::Int,
            };
            let mut writer = WavWriter::create(&input, spec)?;
            for (left, right) in [
                (0_i16, 0_i16),
                (1_000, -1_000),
                (2_000, -2_000),
                (3_000, -3_000),
            ] {
                writer.write_sample(left)?;
                writer.write_sample(right)?;
            }
            writer.finalize()?;

            let (frames, mean, std) = stereo_wav_normalization(&input)?;
            assert_eq!(frames, 4);
            assert_eq!(mean, 0.0);
            let mut reader = StereoWavInput::open(&input)?;
            let mut destination = [vec![0.0; 5], vec![0.0; 5]];
            reader.fill_normalized(1, 3, mean, std, &mut destination, 2)?;
            assert!((destination[0][2] - (1_000.0 / 32_768.0 / std)).abs() < 1e-6);
            assert!((destination[1][3] - (-2_000.0 / 32_768.0 / std)).abs() < 1e-6);
            assert_eq!(destination[0][0], 0.0);
            Ok(())
        })();
        let _ = fs::remove_dir_all(&directory);
        result
    }
}
