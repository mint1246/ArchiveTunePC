use std::{
    collections::VecDeque,
    ffi::OsString,
    fs::File,
    io::{self, BufRead},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError},
        Arc,
    },
    thread,
    time::Duration,
};

use anyhow::{anyhow, bail, Context, Result};
use cpal::{
    traits::{DeviceTrait, HostTrait, StreamTrait},
    Device, FromSample, SampleFormat, SizedSample, Stream, StreamConfig,
};
use ogg::PacketReader;
use opus::MSDecoder;
use serde_json::{json, Value};

const OPUS_RATE: u32 = 48_000;
const STEM_CHANNELS: usize = 8;
const STEM_COUNT: usize = 4;
const OUTPUT_CHANNELS: usize = 2;
const MAX_OPUS_FRAME: usize = 5_760;
const QUEUED_CHUNKS: usize = 48;
const STEM_NAMES: [&str; STEM_COUNT] = ["drums", "bass", "other", "vocals"];

#[derive(Debug)]
struct Args {
    input: PathBuf,
    start_ms: u64,
}

impl Args {
    fn parse(mut args: impl Iterator<Item = OsString>) -> Result<Self> {
        let mut input = None;
        let mut start_ms = 0u64;
        while let Some(arg) = args.next() {
            match arg.to_string_lossy().as_ref() {
                "--input" => input = args.next().map(PathBuf::from),
                "--start-ms" => {
                    start_ms = args
                        .next()
                        .context("missing --start-ms value")?
                        .to_string_lossy()
                        .parse()
                        .context("invalid --start-ms")?;
                }
                "--help" | "-h" => {
                    println!("archivetune-stems play --input <stem-cache-directory> [--start-ms <milliseconds>]");
                    std::process::exit(0);
                }
                other => bail!("unknown play argument {other}"),
            }
        }
        Ok(Self {
            input: input.context("missing play --input")?,
            start_ms,
        })
    }
}

struct Controls {
    gains: [AtomicU32; STEM_COUNT],
    master: AtomicU32,
    paused: AtomicBool,
    stop: AtomicBool,
    seek_ms: AtomicI64,
    generation: AtomicU64,
}

impl Controls {
    fn new(start_ms: u64) -> Self {
        Self {
            gains: std::array::from_fn(|_| AtomicU32::new(1.0f32.to_bits())),
            master: AtomicU32::new(1.0f32.to_bits()),
            // ArchiveTune first aligns this renderer to the already-running VLC transport, then
            // suppresses VLC and explicitly releases us. Starting silent avoids a short doubled
            // audio burst while the helper process is booting.
            paused: AtomicBool::new(true),
            stop: AtomicBool::new(false),
            seek_ms: AtomicI64::new(start_ms.min(i64::MAX as u64) as i64),
            generation: AtomicU64::new(1),
        }
    }

    fn gain(&self, index: usize) -> f32 {
        f32::from_bits(self.gains[index].load(Ordering::Relaxed)).clamp(0.0, 1.0)
    }

    fn master(&self) -> f32 {
        f32::from_bits(self.master.load(Ordering::Relaxed)).clamp(0.0, 1.5)
    }
}

struct AudioChunk {
    generation: u64,
    samples: Vec<f32>,
}

#[derive(Clone)]
struct OpusHeader {
    pre_skip: usize,
    streams: u8,
    coupled_streams: u8,
    mapping: Vec<u8>,
    gain: f32,
}

