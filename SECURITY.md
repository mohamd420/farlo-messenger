# Farlo Security Architecture & Threat Model

## Implemented Cryptography
- **Message & File E2EE**: Rust `ChaCha20-Poly1305` AEAD + `X25519` static key exchange (`BOX:` envelopes).
- **Blind DTN Multi-Hop**: Intermediate relay phones observe only blinded destination tags (`blindDestTag`) and ciphertext; zero UI notification or plaintext exposure on relay nodes.
- **Nostr Transport**: Pure-Kotlin BIP-340 Schnorr signatures (`secp256k1` Jacobian) wrapping opaque E2EE envelopes inside NIP-59 (`kind = 1059`) events.
- **Voice Calls**: WebRTC `DTLS-SRTP` encrypted Opus audio streams.
- **Backups**: `PBKDF2WithHmacSHA256` (65,536 iterations, random 16-byte salt) + Rust `ChaCha20-Poly1305`.
- **Verification**: Canonical 30-digit `SHA-256` Safety Numbers for MITM detection.

## Known Limitations & v5.0 Security Roadmap
We believe in transparent security engineering. Current known limitations targeted for v5.0 funding:
1. **Forward Secrecy**: Transitioning from static `X25519` boxes to full **Double Ratchet** inside `desertcomm-v3`.
2. **Metadata / IP Protection**: Adding optional **Tor / Orbot** SOCKS5 routing for online relays.
3. **Decentralized Media Storage**: Replacing temporary public blob hosts with **Blossom** (Nostr media protocol) and self-hosted `sada-server`.
4. **Independent Security Audit**: Preparing `desertcomm-v3` and Android JNI boundaries for third-party cryptographic review.