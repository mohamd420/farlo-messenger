package com.desertcomm.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

@Composable
fun AcousticWaveformPlayer(
    uid: String,
    isPlaying: Boolean,
    isActive: Boolean,
    positionMs: Int,
    durationMs: Int,
    speed: Float,
    labelWhenIdle: String,
    isMe: Boolean,
    onTogglePlay: () -> Unit,
    onSeekMs: (Int) -> Unit,
    onCycleSpeed: () -> Unit
) {
    val bars = remember(uid) {
        val seed = uid.hashCode()
        List(24) { i ->
            val v = abs((seed * 31 + i * 97 + (i * i * 17)) % 100)
            0.25f + (v / 100f) * 0.75f
        }
    }
    val safeDur = durationMs.coerceAtLeast(1)
    val progress = if (isActive) (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f) else 0f
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = if (isMe) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(activeColor)
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) SadaStrings.tr("إيقاف", "Pause") else SadaStrings.tr("تشغيل", "Play"),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bars.forEachIndexed { idx, amp ->
                    val barFrac = (idx + 1).toFloat() / bars.size.toFloat()
                    val filled = isActive && barFrac <= progress
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((amp * 24f).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (filled) activeColor else inactiveColor)
                            .clickable {
                                if (isActive) onSeekMs(((idx.toFloat() / bars.size.toFloat()) * safeDur).toInt())
                                else onTogglePlay()
                            }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeLabel = if (isActive) {
                    val pSec = positionMs / 1000
                    val dSec = safeDur / 1000
                    String.format(Locale.US, "%02d:%02d / %02d:%02d", pSec / 60, pSec % 60, dSec / 60, dSec % 60)
                } else labelWhenIdle
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Surface(
                    onClick = onCycleSpeed,
                    shape = RoundedCornerShape(10.dp),
                    color = activeColor.copy(alpha = 0.16f),
                    border = BorderStroke(0.5.dp, activeColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        "${speed}x",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = activeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SadaPulsePill(
    onlineConnected: Boolean,
    connectedRelays: Int,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "sadaPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    val dotColor = if (onlineConnected) Color(0xFF34D399) else Color(0xFFE5A93C)
    val label = if (onlineConnected) "$connectedRelays" else "Mesh"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = dotColor.copy(alpha = 0.12f),
        border = BorderStroke(0.75.dp, dotColor.copy(alpha = 0.38f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha))
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = dotColor, maxLines = 1, softWrap = false
            )
        }
    }
}

@Composable
fun RelayConstellationDialog(
    relayStatuses: Map<String, Boolean>,
    onAddRelay: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newRelayUrl by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SadaStrings.current().relaysTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    SadaStrings.current().relaysDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                relayStatuses.forEach { (url, up) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, (if (up) Color(0xFF34D399) else Color.Gray).copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                url.removePrefix("wss://").removePrefix("https://"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (up) Color(0xFF34D399) else Color(0xFFE57373)))
                                Text(
                                    if (up) SadaStrings.current().connected else SadaStrings.current().connecting,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (up) Color(0xFF34D399) else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newRelayUrl,
                        onValueChange = { newRelayUrl = it },
                        placeholder = { Text("wss://relay.example.com", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val cleaned = newRelayUrl.trim()
                            if (cleaned.startsWith("wss://") || cleaned.startsWith("ws://")) {
                                onAddRelay(cleaned)
                                newRelayUrl = ""
                            }
                        },
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "إضافة مرحّل", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(SadaStrings.current().close) }
        }
    )
}

