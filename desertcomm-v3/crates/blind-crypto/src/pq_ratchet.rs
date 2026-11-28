use std::collections::HashMap;
use chacha20poly1305::{aead::{Aead, KeyInit}, ChaCha20Poly1305, Nonce};
use ml_kem::{Encoded, EncodedSizeUser, KemCore, MlKem768};
use ml_kem::kem::{Decapsulate, Encapsulate};
use rand::rngs::OsRng;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

const MAX_SKIP: usize = 64;

fn kdf_rk(rk: &[u8; 32], dh_out: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let mut input = [0u8; 64];
    input[..32].copy_from_slice(rk);
    input[32..].copy_from_slice(dh_out);
    let new_rk = blake3::derive_key("farlo-v49-dr-root", &input);
    let new_ck = blake3::derive_key("farlo-v49-dr-chain", &input);
    input.zeroize();
    (new_rk, new_ck)
}

fn kdf_ck(ck: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let next_ck = blake3::derive_key("farlo-v49-ck-step", ck);
    let msg_key = blake3::derive_key("farlo-v49-msg-key", ck);
    (next_ck, msg_key)
}

pub fn mlkem768_keygen() -> (Vec<u8>, Vec<u8>) {
    let (dk, ek) = MlKem768::generate(&mut OsRng);
    (dk.as_bytes().as_slice().to_vec(), ek.as_bytes().as_slice().to_vec())
}

pub fn hybrid_pq_initiate(peer_ek_bytes: &[u8], my_x_priv: &[u8; 32], peer_x_pub: &[u8; 32]) -> Result<(Vec<u8>, [u8; 32]), &'static str> {
    let enc_ek: Encoded<<MlKem768 as KemCore>::EncapsulationKey> = peer_ek_bytes.try_into().map_err(|_| "bad mlkem ek len")?;
    let ek = <MlKem768 as KemCore>::EncapsulationKey::from_bytes(&enc_ek);
    let (ct, pq_ss) = ek.encapsulate(&mut OsRng).map_err(|_| "mlkem encapsulate failed")?;

    let sk = StaticSecret::from(*my_x_priv);
    let pk = PublicKey::from(*peer_x_pub);
    let x_ss = sk.diffie_hellman(&pk);

    let mut combined = [0u8; 64];
    combined[..32].copy_from_slice(x_ss.as_bytes());
    combined[32..].copy_from_slice(pq_ss.as_slice());
    let root = blake3::derive_key("farlo-v49-pq-hybrid-root", &combined);
    combined.zeroize();
    Ok((ct.as_slice().to_vec(), root))
}

pub fn hybrid_pq_respond(my_dk_bytes: &[u8], ct_bytes: &[u8], my_x_priv: &[u8; 32], peer_x_pub: &[u8; 32]) -> Result<[u8; 32], &'static str> {
    let enc_dk: Encoded<<MlKem768 as KemCore>::DecapsulationKey> = my_dk_bytes.try_into().map_err(|_| "bad mlkem dk len")?;
    let dk = <MlKem768 as KemCore>::DecapsulationKey::from_bytes(&enc_dk);
    let ct: ml_kem::Ciphertext<MlKem768> = ct_bytes.try_into().map_err(|_| "bad mlkem ct len")?;
    let pq_ss = dk.decapsulate(&ct).map_err(|_| "mlkem decapsulate failed")?;

    let sk = StaticSecret::from(*my_x_priv);
    let pk = PublicKey::from(*peer_x_pub);
    let x_ss = sk.diffie_hellman(&pk);

    let mut combined = [0u8; 64];
    combined[..32].copy_from_slice(x_ss.as_bytes());
    combined[32..].copy_from_slice(pq_ss.as_slice());
    let root = blake3::derive_key("farlo-v49-pq-hybrid-root", &combined);
    combined.zeroize();
    Ok(root)
}

#[derive(Clone)]
pub struct PqRatchetSession {
    root_key: [u8; 32],
    dh_priv: [u8; 32],
    dh_pub: [u8; 32],
    peer_dh_pub: Option<[u8; 32]>,
    send_ck: Option<[u8; 32]>,
    recv_ck: Option<[u8; 32]>,
    ns: u32,
    nr: u32,
    skipped: HashMap<([u8; 32], u32), [u8; 32]>,
}

impl Drop for PqRatchetSession {
    fn drop(&mut self) {
        self.root_key.zeroize();
        self.dh_priv.zeroize();
        if let Some(ref mut k) = self.send_ck { k.zeroize(); }
        if let Some(ref mut k) = self.recv_ck { k.zeroize(); }
        for (_, v) in self.skipped.iter_mut() { v.zeroize(); }
    }
}

impl PqRatchetSession {
    pub fn init_sender(shared_root: [u8; 32], peer_ratchet_pub: [u8; 32]) -> Self {
        let sk = StaticSecret::random_from_rng(OsRng);
        let pub_k = PublicKey::from(&sk);
        let dh = sk.diffie_hellman(&PublicKey::from(peer_ratchet_pub));
        let (rk, sck) = kdf_rk(&shared_root, dh.as_bytes());
        Self {
            root_key: rk, dh_priv: sk.to_bytes(), dh_pub: pub_k.to_bytes(),
            peer_dh_pub: Some(peer_ratchet_pub), send_ck: Some(sck), recv_ck: None,
            ns: 0, nr: 0, skipped: HashMap::new(),
        }
    }

