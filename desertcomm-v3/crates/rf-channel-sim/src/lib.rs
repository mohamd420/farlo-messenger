//! محاكي القناة اللاسلكية (AWGN, Gilbert-Elliott, Watterson HF) - v3.1

use rand::Rng;

pub fn apply_awgn_ber(data: &[u8], ber: f64, rng: &mut impl Rng) -> Vec<u8> {
    data.iter()
        .map(|&b| {
            let mut out = b;
            for bit in 0..8 {
                if rng.gen_bool(ber) {
                    out ^= 1 << bit;
                }
            }
            out
        })
        .collect()
}

pub struct GilbertElliottChannel {
    pub p_good_to_bad: f64,
    pub p_bad_to_good: f64,
    pub ber_good: f64,
    pub ber_bad: f64,
    pub in_bad_state: bool,
}

impl GilbertElliottChannel {
    pub fn desert_tactical() -> Self {
        Self {
            p_good_to_bad: 0.02,
            p_bad_to_good: 0.25,
            ber_good: 0.001,
            ber_bad: 0.20,
            in_bad_state: false,
        }
    }

    pub fn transmit(&mut self, data: &[u8], rng: &mut impl Rng) -> Vec<u8> {
        let mut out = Vec::with_capacity(data.len());
        for &b in data {
            if self.in_bad_state {
                if rng.gen_bool(self.p_bad_to_good) { self.in_bad_state = false; }
            } else if rng.gen_bool(self.p_good_to_bad) {
                self.in_bad_state = true;
            }
            let ber = if self.in_bad_state { self.ber_bad } else { self.ber_good };
            let mut corrupted = b;
            for bit in 0..8 {
                if rng.gen_bool(ber) { corrupted ^= 1 << bit; }
            }
            out.push(corrupted);
        }
        out
    }
}

pub fn apply_watterson_hf(data: &[u8], fade_prob: f64, fade_len_bytes: usize, rng: &mut impl Rng) -> Vec<u8> {
    let mut out = data.to_vec();
    let mut i = 0;
    while i < out.len() {
        if rng.gen_bool(fade_prob) {
            for j in i..(i + fade_len_bytes).min(out.len()) {
                out[j] ^= rng.gen::<u8>();
            }
            i += fade_len_bytes;
        } else {
            i += 1;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use fec_interleaver::{decode_cell_resilient, encode_cell_resilient};
    use rand::SeedableRng;

    #[test]
    fn test_pass_gateway_ber_4e2() {
        let mut rng = rand::rngs::StdRng::seed_from_u64(20261115);
        let trials = 500;
        let mut successes = 0;

        for t in 0..trials {
            let cell = [(t & 0xFF) as u8; 128];
            let encoded = encode_cell_resilient(&cell);
            let noisy = apply_awgn_ber(&encoded, 0.04, &mut rng);
            if let Ok(decoded) = decode_cell_resilient(&noisy) {
                if decoded == cell {
                    successes += 1;
                }
            }
        }
        let rate = (successes as f64) / (trials as f64);
        assert!(rate > 0.99, "Recovery rate at BER 4e-2 was {:.2}%, expected > 99%", rate * 100.0);
    }
}