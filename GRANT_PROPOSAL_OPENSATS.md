# Grant Proposal Draft — Farlo

**Target Grants**: OpenSats (Nostr & Privacy Fund) / NLnet Foundation (NGI Zero)

## 1. Executive Summary
Farlo is a working, open-source Android + Rust hybrid messenger designed for regions facing frequent internet blackouts, infrastructure outages, and censorship. Unlike traditional messengers that fail completely when mobile data drops, Farlo seamlessly shifts between **NIP-59 Nostr Multi-Relay** online and **BLE / Wi-Fi Direct DTN Multi-Hop Blind Relaying** offline.

## 2. Working Proof of Concept (Already Built)
- 8-crate Rust core (`desertcomm-v3`) compiled for `arm64-v8a`.
- Verified Android app (`minSdk 26` to Android 17) with NIP-59 Nostr multi-relay, BLE DTN multi-hop, WebRTC Opus voice calls, Group PTT, Safety Numbers, Biometric + Duress PIN, Encrypted Backups, 5 languages, and a self-hosted Docker server kit.

## 3. Proposed 3-Milestone Roadmap (6 Months)
- **Milestone 1 (Months 1–2)**: Upgrade Rust core to **Double Ratchet** (Forward Secrecy) + remove temporary media hosts in favor of **Blossom** decentralized Nostr media servers + Tor/Orbot proxy support.
- **Milestone 2 (Months 3–4)**: Cross-platform **iOS client** via Rust `UniFFI` + `SwiftUI`, plus **BLE-to-Internet Gateway** (Data Mule) mode.
- **Milestone 3 (Months 5–6)**: **LoRa / Meshtastic** hardware bridge integration + F-Droid reproducible builds + independent cryptographic audit.