@Composable
fun WalkieTalkieDialog(
    peerName: String,
    isTalking: Boolean,
    isReceiving: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onDismiss: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pttPulse")
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "pttAlpha"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SadaStrings.current().pttTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    SadaStrings.tr("قناة لاسلكي مشفرة مفتوحة مع $peerName — اضغط مطوّلاً للتحدث الحي مباشرة (بدون حفظ تسجيلات)", "Encrypted live radio channel with $peerName — hold to talk (no recordings saved)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(164.dp)) {
                    if (isTalking || isReceiving) {
                        Box(
                            modifier = Modifier
                                .size(156.dp)
                                .clip(CircleShape)
                                .background((if (isTalking) Color(0xFFEF4444) else Color(0xFF10B981)).copy(alpha = ringAlpha))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isTalking -> Color(0xFFDC2626)
                                    isReceiving -> Color(0xFF059669)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    onPressStart()
                                    waitForUpOrCancellation()
                                    onPressEnd()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                when {
                                    isTalking -> SadaStrings.current().pttTalking
                                    isReceiving -> SadaStrings.current().pttReceiving
                                    else -> SadaStrings.current().pttHold
                                },
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("PUSH TO TALK", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    when {
                        isTalking -> SadaStrings.tr("🔴 جاري البث الحي... ارفع إصبعك للإيقاف", "🔴 Transmitting live... release to stop")
                        isReceiving -> SadaStrings.tr("🟢 $peerName يتحدث الآن على القناة...", "🟢 $peerName is speaking on channel...")
                        else -> SadaStrings.tr("القناة جاهزة — افتح اللاسلكي على الجهازين وتحدث فوراً", "Channel ready — open PTT on both devices and talk")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isTalking) Color(0xFFEF4444) else if (isReceiving) Color(0xFF10B981) else MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(SadaStrings.current().close) } }
    )
}

@Composable
fun EncryptedBackupDialog(
    onExport: (String) -> Unit,
    onPickRestoreFile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SadaStrings.current().backupTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    SadaStrings.current().backupDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(SadaStrings.current().backupPwdLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onExport(password) },
                        enabled = password.length >= 4,
                        modifier = Modifier.weight(1f)
                    ) { Text(SadaStrings.current().exportBtn) }
                    Surface(
                        onClick = { if (password.length >= 4) onPickRestoreFile(password) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = if (password.length >= 4) 0.2f else 0.08f),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(SadaStrings.current().restoreBtn, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(SadaStrings.current().close) } }
    )
}

@Composable
fun SadaCallOverlay(
    callState: com.desertcomm.p2p.CallUiState,
    onAccept: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    if (callState.phase == com.desertcomm.p2p.CallPhase.IDLE) return
    val mm = callState.durationSec / 60
    val ss = callState.durationSec % 60
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A1017))
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    when (callState.phase) {
                        com.desertcomm.p2p.CallPhase.INCOMING -> SadaStrings.current().callIncoming
                        com.desertcomm.p2p.CallPhase.OUTGOING -> SadaStrings.current().callOutgoing
                        com.desertcomm.p2p.CallPhase.ACTIVE -> String.format(Locale.US, SadaStrings.tr("مكالمة مشفّرة E2EE • %02d:%02d", "E2EE Voice Call • %02d:%02d"), mm, ss)
                        else -> ""
                    },
                    color = Color(0xFF34D399),
                    style = MaterialTheme.typography.labelLarge
                )
                Box(
                    modifier = Modifier.size(104.dp).clip(CircleShape).background(Color(0xFF1E3A5F)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        callState.peerName.take(2).ifEmpty { "FL" },
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    callState.peerName.ifEmpty { callState.peerId },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (callState.statusText.isNotEmpty()) { Text(callState.statusText, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.height(24.dp))
                if (callState.phase == com.desertcomm.p2p.CallPhase.INCOMING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        Button(onClick = onEnd, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) {
                            Text(SadaStrings.current().callDecline, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = onAccept, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))) {
                            Text(SadaStrings.current().callAccept, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = onToggleMute,
                            shape = CircleShape,
                            color = if (callState.isMuted) Color(0xFFEF4444) else Color(0xFF1F2937),
                            modifier = Modifier.size(56.dp)
                        ) { Box(contentAlignment = Alignment.Center) { Text(if (callState.isMuted) SadaStrings.current().callMute else SadaStrings.current().callMic, color = Color.White, style = MaterialTheme.typography.labelSmall) } }

                        Surface(
                            onClick = onEnd,
                            shape = CircleShape,
                            color = Color(0xFFDC2626),
                            modifier = Modifier.size(68.dp)
                        ) { Box(contentAlignment = Alignment.Center) { Text(SadaStrings.current().callEnd, color = Color.White, fontWeight = FontWeight.Bold) } }

                        Surface(
                            onClick = onToggleSpeaker,
                            shape = CircleShape,
                            color = if (callState.isSpeaker) Color(0xFF0284C7) else Color(0xFF1F2937),
                            modifier = Modifier.size(56.dp)
                        ) { Box(contentAlignment = Alignment.Center) { Text(SadaStrings.current().callSpeaker, color = Color.White, style = MaterialTheme.typography.labelSmall) } }
                    }
                }
            }
        }
    }
}