pub fn run(args: impl Iterator<Item = OsString>) -> Result<()> {
    let args = Args::parse(args)?;
    if !args.input.is_dir() {
        bail!("stem cache input must be a directory: {}", args.input.display());
    }
    let headers: Vec<OpusHeader> = STEM_NAMES
        .iter()
        .map(|name| read_header(&args.input.join(format!("{name}.opus"))))
        .collect::<Result<_>>()?;
    for (index, header) in headers.iter().enumerate() {
        if header.mapping.len() != OUTPUT_CHANNELS {
            bail!("{} stem cache must be stereo; got {} channels", STEM_NAMES[index], header.mapping.len());
        }
    }
    let codec_gains: [f32; STEM_COUNT] = std::array::from_fn(|index| headers[index].gain);

    let controls = Arc::new(Controls::new(args.start_ms));
    let decoder_ended = Arc::new(AtomicBool::new(false));
    let output_drained = Arc::new(AtomicBool::new(false));
    let (sender, receiver) = mpsc::sync_channel::<AudioChunk>(QUEUED_CHUNKS);

    let decoder_controls = Arc::clone(&controls);
    let decoder_done = Arc::clone(&decoder_ended);
    let decoder_input = args.input.clone();
    let decoder = thread::Builder::new()
        .name("ArchiveTuneStemDecoder".into())
        .spawn(move || {
            if let Err(error) = decode_loop(decoder_input, decoder_controls, sender) {
                emit(json!({ "type": "error", "message": format!("{error:#}") }));
            }
            decoder_done.store(true, Ordering::Release);
        })?;

    let stream = build_output(
        receiver,
        Arc::clone(&controls),
        codec_gains,
        Arc::clone(&output_drained),
    )?;
    stream.play().context("start stem audio output")?;

    let command_controls = Arc::clone(&controls);
    let command_thread = thread::Builder::new()
        .name("ArchiveTuneStemCommands".into())
        .spawn(move || command_loop(command_controls))?;

    emit(json!({
        "type": "ready",
        "sample_rate": OPUS_RATE,
        "stems": STEM_COUNT,
        "source_order": ["drums", "bass", "other", "vocals"],
    }));

    while !controls.stop.load(Ordering::Acquire) && !output_drained.load(Ordering::Acquire) {
        thread::sleep(Duration::from_millis(40));
    }
    controls.stop.store(true, Ordering::Release);
    drop(stream);
    let _ = decoder.join();
    // stdin can remain open while the process naturally reaches EOF, so don't block shutdown on it.
    drop(command_thread);
    if decoder_ended.load(Ordering::Acquire) && output_drained.load(Ordering::Acquire) {
        emit(json!({ "type": "ended" }));
    }
    Ok(())
}

