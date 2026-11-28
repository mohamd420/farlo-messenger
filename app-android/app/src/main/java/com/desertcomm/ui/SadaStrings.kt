package com.desertcomm.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SadaLang(
    val isRtl: Boolean,
    val appTitle: String,
    val lockedTitle: String,
    val enterPin: String,
    val wrongPin: String,
    val unlockBtn: String,
    val biometricBtn: String,
    val duressHint: String,
    val noChats: String,
    val settings: String,
    val close: String,
    val cancel: String,
    val send: String,
    val copy: String,
    val messageHint: String,
    val photoHd: String,
    val fileVideo: String,
    val myLocation: String,
    val walkieTalkie: String,
    val voiceCall: String,
    val disappear: String,
    val safetyNumber: String,
    val verifiedContact: String,
    val meshDiag: String,
    val backup: String,
    val relaysTitle: String,
    val relaysDesc: String,
    val connected: String,
    val connecting: String,
    val exportBtn: String,
    val restoreBtn: String,
    val backupTitle: String,
    val backupDesc: String,
    val backupPwdLabel: String,
    val pttTitle: String,
    val pttHold: String,
    val pttTalking: String,
    val pttReceiving: String,
    val callIncoming: String,
    val callOutgoing: String,
    val callActive: String,
    val callAccept: String,
    val callDecline: String,
    val callEnd: String,
    val callMute: String,
    val callMic: String,
    val callSpeaker: String,
    val replyPrefix: String,
    val verifyBtn: String,
    val unverifyBtn: String,
    val yourId: String,
    val onlineHybrid: String,
    val onlineConnDesc: String,
    val waitingNetDesc: String,
    val airGapDesc: String,
    val appLock: String,
    val darkMode: String,
    val muteNotif: String,
    val tacticalMode: String,
    val blockedTitle: String,
    val noBlocked: String
)

object SadaStrings {
    private val ar = SadaLang(
        isRtl = true, appTitle = "Farlo", lockedTitle = "Farlo مقفل", enterPin = "أدخل الرمز السري (PIN)",
        wrongPin = "الرمز غير صحيح", unlockBtn = "فتح التطبيق", biometricBtn = "البصمة",
        duressHint = "", noChats = "لا توجد محادثات حالياً",
        settings = "الإعدادات", close = "إغلاق", cancel = "إلغاء", send = "إرسال", copy = "نسخ",
        messageHint = "رسالة...", photoHd = "صورة HD", fileVideo = "ملف / فيديو", myLocation = "موقعي",
        walkieTalkie = "لاسلكي", voiceCall = "مكالمة", disappear = "اختفاء",
        safetyNumber = "🛡️ التحقق من رقم الأمان (Safety Number)", verifiedContact = "🛡️ جهة اتصال موثّقة • عرض رقم الأمان",
        meshDiag = "تشخيص Mesh", backup = "نسخ احتياطي", relaysTitle = "كوكبة المرحّلات اللامركزية",
        relaysDesc = "يتصل Farlo بعدة عقد مستقلة بالتوازي لضمان وصول رسائلك المشفرة بدون نقطة فشل واحدة.",
        connected = "متصل", connecting = "محاولة...", exportBtn = "تصدير", restoreBtn = "استعادة",
        backupTitle = "النسخ الاحتياطي المشفّر", backupDesc = "احفظ هويتك ومحادثاتك في ملف مشفّر بكلمة مرور لاستعادتها على أي هاتف.",
        backupPwdLabel = "كلمة مرور التشفير (8 أحرف+)", pttTitle = "لاسلكي Farlo الحي • Live PTT",
        pttHold = "اضغط مطوّلاً", pttTalking = "يبث الآن", pttReceiving = "يستقبل",
        callIncoming = "مكالمة صوتية مشفّرة واردة...", callOutgoing = "جاري الاتصال المشفّر...",
        callActive = "مكالمة مشفّرة E2EE", callAccept = "رد", callDecline = "رفض", callEnd = "إنهاء",
        callMute = "كتم", callMic = "مايك", callSpeaker = "مكبر", replyPrefix = "↩ الرد على:",
        verifyBtn = "توثيق جهة الاتصال ✓", unverifyBtn = "إلغاء التوثيق", yourId = "رقمك الفريد:", onlineHybrid = "الاتصال العالمي عبر الإنترنت (هجين)", onlineConnDesc = "متصل بالمرحّل الأعمى 🌐 + بلوتوث", waitingNetDesc = "بانتظار الإنترنت (يعمل عبر Mesh)", airGapDesc = "وضع Air-Gap (بدون إنترنت نهائياً)", appLock = "قفل التطبيق برمز سري (اختياري)", darkMode = "الوضع الداكن", muteNotif = "كتم الإشعارات", tacticalMode = "الطور التكتيكي (WakeLock)", blockedTitle = "المحظورون", noBlocked = "لا توجد أرقام محظورة"
    )