@Composable
fun MeshDiagnosticsDialog(
    myId: String,
    bleStateLabel: String,
    nearbyPeersCount: Int,
    wifiDirectConnected: Boolean,
    dtnQueuedCount: Int,
    connectedRelays: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SadaStrings.tr("تشخيص الشبكة الميداني • Mesh", "Field Mesh Diagnostics"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun DiagRow(label: String, value: String, good: Boolean) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (good) Color(0xFF34D399) else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                DiagRow(SadaStrings.tr("معرّف العقدة", "Node ID"), myId, true)
                DiagRow(SadaStrings.tr("حالة البلوتوث (BLE)", "Bluetooth (BLE)"), bleStateLabel, nearbyPeersCount > 0)
                DiagRow(SadaStrings.tr("الأجهزة القريبة المكتشفة", "Nearby Peers"), SadaStrings.tr("$nearbyPeersCount جهاز", "$nearbyPeersCount device(s)"), nearbyPeersCount > 0)
                DiagRow("Wi-Fi Direct", if (wifiDirectConnected) SadaStrings.current().connected else SadaStrings.tr("جاهز / غير متصل", "Ready / Idle"), wifiDirectConnected)
                DiagRow(SadaStrings.tr("حزم DTN بانتظار القفز", "Queued DTN Bundles"), SadaStrings.tr("$dtnQueuedCount حزمة", "$dtnQueuedCount bundle(s)"), dtnQueuedCount == 0)
                DiagRow(SadaStrings.tr("مرحّلات Nostr النشطة", "Active Nostr Relays"), SadaStrings.tr("$connectedRelays عقدة", "$connectedRelays relay(s)"), connectedRelays > 0)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(SadaStrings.current().close) } }
    )
}

@Composable
fun SafetyNumberDialog(
    peerName: String,
    info: com.desertcomm.security.SafetyNumberInfo,
    onToggleVerify: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SadaStrings.tr("رقم الأمان • Safety Number", "Safety Number"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    SadaStrings.tr("قارن هذه الأرقام مع $peerName (صوتياً أو عن قرب). تطابقها يؤكد عدم وجود أي تنصّت أو انتحال للمفاتيح.", "Compare these numbers with $peerName in person or over a call. A match confirms end-to-end encryption with no MITM."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, (if (info.isVerified) Color(0xFF34D399) else MaterialTheme.colorScheme.primary).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val lines = info.formattedDigits.split("  ").chunked(3).map { it.joinToString("   ") }
                        lines.forEach { ln ->
                            Text(ln, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
                Text(
                    if (info.hasPeerPublicKey) SadaStrings.tr("🔒 تشفير مفتاح عام كامل (E2EE)", "🔒 Full Public-Key E2EE Active") else SadaStrings.tr("⏳ مبدئي — أرسل رسالة واحدة لإتمام تبادل المفتاح العام", "⏳ Pending — send one message to complete key exchange"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (info.hasPeerPublicKey) Color(0xFF34D399) else Color(0xFFF59E0B)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleVerify,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (info.isVerified) SadaStrings.current().unverifyBtn else SadaStrings.current().verifyBtn)
                    }
                    TextButton(onClick = onCopy) { Text(SadaStrings.current().copy) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(SadaStrings.current().close) } }
    )
}