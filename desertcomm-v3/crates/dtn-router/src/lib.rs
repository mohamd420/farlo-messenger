//! طبقة التخزين والتمرير الأعمى (Blind DTN) وتشفير المجموعات (v3.1)

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand::RngCore;
use std::collections::{HashMap, VecDeque};
use thiserror::Error;
use zeroize::{Zeroize, ZeroizeOnDrop};

#[derive(Debug, Error)]
pub enum DtnError {
    #[error("Storage encryption/decryption failed")]
    StorageCryptoError,
    #[error("Group rekey required (>24h elapsed)")]
    RekeyRequired,
    #[error("Bundle exceeds max store capacity")]
    BundleTooLarge,
}

#[derive(Debug, Clone)]
pub struct DtnBundle {
    pub id: [u8; 32],
    pub created_utc_ms: u64,
    pub ttl_ms: u64,
    pub hop_count: u8,
    pub encrypted_blob: Vec<u8>,
}

pub struct BlindDtnStore {
    pub max_bytes: usize,
    current_bytes: usize,
    storage_key: [u8; 32],
    order: VecDeque<[u8; 32]>,
    bundles: HashMap<[u8; 32], DtnBundle>,
}

impl Drop for BlindDtnStore {
    fn drop(&mut self) {
        self.storage_key.zeroize();
    }
}

impl BlindDtnStore {
    pub const DEFAULT_MAX_BYTES: usize = 50 * 1024 * 1024;

    pub fn new(storage_key: [u8; 32], max_bytes: usize) -> Self {
        Self {
            max_bytes,
            current_bytes: 0,
            storage_key,
            order: VecDeque::new(),
            bundles: HashMap::new(),
        }
    }

    pub fn enqueue_blind_bundle(&mut self, mut opaque_payload: Vec<u8>, now_utc_ms: u64, ttl_ms: u64) -> Result<[u8; 32], DtnError> {
        let id = *blake3::hash(&opaque_payload).as_bytes();
        if self.bundles.contains_key(&id) {
            return Ok(id);
        }

        let cipher = XChaCha20Poly1305::new((&self.storage_key).into());
        let mut nonce_bytes = [0u8; 24];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let ct = cipher
            .encrypt(XNonce::from_slice(&nonce_bytes), opaque_payload.as_slice())
            .map_err(|_| DtnError::StorageCryptoError)?;
        opaque_payload.zeroize();

        let mut blob = Vec::with_capacity(24 + ct.len());
        blob.extend_from_slice(&nonce_bytes);
        blob.extend_from_slice(&ct);

        if blob.len() > self.max_bytes {
            return Err(DtnError::BundleTooLarge);
        }
        while self.current_bytes + blob.len() > self.max_bytes {
            if let Some(old_id) = self.order.pop_front() {
                if let Some(removed) = self.bundles.remove(&old_id) {
                    self.current_bytes = self.current_bytes.saturating_sub(removed.encrypted_blob.len());
                }
            } else {
                break;
            }
        }

        self.current_bytes += blob.len();
        self.order.push_back(id);
        self.bundles.insert(id, DtnBundle {
            id,
            created_utc_ms: now_utc_ms,
            ttl_ms,
            hop_count: 0,
            encrypted_blob: blob,
        });
        Ok(id)
    }

    pub fn evict_expired(&mut self, now_utc_ms: u64) -> usize {
        let mut removed_count = 0;
        let expired_ids: Vec<[u8; 32]> = self.bundles
            .iter()
            .filter(|(_, b)| now_utc_ms.saturating_sub(b.created_utc_ms) >= b.ttl_ms)
            .map(|(id, _)| *id)
            .collect();

        for id in expired_ids {
            if let Some(b) = self.bundles.remove(&id) {
                self.current_bytes = self.current_bytes.saturating_sub(b.encrypted_blob.len());
                removed_count += 1;
            }
        }
        self.order.retain(|id| self.bundles.contains_key(id));
        removed_count
    }

    pub fn read_bundle_for_relay(&self, id: &[u8; 32]) -> Result<Option<Vec<u8>>, DtnError> {
        let Some(b) = self.bundles.get(id) else { return Ok(None); };
        let cipher = XChaCha20Poly1305::new((&self.storage_key).into());
        let (nonce, ct) = b.encrypted_blob.split_at(24);
        let plain = cipher
            .decrypt(XNonce::from_slice(nonce), ct)
            .map_err(|_| DtnError::StorageCryptoError)?;
        Ok(Some(plain))
    }

    pub fn all_relay_payloads(&self) -> Vec<Vec<u8>> {
        self.order
            .iter()
            .filter_map(|id| self.read_bundle_for_relay(id).ok().flatten())
            .collect()
    }

    pub fn len(&self) -> usize {
        self.bundles.len()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GroupCryptoStrategy {
    RatchetedSenderKeys,
    TreeKemMlsLite,
}

pub fn select_group_strategy(member_count: usize) -> GroupCryptoStrategy {
    if member_count <= 20 {
        GroupCryptoStrategy::RatchetedSenderKeys
    } else {
        GroupCryptoStrategy::TreeKemMlsLite
    }
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct SenderKeyRatchet {
    chain_key: [u8; 32],
    last_rekey_utc_ms: u64,
    pub generation: u64,
}

impl SenderKeyRatchet {
    pub const REKEY_INTERVAL_MS: u64 = 86_400_000;

    pub fn new(initial_key: [u8; 32], now_utc_ms: u64) -> Self {
        Self { chain_key: initial_key, last_rekey_utc_ms: now_utc_ms, generation: 0 }
    }

    pub fn next_message_key(&mut self, now_utc_ms: u64) -> Result<[u8; 32], DtnError> {
        if now_utc_ms.saturating_sub(self.last_rekey_utc_ms) >= Self::REKEY_INTERVAL_MS {
            return Err(DtnError::RekeyRequired);
        }
        let mut msg_h = blake3::Hasher::new_keyed(&self.chain_key);
        msg_h.update(b"sender-msg-key");
        msg_h.update(&self.generation.to_le_bytes());
        let mut msg_key = [0u8; 32];
        msg_key.copy_from_slice(msg_h.finalize().as_bytes());

        let mut next_h = blake3::Hasher::new_keyed(&self.chain_key);
        next_h.update(b"sender-chain-step");
        let mut next_chain = [0u8; 32];
        next_chain.copy_from_slice(next_h.finalize().as_bytes());

        self.chain_key.zeroize();
        self.chain_key = next_chain;
        next_chain.zeroize();
        self.generation += 1;

        Ok(msg_key)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_blind_dtn_store_encrypt_evict() {
        let mut store = BlindDtnStore::new([0x11u8; 32], 1024);
        let id = store.enqueue_blind_bundle(b"opaque-encrypted-cell".to_vec(), 1_000, 5_000).unwrap();
        assert_eq!(store.read_bundle_for_relay(&id).unwrap().unwrap(), b"opaque-encrypted-cell");
        assert_eq!(store.evict_expired(6_001), 1);
        assert_eq!(store.len(), 0);
    }

    #[test]
    fn test_sender_key_ratchet_and_24h_rekey() {
        let mut r = SenderKeyRatchet::new([0x22u8; 32], 1_000);
        let k0 = r.next_message_key(2_000).unwrap();
        let k1 = r.next_message_key(3_000).unwrap();
        assert_ne!(k0, k1);
        assert!(r.next_message_key(1_000 + SenderKeyRatchet::REKEY_INTERVAL_MS).is_err());
    }
}