    private val en = SadaLang(
        isRtl = false, appTitle = "Farlo", lockedTitle = "Farlo Locked", enterPin = "Enter PIN",
        wrongPin = "Incorrect PIN", unlockBtn = "Unlock", biometricBtn = "Biometrics",
        duressHint = "", noChats = "No conversations yet",
        settings = "Settings", close = "Close", cancel = "Cancel", send = "Send", copy = "Copy",
        messageHint = "Message...", photoHd = "HD Photo", fileVideo = "File / Video", myLocation = "Location",
        walkieTalkie = "PTT Radio", voiceCall = "Call", disappear = "Timer",
        safetyNumber = "🛡️ Verify Safety Number", verifiedContact = "🛡️ Verified Contact • View Safety Number",
        meshDiag = "Mesh Status", backup = "Backup", relaysTitle = "Decentralized Relays",
        relaysDesc = "Farlo connects to multiple independent relays in parallel for resilient E2EE delivery.",
        connected = "Connected", connecting = "Connecting...", exportBtn = "Export", restoreBtn = "Restore",
        backupTitle = "Encrypted Backup", backupDesc = "Export your identity & chats into a password-encrypted file to restore on any device.",
        backupPwdLabel = "Encryption Password (8+ chars)", pttTitle = "Farlo Live Walkie-Talkie • PTT",
        pttHold = "Hold to Talk", pttTalking = "Transmitting", pttReceiving = "Receiving",
        callIncoming = "Incoming Encrypted Voice Call...", callOutgoing = "Calling (E2EE)...",
        callActive = "E2EE Voice Call", callAccept = "Answer", callDecline = "Decline", callEnd = "End",
        callMute = "Muted", callMic = "Mic", callSpeaker = "Speaker", replyPrefix = "↩ Replying to:",
        verifyBtn = "Mark Verified ✓", unverifyBtn = "Remove Verification", yourId = "Your Unique ID:", onlineHybrid = "Global Online Relay (Hybrid)", onlineConnDesc = "Connected to Blind Relays 🌐 + BLE", waitingNetDesc = "Waiting for internet (Mesh active)", airGapDesc = "Air-Gap Mode (Offline only)", appLock = "App PIN Lock (Optional)", darkMode = "Dark Mode", muteNotif = "Mute Notifications", tacticalMode = "Tactical Mode (WakeLock)", blockedTitle = "Blocked", noBlocked = "No blocked contacts"
    )