    pub fn init_receiver(shared_root: [u8; 32], my_ratchet_priv: [u8; 32]) -> Self {
        let sk = StaticSecret::from(my_ratchet_priv);
        let pub_k = PublicKey::from(&sk);
        Self {
            root_key: shared_root, dh_priv: my_ratchet_priv, dh_pub: pub_k.to_bytes(),
            peer_dh_pub: None, send_ck: None, recv_ck: None,
            ns: 0, nr: 0, skipped: HashMap::new(),
        }
    }

    pub fn export_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(220 + self.skipped.len() * 68);
        out.push(0xF1);
        out.extend_from_slice(&self.root_key);
        out.extend_from_slice(&self.dh_priv);
        out.extend_from_slice(&self.dh_pub);
        let flags: u8 = (if self.peer_dh_pub.is_some() { 1 } else { 0 })
            | (if self.send_ck.is_some() { 2 } else { 0 })
            | (if self.recv_ck.is_some() { 4 } else { 0 });
        out.push(flags);
        if let Some(k) = &self.peer_dh_pub { out.extend_from_slice(k); }
        if let Some(k) = &self.send_ck { out.extend_from_slice(k); }
        if let Some(k) = &self.recv_ck { out.extend_from_slice(k); }
        out.extend_from_slice(&self.ns.to_be_bytes());
        out.extend_from_slice(&self.nr.to_be_bytes());
        let cnt = self.skipped.len().min(MAX_SKIP) as u16;
        out.extend_from_slice(&cnt.to_be_bytes());
        for ((pk, n), mk) in self.skipped.iter().take(MAX_SKIP) {
            out.extend_from_slice(pk);
            out.extend_from_slice(&n.to_be_bytes());
            out.extend_from_slice(mk);
        }
        out
    }

    pub fn import_bytes(data: &[u8]) -> Result<Self, &'static str> {
        if data.len() < 1 + 96 + 1 + 10 || data[0] != 0xF1 { return Err("bad pq state header"); }
        let mut off = 1usize;
        let mut take32 = |d: &[u8], o: &mut usize| -> Result<[u8; 32], &'static str> {
            if *o + 32 > d.len() { return Err("truncated pq state"); }
            let mut b = [0u8; 32];
            b.copy_from_slice(&d[*o..*o + 32]);
            *o += 32;
            Ok(b)
        };
        let root_key = take32(data, &mut off)?;
        let dh_priv = take32(data, &mut off)?;
        let dh_pub = take32(data, &mut off)?;
        if off >= data.len() { return Err("missing flags"); }
        let flags = data[off];
        off += 1;
        let peer_dh_pub = if (flags & 1) != 0 { Some(take32(data, &mut off)?) } else { None };
        let send_ck = if (flags & 2) != 0 { Some(take32(data, &mut off)?) } else { None };
        let recv_ck = if (flags & 4) != 0 { Some(take32(data, &mut off)?) } else { None };
        if off + 10 > data.len() { return Err("truncated counters"); }
        let ns = u32::from_be_bytes(data[off..off + 4].try_into().map_err(|_| "ns")?);
        let nr = u32::from_be_bytes(data[off + 4..off + 8].try_into().map_err(|_| "nr")?);
        let cnt = u16::from_be_bytes(data[off + 8..off + 10].try_into().map_err(|_| "cnt")?) as usize;
        off += 10;
        if cnt > MAX_SKIP || off + cnt * 68 > data.len() { return Err("bad skipped len"); }
        let mut skipped = HashMap::with_capacity(cnt);
        for _ in 0..cnt {
            let pk = take32(data, &mut off)?;
            let n = u32::from_be_bytes(data[off..off + 4].try_into().map_err(|_| "sn")?);
            off += 4;
            let mk = take32(data, &mut off)?;
            skipped.insert((pk, n), mk);
        }
        Ok(Self { root_key, dh_priv, dh_pub, peer_dh_pub, send_ck, recv_ck, ns, nr, skipped })
    }

    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, &'static str> {
        let ck = self.send_ck.ok_or("send chain not initialized")?;
        let (next_ck, mut mk) = kdf_ck(&ck);
        self.send_ck = Some(next_ck);
        let n = self.ns;
        self.ns = self.ns.wrapping_add(1);

        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[8..12].copy_from_slice(&n.to_be_bytes());
        let cipher = ChaCha20Poly1305::new_from_slice(&mk).map_err(|_| "cipher init")?;
        let ct = cipher.encrypt(Nonce::from_slice(&nonce_bytes), plaintext).map_err(|_| "encrypt failed")?;
        mk.zeroize();

        let mut out = Vec::with_capacity(32 + 4 + 12 + ct.len());
        out.extend_from_slice(&self.dh_pub);
        out.extend_from_slice(&n.to_be_bytes());
        out.extend_from_slice(&nonce_bytes);
        out.extend_from_slice(&ct);
        Ok(out)
    }

    /// Atomic decrypt: mutates session state ONLY AFTER AEAD authentication succeeds.
    pub fn decrypt(&mut self, packet: &[u8]) -> Result<Vec<u8>, &'static str> {
        if packet.len() < 32 + 4 + 12 + 16 { return Err("packet too short"); }
        let mut hdr_pub = [0u8; 32];
        hdr_pub.copy_from_slice(&packet[0..32]);
        let n = u32::from_be_bytes(packet[32..36].try_into().map_err(|_| "bad header")?);
        let nonce = Nonce::from_slice(&packet[36..48]);
        let ct = &packet[48..];

        // 1) Try skipped key first (verify AEAD before removing key)
        if let Some(mk) = self.skipped.get(&(hdr_pub, n)).copied() {
            let cipher = ChaCha20Poly1305::new_from_slice(&mk).map_err(|_| "cipher init")?;
            let pt = cipher.decrypt(nonce, ct).map_err(|_| "decrypt skipped failed")?;
            if let Some(mut old) = self.skipped.remove(&(hdr_pub, n)) { old.zeroize(); }
            return Ok(pt);
        }

        // 2) Run ratchet step on an isolated clone; commit ONLY if AEAD decrypt succeeds
        let mut trial = self.clone();
        if trial.peer_dh_pub != Some(hdr_pub) {
            trial.dh_ratchet_step(hdr_pub);
        }
        if n < trial.nr { return Err("stale or replayed message number"); }
        if (n - trial.nr) as usize > MAX_SKIP { return Err("skip window exceeded"); }

        while trial.nr < n {
            if trial.skipped.len() >= MAX_SKIP { return Err("too many skipped messages"); }
            let ck = trial.recv_ck.ok_or("recv chain missing")?;
            let (next_ck, mk) = kdf_ck(&ck);
            trial.recv_ck = Some(next_ck);
            trial.skipped.insert((hdr_pub, trial.nr), mk);
            trial.nr = trial.nr.wrapping_add(1);
        }

        let ck = trial.recv_ck.ok_or("recv chain missing")?;
        let (next_ck, mut mk) = kdf_ck(&ck);
        trial.recv_ck = Some(next_ck);
        trial.nr = trial.nr.wrapping_add(1);

        let cipher = ChaCha20Poly1305::new_from_slice(&mk).map_err(|_| "cipher init")?;
        let pt = cipher.decrypt(nonce, ct).map_err(|_| "aead auth failed")?;
        mk.zeroize();

        // Commit authenticated state atomically
        *self = trial;
        Ok(pt)
    }

    fn dh_ratchet_step(&mut self, peer_pub: [u8; 32]) {
        self.peer_dh_pub = Some(peer_pub);
        self.nr = 0;
        let sk_old = StaticSecret::from(self.dh_priv);
        let dh_recv = sk_old.diffie_hellman(&PublicKey::from(peer_pub));
        let (rk1, rck) = kdf_rk(&self.root_key, dh_recv.as_bytes());
        self.recv_ck = Some(rck);

        let sk_new = StaticSecret::random_from_rng(OsRng);
        let pub_new = PublicKey::from(&sk_new);
        let dh_send = sk_new.diffie_hellman(&PublicKey::from(peer_pub));
        let (rk2, sck) = kdf_rk(&rk1, dh_send.as_bytes());
        self.root_key = rk2;
        self.dh_priv = sk_new.to_bytes();
        self.dh_pub = pub_new.to_bytes();
        self.send_ck = Some(sck);
        self.ns = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_atomic_ratchet_resists_forged_packets() {
        let (bob_dk, bob_ek) = mlkem768_keygen();
        let alice_x = StaticSecret::random_from_rng(OsRng);
        let bob_x = StaticSecret::random_from_rng(OsRng);
        let (ct, alice_root) = hybrid_pq_initiate(&bob_ek, &alice_x.to_bytes(), PublicKey::from(&bob_x).as_bytes()).unwrap();
        let bob_root = hybrid_pq_respond(&bob_dk, &ct, &bob_x.to_bytes(), PublicKey::from(&alice_x).as_bytes()).unwrap();
        assert_eq!(alice_root, bob_root);

        let bob_r_priv = StaticSecret::random_from_rng(OsRng);
        let mut alice = PqRatchetSession::init_sender(alice_root, PublicKey::from(&bob_r_priv).to_bytes());
        let mut bob = PqRatchetSession::init_receiver(bob_root, bob_r_priv.to_bytes());

        let m1 = alice.encrypt(b"hello-pq").unwrap();
        // Send forged packet with bogus DH key & high counter before m1
        let mut forged = m1.clone();
        forged[0] ^= 0xFF;
        forged[35] = 20;
        assert!(bob.decrypt(&forged).is_err(), "forged packet must fail AEAD");

        // Bob's state MUST remain completely intact and decrypt valid m1!
        assert_eq!(bob.decrypt(&m1).unwrap(), b"hello-pq");
        assert!(bob.decrypt(&m1).is_err(), "replay must be rejected");
    }
}