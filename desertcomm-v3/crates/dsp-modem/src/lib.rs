//! طبقة المودم الفيزيائي، الطورين التشغيليين، وحماية Slot-0 (v3.1)

pub mod sub_noise_8fsk;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OperationalMode {
    ExpeditionSar,
    HostileTactical,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportPath {
    Path1LocalBleWifi,
    Path2TacticalLoRaVhf,
    Path3StrategicHfNvis,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModemEvent {
    BleHopChannel(u8),
    Slot0HaltedRecommendCrv,
}

#[derive(Default)]
pub struct Slot0Guard {
    consecutive_failures: u8,
    pub halted: bool,
}

impl Slot0Guard {
    pub const MAX_FAILURES: u8 = 3;

    pub fn new() -> Self {
        Self { consecutive_failures: 0, halted: false }
    }

    pub fn record_failure(&mut self) -> Option<ModemEvent> {
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        if self.consecutive_failures >= Self::MAX_FAILURES {
            self.halted = true;
            Some(ModemEvent::Slot0HaltedRecommendCrv)
        } else {
            None
        }
    }

    pub fn record_success(&mut self) {
        self.consecutive_failures = 0;
        self.halted = false;
    }
}

pub fn next_ble_adv_channel(seed: u64, tick_100ms: u64) -> u8 {
    const CHANNELS: [u8; 3] = [37, 38, 39];
    let mixed = seed.wrapping_add(tick_100ms).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    CHANNELS[((mixed >> 32) % 3) as usize]
}

pub mod kiss {
    const FEND: u8 = 0xC0;
    const FESC: u8 = 0xDB;
    const TFEND: u8 = 0xDC;
    const TFESC: u8 = 0xDD;

    pub fn encode_frame(payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(payload.len() + 8);
        out.push(FEND);
        out.push(0x00);
        for &b in payload {
            match b {
                FEND => { out.push(FESC); out.push(TFEND); }
                FESC => { out.push(FESC); out.push(TFESC); }
                _ => out.push(b),
            }
        }
        out.push(FEND);
        out
    }

    pub fn decode_frame(frame: &[u8]) -> Option<Vec<u8>> {
        let mut out = Vec::with_capacity(frame.len());
        let mut i = 0;
        while i < frame.len() && frame[i] == FEND { i += 1; }
        if i >= frame.len() { return None; }
        i += 1;
        while i < frame.len() {
            let b = frame[i];
            if b == FEND {
                return Some(out);
            } else if b == FESC {
                i += 1;
                if i >= frame.len() { return None; }
                match frame[i] {
                    TFEND => out.push(FEND),
                    TFESC => out.push(FESC),
                    _ => return None,
                }
            } else {
                out.push(b);
            }
            i += 1;
        }
        None
    }
}

pub fn bytes_to_4fsk_symbols(data: &[u8]) -> Vec<u8> {
    let mut syms = Vec::with_capacity(data.len() * 4);
    for &b in data {
        syms.push((b >> 6) & 0x03);
        syms.push((b >> 4) & 0x03);
        syms.push((b >> 2) & 0x03);
        syms.push(b & 0x03);
    }
    syms
}

pub fn symbols_4fsk_to_bytes(syms: &[u8]) -> Vec<u8> {
    syms.chunks_exact(4)
        .map(|c| ((c[0] & 3) << 6) | ((c[1] & 3) << 4) | ((c[2] & 3) << 2) | (c[3] & 3))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_slot0_halts_after_3_failures() {
        let mut guard = Slot0Guard::new();
        assert_eq!(guard.record_failure(), None);
        assert_eq!(guard.record_failure(), None);
        assert_eq!(guard.record_failure(), Some(ModemEvent::Slot0HaltedRecommendCrv));
        assert!(guard.halted);
    }

    #[test]
    fn test_kiss_roundtrip_with_escapes() {
        let payload = vec![0x01, 0xC0, 0xDB, 0xFF, 0xC0];
        let frame = kiss::encode_frame(&payload);
        assert_eq!(kiss::decode_frame(&frame).unwrap(), payload);
    }

    #[test]
    fn test_4fsk_roundtrip() {
        let data = b"DesertComm-VHF";
        let syms = bytes_to_4fsk_symbols(data);
        assert_eq!(symbols_4fsk_to_bytes(&syms), data);
    }
}