    private val fr = SadaLang(
        isRtl = false, appTitle = "Farlo", lockedTitle = "Farlo Verrouillé", enterPin = "Code PIN",
        wrongPin = "Code PIN incorrect", unlockBtn = "Déverrouiller", biometricBtn = "Biométrie",
        duressHint = "", noChats = "Aucune conversation",
        settings = "Paramètres", close = "Fermer", cancel = "Annuler", send = "Envoyer", copy = "Copier",
        messageHint = "Message...", photoHd = "Photo HD", fileVideo = "Fichier / Vidéo", myLocation = "Position",
        walkieTalkie = "Talkie PTT", voiceCall = "Appel", disappear = "Éphémère",
        safetyNumber = "🛡️ Vérifier le numéro de sécurité", verifiedContact = "🛡️ Contact vérifié • Numéro de sécurité",
        meshDiag = "État Mesh", backup = "Sauvegarde", relaysTitle = "Relais Décentralisés",
        relaysDesc = "Farlo se connecte à plusieurs relais indépendants en parallèle pour vos messages chiffrés.",
        connected = "Connecté", connecting = "Connexion...", exportBtn = "Exporter", restoreBtn = "Restaurer",
        backupTitle = "Sauvegarde Chiffrée", backupDesc = "Exportez votre identité et vos discussions dans un fichier chiffré par mot de passe.",
        backupPwdLabel = "Mot de passe (4+ caractères)", pttTitle = "Talkie-Walkie Farlo • PTT",
        pttHold = "Maintenir", pttTalking = "Émission", pttReceiving = "Réception",
        callIncoming = "Appel vocal chiffré entrant...", callOutgoing = "Appel chiffré en cours...",
        callActive = "Appel chiffré E2EE", callAccept = "Répondre", callDecline = "Refuser", callEnd = "Fin",
        callMute = "Muet", callMic = "Micro", callSpeaker = "Haut-parleur", replyPrefix = "↩ Réponse à :",
        verifyBtn = "Marquer vérifié ✓", unverifyBtn = "Retirer vérification", yourId = "Votre ID unique :", onlineHybrid = "Relais en ligne (Hybride)", onlineConnDesc = "Connecté aux relais 🌐 + BLE", waitingNetDesc = "En attente internet (Mesh actif)", airGapDesc = "Mode Air-Gap (Hors ligne)", appLock = "Verrouillage PIN (Optionnel)", darkMode = "Mode sombre", muteNotif = "Couper notifications", tacticalMode = "Mode tactique (WakeLock)", blockedTitle = "Bloqués", noBlocked = "Aucun contact bloqué"
    )

    private val es = SadaLang(
        isRtl = false, appTitle = "Farlo", lockedTitle = "Farlo Bloqueado", enterPin = "Ingresar PIN",
        wrongPin = "PIN incorrecto", unlockBtn = "Desbloquear", biometricBtn = "Biometría",
        duressHint = "", noChats = "Sin conversaciones",
        settings = "Ajustes", close = "Cerrar", cancel = "Cancelar", send = "Enviar", copy = "Copiar",
        messageHint = "Mensaje...", photoHd = "Foto HD", fileVideo = "Archivo / Video", myLocation = "Ubicación",
        walkieTalkie = "Radio PTT", voiceCall = "Llamar", disappear = "Temporal",
        safetyNumber = "🛡️ Verificar número de seguridad", verifiedContact = "🛡️ Contacto verificado • Ver número",
        meshDiag = "Estado Mesh", backup = "Respaldo", relaysTitle = "Relés Descentralizados",
        relaysDesc = "Farlo se conecta a múltiples relés independientes en paralelo para entrega cifrada.",
        connected = "Conectado", connecting = "Conectando...", exportBtn = "Exportar", restoreBtn = "Restaurar",
        backupTitle = "Copia de Seguridad Cifrada", backupDesc = "Guarda tu identidad y chats en un archivo cifrado con contraseña.",
        backupPwdLabel = "Contraseña (4+ caracteres)", pttTitle = "Walkie-Talkie Farlo • PTT",
        pttHold = "Mantén pulsado", pttTalking = "Transmitiendo", pttReceiving = "Recibiendo",
        callIncoming = "Llamada cifrada entrante...", callOutgoing = "Llamando (E2EE)...",
        callActive = "Llamada cifrada E2EE", callAccept = "Contestar", callDecline = "Rechazar", callEnd = "Colgar",
        callMute = "Silencio", callMic = "Mic", callSpeaker = "Altavoz", replyPrefix = "↩ Respondiendo a:",
        verifyBtn = "Marcar verificado ✓", unverifyBtn = "Quitar verificación", yourId = "Tu ID único:", onlineHybrid = "Relé global en línea (Híbrido)", onlineConnDesc = "Conectado a relés 🌐 + BLE", waitingNetDesc = "Esperando internet (Mesh activo)", airGapDesc = "Modo Air-Gap (Sin conexión)", appLock = "Bloqueo con PIN (Opcional)", darkMode = "Modo oscuro", muteNotif = "Silenciar notificaciones", tacticalMode = "Modo táctico (WakeLock)", blockedTitle = "Bloqueados", noBlocked = "Sin contactos bloqueados"
    )

