//! تمويه المفاتيح العلنية Curve25519 عبر Elligator2

use num_bigint::BigUint;
use rand::RngCore;
use x25519_dalek::{PublicKey, StaticSecret};

fn prime_p() -> BigUint {
    (BigUint::from(1u8) << 255) - BigUint::from(19u8)
}

fn mod_a() -> BigUint {
    BigUint::from(486662u32)
}

fn mod_neg(x: &BigUint, p: &BigUint) -> BigUint {
    if x == &BigUint::from(0u8) {
        BigUint::from(0u8)
    } else {
        p - (x % p)
    }
}

fn mod_inv(x: &BigUint, p: &BigUint) -> BigUint {
    x.modpow(&(p - BigUint::from(2u8)), p)
}

fn is_square(x: &BigUint, p: &BigUint) -> bool {
    if x % p == BigUint::from(0u8) {
        return true;
    }
    let exp = (p - BigUint::from(1u8)) >> 1;
    x.modpow(&exp, p) == BigUint::from(1u8)
}

fn mod_sqrt(x: &BigUint, p: &BigUint) -> Option<BigUint> {
    if !is_square(x, p) {
        return None;
    }
    if x % p == BigUint::from(0u8) {
        return Some(BigUint::from(0u8));
    }
    // p = 5 (mod 8) لـ Curve25519
    let exp = (p + BigUint::from(3u8)) >> 3;
    let mut r = x.modpow(&exp, p);
    if (&r * &r) % p != x % p {
        let sqrt_m1_exp = (p - BigUint::from(1u8)) >> 2;
        let sqrt_m1 = BigUint::from(2u8).modpow(&sqrt_m1_exp, p);
        r = (r * sqrt_m1) % p;
    }
    let half = (p - BigUint::from(1u8)) >> 1;
    if r > half {
        r = p - r;
    }
    Some(r)
}

/// تحويل ممثل عشوائي (32 بايت) إلى إحداثي u على منحنى Curve25519
pub fn elligator2_decode(rep: &[u8; 32]) -> [u8; 32] {
    let p = prime_p();
    let a = mod_a();
    let mut masked = *rep;
    masked[31] &= 0x3F;
    let r = BigUint::from_bytes_le(&masked);

    // d = -A / (1 + 2 * r^2) mod p
    let r2 = (&r * &r) % &p;
    let denom = (BigUint::from(1u8) + BigUint::from(2u8) * r2) % &p;
    let d = (mod_neg(&a, &p) * mod_inv(&denom, &p)) % &p;

    // w = d^3 + A*d^2 + d
    let d2 = (&d * &d) % &p;
    let d3 = (&d2 * &d) % &p;
    let w = (d3 + (&a * d2) + &d) % &p;

    let u = if is_square(&w, &p) {
        d
    } else {
        mod_neg(&((d + a) % &p), &p)
    };

    let mut out = [0u8; 32];
    let bytes = u.to_bytes_le();
    let len = bytes.len().min(32);
    out[..len].copy_from_slice(&bytes[..len]);
    out
}

/// محاولة ترميز مفتاح علني Curve25519 إلى ممثل عشوائي (ينجح لـ ~50% من المفاتيح)
pub fn elligator2_encode(pub_u: &[u8; 32], rng: &mut impl RngCore) -> Option<[u8; 32]> {
    let p = prime_p();
    let a = mod_a();
    let u = BigUint::from_bytes_le(pub_u) % &p;
    let u_plus_a = (&u + &a) % &p;
    if u_plus_a == BigUint::from(0u8) {
        return None;
    }

    // التحقق من شرط وجود ممثل: -2 * u * (u + A) مربع
    let check = mod_neg(&((BigUint::from(2u8) * &u * &u_plus_a) % &p), &p);
    if !is_square(&check, &p) {
        return None;
    }

    // اختيار إحدى الصيغتين حسب ما إذا كان u <= (p-1)/2
    let half = (&p - BigUint::from(1u8)) >> 1;
    let r_sq = if u <= half {
        // r^2 = -u / (2 * (u + A))
        let denom = (BigUint::from(2u8) * &u_plus_a) % &p;
        (mod_neg(&u, &p) * mod_inv(&denom, &p)) % &p
    } else {
        // r^2 = -(u + A) / (2 * u)
        let denom = (BigUint::from(2u8) * &u) % &p;
        (mod_neg(&u_plus_a, &p) * mod_inv(&denom, &p)) % &p
    };

    let r = mod_sqrt(&r_sq, &p)?;
    let mut out = [0u8; 32];
    let bytes = r.to_bytes_le();
    let len = bytes.len().min(32);
    out[..len].copy_from_slice(&bytes[..len]);

    // حشو البتّين الأعليين بعشوائية نقية لإخفاء البنية
    let mut pad = [0u8; 1];
    rng.fill_bytes(&mut pad);
    out[31] = (out[31] & 0x3F) | (pad[0] & 0xC0);

    // تحقق نهائي أن فك الترميز يعيد نفس u
    if elligator2_decode(&out) == *pub_u {
        Some(out)
    } else {
        None
    }
}

/// توليد زوج مفاتيح X25519 يمتلك ممثل Elligator2 صالحاً
pub fn generate_elligator2_keypair(rng: &mut (impl RngCore + rand::CryptoRng)) -> (StaticSecret, PublicKey, [u8; 32]) {
    loop {
        let secret = StaticSecret::random_from_rng(&mut *rng);
        let public = PublicKey::from(&secret);
        if let Some(rep) = elligator2_encode(public.as_bytes(), rng) {
            return (secret, public, rep);
        }
    }
}