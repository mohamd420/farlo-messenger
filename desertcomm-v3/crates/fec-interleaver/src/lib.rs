//! طبقة تصحيح الأخطاء الأمامي والتشذير (v3.1)

use raptorq::{Decoder, Encoder, EncodingPacket, ObjectTransmissionInformation};
use reed_solomon::{Decoder as RsDecoder, Encoder as RsEncoder};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum FecError {
    #[error("RaptorQ failed to reconstruct payload (insufficient packets)")]
    RaptorQFailed,
    #[error("Reed-Solomon decode failed: too many errors")]
    ReedSolomonFailed,
    #[error("Invalid block size or empty input")]
    InvalidInput,
}

// ============================================================================
// المسار 1: RaptorQ Fountain Codes (RFC 6330)
// ============================================================================

pub fn raptorq_encode(data: &[u8], mtu: u16, repair_packets: u32) -> (ObjectTransmissionInformation, Vec<Vec<u8>>) {
    let encoder = Encoder::with_defaults(data, mtu);
    let config = encoder.get_config();
    let packets: Vec<Vec<u8>> = encoder
        .get_encoded_packets(repair_packets)
        .into_iter()
        .map(|p| p.serialize())
        .collect();
    (config, packets)
}

pub fn raptorq_decode(config: ObjectTransmissionInformation, packets: &[Vec<u8>]) -> Result<Vec<u8>, FecError> {
    let mut decoder = Decoder::new(config);
    for raw in packets {
        let pkt = EncodingPacket::deserialize(raw);
        if let Some(res) = decoder.decode(pkt) {
            return Ok(res);
        }
    }
    Err(FecError::RaptorQFailed)
}

// ============================================================================
// المسار 2: RS(255, 223) + Matrix Byte Interleaver (عمق 8 حزم)
// ============================================================================

pub const RS_ECC_LEN: usize = 32;
pub const INTERLEAVER_DEPTH: usize = 8;

pub fn rs_encode_block(data: &[u8]) -> Result<Vec<u8>, FecError> {
    if data.is_empty() || data.len() > 223 {
        return Err(FecError::InvalidInput);
    }
    let enc = RsEncoder::new(RS_ECC_LEN);
    Ok(enc.encode(data).to_vec())
}

pub fn rs_decode_block(encoded: &[u8], known_erasures: &[u8]) -> Result<Vec<u8>, FecError> {
    if encoded.len() <= RS_ECC_LEN {
        return Err(FecError::InvalidInput);
    }
    let dec = RsDecoder::new(RS_ECC_LEN);
    let recovered = if known_erasures.is_empty() {
        dec.correct(encoded, None)
    } else {
        dec.correct(encoded, Some(known_erasures))
    }
    .map_err(|_| FecError::ReedSolomonFailed)?;
    Ok(recovered.data().to_vec())
}

pub fn matrix_interleave(blocks: &[Vec<u8>]) -> Result<Vec<Vec<u8>>, FecError> {
    if blocks.len() != INTERLEAVER_DEPTH || blocks.iter().any(|b| b.is_empty()) {
        return Err(FecError::InvalidInput);
    }
    let cols = blocks[0].len();
    if blocks.iter().any(|b| b.len() != cols) {
        return Err(FecError::InvalidInput);
    }
    let mut frames = vec![vec![0u8; INTERLEAVER_DEPTH]; cols];
    for r in 0..INTERLEAVER_DEPTH {
        for c in 0..cols {
            frames[c][r] = blocks[r][c];
        }
    }
    Ok(frames)
}

pub fn matrix_deinterleave(frames: &[Option<Vec<u8>>], original_block_len: usize) -> (Vec<Vec<u8>>, Vec<u8>) {
    let mut blocks = vec![vec![0u8; original_block_len]; INTERLEAVER_DEPTH];
    let mut erasure_indices = Vec::new();
    for c in 0..original_block_len.min(frames.len()) {
        match &frames[c] {
            Some(frame) if frame.len() == INTERLEAVER_DEPTH => {
                for r in 0..INTERLEAVER_DEPTH {
                    blocks[r][c] = frame[r];
                }
            }
            _ => erasure_indices.push(c as u8),
        }
    }
    (blocks, erasure_indices)
}

// ============================================================================
// المسار 3: Block Interleaver + Outer RS + Inner Hamming(7,4)
// ============================================================================

pub fn hf_block_interleave(bits: &[u8], depth: usize) -> Vec<u8> {
    if depth <= 1 || bits.is_empty() {
        return bits.to_vec();
    }
    let cols = (bits.len() + depth - 1) / depth;
    let mut out = Vec::with_capacity(bits.len());
    for c in 0..cols {
        for r in 0..depth {
            let idx = r * cols + c;
            if idx < bits.len() {
                out.push(bits[idx]);
            }
        }
    }
    out
}