fn command_loop(controls: Arc<Controls>) {
    for line in io::stdin().lock().lines() {
        if controls.stop.load(Ordering::Acquire) {
            break;
        }
        let Ok(line) = line else { break };
        let Ok(command) = serde_json::from_str::<Value>(&line) else { continue };
        match command.get("type").and_then(Value::as_str) {
            Some("gains") => {
                if let Some(values) = command.get("values").and_then(Value::as_array) {
                    for (index, value) in values.iter().take(STEM_COUNT).enumerate() {
                        if let Some(value) = value.as_f64() {
                            controls.gains[index].store((value as f32).clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
                        }
                    }
                }
            }
            Some("master") => {
                if let Some(value) = command.get("value").and_then(Value::as_f64) {
                    controls.master.store((value as f32).clamp(0.0, 1.5).to_bits(), Ordering::Relaxed);
                }
            }
            Some("pause") => {
                if let Some(value) = command.get("value").and_then(Value::as_bool) {
                    controls.paused.store(value, Ordering::Release);
                }
            }
            Some("seek") => {
                if let Some(value) = command.get("position_ms").and_then(Value::as_u64) {
                    controls.seek_ms.store(value.min(i64::MAX as u64) as i64, Ordering::Release);
                    controls.generation.fetch_add(1, Ordering::AcqRel);
                }
            }
            Some("stop") => {
                controls.stop.store(true, Ordering::Release);
                break;
            }
            _ => {}
        }
    }
}

fn decode_loop(input: PathBuf, controls: Arc<Controls>, sender: SyncSender<AudioChunk>) -> Result<()> {
    loop {
        if controls.stop.load(Ordering::Acquire) {
            return Ok(());
        }
        let requested = controls.seek_ms.swap(-1, Ordering::AcqRel);
        let start_ms = requested.max(0) as u64;
        let generation = controls.generation.load(Ordering::Acquire);
        let reached_eof = decode_from_position(&input, &controls, &sender, generation, start_ms)?;
        if reached_eof && controls.seek_ms.load(Ordering::Acquire) < 0 {
            return Ok(());
        }
    }
}

fn decode_from_position(
    input: &PathBuf,
    controls: &Arc<Controls>,
    sender: &SyncSender<AudioChunk>,
    generation: u64,
    start_ms: u64,
) -> Result<bool> {
    let mut decoders: Vec<StemDecoder> = STEM_NAMES
        .iter()
        .map(|name| StemDecoder::open(input.join(format!("{name}.opus")), start_ms))
        .collect::<Result<_>>()?;
    let mut pcm: Vec<Vec<f32>> = (0..STEM_COUNT)
        .map(|_| vec![0.0f32; MAX_OPUS_FRAME * OUTPUT_CHANNELS])
        .collect();

    loop {
        if controls.stop.load(Ordering::Acquire) {
            return Ok(false);
        }
        if controls.generation.load(Ordering::Acquire) != generation || controls.seek_ms.load(Ordering::Acquire) >= 0 {
            return Ok(false);
        }
        let mut ranges = Vec::with_capacity(STEM_COUNT);
        let mut eof_count = 0usize;
        for source in 0..STEM_COUNT {
            let next = decoders[source].next_audio(&mut pcm[source], controls, generation)?;
            if controls.stop.load(Ordering::Acquire)
                || controls.generation.load(Ordering::Acquire) != generation
                || controls.seek_ms.load(Ordering::Acquire) >= 0
            {
                return Ok(false);
            }
            match next {
                Some(range) => ranges.push(range),
                None => {
                    eof_count += 1;
                    ranges.push((0, 0));
                }
            }
        }
        if eof_count == STEM_COUNT {
            return Ok(true);
        }
        if eof_count != 0 {
            bail!("stem cache streams ended at different positions");
        }
        let output_frames = ranges[0].1 - ranges[0].0;
        if ranges.iter().any(|(start, end)| end - start != output_frames) {
            bail!("stem cache streams produced mismatched Opus frame sizes");
        }
        if output_frames == 0 {
            continue;
        }

        let mut raw = Vec::with_capacity(output_frames * STEM_CHANNELS);
        for local in 0..output_frames {
            for source in 0..STEM_COUNT {
                let frame = ranges[source].0 + local;
                let base = frame * OUTPUT_CHANNELS;
                raw.push(pcm[source][base]);
                raw.push(pcm[source][base + 1]);
            }
        }

        let mut chunk = AudioChunk { generation, samples: raw };
        loop {
            if controls.stop.load(Ordering::Acquire)
                || controls.generation.load(Ordering::Acquire) != generation
                || controls.seek_ms.load(Ordering::Acquire) >= 0
            {
                return Ok(false);
            }
            match sender.try_send(chunk) {
                Ok(()) => break,
                Err(TrySendError::Full(returned)) => {
                    chunk = returned;
                    thread::sleep(Duration::from_millis(3));
                }
                Err(TrySendError::Disconnected(_)) => return Ok(false),
            }
        }
    }
}

struct StemDecoder {
    reader: PacketReader<File>,
    decoder: MSDecoder,
    skip_frames: usize,
}

impl StemDecoder {
    fn open(path: PathBuf, start_ms: u64) -> Result<Self> {
        let header = read_header(&path)?;
        if header.mapping.len() != OUTPUT_CHANNELS {
            bail!("stem cache must be stereo: {}", path.display());
        }
        let file = File::open(&path).with_context(|| format!("open {}", path.display()))?;
        let mut reader = PacketReader::new(file);
        reader.read_packet().context("read OpusHead")?.context("empty Ogg stream")?;
        reader.read_packet().context("read OpusTags")?.context("missing OpusTags")?;
        let decoder = MSDecoder::new(OPUS_RATE, header.streams, header.coupled_streams, &header.mapping)
            .map_err(|error| anyhow!(error.to_string()))?;
        let seek_frames = ((start_ms as u128 * OPUS_RATE as u128) / 1_000) as usize;
        Ok(Self {
            reader,
            decoder,
            skip_frames: header.pre_skip + seek_frames,
        })
    }

    fn next_audio(
        &mut self,
        pcm: &mut [f32],
        controls: &Controls,
        generation: u64,
    ) -> Result<Option<(usize, usize)>> {
        loop {
            if controls.stop.load(Ordering::Acquire)
                || controls.generation.load(Ordering::Acquire) != generation
                || controls.seek_ms.load(Ordering::Acquire) >= 0
            {
                return Ok(None);
            }
            let Some(packet) = self.reader.read_packet().context("read Ogg Opus packet")? else {
                return Ok(None);
            };
            let frames = self
                .decoder
                .decode_float(&packet.data, pcm, false)
                .map_err(|error| anyhow!(error.to_string()))?;
            if frames == 0 {
                continue;
            }
            let start = self.skip_frames.min(frames);
            self.skip_frames -= start;
            if start == frames {
                continue;
            }
            return Ok(Some((start, frames)));
        }
    }
}

fn build_output(
    receiver: Receiver<AudioChunk>,
    controls: Arc<Controls>,
    codec_gains: [f32; STEM_COUNT],
    output_drained: Arc<AtomicBool>,
) -> Result<Stream> {
    let host = cpal::default_host();
    let device = host.default_output_device().context("no default audio output device")?;
    let supported = device
        .supported_output_configs()
        .context("query output formats")?
        .filter(|config| {
            config.channels() >= OUTPUT_CHANNELS as u16
                && config.min_sample_rate() <= OPUS_RATE
                && config.max_sample_rate() >= OPUS_RATE
        })
        .min_by_key(|config| {
            let sample_rank = match config.sample_format() {
                SampleFormat::F32 => 0,
                SampleFormat::I16 => 1,
                _ => 2,
            };
            (config.channels(), sample_rank)
        })
        .context("default audio device has no 48 kHz output format with at least two channels")?
        .with_sample_rate(OPUS_RATE);
    let sample_format = supported.sample_format();
    let config: StreamConfig = supported.config();
    let device_channels = config.channels as usize;
    emit(json!({
        "type": "device",
        "name": device.description().map(|d| d.to_string()).unwrap_or_else(|_| "default".into()),
        "sample_format": format!("{sample_format:?}"),
        "channels": device_channels,
    }));

    match sample_format {
        SampleFormat::F32 => build_typed_output::<f32>(&device, &config, device_channels, receiver, controls, codec_gains, output_drained),
        SampleFormat::I16 => build_typed_output::<i16>(&device, &config, device_channels, receiver, controls, codec_gains, output_drained),
        SampleFormat::I32 => build_typed_output::<i32>(&device, &config, device_channels, receiver, controls, codec_gains, output_drained),
        SampleFormat::U16 => build_typed_output::<u16>(&device, &config, device_channels, receiver, controls, codec_gains, output_drained),
        other => bail!("unsupported output sample format {other:?}"),
    }
}

fn build_typed_output<T>(
    device: &Device,
    config: &StreamConfig,
    device_channels: usize,
    receiver: Receiver<AudioChunk>,
    controls: Arc<Controls>,
    codec_gains: [f32; STEM_COUNT],
    output_drained: Arc<AtomicBool>,
) -> Result<Stream>
where
    T: SizedSample + FromSample<f32>,
{
    let mut queued = VecDeque::<AudioChunk>::new();
    let mut current: Option<AudioChunk> = None;
    let mut cursor = 0usize;
    let mut receiver_disconnected = false;
    let stream = device.build_output_stream(
        config.clone(),
        move |output: &mut [T], _| {
            let generation = controls.generation.load(Ordering::Acquire);
            if controls.paused.load(Ordering::Acquire) {
                output.fill(T::from_sample(0.0));
                return;
            }
            let gains = [
                controls.gain(0) * codec_gains[0],
                controls.gain(1) * codec_gains[1],
                controls.gain(2) * codec_gains[2],
                controls.gain(3) * codec_gains[3],
            ];
            let master = controls.master();
            loop {
                match receiver.try_recv() {
                    Ok(chunk) => queued.push_back(chunk),
                    Err(TryRecvError::Empty) => break,
                    Err(TryRecvError::Disconnected) => {
                        receiver_disconnected = true;
                        break;
                    }
                }
            }
            for frame in output.chunks_mut(device_channels) {
                frame.fill(T::from_sample(0.0));
                loop {
                    let invalid = current.as_ref().is_some_and(|chunk| chunk.generation != generation || cursor >= chunk.samples.len());
                    if invalid {
                        current = None;
                        cursor = 0;
                    }
                    if current.is_none() {
                        current = queued.pop_front().filter(|chunk| chunk.generation == generation);
                        cursor = 0;
                    }
                    if current.is_some() || queued.is_empty() {
                        break;
                    }
                }
                if let Some(chunk) = &current {
                    if cursor + STEM_CHANNELS - 1 < chunk.samples.len() {
                        // Apply gains at the device callback, not in the decoder thread. Decoded
                        // multichannel audio may be buffered ahead, but a fader/mute/solo command
                        // therefore takes effect on the very next hardware buffer instead of after
                        // that queue drains.
                        let left = (
                            chunk.samples[cursor] * gains[0]
                                + chunk.samples[cursor + 2] * gains[1]
                                + chunk.samples[cursor + 4] * gains[2]
                                + chunk.samples[cursor + 6] * gains[3]
                        ) * master;
                        let right = (
                            chunk.samples[cursor + 1] * gains[0]
                                + chunk.samples[cursor + 3] * gains[1]
                                + chunk.samples[cursor + 5] * gains[2]
                                + chunk.samples[cursor + 7] * gains[3]
                        ) * master;
                        frame[0] = T::from_sample(left.clamp(-1.0, 1.0));
                        frame[1] = T::from_sample(right.clamp(-1.0, 1.0));
                        cursor += STEM_CHANNELS;
                        continue;
                    }
                }
            }
            if receiver_disconnected && current.is_none() && queued.is_empty() {
                output_drained.store(true, Ordering::Release);
            }
        },
        |error| emit(json!({ "type": "audio_error", "message": error.to_string() })),
        None,
    )?;
    Ok(stream)
}

fn read_header(input: &PathBuf) -> Result<OpusHeader> {
    let file = File::open(input).with_context(|| format!("open {}", input.display()))?;
    let mut reader = PacketReader::new(file);
    let packet = reader.read_packet().context("read OpusHead")?.context("empty Ogg stream")?;
    let data = &packet.data;
    if data.len() < 19 || &data[..8] != b"OpusHead" {
        bail!("stem cache is not an Ogg Opus stream");
    }
    let channels = data[9] as usize;
    let pre_skip = u16::from_le_bytes([data[10], data[11]]) as usize;
    let gain_q8 = i16::from_le_bytes([data[16], data[17]]) as f32;
    let family = data[18];
    let (streams, coupled_streams, mapping) = if family == 0 {
        if channels > 2 {
            bail!("Opus mapping family 0 cannot carry {channels} channels");
        }
        (1, if channels == 2 { 1 } else { 0 }, (0..channels as u8).collect())
    } else {
        if data.len() < 21 + channels {
            bail!("truncated Opus multistream header");
        }
        (data[19], data[20], data[21..21 + channels].to_vec())
    };
    Ok(OpusHeader {
        pre_skip,
        streams,
        coupled_streams,
        mapping,
        gain: 10.0f32.powf((gain_q8 / 256.0) / 20.0),
    })
}

fn emit(value: Value) {
    println!("{value}");
}
