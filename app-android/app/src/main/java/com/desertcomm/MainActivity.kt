package com.desertcomm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.desertcomm.core.ChatMessage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.SettingsVoice
import com.desertcomm.core.EncryptedBackupManager
import com.desertcomm.p2p.SadaCallManager
import com.desertcomm.ui.EncryptedBackupDialog
import com.desertcomm.ui.SadaCallOverlay
import com.desertcomm.ui.WalkieTalkieDialog
import com.desertcomm.ui.AcousticWaveformPlayer
import com.desertcomm.ui.RelayConstellationDialog
import com.desertcomm.ui.SadaPulsePill
import com.desertcomm.ui.SadaStrings
import com.desertcomm.ui.MeshDiagnosticsDialog
import com.desertcomm.ui.SafetyNumberDialog
import com.desertcomm.security.SafetyNumberHelper
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.graphicsLayer
import com.desertcomm.core.MediaActionsHelper
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.desertcomm.core.ChatRepository
import com.desertcomm.core.ImageHelper
import com.desertcomm.core.LocationHelper
import com.desertcomm.core.QrHelper
import com.desertcomm.core.SendTransportMode
import com.desertcomm.modem.SatelliteNtnManager
import com.desertcomm.modem.SubNoiseAudioModem
import com.desertcomm.modem.VoiceNoteManager
import com.desertcomm.p2p.OnlineBlindRelay
import com.desertcomm.p2p.WifiDirectManager
import com.desertcomm.security.HardwareKeystore
import com.desertcomm.service.DesertCommFgService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlin.math.abs

private val TelegramDarkScheme = darkColorScheme(
    primary = Color(0xFF5EA3E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B5278),
    onPrimaryContainer = Color(0xFFF0F7FF),
    secondary = Color(0xFF4CC38A),
    tertiary = Color(0xFF4CC38A),
    background = Color(0xFF0E1621),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF17212B),
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF1C2938),
    onSurfaceVariant = Color(0xFFE3EAF2),
    outline = Color(0xFF7D90A3)
)

private val TelegramLightScheme = lightColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3FAD6),
    onPrimaryContainer = Color(0xFF111B21),
    secondary = Color(0xFF2E9E5B),
    tertiary = Color(0xFF2E9E5B),
    background = Color(0xFFE7ECF2),
    onBackground = Color(0xFF111B21),
    surface = Color.White,
    onSurface = Color(0xFF111B21),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF111B21),
    outline = Color(0xFF707579)
)

private val AvatarGradients = listOf(
    listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
    listOf(Color(0xFF43E97B), Color(0xFF38F9D7)),
    listOf(Color(0xFFFA709A), Color(0xFFFEE140)),
    listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
    listOf(Color(0xFFF093FB), Color(0xFFF5576C)),
    listOf(Color(0xFF30CFD0), Color(0xFF330867))
)

