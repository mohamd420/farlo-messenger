//! مودم الاختراق العميق تحت الضجيج: 8-FSK Costas Array (شبيه FT8/JS8Call)

use std::f32::consts::PI;

pub const COSTAS_7: [u8; 7] = [3, 1, 4, 0, 6, 5, 2];
pub const TONE_SPACING_HZ: f32 = 6.25;
pub const SYMBOL_DURATION_SEC: f32 = 0.16; // 1 / 6.25 Hz (متعامد تماماً)
pub const DATA_SYMBOLS_PER_HALF: usize = 29;
pub const TOTAL_FRAME_SYMBOLS: usize = 7 + 29 + 7 + 29 + 7; // 79 رمزاً = 12.64 ثانية ضمن نافذة 15 ثانية

/// تحويل البايتات إلى رموز 8-FSK (3 بت لكل رمز) وإدراج مصفوفة Costas الثلاثية
pub fn encode_8fsk_frame(payload: &[u8]) -> Vec<u8> {
    let mut data_syms = Vec::new();
    let mut bit_buf: u32 = 0;
    let mut bit_count: u8 = 0;

    for &b in payload {
        bit_buf = (bit_buf << 8) | (b as u32);
        bit_count += 8;
        while bit_count >= 3 {
            bit_count -= 3;
            data_syms.push(((bit_buf >> bit_count) & 0x07) as u8);
        }
    }
    if bit_count > 0 {
        data_syms.push(((bit_buf << (3 - bit_count)) & 0x07) as u8);
    }

    let max_data = DATA_SYMBOLS_PER_HALF * 2;
    data_syms.resize(max_data, 0);

    let mut frame = Vec::with_capacity(TOTAL_FRAME_SYMBOLS);
    frame.extend_from_slice(&COSTAS_7);
    frame.extend_from_slice(&data_syms[0..DATA_SYMBOLS_PER_HALF]);
    frame.extend_from_slice(&COSTAS_7);
    frame.extend_from_slice(&data_syms[DATA_SYMBOLS_PER_HALF..max_data]);
    frame.extend_from_slice(&COSTAS_7);
    frame
}

pub fn decode_8fsk_frame(frame: &[u8], expected_byte_len: usize) -> Option<Vec<u8>> {
    if frame.len() < TOTAL_FRAME_SYMBOLS {
        return None;
    }
    let mut data_syms = Vec::with_capacity(DATA_SYMBOLS_PER_HALF * 2);
    data_syms.extend_from_slice(&frame[7..7 + DATA_SYMBOLS_PER_HALF]);
    data_syms.extend_from_slice(&frame[14 + DATA_SYMBOLS_PER_HALF..14 + DATA_SYMBOLS_PER_HALF * 2]);

    let mut out = Vec::with_capacity(expected_byte_len);
    let mut bit_buf: u32 = 0;
    let mut bit_count: u8 = 0;

    for &sym in &data_syms {
        bit_buf = (bit_buf << 3) | ((sym & 0x07) as u32);
        bit_count += 3;
        while bit_count >= 8 {
            bit_count -= 8;
            out.push(((bit_buf >> bit_count) & 0xFF) as u8);
            if out.len() == expected_byte_len {
                return Some(out);
            }
        }
    }
    Some(out)
}

/// توليد إشارة صوتية PCM (16-bit) متصلة الطور
pub fn synthesize_8fsk_pcm(symbols: &[u8], base_freq_hz: f32, sample_rate: u32) -> Vec<i16> {
    let samples_per_sym = (sample_rate as f32 * SYMBOL_DURATION_SEC).round() as usize;
    let mut pcm = Vec::with_capacity(symbols.len() * samples_per_sym);
    let mut phase: f32 = 0.0;

    for &sym in symbols {
        let freq = base_freq_hz + ((sym & 0x07) as f32) * TONE_SPACING_HZ;
        let phase_step = 2.0 * PI * freq / (sample_rate as f32);
        for _ in 0..samples_per_sym {
            pcm.push((phase.sin() * 16_000.0) as i16);
            phase += phase_step;
            if phase > 2.0 * PI {
                phase -= 2.0 * PI;
            }
        }
    }
    pcm
}

fn goertzel_power(samples: &[i16], target_freq_hz: f32, sample_rate: u32) -> f32 {
    let n = samples.len() as f32;
    let k = (0.5 + (n * target_freq_hz) / (sample_rate as f32)).floor();
    let omega = (2.0 * PI * k) / n;
    let coeff = 2.0 * omega.cos();
    let mut q1 = 0.0f32;
    let mut q2 = 0.0f32;
    for &s in samples {
        let q0 = coeff * q1 - q2 + (s as f32);
        q2 = q1;
        q1 = q0;
    }
    q1 * q1 + q2 * q2 - q1 * q2 * coeff
}

/// فك تضمين الصوت عبر كاشف Goertzel للـ 8 نغمات
pub fn demodulate_8fsk_pcm(pcm: &[i16], base_freq_hz: f32, sample_rate: u32) -> Vec<u8> {
    let samples_per_sym = (sample_rate as f32 * SYMBOL_DURATION_SEC).round() as usize;
    let mut syms = Vec::new();
    for chunk in pcm.chunks_exact(samples_per_sym) {
        let mut best_tone = 0u8;
        let mut best_pow = -1.0f32;
        for tone in 0..8u8 {
            let freq = base_freq_hz + (tone as f32) * TONE_SPACING_HZ;
            let p = goertzel_power(chunk, freq, sample_rate);
            if p > best_pow {
                best_pow = p;
                best_tone = tone;
            }
        }
        syms.push(best_tone);
    }
    syms
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_8fsk_costas_audio_roundtrip() {
        let msg = b"34.7324N,36.7137E"; // إحداثيات حمص (17 بايت)
        let frame = encode_8fsk_frame(msg);
        assert_eq!(frame.len(), TOTAL_FRAME_SYMBOLS);
        assert_eq!(&frame[0..7], &COSTAS_7);

        let pcm = synthesize_8fsk_pcm(&frame, 1500.0, 12_000);
        let rx_syms = demodulate_8fsk_pcm(&pcm, 1500.0, 12_000);
        let decoded = decode_8fsk_frame(&rx_syms, msg.len()).unwrap();
        assert_eq!(decoded, msg);
    }
}