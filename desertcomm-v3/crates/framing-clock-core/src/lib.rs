//! طبقة التأطير الزمني، الوسوم، ومكافحة الإعادة (v3.1)

use siphasher::sip::SipHasher24;
use std::collections::HashMap;
use std::hash::Hasher;
use std::sync::Mutex;
use thiserror::Error;
use zeroize::Zeroize;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum FramingError {
    #[error("CRITICAL: GNSS Time Spoofing Detected! Isolating GPS receiver.")]
    GnssSpoofingDetected,
    #[error("WARNING: Peers < 3. Using time as search hint only; switching to Stratum3.")]
    InsufficientConsensusPeers,
    #[error("ALERT: Mesh clock skew > 200ms corrected via N>=3 consensus.")]
    MeshSkewCorrected,
    #[error("Invalid cell length: expected {expected}, got {got}")]
    InvalidCellLength { expected: usize, got: usize },
}

// ============================================================================
// 1. نافذة الحماية من إعادة الإرسال (RFC 6479 - 128-bit Sliding Bitmap)
// ============================================================================

#[derive(Debug, Clone, Default)]
pub struct Rfc6479ReplayWindow {
    highest_seq: u64,
    bitmap: u128,
}

impl Rfc6479ReplayWindow {
    pub const WINDOW_SIZE: u64 = 128;

    pub fn new() -> Self {
        Self { highest_seq: 0, bitmap: 0 }
    }

    /// يجب أن يبدأ عداد المرسل دائماً من 1 (الرقم 0 مرفوض)
    pub fn check(&self, seq: u64) -> bool {
        if seq == 0 {
            return false;
        }
        if seq > self.highest_seq {
            return true;
        }
        let diff = self.highest_seq - seq;
        if diff >= Self::WINDOW_SIZE {
            return false;
        }
        (self.bitmap & (1u128 << diff)) == 0
    }

    /// يُستدعى حصراً بعد نجاح التحقق التشفيري من الحزمة (MAC)
    pub fn update(&mut self, seq: u64) {
        if seq == 0 {
            return;
        }
        if seq > self.highest_seq {
            let shift = seq - self.highest_seq;
            if shift >= Self::WINDOW_SIZE {
                self.bitmap = 1u128;
            } else {
                self.bitmap = (self.bitmap << shift) | 1u128;
            }
            self.highest_seq = seq;
        } else {
            let diff = self.highest_seq - seq;
            if diff < Self::WINDOW_SIZE {
                self.bitmap |= 1u128 << diff;
            }
        }
    }
}

// ============================================================================
// 2. الساعة الهجينة رباعية المستويات + إجماع Marzullo (N >= 3)
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum TimeStratum {
    Stratum1VerifiedGnss = 1,
    Stratum2TcxoHoldover = 2,
    Stratum3DegradedWide = 3,
    Stratum4ColdStart = 4,
}

#[derive(Debug, Clone, Copy)]
pub struct PeerTimeSample {
    pub peer_id: u16,
    pub reported_utc_ms: u64,
    pub peer_stratum: TimeStratum,
}

#[derive(Debug, Clone)]
pub struct ResilientClock {
    base_utc_ms: u64,
    base_monotonic_ms: u64,
    last_verified_sync_ms: u64,
    pub stratum: TimeStratum,
    pub search_hint_offset_ms: Option<i64>,
}

impl ResilientClock {
    const MAX_DRIFT_MS_PER_HOUR: u64 = 10;
    const SPOOF_THRESHOLD_BASE_MS: u64 = 50;
    const MIN_PEERS_FOR_CONSENSUS: usize = 3;

    pub fn new_cold(current_monotonic_ms: u64) -> Self {
        Self {
            base_utc_ms: 0,
            base_monotonic_ms: current_monotonic_ms,
            last_verified_sync_ms: 0,
            stratum: TimeStratum::Stratum4ColdStart,
            search_hint_offset_ms: None,
        }
    }

    pub fn current_utc_ms(&self, current_monotonic_ms: u64) -> u64 {
        let elapsed = current_monotonic_ms.saturating_sub(self.base_monotonic_ms);
        self.base_utc_ms.saturating_add(elapsed)
    }

    pub fn current_slot_minute(&self, current_monotonic_ms: u64) -> u64 {
        self.current_utc_ms(current_monotonic_ms) / 60_000
    }