private fun avatarBrushFor(id: String): Brush =
    Brush.linearGradient(AvatarGradients[abs(id.hashCode()) % AvatarGradients.size])

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialChat = intent?.getStringExtra("open_chat_id")
        setContent {
            var darkTheme by remember { mutableStateOf(HardwareKeystore.isDarkMode(this)) }
            LaunchedEffect(Unit) { SadaStrings.init(this@MainActivity) }
            val activeLangCode by SadaStrings.langOverride.collectAsState()
            val tr = SadaStrings.current(activeLangCode)
            CompositionLocalProvider(LocalLayoutDirection provides if (tr.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = if (darkTheme) TelegramDarkScheme else TelegramLightScheme) {
                    var isUnlocked by remember { mutableStateOf(!HardwareKeystore.isAppLockEnabled(this)) }
                    var isDuressMode by remember { mutableStateOf(false) }
                    var pinTry by remember { mutableStateOf("") }
                    var pinErr by remember { mutableStateOf(false) }
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (!isUnlocked) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(tr.lockedTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    OutlinedTextField(
                                        value = pinTry,
                                        onValueChange = {
                                            if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                                                pinTry = it
                                                pinErr = false
                                            }
                                        },
                                        label = { Text(tr.enterPin) },
                                        singleLine = true,
                                        isError = pinErr
                                    )
                                    if (pinErr) Text(tr.wrongPin, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = {
                                            if (HardwareKeystore.verifyAppLockPin(this@MainActivity, pinTry)) {
                                                isDuressMode = false
                                                isUnlocked = true
                                            } else if (pinTry == "0000") {
                                                // رمز الذعر: يفتح واجهة فارغة آمنة بدون محادثات
                                                isDuressMode = true
                                                isUnlocked = true
                                            } else {
                                                pinErr = true
                                                pinTry = ""
                                            }
                                        }) { Text(tr.unlockBtn) }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                            TextButton(onClick = {
                                                runCatching {
                                                    val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this@MainActivity)
                                                        .setTitle("فتح فارلو بالبصمة")
                                                        .setNegativeButton("إلغاء", mainExecutor) { _, _ -> }
                                                        .build()
                                                    prompt.authenticate(
                                                        android.os.CancellationSignal(),
                                                        mainExecutor,
                                                        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                                                            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                                                                isDuressMode = false
                                                                isUnlocked = true
                                                            }
                                                        }
                                                    )
                                                }
                                            }) { Text(tr.biometricBtn) }
                                        }
                                    }
                                    Text(tr.duressHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        } else if (isDuressMode) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(SadaStrings.tr("فارلو • Farlo", "Farlo"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(tr.noChats, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        } else ChatApp(
                            initialChatId = initialChat,
                            isDarkMode = darkTheme,
                            onToggleDarkMode = {
                                darkTheme = it
                                HardwareKeystore.setDarkMode(this, it)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ChatRepository.onUiVisibilityChanged(true)
    }

    override fun onPause() {
        super.onPause()
        ChatRepository.onUiVisibilityChanged(false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatApp(
    initialChatId: String?,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit
) {
    val context = LocalContext.current

    val myId by ChatRepository.myId.collectAsState()
    val conversations by ChatRepository.conversations.collectAsState()
    val savedPeers by ChatRepository.savedPeers.collectAsState()
    val nicknames by ChatRepository.nicknames.collectAsState()
    val dtnQueuedCount by ChatRepository.dtnQueuedCount.collectAsState()
    val blockedPeers by ChatRepository.blockedPeers.collectAsState()
    val groups by ChatRepository.groups.collectAsState()
    val unreadCounts by ChatRepository.unreadCounts.collectAsState()
    val onlineEnabled by ChatRepository.onlineEnabled.collectAsState()
    val onlineConnected by OnlineBlindRelay.isOnlineConnected.collectAsState()
    val connectedRelays by OnlineBlindRelay.connectedRelayCount.collectAsState()
    val relayStatuses by OnlineBlindRelay.relayStatuses.collectAsState()
    var showRelayDialog by remember { mutableStateOf(false) }
    var showMeshDiagDialog by remember { mutableStateOf(false) }
    val reactions by ChatRepository.reactions.collectAsState()
    val chatLastActivity by ChatRepository.chatLastActivity.collectAsState()
    var replyingToMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var disappearSeconds by remember { mutableStateOf(0) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var torProxyEnabled by remember { mutableStateOf(HardwareKeystore.isTorProxyEnabled(context)) }
    val torStatus by com.desertcomm.p2p.EmbeddedTorManager.status.collectAsState()
    var safetyRefreshTick by remember { mutableStateOf(0) }
    val dtnQueued by ChatRepository.dtnQueuedCount.collectAsState()
    val wfdConnectedState by WifiDirectManager.isConnected.collectAsState()
    var showBackupDialog by remember { mutableStateOf(false) }
    var backupRestorePassword by remember { mutableStateOf("") }
    var showPttDialog by remember { mutableStateOf(false) }
    var isPttTalking by remember { mutableStateOf(false) }
    var pttSeconds by remember { mutableStateOf(0) }
    val callState by SadaCallManager.state.collectAsState()
    val livePttState by SadaCallManager.pttState.collectAsState()

    LaunchedEffect(isPttTalking) {
        if (isPttTalking) {
            pttSeconds = 0
            while (isPttTalking && pttSeconds < 25) {
                delay(1_000L)
                pttSeconds++
            }
        }
    }

    val backupRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            Thread {
                EncryptedBackupManager.restoreEncryptedBackup(context, uri, backupRestorePassword)
                    .onSuccess { cnt ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "تمت الاستعادة بنجاح ($cnt عنصر) — أعد فتح التطبيق", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    .onFailure { err ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "فشلت الاستعادة: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
            }.start()
        }
    }
    val isOnlineUnlimited = onlineEnabled && onlineConnected
    val voicePlayback by VoiceNoteManager.playback.collectAsState()
    val transferProgress by ChatRepository.transferProgress.collectAsState()

    val bleState by BleManager.bleState.collectAsState()
    val peers by BleManager.peers.collectAsState()
    val bleStatusText by BleManager.statusText.collectAsState()
    val packetProgress by BleManager.packetProgress.collectAsState()
    val stratum by DesertCommFgService.stratum.collectAsState()
    val wfdPeers by WifiDirectManager.peers.collectAsState()
    val wfdStatus by WifiDirectManager.status.collectAsState()

    var activeChatId by remember { mutableStateOf(initialChatId) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var isTacticalMode by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(HardwareKeystore.isMuted(context)) }
    var appLockEnabled by remember { mutableStateOf(HardwareKeystore.isAppLockEnabled(context)) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var newPinInput by remember { mutableStateOf("") }
    var sendMode by remember { mutableStateOf(SendTransportMode.LOCAL_DIRECT) }
    var messageText by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordCancelled by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var isListening8Fsk by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }

    var showAddChatDialog by remember { mutableStateOf(false) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showGroupQrForId by remember { mutableStateOf<String?>(null) }
    var createGroupMode by remember { mutableStateOf(false) }
    var newGroupNameInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showModeBar by remember { mutableStateOf(false) }
    var newPeerIdInput by remember { mutableStateOf("") }
    var newPeerNickInput by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var selectedMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var editMsgInput by remember { mutableStateOf("") }
    var forwardMsgContent by remember { mutableStateOf<String?>(null) }
    var fullScreenImageB64 by remember { mutableStateOf<String?>(null) }
    var fullScreenImageFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showBatteryPrompt by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == false
            } else false
        )
    }

    // إيماءة الرجوع في الجهاز ترجع من المحادثة إلى قائمة المحادثات بدلاً من إغلاق التطبيق
    LaunchedEffect(activeChatId) {
        val cid = activeChatId
        if (cid != null) ChatRepository.markChatRead(context, cid)
        else ChatRepository.clearOpenChat()
    }

    BackHandler(enabled = activeChatId != null || showSearchBar) {
        if (activeChatId != null) {
            activeChatId = null
        } else if (showSearchBar) {
            showSearchBar = false
            searchQuery = ""
        }
    }

    // عداد ثواني التسجيل الحي (حتى 10 دقائق أونلاين، 29 ثانية أوفلاين)
    LaunchedEffect(isRecording, isOnlineUnlimited) {
        if (isRecording) {
            recordSeconds = 0
            val maxSec = if (isOnlineUnlimited) 600 else 29
            while (isRecording && recordSeconds < maxSec) {
                delay(1_000L)
                recordSeconds++
            }
        }
    }

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }

    fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun startFgService(tactical: Boolean) {
        val intent = Intent(context, DesertCommFgService::class.java).apply {
            putExtra(DesertCommFgService.EXTRA_TACTICAL_MODE, tactical)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val rawIn = result.contents?.trim() ?: return@rememberLauncherForActivityResult
        if (rawIn.startsWith("GRP|")) {
            val gid = ChatRepository.joinGroupFromCode(context, rawIn)
            if (gid != null) {
                activeChatId = gid
                BleManager.setStatus("تم الانضمام للمجموعة بنجاح")
            }
            return@rememberLauncherForActivityResult
        }
        val splitIdx = rawIn.indexOf(':')
        val clean = (if (splitIdx > 0) rawIn.substring(0, splitIdx) else rawIn).trim().uppercase()
        if (clean.startsWith("DC-")) {
            if (splitIdx > 0) {
                runCatching {
                    val pk = Base64.decode(rawIn.substring(splitIdx + 1).trim(), Base64.NO_WRAP)
                    if (pk.size == 32) HardwareKeystore.savePeerPublicKey(context, clean, pk)
                }
            }
            ChatRepository.savePeer(context, clean)
            activeChatId = clean
            BleManager.setStatus("تمت إضافة $clean عبر QR بنجاح")
        } else {
            BleManager.setStatus("رمز QR غير تابع لـ DesertComm")
        }
    }

    val mediaFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val chat = activeChatId ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        if (!isOnlineUnlimited) {
            android.widget.Toast.makeText(context, "إرسال الملفات والفيديو يتطلب تفعيل وضع الإنترنت", android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        Thread {
            runCatching {
                val (fileName, sizeBytes) = com.desertcomm.core.EncryptedMediaTransfer.getFileNameAndSize(context, uri)
                val mime = context.contentResolver.getType(uri) ?: ""
                val kind = when {
                    mime.startsWith("image/") -> "HDIMG"
                    mime.startsWith("video/") -> "VIDEO"
                    mime.startsWith("audio/") -> "HDVOICE"
                    else -> "FILE"
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("تعذر قراءة الملف")
                if (bytes.size > 14 * 1024 * 1024) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "حجم الملف أكبر من الحد المسموح (14 MB)", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@runCatching
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "جاري تشفير ورفع: $fileName", android.widget.Toast.LENGTH_SHORT).show()
                }
                ChatRepository.sendOnlineEncryptedMedia(context, chat, kind, bytes, fileName, sendMode)
            }.onFailure { err ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "فشل إرسال الملف: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingImageUri = uri
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) startFgService(isTacticalMode)
        else BleManager.setStatus("يرجى منح صلاحيات البلوتوث والميكروفون")
    }

    LaunchedEffect(Unit) {
        if (hasAllPermissions()) {
            permissionsGranted = true
            startFgService(isTacticalMode)
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    val isSecure = bleState == BleState.CONNECTED_SECURE && ChatRepository.sessionKey != null
    val displayStatus = packetProgress?.let { (s, t) -> "جاري الإرسال $s/$t..." } ?: bleStatusText
    val allChatIds = (groups.keys + savedPeers + peers.map { it.id } + conversations.keys).distinct()

    // ===================== الحوارات (Dialogs) =====================
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(SadaStrings.current().settings, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val curLang by SadaStrings.langOverride.collectAsState()
                    Text(SadaStrings.tr("🌐 اللغة", "🌐 Language"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("auto" to "Auto", "ar" to "العربية", "en" to "English").forEach { (code, lbl) ->
                            val sel = curLang == code
                            Surface(
                                onClick = { SadaStrings.setLanguage(context, code) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                    Text(lbl, style = MaterialTheme.typography.labelSmall, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                }
                            }
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${SadaStrings.current().yourId} $myId", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(SadaStrings.tr("الساعة: Stratum $stratum  •  DTN: $dtnQueuedCount", "Clock: Stratum $stratum  •  DTN: $dtnQueuedCount"), style = MaterialTheme.typography.labelSmall)
                            Text(wfdStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(SadaStrings.current().onlineHybrid)
                            Text(
                                if (onlineConnected) SadaStrings.current().onlineConnDesc
                                else if (onlineEnabled) SadaStrings.current().waitingNetDesc
                                else SadaStrings.current().airGapDesc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(checked = onlineEnabled, onCheckedChange = {
                            ChatRepository.setOnlineRelayEnabled(context, it)
                        })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(SadaStrings.tr("🧅 Tor المدمج (بدون Orbot)", "🧅 Built-in Tor (No Orbot)"), fontWeight = FontWeight.Medium)
                            Text(if (torProxyEnabled) SadaStrings.tr("حالة Tor: $torStatus", "Tor: $torStatus") else SadaStrings.tr("إخفاء الـ IP وتجاوز الحجب", "Hide IP & bypass censorship"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = torProxyEnabled, onCheckedChange = { on ->
                            torProxyEnabled = on
                            HardwareKeystore.setTorProxyEnabled(context, on)
                            if (on) {
                                android.widget.Toast.makeText(context, SadaStrings.tr("جاري تشغيل نواة Tor المدمجة (10-20 ثانية)...", "Starting built-in Tor (10-20s)..."), android.widget.Toast.LENGTH_LONG).show()
                                com.desertcomm.p2p.EmbeddedTorManager.start(context) { p -> OnlineBlindRelay.setTorEnabled(true, p) }
                            } else {
                                com.desertcomm.p2p.EmbeddedTorManager.stop(context)
                                OnlineBlindRelay.setTorEnabled(false)
                            }
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(SadaStrings.current().appLock)
                        Switch(checked = appLockEnabled, onCheckedChange = { enable ->
                            if (enable) {
                                newPinInput = ""
                                showSetPinDialog = true
                            } else {
                                HardwareKeystore.setAppLockPin(context, null)
                                appLockEnabled = false
                                BleManager.setStatus("تم إيقاف قفل التطبيق")
                            }
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(SadaStrings.current().darkMode)
                        Switch(checked = isDarkMode, onCheckedChange = onToggleDarkMode)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(SadaStrings.current().muteNotif)
                        Switch(checked = isMuted, onCheckedChange = {
                            isMuted = it
                            HardwareKeystore.setMuted(context, it)
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(SadaStrings.current().tacticalMode)
                        Switch(checked = isTacticalMode, onCheckedChange = {
                            isTacticalMode = it
                            if (permissionsGranted) startFgService(it)
                        })
                    }
                    HorizontalDivider()
                    Text("${SadaStrings.current().blockedTitle} (${blockedPeers.size})", fontWeight = FontWeight.SemiBold)
                    if (blockedPeers.isEmpty()) {
                        Text(SadaStrings.current().noBlocked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        blockedPeers.forEach { bId ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(nicknames[bId] ?: bId)
                                TextButton(onClick = { ChatRepository.setBlocked(context, bId, false) }) { Text(SadaStrings.tr("فك الحظر", "Unblock")) }
                            }
                        }
                    }
                    if (wfdPeers.isNotEmpty()) {
                        HorizontalDivider()
                        Text(SadaStrings.tr("أجهزة Wi-Fi Direct القريبة:", "Nearby Wi-Fi Direct devices:"), fontWeight = FontWeight.SemiBold)
                        wfdPeers.forEach { wp ->
                            TextButton(onClick = { WifiDirectManager.connect(wp); showSettingsDialog = false }) {
                                Text(SadaStrings.tr("اتصال بـ ${wp.deviceName}", "Connect to ${wp.deviceName}"))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text(SadaStrings.current().close) } }, dismissButton = { Row { TextButton(onClick = { showSettingsDialog = false; showMeshDiagDialog = true }) { Text(SadaStrings.current().meshDiag) }; TextButton(onClick = { showSettingsDialog = false; showBackupDialog = true }) { Text(SadaStrings.current().backup) } } }
        )
    }

    if (showSetPinDialog) {
        AlertDialog(
            onDismissRequest = { showSetPinDialog = false },
            title = { Text(SadaStrings.tr("تعيين رمز قفل التطبيق", "Set App Lock PIN")) },
            text = {
                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) newPinInput = it },
                    label = { Text(SadaStrings.tr("أدخل 4 إلى 6 أرقام", "Enter 4 to 6 digits")) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPinInput.length >= 4) {
                        HardwareKeystore.setAppLockPin(context, newPinInput)
                        appLockEnabled = true
                        showSetPinDialog = false
                        BleManager.setStatus("تم تفعيل قفل التطبيق بنجاح")
                    }
                }) { Text(SadaStrings.tr("حفظ", "Save")) }
            },
            dismissButton = { TextButton(onClick = { showSetPinDialog = false }) { Text(SadaStrings.current().cancel) } }
        )
    }

    if (showMyQrDialog) {
        val code = ChatRepository.getShareableContactCode()
        val qrBmp = remember(code) { QrHelper.generateQrBitmap(code, 600) }
        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text(SadaStrings.tr("بطاقة QR الخاصة بك", "Your QR Card")) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(myId, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    if (qrBmp != null) {
                        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp) {
                            Image(bitmap = qrBmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(220.dp).padding(10.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(SadaStrings.tr("امسح هذا الرمز لإضافة هاتفك بمفتاحه الفريد", "Scan this QR code to add your device and public key"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("DesertCommID", code))
                    BleManager.setStatus("تم نسخ كودك الفريد")
                    showMyQrDialog = false
                }) { Text(SadaStrings.tr("نسخ الكود", "Copy Code")) }
            },
            dismissButton = { TextButton(onClick = { showMyQrDialog = false }) { Text(SadaStrings.current().close) } }
        )
    }

    showGroupQrForId?.let { gid ->
        val gCode = ChatRepository.getGroupInviteCode(context, gid)
        val qrBmp = remember(gCode) { gCode?.let { QrHelper.generateQrBitmap(it, 600) } }
        AlertDialog(
            onDismissRequest = { showGroupQrForId = null },
            title = { Text(SadaStrings.tr("دعوة أعضاء لمجموعة ${groups[gid] ?: gid}", "Invite to ${groups[gid] ?: gid}")) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (qrBmp != null) {
                        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp) {
                            Image(bitmap = qrBmp.asImageBitmap(), contentDescription = "Group QR", modifier = Modifier.size(220.dp).padding(10.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(SadaStrings.tr("يمسح الأصدقاء هذا الرمز للانضمام للمجموعة المشفرة", "Friends scan this code to join the encrypted group"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                if (gCode != null) {
                    Button(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("SadaGroup", gCode))
                        BleManager.setStatus("تم نسخ كود دعوة المجموعة")
                        showGroupQrForId = null
                    }) { Text(SadaStrings.tr("نسخ الكود", "Copy Code")) }
                }
            },
            dismissButton = { TextButton(onClick = { showGroupQrForId = null }) { Text(SadaStrings.current().close) } }
        )
    }

    if (showAddChatDialog) {
        AlertDialog(
            onDismissRequest = { showAddChatDialog = false },
            title = { Text(if (createGroupMode) SadaStrings.tr("إنشاء مجموعة مشفرة", "New Encrypted Group") else SadaStrings.tr("محادثة جديدة", "New Chat")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !createGroupMode, onClick = { createGroupMode = false }, label = { Text(SadaStrings.tr("شخص", "Person")) }, modifier = Modifier.weight(1f))
                        FilterChip(selected = createGroupMode, onClick = { createGroupMode = true }, label = { Text(SadaStrings.tr("مجموعة", "Group")) }, modifier = Modifier.weight(1f))
                    }
                    if (createGroupMode) {
                        OutlinedTextField(
                            value = newGroupNameInput,
                            onValueChange = { newGroupNameInput = it },
                            label = { Text(SadaStrings.tr("اسم المجموعة (مثال: العائلة)", "Group name (e.g. Family)")) },
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = newPeerIdInput,
                            onValueChange = { newPeerIdInput = it.trim() },
                            label = { Text(SadaStrings.tr("الرقم أو الكود الكامل (DC-XXXX أو GRP|...)", "ID or full invite code (DC-XXXX or GRP|...)")) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newPeerNickInput,
                            onValueChange = { newPeerNickInput = it },
                            label = { Text(SadaStrings.tr("الاسم المستعار (اختياري)", "Nickname (optional)")) },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (createGroupMode) {
                        if (newGroupNameInput.isNotBlank()) {
                            val gid = ChatRepository.createGroup(context, newGroupNameInput)
                            activeChatId = gid
                            showGroupQrForId = gid
                            showAddChatDialog = false
                            newGroupNameInput = ""
                        }
                    } else {
                        val rawIn = newPeerIdInput.trim()
                        if (rawIn.startsWith("GRP|")) {
                            ChatRepository.joinGroupFromCode(context, rawIn)?.let { activeChatId = it }
                            showAddChatDialog = false
                            newPeerIdInput = ""
                            return@Button
                        }
                        val splitIdx = rawIn.indexOf(':')
                        val clean = (if (splitIdx > 0) rawIn.substring(0, splitIdx) else rawIn).trim().uppercase()
                        if (clean.startsWith("DC-")) {
                            if (splitIdx > 0) {
                                runCatching {
                                    val pk = Base64.decode(rawIn.substring(splitIdx + 1).trim(), Base64.NO_WRAP)
                                    if (pk.size == 32) HardwareKeystore.savePeerPublicKey(context, clean, pk)
                                }
                            }
                            ChatRepository.savePeer(context, clean)
                            if (newPeerNickInput.isNotBlank()) ChatRepository.setNickname(context, clean, newPeerNickInput)
                            activeChatId = clean
                            showAddChatDialog = false
                            newPeerIdInput = ""
                            newPeerNickInput = ""
                        }
                    }
                }) { Text(if (createGroupMode) SadaStrings.tr("إنشاء", "Create") else SadaStrings.tr("بدء", "Start")) }
            },
            dismissButton = { TextButton(onClick = { showAddChatDialog = false }) { Text(SadaStrings.current().cancel) } }
        )
    }

    renameTargetId?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            title = { Text(SadaStrings.tr("تعديل الاسم المستعار", "Edit Nickname")) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(SadaStrings.tr("الاسم المستعار لـ $target", "Nickname for $target")) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    ChatRepository.setNickname(context, target, renameInput)
                    renameTargetId = null
                }) { Text(SadaStrings.tr("حفظ", "Save")) }
            },
            dismissButton = { TextButton(onClick = { renameTargetId = null }) { Text(SadaStrings.current().cancel) } }
        )
    }

    selectedMsg?.let { msg ->
        val chatIdNow = activeChatId
        if (chatIdNow != null) {
            val isTextMsg = !msg.text.startsWith("VOICE:") && !msg.text.startsWith("IMG:")
            AlertDialog(
                onDismissRequest = { selectedMsg = null },
                title = { Text(SadaStrings.tr("خيارات الرسالة", "Message Options")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("❤️", "👍", "😂", "🔥", "🙏").forEach { em ->
                                TextButton(
                                    onClick = {
                                        ChatRepository.sendReaction(context, chatIdNow, msg.uid, em, sendMode)
                                        selectedMsg = null
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(40.dp)
                                ) { Text(em, style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                        TextButton(
                            onClick = { replyingToMsg = msg; selectedMsg = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(SadaStrings.tr("↩ رد على هذه الرسالة", "↩ Reply to message")) }
                        if (msg.isMe && isTextMsg) {
                            OutlinedTextField(
                                value = editMsgInput,
                                onValueChange = { editMsgInput = it },
                                label = { Text(SadaStrings.tr("تعديل النص", "Edit text")) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            if (isTextMsg) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    cm?.setPrimaryClip(ClipData.newPlainText("Message", msg.text))
                                    BleManager.setStatus("تم نسخ الرسالة")
                                    selectedMsg = null
                                }.padding(6.dp)) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ")
                                    Text(SadaStrings.current().copy, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                forwardMsgContent = msg.text
                                selectedMsg = null
                            }.padding(6.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = "إعادة توجيه")
                                Text(SadaStrings.tr("توجيه", "Forward"), style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                ChatRepository.deleteMessage(context, chatIdNow, msg.uid, sendMode, notifyPeer = msg.isMe)
                                selectedMsg = null
                            }.padding(6.dp)) {
                                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                Text(SadaStrings.tr("حذف", "Delete"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (msg.isMe && isTextMsg) {
                        Button(onClick = {
                            ChatRepository.editSentMessage(context, chatIdNow, msg.uid, editMsgInput, sendMode)
                            selectedMsg = null
                        }) { Text(SadaStrings.tr("حفظ التعديل", "Save Edit")) }
                    }
                },
                dismissButton = { TextButton(onClick = { selectedMsg = null }) { Text(SadaStrings.current().close) } }
            )
        }
    }

    forwardMsgContent?.let { contentToForward ->
        AlertDialog(
            onDismissRequest = { forwardMsgContent = null },
            title = { Text(SadaStrings.tr("إعادة توجيه إلى...", "Forward to...")) },
            text = {
                if (allChatIds.isEmpty()) {
                    Text(SadaStrings.tr("لا توجد محادثات أخرى محفوظة.", "No other saved chats."))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(allChatIds) { destId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ChatRepository.forwardMessage(context, destId, contentToForward, sendMode)
                                        BleManager.setStatus("تمت إعادة التوجيه إلى ${nicknames[destId] ?: destId}")
                                        forwardMsgContent = null
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).clip(CircleShape).background(avatarBrushFor(destId)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text((nicknames[destId] ?: destId).take(2), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(nicknames[destId] ?: destId, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { forwardMsgContent = null }) { Text(SadaStrings.current().cancel) } }
        )
    }

    if (showBatteryPrompt) {
        AlertDialog(
            onDismissRequest = { showBatteryPrompt = false },
            title = { Text(SadaStrings.tr("تفعيل وصول الرسائل بالخلفية", "Enable Background Delivery")) },
            text = { Text(SadaStrings.tr("لكي تصلك الرسائل والإشعارات فوراً حتى عند إغلاق الشاشة أو التطبيق، اسمح لتطبيق فارلو بالعمل بدون قيود البطارية.", "To receive messages reliably when the screen is off, allow Farlo to run without battery restrictions.")) },
            confirmButton = {
                Button(onClick = {
                    showBatteryPrompt = false
                    runCatching {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }) { Text(SadaStrings.tr("تفعيل الآن", "Enable Now")) }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryPrompt = false }) { Text(SadaStrings.tr("لاحقاً", "Later")) }
            }
        )
    }

    SadaCallOverlay(
        callState = callState,
        onAccept = { SadaCallManager.acceptIncomingCall(context, sendMode) },
        onEnd = { SadaCallManager.endCall(context, sendMode) },
        onToggleMute = { SadaCallManager.toggleMute() },
        onToggleSpeaker = { SadaCallManager.toggleSpeaker(context) }
    )

    if (showBackupDialog) {
        EncryptedBackupDialog(
            onExport = { pwd ->
                showBackupDialog = false
                Thread {
                    EncryptedBackupManager.exportEncryptedBackup(context, pwd)
                        .onFailure { err ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(context, "فشل التصدير: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                }.start()
            },
            onPickRestoreFile = { pwd ->
                backupRestorePassword = pwd
                showBackupDialog = false
                backupRestoreLauncher.launch("*/*")
            },
            onDismiss = { showBackupDialog = false }
        )
    }

    if (showPttDialog && activeChatId != null) {
        val targetChat = activeChatId!!
        val targetName = nicknames[targetChat] ?: targetChat
        LaunchedEffect(targetChat) {
            SadaCallManager.openLivePttChannel(context, myId, targetChat)
        }
        WalkieTalkieDialog(
            peerName = targetName,
            isTalking = livePttState.isTransmitting,
            isReceiving = livePttState.isReceiving,
            onPressStart = { SadaCallManager.startPttHold(context) },
            onPressEnd = { SadaCallManager.stopPttHoldAndSend(context, targetChat, sendMode) },
            onDismiss = {
                SadaCallManager.closeLivePttChannel()
                showPttDialog = false
            }
        )
    }

    if (showSafetyDialog && activeChatId != null) {
        val targetChat = activeChatId!!
        val targetName = nicknames[targetChat] ?: targetChat
        val snInfo = remember(targetChat, safetyRefreshTick) { SafetyNumberHelper.compute(context, myId, targetChat) }
        SafetyNumberDialog(
            peerName = targetName,
            info = snInfo,
            onToggleVerify = {
                SafetyNumberHelper.setPeerVerified(context, targetChat, !snInfo.isVerified)
                safetyRefreshTick++
                android.widget.Toast.makeText(context, if (!snInfo.isVerified) "تم توثيق $targetName ✓" else "تم إلغاء التوثيق", android.widget.Toast.LENGTH_SHORT).show()
            },
            onCopy = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("SafetyNumber", snInfo.formattedDigits))
                android.widget.Toast.makeText(context, "تم نسخ رقم الأمان", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSafetyDialog = false }
        )
    }

    if (showMeshDiagDialog) {
        MeshDiagnosticsDialog(
            myId = myId,
            bleStateLabel = bleState.name,
            nearbyPeersCount = peers.size,
            wifiDirectConnected = wfdConnectedState,
            dtnQueuedCount = dtnQueued,
            connectedRelays = connectedRelays,
            onDismiss = { showMeshDiagDialog = false }
        )
    }

    if (showRelayDialog) {
        RelayConstellationDialog(
            relayStatuses = relayStatuses,
            onAddRelay = { url -> OnlineBlindRelay.addCustomRelay(url) },
            onDismiss = { showRelayDialog = false }
        )
    }

    if (fullScreenImageB64 != null || fullScreenImageFile != null) {
        val bmp = remember(fullScreenImageB64, fullScreenImageFile) {
            when {
                fullScreenImageFile != null -> android.graphics.BitmapFactory.decodeFile(fullScreenImageFile!!.absolutePath)
                fullScreenImageB64 != null -> ImageHelper.decodeBase64ToBitmap(fullScreenImageB64!!)
                else -> null
            }
        }
        Dialog(
            onDismissRequest = { fullScreenImageB64 = null; fullScreenImageFile = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var zoomScale by remember { mutableStateOf(1f) }
            var panX by remember { mutableStateOf(0f) }
            var panY by remember { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
                            zoomScale = newScale
                            if (newScale > 1f) {
                                panX += pan.x
                                panY += pan.y
                            } else {
                                panX = 0f; panY = 0f
                            }
                        }
                    }
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "صورة كاملة",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoomScale,
                                scaleY = zoomScale,
                                translationX = panX,
                                translationY = panY
                            ),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (bmp != null) {
                        IconButton(
                            onClick = {
                                val f = fullScreenImageFile
                                if (f != null) MediaActionsHelper.saveToDownloads(context, f)
                                else MediaActionsHelper.saveBitmapToDownloads(context, bmp)
                            },
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "حفظ", tint = Color.White)
                        }
                    }
                    IconButton(
                        onClick = { fullScreenImageB64 = null; fullScreenImageFile = null },
                        modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }
        }
    }

    pendingImageUri?.let { uri ->
        val previewBmp = remember(uri) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        Dialog(
            onDismissRequest = { pendingImageUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = "معاينة الصورة",
                        modifier = Modifier.fillMaxSize().padding(bottom = 84.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                IconButton(
                    onClick = { pendingImageUri = null },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "إلغاء", tint = Color.White)
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isOnlineUnlimited) "إرسال بدقة عالية HD (مشفر)" else "إرسال مضغوط للراديو",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            val chat = activeChatId
                            val selected = uri
                            pendingImageUri = null
                            if (chat != null) {
                                Thread {
                                    if (isOnlineUnlimited) {
                                        runCatching {
                                            val rawBmp = context.contentResolver.openInputStream(selected)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                                                ?: error("تعذر فتح الصورة")
                                            val maxDim = 1600
                                            val scale = minOf(1f, maxDim.toFloat() / maxOf(rawBmp.width, rawBmp.height).coerceAtLeast(1))
                                            val finalBmp = if (scale < 1f) {
                                                android.graphics.Bitmap.createScaledBitmap(rawBmp, (rawBmp.width * scale).toInt().coerceAtLeast(1), (rawBmp.height * scale).toInt().coerceAtLeast(1), true)
                                            } else rawBmp
                                            val out = java.io.ByteArrayOutputStream()
                                            finalBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                            val bytes = out.toByteArray()
                                            ChatRepository.sendOnlineEncryptedMedia(context, chat, "HDIMG", bytes, "photo_${System.currentTimeMillis()}.jpg", sendMode)
                                        }.onFailure { err ->
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                android.widget.Toast.makeText(context, "فشل إرسال الصورة: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } else {
                                        ImageHelper.compressUriToBase64(context, selected)
                                            .onSuccess { b64 -> ChatRepository.sendImageBase64(context, chat, b64, sendMode) }
                                            .onFailure { err ->
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(context, "فشل ضغط الصورة: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                    }
                                }.start()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(SadaStrings.current().send)
                    }
                }
            }
        }
    }

    // ===================== الشاشات =====================
    val currentChat = activeChatId
    if (currentChat == null) {
        val filteredChats = allChatIds.filter { id ->
            val q = searchQuery.trim()
            q.isEmpty() || id.contains(q, ignoreCase = true) || (nicknames[id]?.contains(q, ignoreCase = true) == true)
        }.sortedWith(
            compareByDescending<String> { chatLastActivity[it] ?: 0L }
                .thenByDescending { conversations[it]?.lastOrNull()?.timeText ?: "" }
        )

        Scaffold(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val activeLangCode by SadaStrings.langOverride.collectAsState(); val t = SadaStrings.current(activeLangCode)
                                val unreadChats = unreadCounts.count { it.value > 0 }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (unreadChats > 0) "${t.appTitle} ($unreadChats)" else t.appTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    SadaPulsePill(
                                        onlineConnected = onlineConnected,
                                        connectedRelays = connectedRelays,
                                        onClick = { showRelayDialog = true }
                                    )
                                }
                                Text(
                                    (if (onlineConnected) "🌐($connectedRelays) " else "📡 ") + "$myId  •  $displayStatus",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row {
                                IconButton(onClick = { showSearchBar = !showSearchBar; if (!showSearchBar) searchQuery = "" }) {
                                    Icon(Icons.Filled.Search, contentDescription = "بحث")
                                }
                                IconButton(onClick = { showMyQrDialog = true }) {
                                    Icon(Icons.Filled.QrCode2, contentDescription = "بطاقة QR")
                                }
                                IconButton(onClick = {
                                    val opts = ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setOrientationLocked(true); setPrompt(SadaStrings.tr("وجّه الكاميرا نحو رمز QR لصديقك", "Point camera at your friend's QR code"))
                                        setBeepEnabled(true)
                                        setOrientationLocked(false)
                                    }
                                    qrScanLauncher.launch(opts)
                                }) {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "مسح QR")
                                }
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "الإعدادات")
                                }
                            }
                        }
                        if (showSearchBar) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(SadaStrings.tr("ابحث بالاسم أو الرقم...", "Search by name or ID...")) },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddChatDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Filled.Add, contentDescription = SadaStrings.tr("محادثة جديدة", "New Chat"))
                }
            }
        ) { padding ->
            if (filteredChats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(SadaStrings.current().noChats, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(SadaStrings.tr("امسح رمز QR لصديقك أو اضغط + لبدء محادثة مشفرة", "Scan a friend's QR code or tap + to start an encrypted chat"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(filteredChats, key = { it }) { chatId ->
                        val alias = nicknames[chatId]
                        val displayName = alias ?: chatId
                        val lastObj = conversations[chatId]?.lastOrNull()
                        val preview = when {
                            lastObj == null -> "محادثة مشفرة من طرف لطرف"
                            lastObj.text.startsWith("VOICE:") -> "رسالة صوتية"
                            lastObj.text.startsWith("IMG:") -> "صورة"
                            lastObj.text.startsWith("LOC:") -> "موقع جغرافي"
                            else -> lastObj.text
                        }
                        val nearbyPeer = peers.firstOrNull { it.id == chatId }
                        val isBlocked = blockedPeers.contains(chatId.uppercase())

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    ChatRepository.savePeer(context, chatId)
                                    activeChatId = chatId
                                    if (nearbyPeer != null && !isSecure && bleState != BleState.CONNECTING) {
                                        BleManager.connectToDevice(context, nearbyPeer.device)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(52.dp)) {
                                Box(
                                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(avatarBrushFor(chatId)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(displayName.take(2), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                if (nearbyPeer != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .align(Alignment.BottomEnd)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF34C759))
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        displayName + if (isBlocked) " (محظور)" else "",
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (lastObj != null) {
                                        Text(lastObj.timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                val unread = unreadCounts[chatId] ?: 0
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (unread > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                unread.toString(),
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                    }
                }
            }
        }
    } else {
        // ===================== شاشة المحادثة الفردية الأسطورية =====================
        val alias = nicknames[currentChat]
        val displayName = alias ?: currentChat
        val chatMessages = (conversations[currentChat] ?: emptyList()).filterNot { it.text.startsWith("WEBRTC_") || it.text.startsWith("PTT_LIVE|") || it.text.startsWith("CALL_") }
        val nearbyPeer = peers.firstOrNull { it.id == currentChat }
        val listState = rememberLazyListState()

        LaunchedEffect(chatMessages.size) {
            if (chatMessages.isNotEmpty()) listState.scrollToItem(chatMessages.lastIndex)
        }

        val myBubbleBrush = if (isDarkMode) {
            Brush.linearGradient(listOf(Color(0xFF2B5278), Color(0xFF224263)))
        } else {
            Brush.linearGradient(listOf(Color(0xFFE3FAD6), Color(0xFFD5F5C3)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { activeChatId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                        Box(
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(avatarBrushFor(currentChat)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(displayName.take(2), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    isSecure -> SadaStrings.tr("متصل مشفر (E2EE)", "Connected Encrypted (E2EE)")
                                    nearbyPeer != null -> SadaStrings.tr("قريب عبر البلوتوث", "Nearby via Bluetooth")
                onlineConnected -> SadaStrings.tr("متصل مشفّر عالمياً 🌐 ($connectedRelays)", "Global E2EE Online 🌐 ($connectedRelays)")
                                    else -> displayStatus
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSecure) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (nearbyPeer != null && !isSecure) {
                            TextButton(onClick = { BleManager.connectToDevice(context, nearbyPeer.device) }) { Text(SadaStrings.tr("اتصال", "Connect")) }
                        }
                        Box {
                            IconButton(onClick = { showChatMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "المزيد")
                            }
                            DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                                if (currentChat.startsWith("GRP-")) {
                                    DropdownMenuItem(
                                        text = { Text(SadaStrings.tr("دعوة أعضاء عبر QR", "Invite via QR")) },
                                        onClick = { showChatMenu = false; showGroupQrForId = currentChat }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (showModeBar) "إخفاء أوضاع الإرسال" else "أوضاع الإرسال (BLE / NTN / 8-FSK)") },
                                    onClick = { showChatMenu = false; showModeBar = !showModeBar }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isListening8Fsk) "جاري الاستماع 8-FSK..." else "استقبال 8-FSK عبر الميكروفون") },
                                    onClick = {
                                        showChatMenu = false
                                        if (!isListening8Fsk) {
                                            isListening8Fsk = true
                                            BleManager.setStatus("استماع 8-FSK (13 ثانية)...")
                                            Thread {
                                                SubNoiseAudioModem.listenOnceOverMic()
                                                    .onSuccess { txt ->
                                                        BleManager.setStatus(if (txt.isNotBlank()) "تم التقاط 8-FSK: $txt" else "لم يُلتقط نص واضح")
                                                    }
                                                    .onFailure { BleManager.setStatus("فشل استقبال 8-FSK: ${it.message}") }
                                                isListening8Fsk = false
                                            }.start()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(SadaStrings.tr("تعديل الاسم المستعار", "Edit Nickname")) },
                                    onClick = {
                                        showChatMenu = false
                                        renameTargetId = currentChat
                                        renameInput = alias ?: ""
                                    }
                                )
                                val isBlockedNow = blockedPeers.contains(currentChat.uppercase())
                                DropdownMenuItem(
                                    text = { Text(if (isBlockedNow) "فك حظر الرقم" else "حظر الرقم") },
                                    onClick = {
                                        showChatMenu = false
                                        ChatRepository.setBlocked(context, currentChat, !isBlockedNow)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(SadaStrings.tr("حذف المحادثة تماماً", "Delete Conversation"), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showChatMenu = false
                                        ChatRepository.deleteConversation(context, currentChat)
                                        activeChatId = null
                                    }
                                )
                            }
                        }
                    }
                    if (showModeBar) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SendTransportMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = sendMode == mode,
                                    onClick = {
                                        sendMode = mode
                                        if (mode == SendTransportMode.SATELLITE_NTN) {
                                            BleManager.setStatus(SatelliteNtnManager.getCapabilityStatus(context))
                                        }
                                    },
                                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(chatMessages, key = { idx, msg -> "${msg.uid}_$idx" }) { _, msg ->
                    val bubbleShape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (msg.isMe) 18.dp else 4.dp,
                        bottomEnd = if (msg.isMe) 4.dp else 18.dp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 285.dp)
                                .clip(bubbleShape)
                                .then(
                                    if (msg.isMe) Modifier.background(myBubbleBrush)
                                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            msg.text.startsWith("IMG:") -> fullScreenImageB64 = msg.text.removePrefix("IMG:")
                                            msg.text.startsWith("LOC:") -> {
                                                val coords = msg.text.removePrefix("LOC:")
                                                val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords(Farlo)"))
                                                runCatching { context.startActivity(mapIntent) }
                                                    .onFailure {
                                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                                        cm?.setPrimaryClip(ClipData.newPlainText("GPS", coords))
                                                        BleManager.setStatus("تم نسخ الإحداثيات: $coords")
                                                    }
                                            }
                                    msg.text.startsWith("PTT:") -> {
                                                VoiceNoteManager.togglePlayBase64(context, msg.uid, msg.text.removePrefix("PTT:"))
                                                    .onFailure { BleManager.setStatus("تعذر تشغيل اللاسلكي") }
                                            }
                                    msg.text.startsWith("VOICE:") -> {
                                                VoiceNoteManager.togglePlayBase64(context, msg.uid, msg.text.removePrefix("VOICE:"))
                                                    .onFailure { BleManager.setStatus("تعذر تشغيل الصوت") }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        selectedMsg = msg
                                        editMsgInput = msg.text
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column {
                                when {
                                    msg.text.startsWith("IMG:") -> {
                                        val b64Img = msg.text.removePrefix("IMG:")
                                        val bmp = remember(b64Img) { ImageHelper.decodeBase64ToBitmap(b64Img) }
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "صورة",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 220.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Text(SadaStrings.tr("صورة مشفرة", "Encrypted Photo"))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "${msg.timeText} ${msg.statusBadge}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.align(Alignment.End)
                                        )
                                    }
                                    msg.text.startsWith("LOC:") -> {
                                        val coords = msg.text.removePrefix("LOC:")
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.LocationOn, contentDescription = "موقع", tint = Color.White)
                                            }
                                            Column {
                                                Text(SadaStrings.tr("موقع جغرافي مشفر", "Encrypted Location"), fontWeight = FontWeight.Bold)
                                                Text(coords, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                            }
                                        }
                                        Text(
                                            "${msg.timeText} ${msg.statusBadge}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                                        )
                                    }
                                    msg.text.startsWith("PTT:") -> {
                                        val isActive = voicePlayback.playingUid == msg.uid
                                        val isPlaying = isActive && voicePlayback.isPlaying
                                        val dur = if (isActive) voicePlayback.durationMs.coerceAtLeast(1) else 1
                                        val pos = if (isActive) voicePlayback.positionMs.coerceIn(0, dur) else 0
                                        AcousticWaveformPlayer(
                                            uid = msg.uid,
                                            isPlaying = isPlaying,
                                            isActive = isActive,
                                            positionMs = pos,
                                            durationMs = dur,
                                            speed = voicePlayback.speed,
                                            labelWhenIdle = "📻 بث لاسلكي فوري",
                                            isMe = msg.isMe,
                                            onTogglePlay = {
                                                VoiceNoteManager.togglePlayBase64(context, msg.uid, msg.text.removePrefix("PTT:"))
                                                    .onFailure { BleManager.setStatus("تعذر تشغيل اللاسلكي") }
                                            },
                                            onSeekMs = { ms -> VoiceNoteManager.seekTo(ms) },
                                            onCycleSpeed = { VoiceNoteManager.cycleSpeed() }
                                        )
                                        Text(
                                            "${msg.timeText} ${msg.statusBadge}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                                        )
                                    }
                                    msg.text.startsWith("VOICE:") -> {
                                        val isActive = voicePlayback.playingUid == msg.uid
                                        val isPlaying = isActive && voicePlayback.isPlaying
                                        val dur = if (isActive) voicePlayback.durationMs.coerceAtLeast(1) else 1
                                        val pos = if (isActive) voicePlayback.positionMs.coerceIn(0, dur) else 0
                                        AcousticWaveformPlayer(
                                            uid = msg.uid,
                                            isPlaying = isPlaying,
                                            isActive = isActive,
                                            positionMs = pos,
                                            durationMs = dur,
                                            speed = voicePlayback.speed,
                                            labelWhenIdle = "بصمة صوتية مشفرة",
                                            isMe = msg.isMe,
                                            onTogglePlay = {
                                                VoiceNoteManager.togglePlayBase64(context, msg.uid, msg.text.removePrefix("VOICE:"))
                                                    .onFailure { BleManager.setStatus("تعذر تشغيل الصوت") }
                                            },
                                            onSeekMs = { ms -> VoiceNoteManager.seekTo(ms) },
                                            onCycleSpeed = { VoiceNoteManager.cycleSpeed() }
                                        )
                                        Text(
                                            "${msg.timeText} ${msg.statusBadge}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                                        )
                                    }
                                    msg.text.startsWith("HDIMG|") || msg.text.startsWith("VIDEO|") || msg.text.startsWith("FILE|") || msg.text.startsWith("HDVOICE|") -> {
                                        val parts = msg.text.split("|")
                                        val kind = parts.getOrNull(0) ?: "FILE"
                                        val fName = parts.getOrNull(1) ?: "ملف مشفر"
                                        val fSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                                        val dlUrl = parts.getOrNull(3) ?: ""
                                        val keyB64 = parts.getOrNull(4) ?: ""
                                        val safeName = "${msg.uid}_${fName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
                                        val expectedFile = remember(msg.uid, safeName) {
                                            java.io.File(java.io.File(context.filesDir, "sada_media"), safeName)
                                        }
                                        var localFile by remember(msg.uid) { mutableStateOf(expectedFile.takeIf { it.exists() }) }
                                        val dlProgCheck = transferProgress[msg.uid]
                                        LaunchedEffect(msg.uid, isOnlineUnlimited, localFile) {
                                            if (localFile == null && dlProgCheck == null && isOnlineUnlimited && (kind == "HDIMG" || kind == "HDVOICE") && dlUrl.startsWith("http")) {
                                                ChatRepository.downloadMediaForMessage(context, msg.uid, fName, dlUrl, keyB64) { readyFile ->
                                                    localFile = readyFile
                                                }
                                            }
                                        }
                                        val dlProg = transferProgress[msg.uid]
                                        val sizeLabel = if (fSize >= 1024 * 1024) String.format(java.util.Locale.US, "%.1f MB", fSize / (1024.0 * 1024.0)) else "${(fSize / 1024).coerceAtLeast(1)} KB"
                                        val iconLabel = when (kind) {
                                            "HDIMG" -> "🖼"
                                            "VIDEO" -> "🎬"
                                            "HDVOICE" -> "🎤"
                                            else -> "📄"
                                        }
                                        if (kind == "HDIMG" && localFile != null) {
                                            val bmp = remember(localFile) { android.graphics.BitmapFactory.decodeFile(localFile!!.absolutePath) }
                                            if (bmp != null) {
                                                Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = fName,
                                                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { fullScreenImageFile = localFile }),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(Modifier.height(4.dp))
                                            }
                                            Text("$iconLabel $fName ($sizeLabel)", style = MaterialTheme.typography.labelSmall)
                                        } else if (kind == "HDVOICE" && localFile != null) {
                                            val isActive = voicePlayback.playingUid == msg.uid
                                            val isPlaying = isActive && voicePlayback.isPlaying
                                            val dur = if (isActive) voicePlayback.durationMs.coerceAtLeast(1) else 1
                                            val pos = if (isActive) voicePlayback.positionMs.coerceIn(0, dur) else 0
                                            AcousticWaveformPlayer(
                                                uid = msg.uid,
                                                isPlaying = isPlaying,
                                                isActive = isActive,
                                                positionMs = pos,
                                                durationMs = dur,
                                                speed = voicePlayback.speed,
                                                labelWhenIdle = "صوت HD • $sizeLabel",
                                                isMe = msg.isMe,
                                                onTogglePlay = {
                                                    VoiceNoteManager.togglePlayFile(msg.uid, localFile!!)
                                                        .onFailure { BleManager.setStatus("تعذر تشغيل الصوت") }
                                                },
                                                onSeekMs = { ms -> VoiceNoteManager.seekTo(ms) },
                                                onCycleSpeed = { VoiceNoteManager.cycleSpeed() }
                                            )
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("$iconLabel $fName", fontWeight = FontWeight.Bold)
                                                Text(SadaStrings.tr("ملف مشفر • $sizeLabel", "Encrypted File • $sizeLabel"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                                if (dlProg != null) {
                                                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.Gray.copy(alpha = 0.25f))) {
                                                        Box(modifier = Modifier.fillMaxWidth(dlProg.coerceIn(0.05f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
                                                    }
                                                    Text(SadaStrings.tr("جاري التنزيل وفك التشفير ${(dlProg * 100).toInt()}%", "Decrypting ${(dlProg * 100).toInt()}%"), style = MaterialTheme.typography.labelSmall)
                                                } else if (localFile == null) {
                                                    Button(
                                                        onClick = {
                                                            ChatRepository.downloadMediaForMessage(context, msg.uid, fName, dlUrl, keyB64) { readyFile ->
                                                                localFile = readyFile
                                                                BleManager.setStatus("تم فك تشفير الملف: $fName")
                                                            }
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(SadaStrings.tr("⬇ تنزيل وفك التشفير", "⬇ Download & Decrypt"))
                                                    }
                                                } else {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Button(
                                                            onClick = { localFile?.let { MediaActionsHelper.openFileNatively(context, it) } },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(Modifier.width(4.dp))
                                                            Text(SadaStrings.tr("فتح", "Open"), style = MaterialTheme.typography.labelMedium)
                                                        }
                                                        IconButton(
                                                            onClick = { localFile?.let { MediaActionsHelper.saveToDownloads(context, it, fName) } },
                                                            modifier = Modifier.size(34.dp)
                                                        ) {
                                                            Icon(Icons.Filled.Download, contentDescription = "حفظ", tint = if (msg.isMe) Color.White else MaterialTheme.colorScheme.primary)
                                                        }
                                                        IconButton(
                                                            onClick = { localFile?.let { MediaActionsHelper.shareFile(context, it) } },
                                                            modifier = Modifier.size(34.dp)
                                                        ) {
                                                            Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = if (msg.isMe) Color.White else MaterialTheme.colorScheme.primary)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            "${msg.timeText} ${msg.statusBadge}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                                        )
                                    }
                                    else -> {
                                        val isExp = msg.text.startsWith("EXP|")
                                        val expSec = if (isExp) msg.text.split("|", limit = 3).getOrNull(1) else null
                                        val cleanText = if (isExp) msg.text.split("|", limit = 3).getOrNull(2) ?: msg.text else msg.text
                                        if (isExp && expSec != null) {
                                            Text(SadaStrings.tr("⏱ ذاتية الاختفاء (${expSec}ث)", "⏱ Self-destruct (${expSec}s)"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text(cleanText)
                                        Row(
                                            modifier = Modifier.align(Alignment.End).padding(top = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            reactions[msg.uid]?.let { reactEmoji ->
                                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                                                    Text(reactEmoji, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            Text(msg.timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                            if (msg.statusBadge.isNotEmpty()) {
                                                Text(msg.statusBadge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (transferProgress.isNotEmpty()) {
                val activeProg = transferProgress.values.firstOrNull() ?: 0f
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(SadaStrings.tr("جاري تشفير ونقل الوسائط... ${(activeProg * 100).toInt()}%", "Encrypting & sending media... ${(activeProg * 100).toInt()}%"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.Gray.copy(alpha = 0.25f))) {
                        Box(modifier = Modifier.fillMaxWidth(activeProg.coerceIn(0.05f, 1f)).height(5.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            if (isRecording) {
                val mm = recordSeconds / 60
                val ss = recordSeconds % 60
                val maxLabel = if (isOnlineUnlimited) "10:00" else "00:29"
                Surface(color = Color(0xFFB71C1C).copy(alpha = 0.16f), tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                            Text(
                                String.format(java.util.Locale.US, "جاري التسجيل  %02d:%02d / %s", mm, ss, maxLabel),
                                fontWeight = FontWeight.Bold,
                                color = Color.Red,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            if (isOnlineUnlimited) "HD • AAC" else "Radio • 3GP",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            if (showAttachMenu) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { showAttachMenu = false; imagePickerLauncher.launch("image/*") },
                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF1E88E5))
                                ) { Icon(Icons.Filled.Image, contentDescription = "صورة", tint = Color.White) }
                                Spacer(Modifier.height(4.dp))
                                Text(SadaStrings.current().photoHd, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { showAttachMenu = false; mediaFilePickerLauncher.launch("*/*") },
                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF8E24AA))
                                ) { Icon(Icons.Filled.InsertDriveFile, contentDescription = "ملف", tint = Color.White) }
                                Spacer(Modifier.height(4.dp))
                                Text(SadaStrings.current().fileVideo, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        showAttachMenu = false
                                        LocationHelper.getBestLastKnownLocation(context)
                                            .onSuccess { (lat, lon) ->
                                                ChatRepository.sendLocation(context, currentChat, lat, lon, sendMode)
                                                android.widget.Toast.makeText(context, "تم إرسال موقعك الجغرافي مشفراً", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .onFailure { err ->
                                                android.widget.Toast.makeText(context, err.message ?: "تعذر تحديد الموقع", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                    },
                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF2E7D32))
                                ) { Icon(Icons.Filled.LocationOn, contentDescription = "موقع", tint = Color.White) }
                                Spacer(Modifier.height(4.dp))
                                Text(SadaStrings.current().myLocation, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { showAttachMenu = false; showPttDialog = true },
                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFD97706))
                                ) { Icon(Icons.Filled.SettingsVoice, contentDescription = "لاسلكي", tint = Color.White) }
                                Spacer(Modifier.height(4.dp))
                                Text(SadaStrings.current().walkieTalkie, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        showAttachMenu = false
                                        if (!isOnlineUnlimited) {
                                            android.widget.Toast.makeText(context, "المكالمة الصوتية تتطلب اتصال إنترنت — استخدم اللاسلكي أوفلاين", android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            SadaCallManager.startOutgoingCall(context, currentChat, nicknames[currentChat] ?: currentChat, sendMode)
                                        }
                                    },
                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF059669))
                                ) { Icon(Icons.Filled.Call, contentDescription = "مكالمة", tint = Color.White) }
                                Spacer(Modifier.height(4.dp))
                                Text(SadaStrings.current().voiceCall, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        disappearSeconds = when (disappearSeconds) { 0 -> 30; 30 -> 300; else -> 0 }
                                        showAttachMenu = false
                                    },
                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(if (disappearSeconds > 0) Color(0xFFEA580C) else Color(0xFF475569))
                                ) { Text("⏱", style = MaterialTheme.typography.titleLarge) }
                                Spacer(Modifier.height(4.dp))
                                Text(if (disappearSeconds == 0) SadaStrings.current().disappear else "${disappearSeconds}s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(
                            onClick = { showAttachMenu = false; showSafetyDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val isVer = SafetyNumberHelper.isPeerVerified(context, currentChat)
                            Text(if (isVer) SadaStrings.current().verifiedContact else SadaStrings.current().safetyNumber)
                        }
                    }
                }
            }
            if (showEmojiPicker) {
                val emojis = listOf(
                    "😀","😂","🤣","😍","🥰","😘","😎","🤔",
                    "😭","😡","🤯","🥳","😴","🫡","🙏","🤲",
                    "👍","👎","👏","🤝","✌️","👋","💪","❤️",
                    "💔","🔥","✨","🎉","✅","❌","💯","⚡",
                    "🌹","☀️","🌙","🕌","📍","📷","🎤","🎬",
                    "📄","🔒","🛡️","📡","🌐","🛰️","🚀","⌫"
                )
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        emojis.chunked(8).forEach { rowEmojis ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                rowEmojis.forEach { em ->
                                    TextButton(
                                        onClick = {
                                            if (em == "⌫") {
                                                if (messageText.isNotEmpty()) messageText = messageText.dropLast(1)
                                            } else {
                                                messageText += em
                                            }
                                        },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.size(38.dp)
                                    ) { Text(em, style = MaterialTheme.typography.titleLarge) }
                                }
                            }
                        }
                    }
                }
            }
            if (replyingToMsg != null || disappearSeconds > 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        replyingToMsg?.let { rMsg ->
                            val preview = rMsg.text.removePrefix("EXP|30|").removePrefix("EXP|300|").take(45)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${SadaStrings.current().replyPrefix} $preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                TextButton(onClick = { replyingToMsg = null }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) { Text("✕") }
                            }
                        }
                        if (disappearSeconds > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(SadaStrings.tr("⏱ وضع الاختفاء الذاتي مفعّل (${if (disappearSeconds == 30) "30 ثانية" else "5 دقائق"})", "⏱ Disappearing messages (${if (disappearSeconds == 30) "30s" else "5m"})"), style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                                TextButton(onClick = { disappearSeconds = 0 }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) { Text(SadaStrings.tr("إيقاف", "Off")) }
                            }
                        }
                    }
                }
            }
            // شريط الإدخال العصري (مع الضغط المطوّل للتسجيل والسحب للإلغاء مثل تلغرام)
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRecording) {
                        Row(
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                                Spacer(Modifier.width(8.dp))
                                Text(String.format("0:%02d", recordSeconds), fontWeight = FontWeight.Bold, color = Color.Red)
                            }
                            Text(
                                if (recordCancelled) "إلغاء التسجيل..." else "اسحب أفقياً للإلغاء",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        IconButton(onClick = { showEmojiPicker = !showEmojiPicker; if (showEmojiPicker) showAttachMenu = false }) {
                            Icon(Icons.Filled.EmojiEmotions, contentDescription = "إيموجي", tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { showAttachMenu = !showAttachMenu; if (showAttachMenu) showEmojiPicker = false }) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "مرفقات", tint = if (showAttachMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            placeholder = { Text(SadaStrings.current().messageHint) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        )
                    }

                    AnimatedContent(targetState = messageText.isNotBlank(), label = "sendOrMic") { hasText ->
                        if (hasText) {
                            FilledIconButton(
                                onClick = {
                                    val rawTxt = messageText.trim()
                                    if (rawTxt.isNotEmpty()) {
                                        val withReply = replyingToMsg?.let { r ->
                                            val q = r.text.removePrefix("EXP|30|").removePrefix("EXP|300|").take(35)
                                            "↩ «$q»\n$rawTxt"
                                        } ?: rawTxt
                                        val finalTxt = if (disappearSeconds > 0) "EXP|$disappearSeconds|$withReply" else withReply
                                        messageText = ""
                                        replyingToMsg = null
                                        ChatRepository.sendMessage(context, currentChat, finalTxt, sendMode)
                                    }
                                },
                                shape = CircleShape,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال", tint = Color.White)
                            }
                        } else {
                            if (isRecording) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            VoiceNoteManager.cancelRecording()
                                            isRecording = false
                                            recordCancelled = false
                                        },
                                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFD32F2F))
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "إلغاء التسجيل", tint = Color.White)
                                    }
                                    IconButton(
                                        onClick = {
                                            isRecording = false
                                            recordCancelled = false
                                            val bytes = VoiceNoteManager.stopRecordingBytes()
                                            if (bytes != null && bytes.size > 200) {
                                                if (isOnlineUnlimited && bytes.size > 10_000) {
                                                    ChatRepository.sendOnlineEncryptedMedia(context, currentChat, "HDVOICE", bytes, "voice_${System.currentTimeMillis()}.m4a", sendMode)
                                                    android.widget.Toast.makeText(context, "جاري إرسال الرسالة الصوتية...", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    ChatRepository.sendVoiceNote(context, currentChat, bytes, sendMode)
                                                }
                                            } else {
                                                android.widget.Toast.makeText(context, "التسجيل قصير جداً أو فارغ", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2E7D32))
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال الصوت", tint = Color.White)
                                    }
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        showAttachMenu = false
                                        showEmojiPicker = false
                                        VoiceNoteManager.startRecording(context, isOnlineUnlimited = isOnlineUnlimited)
                                            .onSuccess { isRecording = true }
                                            .onFailure {
                                                android.widget.Toast.makeText(context, "امنح صلاحية الميكروفون للتسجيل", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = "ابدأ التسجيل الصوتي", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}