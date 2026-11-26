package com.desertcomm.core

object NativeBridge {
    init {
        System.loadLibrary("core_crypto")
    }

    external fun generateIdentity(): String?
    external fun generateSessionKey(): ByteArray?
    external fun computeSharedKey(peerPublicKey: ByteArray): ByteArray?
    external fun seal(key: ByteArray, plaintext: ByteArray): Array<ByteArray>?
    external fun open(key: ByteArray, packets: Array<ByteArray>): ByteArray?

    fun safeGenerateIdentity(): Result<String> = runCatching {
        generateIdentity()?.takeIf { it.isNotBlank() } ?: error("Empty identity from Rust")
    }

    fun safeGenerateSessionKey(): Result<ByteArray> = runCatching {
        generateSessionKey()?.takeIf { it.size == 32 } ?: error("Invalid session key from Rust")
    }

    fun safeComputeSharedKey(peerPublicKey: ByteArray): Result<ByteArray> = runCatching {
        require(peerPublicKey.size == 32) { "Peer key must be 32 bytes, got ${peerPublicKey.size}" }
        computeSharedKey(peerPublicKey)?.takeIf { it.size == 32 } ?: error("Null shared key from Rust")
    }

    fun safeSeal(key: ByteArray, plaintext: ByteArray): Result<List<ByteArray>> = runCatching {
        seal(key, plaintext)?.toList() ?: error("seal returned null")
    }

    fun safeOpen(key: ByteArray, packets: List<ByteArray>): Result<ByteArray> = runCatching {
        open(key, packets.toTypedArray()) ?: error("open returned null")
    }
}