    pub fn ingest_gnss_time(
        &mut self,
        gnss_utc_ms: u64,
        current_monotonic_ms: u64,
        osnma_authenticated: bool,
    ) -> Result<(), FramingError> {
        if self.stratum != TimeStratum::Stratum4ColdStart {
            let expected_utc = self.current_utc_ms(current_monotonic_ms);
            let delta = gnss_utc_ms.abs_diff(expected_utc);
            let elapsed_mono = current_monotonic_ms.saturating_sub(self.base_monotonic_ms);
            let elapsed_hours = (elapsed_mono / 3_600_000).max(1);
            let max_allowed_jump =
                (elapsed_hours * Self::MAX_DRIFT_MS_PER_HOUR) + Self::SPOOF_THRESHOLD_BASE_MS;

            if delta > max_allowed_jump {
                self.stratum = TimeStratum::Stratum2TcxoHoldover;
                return Err(FramingError::GnssSpoofingDetected);
            }
        }

        self.base_utc_ms = gnss_utc_ms;
        self.base_monotonic_ms = current_monotonic_ms;
        self.last_verified_sync_ms = current_monotonic_ms;
        self.search_hint_offset_ms = None;
        self.stratum = if osnma_authenticated {
            TimeStratum::Stratum1VerifiedGnss
        } else {
            TimeStratum::Stratum2TcxoHoldover
        };
        Ok(())
    }

    pub fn ingest_mesh_consensus(
        &mut self,
        samples: &[PeerTimeSample],
        current_monotonic_ms: u64,
    ) -> Result<(), FramingError> {
        let mut valid_samples: Vec<u64> = samples
            .iter()
            .filter(|s| {
                s.peer_stratum == TimeStratum::Stratum1VerifiedGnss
                    || s.peer_stratum == TimeStratum::Stratum2TcxoHoldover
            })
            .map(|s| s.reported_utc_ms)
            .collect();

        let my_utc = self.current_utc_ms(current_monotonic_ms);

        if valid_samples.len() < Self::MIN_PEERS_FOR_CONSENSUS {
            if let Some(&first_peer_utc) = valid_samples.first() {
                self.search_hint_offset_ms = Some(first_peer_utc as i64 - my_utc as i64);
            }
            if self.stratum != TimeStratum::Stratum1VerifiedGnss {
                self.stratum = TimeStratum::Stratum3DegradedWide;
            }
            return Err(FramingError::InsufficientConsensusPeers);
        }

        valid_samples.sort_unstable();
        let median_utc = valid_samples[valid_samples.len() / 2];
        let skew_alert = self.stratum != TimeStratum::Stratum4ColdStart && median_utc.abs_diff(my_utc) > 200;

        self.base_utc_ms = median_utc;
        self.base_monotonic_ms = current_monotonic_ms;
        self.last_verified_sync_ms = current_monotonic_ms;
        self.search_hint_offset_ms = None;
        self.stratum = TimeStratum::Stratum2TcxoHoldover;

        if skew_alert {
            Err(FramingError::MeshSkewCorrected)
        } else {
            Ok(())
        }
    }

    pub fn tick(&mut self, current_monotonic_ms: u64, os_sleep_detected: bool) {
        if os_sleep_detected {
            self.stratum = TimeStratum::Stratum3DegradedWide;
            return;
        }
        if self.stratum == TimeStratum::Stratum4ColdStart {
            return;
        }
        let stale_ms = current_monotonic_ms.saturating_sub(self.last_verified_sync_ms);
        self.stratum = match (self.stratum, stale_ms) {
            (TimeStratum::Stratum1VerifiedGnss, t) if t >= 7_200_000 => {
                TimeStratum::Stratum2TcxoHoldover
            }
            (TimeStratum::Stratum2TcxoHoldover, t) if t >= 86_400_000 => {
                TimeStratum::Stratum3DegradedWide
            }
            (s, t)
                if t < 7_200_000
                    && (s == TimeStratum::Stratum2TcxoHoldover
                        || s == TimeStratum::Stratum3DegradedWide) =>
            {
                TimeStratum::Stratum1VerifiedGnss
            }
            (s, _) => s,
        };
    }

    pub fn current_rx_window_ms(&self, current_monotonic_ms: u64) -> u64 {
        let stale_ms = current_monotonic_ms.saturating_sub(self.last_verified_sync_ms);
        match self.stratum {
            TimeStratum::Stratum1VerifiedGnss if stale_ms < 7_200_000 => 1_500,
            TimeStratum::Stratum1VerifiedGnss | TimeStratum::Stratum2TcxoHoldover
                if stale_ms < 86_400_000 =>
            {
                3_000
            }
            TimeStratum::Stratum3DegradedWide => 6_000,
            _ => 15_000,
        }
    }
}

// ============================================================================
// 3. جدول الوسوم المتدحرجة الآمن للخيوط (SipHash-2-4 + Slot Reset)
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GroupAnonymityMode {
    FloodResistantPerMember,
    FullSenderAnonymity,
}

