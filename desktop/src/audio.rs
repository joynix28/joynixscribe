use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::{Arc, Mutex};

pub struct Recorder {
    stream: Option<cpal::Stream>,
}

impl Recorder {
    pub fn new() -> Self { Self { stream: None } }

    pub fn has_input_device() -> bool {
        cpal::default_host().default_input_device().is_some()
    }

    pub fn start(&mut self, samples: Arc<Mutex<Vec<f32>>>, rate: u32) -> Result<(), String> {
        let host = cpal::default_host();
        let device = host.default_input_device().ok_or("Aucun micro détecté")?;
        let config = cpal::StreamConfig {
            channels: 1,
            sample_rate: cpal::SampleRate(rate),
            buffer_size: cpal::BufferSize::Default,
        };
        let samples2 = samples.clone();
        let stream = device.build_input_stream(
            &config,
            move |data: &[f32], _| {
                if let Ok(mut s) = samples2.lock() { s.extend_from_slice(data); }
            },
            |err| eprintln!("audio err: {}", err),
            None,
        ).map_err(|e| e.to_string())?;
        stream.play().map_err(|e| e.to_string())?;
        self.stream = Some(stream);
        Ok(())
    }

    pub fn stop(&mut self) {
        self.stream = None;
    }
}

pub fn encode_wav(samples: &[f32], rate: u32) -> Vec<u8> {
    let mut cursor = std::io::Cursor::new(Vec::new());
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: rate,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut writer = hound::WavWriter::new(&mut cursor, spec).unwrap();
    for &s in samples {
        let v = (s * 32767.0).clamp(-32768.0, 32767.0) as i16;
        let _ = writer.write_sample(v);
    }
    let _ = writer.finalize();
    cursor.into_inner()
}
