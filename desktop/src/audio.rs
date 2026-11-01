use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::{Arc, Mutex};

pub struct Recorder {
    stream: Option<cpal::Stream>,
    pub sample_rate: Arc<Mutex<u32>>,
}

impl Recorder {
    pub fn new() -> Self {
        Self { stream: None, sample_rate: Arc::new(Mutex::new(16000)) }
    }

    pub fn has_input_device() -> bool {
        cpal::default_host().default_input_device().is_some()
    }

    pub fn start(&mut self, samples: Arc<Mutex<Vec<f32>>>, _rate: u32) -> Result<(), String> {
        let host = cpal::default_host();
        let device = host.default_input_device().ok_or("Aucun micro detecte")?;
        let supported = device.default_input_config().map_err(|e| e.to_string())?;
        let config: cpal::StreamConfig = supported.clone().into();
        let rate = config.sample_rate.0;
        *self.sample_rate.lock().unwrap() = rate;
        let channels = config.channels as usize;
        let samples2 = samples.clone();
        let stream = device.build_input_stream(
            &config,
            move |data: &[f32], _| {
                if let Ok(mut s) = samples2.lock() {
                    if channels == 1 {
                        s.extend_from_slice(data);
                    } else {
                        // downmix to mono
                        for chunk in data.chunks(channels) {
                            let v = chunk.iter().sum::<f32>() / channels as f32;
                            s.push(v);
                        }
                    }
                }
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

    pub fn sample_rate(&self) -> u32 {
        *self.sample_rate.lock().unwrap()
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