pub fn compute_rolling_tag(
    k_group: &[u8; 32],
    member_id: u16,
    slot: u64,
    seq: u8,
    salt: &[u8; 4],
    mode: GroupAnonymityMode,
) -> u32 {
    let key0 = u64::from_le_bytes(k_group[0..8].try_into().unwrap());
    let key1 = u64::from_le_bytes(k_group[8..16].try_into().unwrap());
    let mut hasher = SipHasher24::new_with_keys(key0, key1);
    match mode {
        GroupAnonymityMode::FloodResistantPerMember => hasher.write_u16(member_id),
        GroupAnonymityMode::FullSenderAnonymity => hasher.write_u16(RateLimitedTagTable::ANONYMOUS_SHARED_ID),
    }
    hasher.write_u64(slot);
    hasher.write_u8(seq);
    hasher.write(salt);
    (hasher.finish() & 0xFFFF_FFFF) as u32
}

pub struct RateLimitedTagTable {
    pub expected_tags: HashMap<u32, (u16, u8)>,
    member_quota_used: HashMap<u16, u8>,
    pub mode: GroupAnonymityMode,
}

impl RateLimitedTagTable {
    pub const MAX_PACKETS_PER_MEMBER_SLOT: u8 = 10;
    pub const MAX_PACKETS_ANONYMOUS_GROUP_SLOT: u8 = 50;
    pub const ANONYMOUS_SHARED_ID: u16 = 0xFFFF;

    pub fn new(mode: GroupAnonymityMode) -> Self {
        Self {
            expected_tags: HashMap::with_capacity(512),
            member_quota_used: HashMap::new(),
            mode,
        }
    }

    pub fn register_expected_tag(&mut self, tag: u32, member_id: u16, seq: u8) {
        self.expected_tags.insert(tag, (member_id, seq));
    }

    pub fn reset_slot(&mut self) {
        self.member_quota_used.clear();
        self.expected_tags.clear();
    }

    pub fn match_and_consume_tag(&mut self, incoming_tag: u32) -> Option<u16> {
        let (member_id, _) = self.expected_tags.remove(&incoming_tag)?;
        let quota_key = match self.mode {
            GroupAnonymityMode::FloodResistantPerMember => member_id,
            GroupAnonymityMode::FullSenderAnonymity => Self::ANONYMOUS_SHARED_ID,
        };
        let limit = match self.mode {
            GroupAnonymityMode::FloodResistantPerMember => Self::MAX_PACKETS_PER_MEMBER_SLOT,
            GroupAnonymityMode::FullSenderAnonymity => Self::MAX_PACKETS_ANONYMOUS_GROUP_SLOT,
        };

        let used = self.member_quota_used.entry(quota_key).or_insert(0);
        if *used >= limit {
            return None;
        }
        *used += 1;
        Some(member_id)
    }
}

pub struct ThreadSafeTagTable {
    inner: Mutex<RateLimitedTagTable>,
}

impl ThreadSafeTagTable {
    pub fn new(mode: GroupAnonymityMode) -> Self {
        Self { inner: Mutex::new(RateLimitedTagTable::new(mode)) }
    }

    pub fn register(&self, tag: u32, member_id: u16, seq: u8) {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        guard.register_expected_tag(tag, member_id, seq);
    }

    pub fn try_consume(&self, tag: u32) -> Option<u16> {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        guard.match_and_consume_tag(tag)
    }

    pub fn on_slot_boundary(&self) {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        guard.reset_slot();
    }
}

// ============================================================================
// 4. هيكل الخلية الفيزيائية الثابتة (128 بايت)
// ============================================================================

#[derive(Debug, Clone, Zeroize)]
#[zeroize(drop)]
pub struct FixedRfCell {
    pub sync_word: [u8; 8],
    pub salt: [u8; 4],
    pub rolling_tag: u32,
    pub encrypted_payload: [u8; 112],
}

impl FixedRfCell {
    pub const SIZE: usize = 128;

