package com.desertcomm.p2p

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object NostrCrypto {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val EIGHT = BigInteger.valueOf(8)
    private val rng = SecureRandom()

    private data class Point(val x: BigInteger, val y: BigInteger)
    private data class JacPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger)

    private val privKey: BigInteger by lazy {
        var d: BigInteger
        do {
            val b = ByteArray(32).also { rng.nextBytes(it) }
            d = BigInteger(1, b).mod(N)
        } while (d == BigInteger.ZERO)
        d
    }

    private val pubPoint: Point by lazy { mulG(privKey)!! }
    val pubKeyHex: String by lazy { toBytes32(pubPoint.x).toHex() }

    private fun jacDouble(p: JacPoint?): JacPoint? {
        if (p == null || p.y == BigInteger.ZERO || p.z == BigInteger.ZERO) return null
        val y2 = p.y.multiply(p.y).mod(P)
        val s = FOUR.multiply(p.x).multiply(y2).mod(P)
        val m = THREE.multiply(p.x).multiply(p.x).mod(P)
        val x3 = m.multiply(m).subtract(TWO.multiply(s)).mod(P)
        val y4 = y2.multiply(y2).mod(P)
        val y3 = m.multiply(s.subtract(x3)).subtract(EIGHT.multiply(y4)).mod(P)
        val z3 = TWO.multiply(p.y).multiply(p.z).mod(P)
        return JacPoint(x3, y3, z3)
    }

    private fun jacAddAffine(p: JacPoint?, ax: BigInteger, ay: BigInteger): JacPoint? {
        if (p == null || p.z == BigInteger.ZERO) return JacPoint(ax, ay, BigInteger.ONE)
        val z2 = p.z.multiply(p.z).mod(P)
        val u2 = ax.multiply(z2).mod(P)
        val s2 = ay.multiply(p.z).multiply(z2).mod(P)
        val h = u2.subtract(p.x).mod(P)
        val r = s2.subtract(p.y).mod(P)
        if (h == BigInteger.ZERO) {
            return if (r == BigInteger.ZERO) jacDouble(p) else null
        }
        val hh = h.multiply(h).mod(P)
        val hhh = h.multiply(hh).mod(P)
        val v = p.x.multiply(hh).mod(P)
        val x3 = r.multiply(r).subtract(hhh).subtract(TWO.multiply(v)).mod(P)
        val y3 = r.multiply(v.subtract(x3)).subtract(p.y.multiply(hhh)).mod(P)
        val z3 = p.z.multiply(h).mod(P)
        return JacPoint(x3, y3, z3)
    }

    private fun mulG(k: BigInteger): Point? {
        val scalar = k.mod(N)
        if (scalar == BigInteger.ZERO) return null
        var acc: JacPoint? = null
        for (i in scalar.bitLength() - 1 downTo 0) {
            acc = jacDouble(acc)
            if (scalar.testBit(i)) acc = jacAddAffine(acc, GX, GY)
        }
        val j = acc ?: return null
        val zInv = j.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).mod(P)
        val x = j.x.multiply(zInv2).mod(P)
        val y = j.y.multiply(zInv2).multiply(zInv).mod(P)
        return Point(x, y)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val th = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(th, th, *parts)
    }

    private fun toBytes32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) System.arraycopy(raw, raw.size - 32, out, 0, 32)
        else System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun sha256Hex(s: String): String = sha256(s.toByteArray(Charsets.UTF_8)).toHex()

    private fun signSchnorr(msg32: ByteArray): String {
        val d = if (pubPoint.y.testBit(0)) N.subtract(privKey) else privKey
        val px = toBytes32(pubPoint.x)
        val dBytes = toBytes32(d)
        val aux = ByteArray(32).also { rng.nextBytes(it) }
        val auxHash = taggedHash("BIP0340/aux", aux)
        val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }
        val rand = taggedHash("BIP0340/nonce", t, px, msg32)
        val kPrime = BigInteger(1, rand).mod(N)
        if (kPrime == BigInteger.ZERO) error("zero nonce")
        val rPt = mulG(kPrime)!!
        val k = if (rPt.y.testBit(0)) N.subtract(kPrime) else kPrime
        val rx = toBytes32(rPt.x)
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx, px, msg32)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return (rx + toBytes32(s)).toHex()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun buildSignedEventJson(pTagHex: String, content: String, kind: Int = 1059): String {
        val createdAt = System.currentTimeMillis() / 1000L; com.desertcomm.core.NativeBridgeV3.buildNostrEventK256(pTagHex, content, createdAt, kind)?.let { return it }
        val pk = pubKeyHex
        val escTag = jsonEscape(pTagHex)
        val escContent = jsonEscape(content)
        val canonical = "[0,\"$pk\",$createdAt,$kind,[[\"p\",$escTag]],$escContent]"
        val idBytes = sha256(canonical.toByteArray(Charsets.UTF_8))
        val idHex = idBytes.toHex()
        val sigHex = signSchnorr(idBytes)
        return "[\"EVENT\",{\"id\":\"$idHex\",\"pubkey\":\"$pk\",\"created_at\":$createdAt,\"kind\":$kind,\"tags\":[[\"p\",$escTag]],\"content\":$escContent,\"sig\":\"$sigHex\"}]"
    }
}