package com.desertcomm.core

object NativeBridgeV3 {
    init {
        System.loadLibrary("desertcomm_v3")
    }

    external fun tickClock(monotonicMs: Long, osSleepDetected: Boolean): Int
    external fun deriveCrv(
        passphrase: String,
        mgrs: String,
        nonce: ByteArray,
        hourCounter: Long,
        totalChannels: Int
    ): IntArray?
    external fun generateElligatorKeypair(): Array<ByteArray>?
    external fun sealRaptorQ(key: ByteArray, plaintext: ByteArray): Array<ByteArray>?
    external fun openRaptorQ(key: ByteArray, packets: Array<ByteArray>): ByteArray?
    external fun encodeSubNoise8FskPcm(payload: ByteArray, baseFreqHz: Float, sampleRate: Int): ShortArray?
    external fun decodeSubNoise8FskPcm(pcm: ShortArray, baseFreqHz: Float, sampleRate: Int, expectedBytes: Int): ByteArray?
    external fun initDtnStore(storageKey: ByteArray): Boolean
    external fun dtnEnqueueBundle(bundlePayload: ByteArray, nowUtcMs: Long, ttlMs: Long): ByteArray?
    external fun dtnListRelayBundles(nowUtcMs: Long): Array<ByteArray>?
    external fun dtnClearStore()
    external fun sealDtnBody(key: ByteArray, plaintext: ByteArray): ByteArray?
    external fun openDtnBody(key: ByteArray, ciphertext: ByteArray): ByteArray?
    external fun generateStaticKeypair(): Array<ByteArray>?
    external fun sealToRecipient(recipientPub: ByteArray, plaintext: ByteArray): ByteArray?
    external fun openFromSender(myPriv: ByteArray, sealedBox: ByteArray): ByteArray?

    fun safeTickClock(monotonicMs: Long, osSleepDetected: Boolean): Result<Int> = runCatching {
        tickClock(monotonicMs, osSleepDetected)
    }

    fun safeDeriveCrv(
        passphrase: String,
        mgrs: String,
        nonce: ByteArray,
        hourCounter: Long,
        totalChannels: Int
    ): Result<IntArray> = runCatching {
        deriveCrv(passphrase, mgrs, nonce, hourCounter, totalChannels)
            ?.takeIf { it.size == 3 }
            ?: error("CRV derivation returned null")
    }

    external fun signSenderPayload(staticPriv: ByteArray, payload: ByteArray): ByteArray?
    external fun verifySenderPayload(sigBundle: ByteArray, payload: ByteArray): Boolean
    external fun buildNostrEventK256(pTagHex: String, content: String, createdAt: Long, kind: Int): String?
    external fun pqGenerateMlkem768Keypair(): Array<ByteArray>?
    external fun pqInitiateSession(peerId: String, peerEk: ByteArray, myXPriv: ByteArray, peerXPub: ByteArray, peerRatchetPub: ByteArray): ByteArray?
    external fun pqAcceptSession(peerId: String, myDk: ByteArray, mlkemCt: ByteArray, myXPriv: ByteArray, peerXPub: ByteArray, myRatchetPriv: ByteArray): Boolean
    external fun pqRatchetEncrypt(peerId: String, plaintext: ByteArray): ByteArray?
    external fun pqRatchetDecrypt(peerId: String, packet: ByteArray): ByteArray?
    external fun pqExportSession(peerId: String): ByteArray?
    external fun pqImportSession(peerId: String, state: ByteArray): Boolean
    external fun pqHasSession(peerId: String): Boolean

    fun safeGenerateElligatorKeypair(): Result<Triple<ByteArray, ByteArray, ByteArray>> = runCatching {
        val arr = generateElligatorKeypair() ?: error("Elligator2 keygen returned null")
        require(arr.size == 3) { "Expected 3 arrays" }
        Triple(arr[0], arr[1], arr[2])
    }
}