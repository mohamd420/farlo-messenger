# Security Policy — Farlo Messenger

## Supported Versions

| Version | Supported | Status |
| :--- | :---: | :--- |
| `v4.9.x` | Yes | Public Beta (Not Independently Audited) |
| `< v4.9` | No | Upgrade to v4.9 |

## Security Architecture (v4.9 Public Beta)

- **Hybrid Post-Quantum Handshake & Ratchet**: 1-to-1 sessions establish a hybrid **ML-KEM-768 (FIPS-203) + X25519** initial handshake combined with an **X25519 Double Ratchet** (implemented in Rust `blind-crypto` with atomic AEAD-verified state transitions).
- **Mandatory Ed25519 Sender Authentication**: All 1-to-1, control (`ACK`/`EDIT`/`DEL`/`PQ_*`), and group (`GRP:`) messages require strict **Ed25519 (`verify_strict`)** signatures. Plaintext and hardcoded-key fallbacks (`ENC:`) are removed.
- **Synchronized Key Pinning & TOFU Disclosure**: On first contact with a peer, Farlo uses **Trust On First Use (TOFU)** to validate and pin the peer's X25519 and Ed25519 public keys inside a synchronized verification path, rejecting any subsequent key mismatch. **First-contact identities are unverified until users compare Safety Numbers or scan QR codes out-of-band.**
- **Tor Mode Privacy**: When Embedded Tor is enabled, WebRTC ICE is locked to `RELAY`-only with no direct/STUN servers (preventing direct IP leaks; voice calls are therefore unavailable in Tor mode), and encrypted media downloads block `localhost`, `*.localhost`, `*.local`, `*.internal`, `*.lan`, `*.home`, and literal IPv4/IPv6 addresses without performing local DNS lookups.
- **Media & Relay Resource Bounds**: Automatic media downloads are capped at `8 MiB` enforced on actual streamed bytes (`25 MiB` for manual downloads) inside a bounded 2-thread executor; Nostr relay chunk reassembly is bounded by exact UTF-8 byte limits (`16 KiB` per chunk, `256 KiB` per assembly, `16` concurrent assemblies).

## Planned Hardening Roadmap (v5.0)

- Cryptographic binding of `DC-` IDs to `BLAKE3(Ed25519 || X25519)` to eliminate first-contact TOFU.
- Continuous per-step PQ KEM ratchet re-encapsulation for full post-compromise PQ security.
- Persistent `SQLCipher` storage for PQ ratchet sessions and at-rest encryption for cached media files (`farlo_media`).
- Independent third-party cryptographic audit and reproducible builds.

## Reporting a Vulnerability

Please report security issues privately via GitHub Security Advisories on `https://github.com/mohamd420/farlo-messenger/security/advisories` or by opening a minimal non-exploit issue requesting a private contact channel. Do not publish zero-day exploits publicly before a fix is released.