pub fn hf_block_deinterleave(interleaved: &[u8], original_len: usize, depth: usize) -> Vec<u8> {
    if depth <= 1 || interleaved.is_empty() {
        return interleaved.to_vec();
    }
    let cols = (original_len + depth - 1) / depth;
    let mut out = vec![0u8; original_len];
    let mut k = 0;
    for c in 0..cols {
        for r in 0..depth {
            let idx = r * cols + c;
            if idx < original_len && k < interleaved.len() {
                out[idx] = interleaved[k];
                k += 1;
            }
        }
    }
    out
}

pub fn hamming74_encode_bytes(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len() * 2);
    for &b in data {
        out.push(encode_nibble(b >> 4));
        out.push(encode_nibble(b & 0x0F));
    }
    out
}

pub fn hamming74_decode_bytes(encoded: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(encoded.len() / 2);
    for pair in encoded.chunks_exact(2) {
        out.push((decode_nibble(pair[0]) << 4) | decode_nibble(pair[1]));
    }
    out
}

fn encode_nibble(n: u8) -> u8 {
    let d0 = n & 1;
    let d1 = (n >> 1) & 1;
    let d2 = (n >> 2) & 1;
    let d3 = (n >> 3) & 1;
    let p0 = d0 ^ d1 ^ d3;
    let p1 = d0 ^ d2 ^ d3;
    let p2 = d1 ^ d2 ^ d3;
    (p0 << 6) | (p1 << 5) | (d0 << 4) | (p2 << 3) | (d1 << 2) | (d2 << 1) | d3
}

fn decode_nibble(code: u8) -> u8 {
    let p0 = (code >> 6) & 1;
    let p1 = (code >> 5) & 1;
    let d0 = (code >> 4) & 1;
    let p2 = (code >> 3) & 1;
    let d1 = (code >> 2) & 1;
    let d2 = (code >> 1) & 1;
    let d3 = code & 1;

    let s0 = p0 ^ d0 ^ d1 ^ d3;
    let s1 = p1 ^ d0 ^ d2 ^ d3;
    let s2 = p2 ^ d1 ^ d2 ^ d3;
    let syndrome = (s2 << 2) | (s1 << 1) | s0;

    let mut fixed = code & 0x7F;
    if syndrome != 0 {
        let bit_pos = 7 - syndrome;
        fixed ^= 1 << bit_pos;
    }
    (((fixed >> 0) & 1) << 3)
        | (((fixed >> 1) & 1) << 2)
        | (((fixed >> 2) & 1) << 1)
        | ((fixed >> 4) & 1)
}

pub fn encode_cell_resilient(cell_128: &[u8; 128]) -> Vec<u8> {
    let rs_block = RsEncoder::new(48).encode(cell_128).to_vec();
    hamming74_encode_bytes(&rs_block)
}

pub fn decode_cell_resilient(received: &[u8]) -> Result<[u8; 128], FecError> {
    let rs_candidate = hamming74_decode_bytes(received);
    let recovered = RsDecoder::new(48)
        .correct(&rs_candidate, None)
        .map_err(|_| FecError::ReedSolomonFailed)?;
    let plain = recovered.data().to_vec();
    if plain.len() != 128 {
        return Err(FecError::ReedSolomonFailed);
    }
    let mut out = [0u8; 128];
    out.copy_from_slice(&plain);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_raptorq_loss_recovery() {
        let data = vec![0x5Au8; 4096];
        let (cfg, mut pkts) = raptorq_encode(&data, 256, 8);
        pkts.drain(0..6);
        pkts.reverse();
        let recovered = raptorq_decode(cfg, &pkts).unwrap();
        assert_eq!(recovered, data);
    }

    #[test]
    fn test_rs_matrix_interleaver_burst_loss() {
        let blocks: Vec<Vec<u8>> = (0..INTERLEAVER_DEPTH)
            .map(|r| vec![(r as u8) + 10; 223])
            .collect();
        let encoded_blocks: Vec<Vec<u8>> = blocks.iter().map(|b| rs_encode_block(b).unwrap()).collect();
        let frames = matrix_interleave(&encoded_blocks).unwrap();

        let mut rx_frames: Vec<Option<Vec<u8>>> = frames.into_iter().map(Some).collect();
        for i in 20..32 {
            rx_frames[i] = None;
        }

        let (deinterleaved, erasures) = matrix_deinterleave(&rx_frames, 255);
        assert_eq!(erasures.len(), 12);
        for r in 0..INTERLEAVER_DEPTH {
            let dec = rs_decode_block(&deinterleaved[r], &erasures).unwrap();
            assert_eq!(dec, blocks[r]);
        }
    }
}