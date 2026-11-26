//! طبقة التعمية، الهوية، واللقاء الزمكاني (v3.1)

pub mod elligator2;

use bip39::Mnemonic;
use thiserror::Error;
use zeroize::{Zeroize, ZeroizeOnDrop};

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("Noise protocol error: {0}")]
    Noise(#[from] snow::Error),
    #[error("SECURITY VIOLATION: CRV Passphrase must contain at least 8 Diceware words.")]
    InsufficientCrvEntropy,
    #[error("Invalid SAS derivation")]
    SasError,
}

// ============================================================================
// 1. سلسلة المفاتيح المسبقة اللانهائية (HKDF-BLAKE3 Master Chain)
// ============================================================================

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct PrekeyMasterChain {
    chain_key: [u8; 32],
    index: u64,
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct DerivedPrekey {
    pub index: u64,
    pub key: [u8; 32],
}

impl PrekeyMasterChain {
    pub fn new(root_chain_key: [u8; 32]) -> Self {
        Self { chain_key: root_chain_key, index: 0 }
    }

    pub fn current_index(&self) -> u64 {
        self.index
    }

    /// اشتقاق مفتاح الجلسة التالي والتقدم في السلسلة مع مسح المفتاح السابق فوراً
    pub fn advance(&mut self) -> DerivedPrekey {
        let idx = self.index;

        let mut prekey_hasher = blake3::Hasher::new_keyed(&self.chain_key);
        prekey_hasher.update(b"prekey-derivation");
        prekey_hasher.update(&idx.to_le_bytes());
        let mut derived = [0u8; 32];
        derived.copy_from_slice(prekey_hasher.finalize().as_bytes());

        let mut next_hasher = blake3::Hasher::new_keyed(&self.chain_key);
        next_hasher.update(b"chain-advance\x01");
        let mut next_chain = [0u8; 32];
        next_chain.copy_from_slice(next_hasher.finalize().as_bytes());

        self.chain_key.zeroize();
        self.chain_key = next_chain;
        next_chain.zeroize();
        self.index += 1;

        DerivedPrekey { index: idx, key: derived }
    }
}

// ============================================================================
// 2. التحقق خارج القناة (Trust State + 8-Word BIP-39 SAS)
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrustState {
    UnverifiedAirLink,
    Verified,
}

pub fn derive_sas_8_words(handshake_hash: &[u8; 32]) -> Result<[String; 8], CryptoError> {
    // نأخذ 128 بت من بصمة المصافحة (12 كلمة BIP-39) ونعرض أول 8 كلمات للتحقق البشري
    let mnemonic = Mnemonic::from_entropy(&handshake_hash[0..16])
        .map_err(|_| CryptoError::SasError)?;
    let words: Vec<String> = mnemonic.words().take(8).map(|w| w.to_string()).collect();
    words.try_into().map_err(|_| CryptoError::SasError)
}

// ============================================================================
// 3. جلسات Noise_IK (Zero-RTT) و Noise_XX (التعارف عبر الأثير)
// ============================================================================

const NOISE_IK_PARAMS: &str = "Noise_IK_25519_ChaChaPoly_BLAKE2s";
const NOISE_XX_PARAMS: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";

pub struct NoiseChannel {
    pub state: snow::HandshakeState,
    pub trust: TrustState,
}

impl NoiseChannel {
    pub fn new_ik_initiator(local_priv: &[u8; 32], remote_pub: &[u8; 32]) -> Result<Self, CryptoError> {
        let builder = snow::Builder::new(NOISE_IK_PARAMS.parse()?);
        let state = builder
            .local_private_key(local_priv)
            .remote_public_key(remote_pub)
            .build_initiator()?;
        Ok(Self { state, trust: TrustState::Verified })
    }

    pub fn new_ik_responder(local_priv: &[u8; 32]) -> Result<Self, CryptoError> {
        let builder = snow::Builder::new(NOISE_IK_PARAMS.parse()?);
        let state = builder.local_private_key(local_priv).build_responder()?;
        Ok(Self { state, trust: TrustState::Verified })
    }

    pub fn new_xx_initiator(local_priv: &[u8; 32]) -> Result<Self, CryptoError> {
        let builder = snow::Builder::new(NOISE_XX_PARAMS.parse()?);
        let state = builder.local_private_key(local_priv).build_initiator()?;
        Ok(Self { state, trust: TrustState::UnverifiedAirLink })
    }

    pub fn new_xx_responder(local_priv: &[u8; 32]) -> Result<Self, CryptoError> {
        let builder = snow::Builder::new(NOISE_XX_PARAMS.parse()?);
        let state = builder.local_private_key(local_priv).build_responder()?;
        Ok(Self { state, trust: TrustState::UnverifiedAirLink })
    }

    pub fn verify_out_of_band(&mut self) {
        self.trust = TrustState::Verified;
    }

    pub fn sas_words(&self) -> Result<[String; 8], CryptoError> {
        let h = self.state.get_handshake_hash();
        let mut arr = [0u8; 32];
        let len = h.len().min(32);
        arr[..len].copy_from_slice(&h[..len]);
        derive_sas_8_words(&arr)
    }
}

// ============================================================================
// 4. مشتق نقاط اللقاء الزمكانية عالي الإنتروبيا (High-Entropy CRV)
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SpatioTemporalCrv {
    pub channel_index: u16,
    pub wake_second: u8,
    pub rendezvous_tag: u32,
}

impl SpatioTemporalCrv {
    pub fn derive_rendezvous(
        diceware_passphrase: &str,
        mgrs_10km_grid: &str,
        mission_nonce: &[u8; 16],
        hour_counter: u64,
        total_rf_channels: u16,
    ) -> Result<Self, CryptoError> {
        if diceware_passphrase.split_whitespace().count() < 8 {
            return Err(CryptoError::InsufficientCrvEntropy);
        }

        let mut input = Vec::with_capacity(256);
        input.extend_from_slice(diceware_passphrase.as_bytes());
        input.push(0);
        input.extend_from_slice(mgrs_10km_grid.as_bytes());
        input.push(0);
        input.extend_from_slice(mission_nonce);
        input.extend_from_slice(&hour_counter.to_le_bytes());

        let key = blake3::derive_key("desert-crv-v3.1", &input);
        let ch_raw = u16::from_le_bytes([key[0], key[1]]);
        let channel_index = ch_raw % total_rf_channels.max(1);
        let wake_second = key[2] % 60;
        let rendezvous_tag = u32::from_le_bytes([key[4], key[5], key[6], key[7]]);

        Ok(Self { channel_index, wake_second, rendezvous_tag })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::rngs::OsRng;

    #[test]
    fn test_elligator2_roundtrip() {
        let mut rng = OsRng;
        for _ in 0..5 {
            let (_sec, pubk, rep) = elligator2::generate_elligator2_keypair(&mut rng);
            let decoded = elligator2::elligator2_decode(&rep);
            assert_eq!(decoded, *pubk.as_bytes());
        }
    }

    #[test]
    fn test_master_chain_forward_secrecy() {
        let root = [0x42u8; 32];
        let mut alice = PrekeyMasterChain::new(root);
        let mut bob = PrekeyMasterChain::new(root);

        let k0_a = alice.advance();
        let k1_a = alice.advance();
        let k0_b = bob.advance();
        assert_eq!(k0_a.key, k0_b.key);
        assert_ne!(k0_a.key, k1_a.key);
    }

    #[test]
    fn test_noise_xx_and_sas_8_words() {
        let builder_kp = snow::Builder::new(NOISE_XX_PARAMS.parse().unwrap());
        let alice_kp = builder_kp.generate_keypair().unwrap();
        let bob_kp = builder_kp.generate_keypair().unwrap();

        let mut alice = NoiseChannel::new_xx_initiator(&alice_kp.private.try_into().unwrap()).unwrap();
        let mut bob = NoiseChannel::new_xx_responder(&bob_kp.private.try_into().unwrap()).unwrap();
        assert_eq!(alice.trust, TrustState::UnverifiedAirLink);

        let mut buf1 = [0u8; 1024];
        let mut buf2 = [0u8; 1024];
        let mut out = [0u8; 1024];

        let n1 = alice.state.write_message(&[], &mut buf1).unwrap();
        bob.state.read_message(&buf1[..n1], &mut out).unwrap();

        let n2 = bob.state.write_message(&[], &mut buf2).unwrap();
        alice.state.read_message(&buf2[..n2], &mut out).unwrap();

        let n3 = alice.state.write_message(&[], &mut buf1).unwrap();
        bob.state.read_message(&buf1[..n3], &mut out).unwrap();

        assert_eq!(alice.sas_words().unwrap(), bob.sas_words().unwrap());
        alice.verify_out_of_band();
        assert_eq!(alice.trust, TrustState::Verified);
    }

    #[test]
    fn test_crv_high_entropy_enforced() {
        let nonce = [7u8; 16];
        assert!(SpatioTemporalCrv::derive_rendezvous("only four words here", "37SBU1234", &nonce, 10, 64).is_err());

        let ok = SpatioTemporalCrv::derive_rendezvous(
            "alpha bravo charlie delta echo foxtrot golf hotel",
            "37SBU1234",
            &nonce,
            10,
            64,
        ).unwrap();
        assert!(ok.channel_index < 64);
        assert!(ok.wake_second < 60);
    }
}