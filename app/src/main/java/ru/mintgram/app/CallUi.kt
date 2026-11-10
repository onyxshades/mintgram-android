package ru.mintgram.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Возвращает функцию «запросить нужные разрешения (микрофон, для видео ещё камера) и вызвать onGranted(video)».
 */
@Composable
fun rememberMediaPermission(vm: AppVM, onGranted: (Boolean) -> Unit): (Boolean) -> Unit {
    val ctx = LocalContext.current
    val callback by rememberUpdatedState(onGranted)
    var wantVideo by remember { mutableStateOf(false) }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = has(Manifest.permission.RECORD_AUDIO)
        val cam = has(Manifest.permission.CAMERA)
        when {
            !mic -> vm.toast = "Нужен доступ к микрофону"
            wantVideo && !cam -> vm.toast = "Нужен доступ к камере"
            else -> callback(wantVideo)
        }
    }
    return { video ->
        wantVideo = video
        val need = buildList {
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (video && !has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) callback(video) else launcher.launch(need.toTypedArray())
    }
}

@Composable
fun CallOverlay(vm: AppVM) {
    val cm = vm.calls
    val askAccept = rememberMediaPermission(vm) { cm.accept() }
    if (cm.phase == CallPhase.Idle) return

    BackHandler { /* звонок завершается кнопкой, а не системной «назад» */ }

    val user = vm.users[cm.peer]
    val name = user?.title ?: cm.peer
    val incoming = cm.phase == CallPhase.Incoming
    val sub = when {
        incoming -> if (cm.video) "Входящий видеозвонок" else "Входящий звонок"
        cm.phase == CallPhase.Active -> if (cm.weak) "Плохое соединение…" else fmtDuration(cm.seconds)
        else -> cm.statusText
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1210)).pointerInput(Unit) {}) {
        cm.remoteVideo?.let { WebRtcView(it, cm.eglBase, mirror = false, overlay = false, Modifier.fillMaxSize()) }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (cm.remoteVideo == null) {
                Spacer(Modifier.height(64.dp))
                Avatar(name, user?.avatar?.let { vm.api.fileUrl(it) }, user?.accent, 128.dp)
                Spacer(Modifier.height(20.dp))
            }
            Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))

            if (incoming) {
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LabeledBtn(Icons.Filled.CallEnd, "Отклонить", Color(0xFFE5484D)) { cm.hangup() }
                    LabeledBtn(
                        if (cm.video) Icons.Filled.Videocam else Icons.Filled.Call,
                        "Принять", Color(0xFF2FB36A),
                    ) { askAccept(cm.video) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundBtn(
                        if (cm.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        if (cm.muted) "Включить микрофон" else "Выключить микрофон", on = cm.muted,
                    ) { cm.toggleMute() }
                    if (cm.video) {
                        RoundBtn(
                            if (cm.camOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                            if (cm.camOff) "Включить камеру" else "Выключить камеру", on = cm.camOff,
                        ) { cm.toggleCam() }
                        RoundBtn(Icons.Filled.FlipCameraAndroid, "Сменить камеру") { cm.switchCamera() }
                    }
                    RoundBtn(
                        if (cm.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                        if (cm.speaker) "Динамик включён" else "Динамик выключен", on = cm.speaker,
                    ) { cm.toggleSpeaker() }
                }
                Spacer(Modifier.height(24.dp))
                RoundBtn(Icons.Filled.CallEnd, "Завершить", bg = Color(0xFFE5484D), size = 68.dp) { cm.hangup() }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (!incoming) cm.localVideo?.let {
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                    .size(104.dp, 148.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
            ) { WebRtcView(it, cm.eglBase, mirror = true, overlay = true, Modifier.fillMaxSize()) }
        }
    }
}

private fun fmtDuration(s: Int): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)

@Composable
private fun RoundBtn(
    icon: ImageVector, desc: String, on: Boolean = false,
    bg: Color? = null, size: Dp = 56.dp, onClick: () -> Unit,
) {
    val fill = bg ?: if (on) Color.White else Color.White.copy(alpha = 0.18f)
    val tint = if (bg == null && on) Color.Black else Color.White
    Box(
        Modifier.size(size).clip(CircleShape).background(fill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.46f)) }
}

@Composable
private fun LabeledBtn(icon: ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundBtn(icon, label, bg = bg, size = 72.dp, onClick = onClick)
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun WebRtcView(track: VideoTrack, egl: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(overlay)
                renderer = this
            }
        },
        onRelease = { runCatching { it.release() } },
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) runCatching { track.addSink(r) }
        onDispose { if (r != null) runCatching { track.removeSink(r) } }
    }
}