    private val de = SadaLang(
        isRtl = false, appTitle = "Farlo", lockedTitle = "Farlo Gesperrt", enterPin = "PIN eingeben",
        wrongPin = "Falsche PIN", unlockBtn = "Entsperren", biometricBtn = "Biometrie",
        duressHint = "", noChats = "Keine Chats vorhanden",
        settings = "Einstellungen", close = "Schließen", cancel = "Abbrechen", send = "Senden", copy = "Kopieren",
        messageHint = "Nachricht...", photoHd = "HD-Foto", fileVideo = "Datei / Video", myLocation = "Standort",
        walkieTalkie = "Funk PTT", voiceCall = "Anruf", disappear = "Timer",
        safetyNumber = "🛡️ Sicherheitsnummer prüfen", verifiedContact = "🛡️ Verifiziert • Sicherheitsnummer",
        meshDiag = "Mesh-Status", backup = "Backup", relaysTitle = "Dezentrale Relays",
        relaysDesc = "Farlo verbindet sich parallel mit mehreren unabhängigen Relays für verschlüsselte Zustellung.",
        connected = "Verbunden", connecting = "Verbinde...", exportBtn = "Export", restoreBtn = "Wiederherstellen",
        backupTitle = "Verschlüsseltes Backup", backupDesc = "Sichere Identität und Chats in einer passwortgeschützten Datei.",
        backupPwdLabel = "Passwort (4+ Zeichen)", pttTitle = "Farlo Live-Funk • PTT",
        pttHold = "Gedrückt halten", pttTalking = "Sendet", pttReceiving = "Empfängt",
        callIncoming = "Eingehender E2EE-Anruf...", callOutgoing = "Rufe an (E2EE)...",
        callActive = "E2EE-Sprachanruf", callAccept = "Annehmen", callDecline = "Ablehnen", callEnd = "Beenden",
        callMute = "Stumm", callMic = "Mikro", callSpeaker = "Lautsprecher", replyPrefix = "↩ Antwort auf:",
        verifyBtn = "Als verifiziert markieren ✓", unverifyBtn = "Verifizierung aufheben", yourId = "Deine ID:", onlineHybrid = "Online-Relay (Hybrid)", onlineConnDesc = "Mit Relays verbunden 🌐 + BLE", waitingNetDesc = "Warte auf Internet (Mesh aktiv)", airGapDesc = "Air-Gap-Modus (Nur Offline)", appLock = "PIN-Sperre (Optional)", darkMode = "Dunkelmodus", muteNotif = "Benachrichtigungen stumm", tacticalMode = "Taktik-Modus (WakeLock)", blockedTitle = "Blockiert", noBlocked = "Keine blockierten Kontakte"
    )

    private const val PREFS = "desertcomm_secure_prefs"
    private const val KEY_LANG = "app_language_code"
    private val _langOverride = MutableStateFlow("auto")
    private val composeLang = mutableStateOf("auto")
    val langOverride = _langOverride.asStateFlow()

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, "auto") ?: "auto"
        _langOverride.value = saved
        composeLang.value = saved
    }

    fun setLanguage(context: Context, code: String) {
        _langOverride.value = code
        composeLang.value = code
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, code).apply()
    }

    fun tr(arText: String, enText: String): String = if (current().isRtl) arText else enText

    fun current(overrideCode: String = composeLang.value): SadaLang {
        val code = if (overrideCode == "auto") Locale.getDefault().language.lowercase(Locale.US) else overrideCode
        return when (code) {
        "ar" -> ar
        else -> en
        }
    }
}