    pub fn derive_sync_word(k_net: &[u8; 32], slot: u64) -> [u8; 8] {
        let mut hasher = blake3::Hasher::new_keyed(k_net);
        hasher.update(&slot.to_le_bytes());
        let hash = hasher.finalize();
        let mut sync = [0u8; 8];
        sync.copy_from_slice(&hash.as_bytes()[0..8]);
        sync
    }

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut out = [0u8; Self::SIZE];
        out[0..8].copy_from_slice(&self.sync_word);
        out[8..12].copy_from_slice(&self.salt);
        out[12..16].copy_from_slice(&self.rolling_tag.to_le_bytes());
        out[16..128].copy_from_slice(&self.encrypted_payload);
        out
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, FramingError> {
        if bytes.len() != Self::SIZE {
            return Err(FramingError::InvalidCellLength {
                expected: Self::SIZE,
                got: bytes.len(),
            });
        }
        let mut sync_word = [0u8; 8];
        sync_word.copy_from_slice(&bytes[0..8]);
        let mut salt = [0u8; 4];
        salt.copy_from_slice(&bytes[8..12]);
        let rolling_tag = u32::from_le_bytes(bytes[12..16].try_into().unwrap());
        let mut encrypted_payload = [0u8; 112];
        encrypted_payload.copy_from_slice(&bytes[16..128]);
        Ok(Self { sync_word, salt, rolling_tag, encrypted_payload })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rfc6479_window() {
        let mut w = Rfc6479ReplayWindow::new();
        assert!(!w.check(0));
        assert!(w.check(1));
        w.update(1);
        assert!(!w.check(1));

        assert!(w.check(5));
        w.update(5);
        assert!(w.check(3));
        w.update(3);
        assert!(!w.check(3));

        assert!(w.check(200));
        w.update(200);
        assert!(!w.check(5));
    }

    #[test]
    fn test_gnss_spoofing_detection() {
        let mut clock = ResilientClock::new_cold(1_000);
        assert!(clock.ingest_gnss_time(1_700_000_000_000, 1_000, true).is_ok());
        assert_eq!(clock.stratum, TimeStratum::Stratum1VerifiedGnss);

        // قفزة +5000ms خلال ساعة واحدة (تتجاوز 60ms) -> تضليل مؤكد
        let res = clock.ingest_gnss_time(1_700_000_005_000, 2_000, true);
        assert_eq!(res, Err(FramingError::GnssSpoofingDetected));
        assert_eq!(clock.stratum, TimeStratum::Stratum2TcxoHoldover);
    }

    #[test]
    fn test_mesh_consensus_rules() {
        let mut clock = ResilientClock::new_cold(1_000);
        let two_peers = vec![
            PeerTimeSample { peer_id: 1, reported_utc_ms: 1_700_000_000_000, peer_stratum: TimeStratum::Stratum1VerifiedGnss },
            PeerTimeSample { peer_id: 2, reported_utc_ms: 1_700_000_000_010, peer_stratum: TimeStratum::Stratum2TcxoHoldover },
        ];
        assert_eq!(clock.ingest_mesh_consensus(&two_peers, 1_000), Err(FramingError::InsufficientConsensusPeers));
        assert_eq!(clock.stratum, TimeStratum::Stratum3DegradedWide);
        assert!(clock.search_hint_offset_ms.is_some());

        let three_peers_with_outlier = vec![
            PeerTimeSample { peer_id: 1, reported_utc_ms: 1_700_000_000_000, peer_stratum: TimeStratum::Stratum1VerifiedGnss },
            PeerTimeSample { peer_id: 2, reported_utc_ms: 1_700_000_000_020, peer_stratum: TimeStratum::Stratum2TcxoHoldover },
            PeerTimeSample { peer_id: 3, reported_utc_ms: 1_700_099_000_000, peer_stratum: TimeStratum::Stratum1VerifiedGnss },
        ];
        let _ = clock.ingest_mesh_consensus(&three_peers_with_outlier, 2_000);
        assert_eq!(clock.stratum, TimeStratum::Stratum2TcxoHoldover);
        assert_eq!(clock.current_utc_ms(2_000), 1_700_000_000_020);
    }

    #[test]
    fn test_tag_rate_limiting_and_slot_reset() {
        let table = ThreadSafeTagTable::new(GroupAnonymityMode::FloodResistantPerMember);
        let k_group = [7u8; 32];
        let salt = [1, 2, 3, 4];

        for seq in 1..=12u8 {
            let tag = compute_rolling_tag(&k_group, 42, 100, seq, &salt, GroupAnonymityMode::FloodResistantPerMember);
            table.register(tag, 42, seq);
            let res = table.try_consume(tag);
            if seq <= 10 {
                assert_eq!(res, Some(42));
            } else {
                assert_eq!(res, None, "11th packet must be dropped");
            }
        }

        table.on_slot_boundary();
        let tag_next = compute_rolling_tag(&k_group, 42, 101, 1, &salt, GroupAnonymityMode::FloodResistantPerMember);
        table.register(tag_next, 42, 1);
        assert_eq!(table.try_consume(tag_next), Some(42));
    }

    #[test]
    fn test_fixed_cell_roundtrip() {
        let k_net = [9u8; 32];
        let cell = FixedRfCell {
            sync_word: FixedRfCell::derive_sync_word(&k_net, 555),
            salt: [10, 20, 30, 40],
            rolling_tag: 0xDEADBEEF,
            encrypted_payload: [0xAB; 112],
        };
        let raw = cell.to_bytes();
        let parsed = FixedRfCell::from_bytes(&raw).unwrap();
        assert_eq!(parsed.sync_word, cell.sync_word);
        assert_eq!(parsed.rolling_tag, 0xDEADBEEF);
    }
}