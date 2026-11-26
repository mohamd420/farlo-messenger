use blind_crypto::{elligator2, SpatioTemporalCrv};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use framing_clock_core::ResilientClock;
use jni::objects::{JByteArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jobjectArray};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use rand::{rngs::OsRng, RngCore};
use std::sync::Mutex;

static CLOCK: Lazy<Mutex<ResilientClock>> = Lazy::new(|| Mutex::new(ResilientClock::new_cold(0)));

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_tickClock(
    _env: JNIEnv,
    _this: JObject,
    monotonic_ms: jlong,
    os_sleep_detected: jboolean,
) -> jint {
    let mut clk = CLOCK.lock().unwrap_or_else(|e| e.into_inner());
    clk.tick(monotonic_ms.max(0) as u64, os_sleep_detected != 0);
    clk.stratum as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_deriveCrv(
    mut env: JNIEnv,
    _this: JObject,
    passphrase: JString,
    mgrs: JString,
    nonce: JByteArray,
    hour_counter: jlong,
    total_channels: jint,
) -> jintArray {
    let pass_str: String = match env.get_string(&passphrase) {
        Ok(s) => s.into(),
        Err(e) => { throw(&mut env, &format!("passphrase error: {e}")); return std::ptr::null_mut(); }
    };
    let mgrs_str: String = match env.get_string(&mgrs) {
        Ok(s) => s.into(),
        Err(e) => { throw(&mut env, &format!("mgrs error: {e}")); return std::ptr::null_mut(); }
    };
    let nonce_bytes = match env.convert_byte_array(&nonce) {
        Ok(b) if b.len() == 16 => b,
        _ => { throw(&mut env, "mission nonce must be 16 bytes"); return std::ptr::null_mut(); }
    };
    let mut n16 = [0u8; 16];
    n16.copy_from_slice(&nonce_bytes);

    match SpatioTemporalCrv::derive_rendezvous(
        &pass_str,
        &mgrs_str,
        &n16,
        hour_counter.max(0) as u64,
        total_channels.max(1) as u16,
    ) {
        Ok(crv) => {
            let out = match env.new_int_array(3) {
                Ok(a) => a,
                Err(e) => { throw(&mut env, &format!("new_int_array: {e}")); return std::ptr::null_mut(); }
            };
            let vals = [crv.channel_index as jint, crv.wake_second as jint, crv.rendezvous_tag as jint];
            if let Err(e) = env.set_int_array_region(&out, 0, &vals) {
                throw(&mut env, &format!("set_int_array_region: {e}"));
                return std::ptr::null_mut();
            }
            out.into_raw()
        }
        Err(e) => {
            throw(&mut env, &e.to_string());
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_generateElligatorKeypair(
    mut env: JNIEnv,
    _this: JObject,
) -> jobjectArray {
    let mut rng = OsRng;
    let (sec, pubk, rep) = elligator2::generate_elligator2_keypair(&mut rng);
    let byte_class = match env.find_class("[B") {
        Ok(c) => c,
        Err(e) => { throw(&mut env, &format!("find_class: {e}")); return std::ptr::null_mut(); }
    };
    let null_obj = JObject::null();
    let out = match env.new_object_array(3, &byte_class, &null_obj) {
        Ok(a) => a,
        Err(e) => { throw(&mut env, &format!("new_object_array: {e}")); return std::ptr::null_mut(); }
    };
    let parts: [&[u8]; 3] = [&sec.to_bytes(), pubk.as_bytes(), &rep];
    for (i, p) in parts.iter().enumerate() {
        let arr = match env.byte_array_from_slice(p) {
            Ok(a) => a,
            Err(e) => { throw(&mut env, &format!("byte_array_from_slice: {e}")); return std::ptr::null_mut(); }
        };
        if let Err(e) = env.set_object_array_element(&out, i as i32, &arr) {
            throw(&mut env, &format!("set_object_array_element: {e}"));
            return std::ptr::null_mut();
        }
    }
    out.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_sealRaptorQ(
    mut env: JNIEnv,
    _this: JObject,
    key: JByteArray,
    plaintext: JByteArray,
) -> jobjectArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "key must be 32 bytes"); return std::ptr::null_mut(); }
    };
    let pt_bytes = match env.convert_byte_array(&plaintext) {
        Ok(b) => b,
        Err(e) => { throw(&mut env, &format!("plaintext error: {e}")); return std::ptr::null_mut(); }
    };
    let mut k32 = [0u8; 32];
    k32.copy_from_slice(&key_bytes);

    let cipher = XChaCha20Poly1305::new((&k32).into());
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    let ct = match cipher.encrypt(XNonce::from_slice(&nonce), pt_bytes.as_slice()) {
        Ok(c) => c,
        Err(_) => { throw(&mut env, "encryption failed"); return std::ptr::null_mut(); }
    };

    let mut framed = Vec::with_capacity(24 + ct.len());
    framed.extend_from_slice(&nonce);
    framed.extend_from_slice(&ct);

    let (cfg, pkts) = fec_interleaver::raptorq_encode(&framed, 256, 4);
    let cfg_bytes = cfg.serialize();

    let byte_class = match env.find_class("[B") {
        Ok(c) => c,
        Err(e) => { throw(&mut env, &format!("find_class: {e}")); return std::ptr::null_mut(); }
    };
    let null_obj = JObject::null();
    let out = match env.new_object_array((pkts.len() + 1) as i32, &byte_class, &null_obj) {
        Ok(a) => a,
        Err(e) => { throw(&mut env, &format!("new_object_array: {e}")); return std::ptr::null_mut(); }
    };

    let cfg_arr = env.byte_array_from_slice(&cfg_bytes).unwrap();
    let _ = env.set_object_array_element(&out, 0, &cfg_arr);
    for (i, p) in pkts.iter().enumerate() {
        if let Ok(a) = env.byte_array_from_slice(p) {
            let _ = env.set_object_array_element(&out, (i + 1) as i32, &a);
        }
    }
    out.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_openRaptorQ(
    mut env: JNIEnv,
    _this: JObject,
    key: JByteArray,
    packets: JObjectArray,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "key must be 32 bytes"); return std::ptr::null_mut(); }
    };
    let len = match env.get_array_length(&packets) {
        Ok(l) if l >= 2 => l as usize,
        _ => { throw(&mut env, "packets must include config + >=1 packet"); return std::ptr::null_mut(); }
    };

    let cfg_obj = JByteArray::from(env.get_object_array_element(&packets, 0).unwrap());
    let cfg_bytes = env.convert_byte_array(&cfg_obj).unwrap_or_default();
    if cfg_bytes.len() != 12 {
        throw(&mut env, "invalid RaptorQ OTI config");
        return std::ptr::null_mut();
    }
    let mut cfg_arr = [0u8; 12];
    cfg_arr.copy_from_slice(&cfg_bytes);
    let cfg = raptorq::ObjectTransmissionInformation::deserialize(&cfg_arr);

    let mut raw_pkts = Vec::with_capacity(len - 1);
    for i in 1..len {
        if let Ok(elem) = env.get_object_array_element(&packets, i as i32) {
            if !elem.is_null() {
                if let Ok(b) = env.convert_byte_array(&JByteArray::from(elem)) {
                    raw_pkts.push(b);
                }
            }
        }
    }

    let framed = match fec_interleaver::raptorq_decode(cfg, &raw_pkts) {
        Ok(d) => d,
        Err(e) => { throw(&mut env, &e.to_string()); return std::ptr::null_mut(); }
    };
    if framed.len() < 24 {
        throw(&mut env, "corrupt decrypted frame");
        return std::ptr::null_mut();
    }
    let mut k32 = [0u8; 32];
    k32.copy_from_slice(&key_bytes);
    let cipher = XChaCha20Poly1305::new((&k32).into());
    let (nonce, ct) = framed.split_at(24);
    match cipher.decrypt(XNonce::from_slice(nonce), ct) {
        Ok(plain) => env.byte_array_from_slice(&plain).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => { throw(&mut env, "AEAD authentication failed"); std::ptr::null_mut() }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_encodeSubNoise8FskPcm(
    mut env: JNIEnv,
    _this: JObject,
    payload: JByteArray,
    base_freq_hz: jni::sys::jfloat,
    sample_rate: jint,
) -> jni::sys::jshortArray {
    let data = match env.convert_byte_array(&payload) {
        Ok(b) => b,
        Err(e) => { throw(&mut env, &format!("payload error: {e}")); return std::ptr::null_mut(); }
    };
    let syms = dsp_modem::sub_noise_8fsk::encode_8fsk_frame(&data);
    let pcm = dsp_modem::sub_noise_8fsk::synthesize_8fsk_pcm(&syms, base_freq_hz, sample_rate.max(8000) as u32);
    let out = match env.new_short_array(pcm.len() as i32) {
        Ok(a) => a,
        Err(e) => { throw(&mut env, &format!("new_short_array: {e}")); return std::ptr::null_mut(); }
    };
    if let Err(e) = env.set_short_array_region(&out, 0, &pcm) {
        throw(&mut env, &format!("set_short_array_region: {e}"));
        return std::ptr::null_mut();
    }
    out.into_raw()
}
use dtn_router::BlindDtnStore;
static DTN_STORE: Lazy<Mutex<Option<BlindDtnStore>>> = Lazy::new(|| Mutex::new(None));

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_initDtnStore(
    mut env: JNIEnv,
    _this: JObject,
    storage_key: JByteArray,
) -> jboolean {
    let key_bytes = match env.convert_byte_array(&storage_key) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "DTN storage key must be 32 bytes"); return 0; }
    };
    let mut k32 = [0u8; 32];
    k32.copy_from_slice(&key_bytes);
    let mut guard = DTN_STORE.lock().unwrap_or_else(|e| e.into_inner());
    *guard = Some(BlindDtnStore::new(k32, BlindDtnStore::DEFAULT_MAX_BYTES));
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_dtnEnqueueBundle(
    mut env: JNIEnv,
    _this: JObject,
    bundle_payload: JByteArray,
    now_utc_ms: jlong,
    ttl_ms: jlong,
) -> jbyteArray {
    let payload = match env.convert_byte_array(&bundle_payload) {
        Ok(b) if !b.is_empty() => b,
        _ => { throw(&mut env, "invalid DTN bundle payload"); return std::ptr::null_mut(); }
    };
    let mut guard = DTN_STORE.lock().unwrap_or_else(|e| e.into_inner());
    let store = match guard.as_mut() {
        Some(s) => s,
        None => { throw(&mut env, "DTN store not initialized"); return std::ptr::null_mut(); }
    };
    match store.enqueue_blind_bundle(payload, now_utc_ms.max(0) as u64, ttl_ms.max(1000) as u64) {
        Ok(id) => env.byte_array_from_slice(&id).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => { throw(&mut env, &e.to_string()); std::ptr::null_mut() }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_dtnListRelayBundles(
    mut env: JNIEnv,
    _this: JObject,
    now_utc_ms: jlong,
) -> jobjectArray {
    let mut guard = DTN_STORE.lock().unwrap_or_else(|e| e.into_inner());
    let store = match guard.as_mut() {
        Some(s) => s,
        None => { throw(&mut env, "DTN store not initialized"); return std::ptr::null_mut(); }
    };
    let _ = store.evict_expired(now_utc_ms.max(0) as u64);

    let byte_class = match env.find_class("[B") {
        Ok(c) => c,
        Err(e) => { throw(&mut env, &format!("find_class: {e}")); return std::ptr::null_mut(); }
    };
    let null_obj = JObject::null();
    let out = match env.new_object_array(store.len() as i32, &byte_class, &null_obj) {
        Ok(a) => a,
        Err(e) => { throw(&mut env, &format!("new_object_array: {e}")); return std::ptr::null_mut(); }
    };

    let payloads = store.all_relay_payloads();
    let out = match env.new_object_array(payloads.len() as i32, &byte_class, &null_obj) {
        Ok(a) => a,
        Err(e) => { throw(&mut env, &format!("new_object_array: {e}")); return std::ptr::null_mut(); }
    };
    for (i, p) in payloads.iter().enumerate() {
        if let Ok(arr) = env.byte_array_from_slice(p) {
            let _ = env.set_object_array_element(&out, i as i32, &arr);
        }
    }
    out.into_raw()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_dtnClearStore(
    _env: JNIEnv,
    _this: JObject,
) {
    let mut guard = DTN_STORE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(store) = guard.as_mut() {
        let _ = store.evict_expired(u64::MAX);
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_sealDtnBody(
    mut env: JNIEnv,
    _this: JObject,
    key: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "key must be 32 bytes"); return std::ptr::null_mut(); }
    };
    let pt = match env.convert_byte_array(&plaintext) {
        Ok(b) => b,
        Err(e) => { throw(&mut env, &format!("plaintext error: {e}")); return std::ptr::null_mut(); }
    };
    let mut k32 = [0u8; 32];
    k32.copy_from_slice(&key_bytes);
    let cipher = XChaCha20Poly1305::new((&k32).into());
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    let ct = match cipher.encrypt(XNonce::from_slice(&nonce), pt.as_slice()) {
        Ok(c) => c,
        Err(_) => { throw(&mut env, "DTN E2EE encryption failed"); return std::ptr::null_mut(); }
    };
    let mut out = Vec::with_capacity(24 + ct.len());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    env.byte_array_from_slice(&out).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_openDtnBody(
    mut env: JNIEnv,
    _this: JObject,
    key: JByteArray,
    ciphertext: JByteArray,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "key must be 32 bytes"); return std::ptr::null_mut(); }
    };
    let ct_all = match env.convert_byte_array(&ciphertext) {
        Ok(b) if b.len() > 24 => b,
        _ => { throw(&mut env, "invalid ciphertext"); return std::ptr::null_mut(); }
    };
    let mut k32 = [0u8; 32];
    k32.copy_from_slice(&key_bytes);
    let cipher = XChaCha20Poly1305::new((&k32).into());
    let (nonce, ct) = ct_all.split_at(24);
    match cipher.decrypt(XNonce::from_slice(nonce), ct) {
        Ok(plain) => env.byte_array_from_slice(&plain).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => { throw(&mut env, "DTN E2EE auth failed"); std::ptr::null_mut() }
    }
}
use x25519_dalek::{PublicKey as X25519Pub, StaticSecret as X25519Priv};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_generateStaticKeypair(
    mut env: JNIEnv,
    _this: JObject,
) -> jobjectArray {
    let sec = X25519Priv::random_from_rng(OsRng);
    let pubk = X25519Pub::from(&sec);
    let byte_class = match env.find_class("[B") {
        Ok(c) => c,
        Err(e) => { throw(&mut env, &format!("find_class: {e}")); return std::ptr::null_mut(); }
    };
    let null_obj = JObject::null();
    let out = match env.new_object_array(2, &byte_class, &null_obj) {
        Ok(a) => a,
        Err(e) => { throw(&mut env, &format!("new_object_array: {e}")); return std::ptr::null_mut(); }
    };
    if let Ok(a0) = env.byte_array_from_slice(&sec.to_bytes()) {
        let _ = env.set_object_array_element(&out, 0, &a0);
    }
    if let Ok(a1) = env.byte_array_from_slice(pubk.as_bytes()) {
        let _ = env.set_object_array_element(&out, 1, &a1);
    }
    out.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_sealToRecipient(
    mut env: JNIEnv,
    _this: JObject,
    recipient_pub: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    let rpub_bytes = match env.convert_byte_array(&recipient_pub) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "recipient pubkey must be 32 bytes"); return std::ptr::null_mut(); }
    };
    let pt = match env.convert_byte_array(&plaintext) {
        Ok(b) => b,
        Err(e) => { throw(&mut env, &format!("plaintext error: {e}")); return std::ptr::null_mut(); }
    };
    let mut rpub_arr = [0u8; 32];
    rpub_arr.copy_from_slice(&rpub_bytes);
    let rpub = X25519Pub::from(rpub_arr);

    let eph_sec = X25519Priv::random_from_rng(OsRng);
    let eph_pub = X25519Pub::from(&eph_sec);
    let shared = eph_sec.diffie_hellman(&rpub);
    let sym_key = blake3::derive_key("desertcomm-sealed-box-v3.1", shared.as_bytes());

    let cipher = XChaCha20Poly1305::new((&sym_key).into());
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    let ct = match cipher.encrypt(XNonce::from_slice(&nonce), pt.as_slice()) {
        Ok(c) => c,
        Err(_) => { throw(&mut env, "sealed box encrypt failed"); return std::ptr::null_mut(); }
    };

    let mut out = Vec::with_capacity(32 + 24 + ct.len());
    out.extend_from_slice(eph_pub.as_bytes());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    env.byte_array_from_slice(&out).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_openFromSender(
    mut env: JNIEnv,
    _this: JObject,
    my_priv: JByteArray,
    sealed_box: JByteArray,
) -> jbyteArray {
    let priv_bytes = match env.convert_byte_array(&my_priv) {
        Ok(b) if b.len() == 32 => b,
        _ => { throw(&mut env, "private key must be 32 bytes"); return std::ptr::null_mut(); }
    };
    let raw = match env.convert_byte_array(&sealed_box) {
        Ok(b) if b.len() > 56 => b,
        _ => { throw(&mut env, "invalid sealed box"); return std::ptr::null_mut(); }
    };
    let mut priv_arr = [0u8; 32];
    priv_arr.copy_from_slice(&priv_bytes);
    let mut eph_arr = [0u8; 32];
    eph_arr.copy_from_slice(&raw[0..32]);

    let sec = X25519Priv::from(priv_arr);
    let eph_pub = X25519Pub::from(eph_arr);
    let shared = sec.diffie_hellman(&eph_pub);
    let sym_key = blake3::derive_key("desertcomm-sealed-box-v3.1", shared.as_bytes());

    let cipher = XChaCha20Poly1305::new((&sym_key).into());
    let nonce = &raw[32..56];
    let ct = &raw[56..];
    match cipher.decrypt(XNonce::from_slice(nonce), ct) {
        Ok(plain) => env.byte_array_from_slice(&plain).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => { throw(&mut env, "sealed box auth failed"); std::ptr::null_mut() }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_desertcomm_core_NativeBridgeV3_decodeSubNoise8FskPcm(
    mut env: JNIEnv,
    _this: JObject,
    pcm_array: jni::sys::jshortArray,
    base_freq_hz: jni::sys::jfloat,
    sample_rate: jint,
    expected_bytes: jint,
) -> jbyteArray {
    let jarr = unsafe { jni::objects::JShortArray::from_raw(pcm_array) };
    let len = match env.get_array_length(&jarr) {
        Ok(l) if l > 0 => l as usize,
        _ => return std::ptr::null_mut(),
    };
    let mut pcm = vec![0i16; len];
    if env.get_short_array_region(&jarr, 0, &mut pcm).is_err() {
        return std::ptr::null_mut();
    }
    let syms = dsp_modem::sub_noise_8fsk::demodulate_8fsk_pcm(&pcm, base_freq_hz, sample_rate.max(8000) as u32);
    match dsp_modem::sub_noise_8fsk::decode_8fsk_frame(&syms, expected_bytes.max(1) as usize) {
        Some(bytes) => env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}