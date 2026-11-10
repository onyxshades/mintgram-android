package ru.mintgram.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

enum class CallPhase { Idle, Outgoing, Incoming, Connecting, Active }

private class Call(
    val outgoing: Boolean,
    val peer: String,
    val video: Boolean,
    var id: String? = null,
    val offerSdp: String? = null,
) {
    var pc: PeerConnection? = null
    var canSend = false                       // можно ли уже слать ICE на сервер
    val pendingLocal = ArrayList<IceCandidate>()
    val pendingRemote = ArrayList<IceCandidate>()
    var remoteSet = false
    var accepted = false
    var dead = false
    var startedAt = 0L
    var timerJob: Job? = null

    var audioSource: AudioSource? = null
    var audioTrack: AudioTrack? = null
    var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null
    var capturer: CameraVideoCapturer? = null
    var helper: SurfaceTextureHelper? = null
}

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

private suspend fun PeerConnection.createOfferS(): SessionDescription = suspendCoroutine { c ->
    createOffer(object : SdpAdapter() {
        override fun onCreateSuccess(d: SessionDescription?) { c.resume(d!!) }
        override fun onCreateFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
    }, MediaConstraints())
}

private suspend fun PeerConnection.createAnswerS(): SessionDescription = suspendCoroutine { c ->
    createAnswer(object : SdpAdapter() {
        override fun onCreateSuccess(d: SessionDescription?) { c.resume(d!!) }
        override fun onCreateFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
    }, MediaConstraints())
}

private suspend fun PeerConnection.setLocalS(d: SessionDescription) = suspendCoroutine<Unit> { c ->
    setLocalDescription(object : SdpAdapter() {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
    }, d)
}

private suspend fun PeerConnection.setRemoteS(d: SessionDescription) = suspendCoroutine<Unit> { c ->
    setRemoteDescription(object : SdpAdapter() {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
    }, d)
}

/**
 * Звонки 1-на-1. Протокол сигналинга тот же, что у сайта (server/calls.js):
 *  → call_invite {to, video, sdp}      ← call_ringing {callId}
 *  ← call_incoming {callId, from, video, sdp}   → call_accept {callId, sdp}   → call_decline / call_end {callId}
 *  ← call_accepted {callId, sdp}   ↔ call_ice {callId, candidate}   ← call_taken / call_ended / call_error
 * Всё вызывается из главного потока.
 */
class CallManager(
    private val ctx: Context,
    private val api: Api,
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    // ---- состояние для интерфейса
    var phase by mutableStateOf(CallPhase.Idle); private set
    var peer by mutableStateOf(""); private set
    var video by mutableStateOf(false); private set
    var statusText by mutableStateOf(""); private set
    var seconds by mutableStateOf(0); private set
    var weak by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var camOff by mutableStateOf(false); private set
    var speaker by mutableStateOf(false); private set
    var localVideo by mutableStateOf<VideoTrack?>(null); private set
    var remoteVideo by mutableStateOf<VideoTrack?>(null); private set

    val eglBase: EglBase by lazy { EglBase.create() }

    private var cur: Call? = null
    private var factory: PeerConnectionFactory? = null
    private val audioManager = ctx.getSystemService(AudioManager::class.java)
    private var savedMode = AudioManager.MODE_NORMAL
    private var audioActive = false
    private var ring: Ringtone? = null
    private var tone: ToneGenerator? = null

    // =============================================================== действия пользователя

    fun start(peerName: String, withVideo: Boolean) {
        if (cur != null) { toast("Вы уже в звонке"); return }
        if (!socket.ready.value) { toast("Нет соединения с сервером"); return }
        val call = Call(outgoing = true, peer = peerName, video = withVideo)
        cur = call
        begin(call, CallPhase.Outgoing, "Вызов…")
        CallService.start(ctx)
        scope.launch {
            try {
                prepare(call)
                val pc = call.pc!!
                val offer = pc.createOfferS()
                alive(call)
                pc.setLocalS(offer)
                alive(call)
                val sent = socket.send(
                    JSONObject().put("type", "call_invite").put("to", peerName)
                        .put("video", withVideo).put("sdp", offer.description)
                )
                if (!sent) throw IllegalStateException("offline")
                startRingback()
            } catch (_: CancellationException) {
                // звонок уже завершён
            } catch (e: Exception) {
                if (cur === call) fail(errText(e))
            }
        }
    }

    fun accept() {
        val call = cur ?: return
        if (call.outgoing || call.pc != null || phase != CallPhase.Incoming) return
        stopTone()
        Notifier.cancelCall(ctx)
        phase = CallPhase.Connecting
        statusText = "Соединение…"
        enterCallAudio(call.video)
        CallService.start(ctx)
        scope.launch {
            try {
                prepare(call)
                val pc = call.pc!!
                pc.setRemoteS(SessionDescription(SessionDescription.Type.OFFER, call.offerSdp ?: ""))
                alive(call)
                call.remoteSet = true
                flushRemote(call)
                val answer = pc.createAnswerS()
                alive(call)
                pc.setLocalS(answer)
                alive(call)
                val sent = socket.send(
                    JSONObject().put("type", "call_accept").put("callId", call.id).put("sdp", answer.description)
                )
                if (!sent) throw IllegalStateException("offline")
                call.accepted = true
                openIce(call)
            } catch (_: CancellationException) {
                // звонок уже завершён
            } catch (e: Exception) {
                if (cur === call) fail(errText(e))
            }
        }
    }

    /** Отбой / отклонить / отмена вызова. */
    fun hangup() {
        val c = cur ?: return
        c.id?.let { id ->
            val type = if (!c.outgoing && !c.accepted) "call_decline" else "call_end"
            socket.send(JSONObject().put("type", type).put("callId", id))
        }
        cleanup()
    }

    fun toggleMute() {
        val c = cur ?: return
        muted = !muted
        c.audioTrack?.setEnabled(!muted)
    }

    fun toggleCam() {
        val c = cur ?: return
        if (!c.video) return
        camOff = !camOff
        c.videoTrack?.setEnabled(!camOff)
    }

    fun switchCamera() {
        cur?.capturer?.switchCamera(null)
    }

    fun toggleSpeaker() = applySpeaker(!speaker)

    /** Пропала связь с сервером — сигналов больше не будет. */
    fun onDisconnected() {
        if (cur == null) return
        toast("Связь с сервером потеряна, звонок завершён")
        cleanup()
    }

    fun release() {
        cleanup()
        factory?.dispose()
        factory = null
        runCatching { eglBase.release() }
    }

    // =============================================================== события от сервера

    fun handle(e: JSONObject) {
        when (e.optString("type")) {
            "call_ringing" -> {
                val c = cur
                val id = e.optString("callId")
                if (c != null && c.outgoing && c.id == null) {
                    c.id = id
                    statusText = "Звонок…"
                    openIce(c)
                } else {
                    // успели нажать «отбой» до ответа сервера
                    socket.send(JSONObject().put("type", "call_end").put("callId", id))
                }
            }

            "call_incoming" -> {
                val id = e.optString("callId")
                if (cur != null) {
                    socket.send(JSONObject().put("type", "call_decline").put("callId", id))
                    return
                }
                val call = Call(
                    outgoing = false, peer = e.optString("from"), video = e.optBoolean("video"),
                    id = id, offerSdp = e.optString("sdp"),
                )
                cur = call
                begin(call, CallPhase.Incoming, "")
                startRing()
            }

            "call_accepted" -> {
                val c = cur ?: return
                val pc = c.pc ?: return
                if (c.id != e.optString("callId") || c.remoteSet) return
                val sdp = e.optString("sdp")
                scope.launch {
                    try {
                        pc.setRemoteS(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                        alive(c)
                        c.remoteSet = true
                        stopTone()
                        phase = CallPhase.Connecting
                        statusText = "Соединение…"
                        flushRemote(c)
                    } catch (_: CancellationException) {
                    } catch (_: Exception) {
                        if (cur === c) fail("Не удалось установить соединение")
                    }
                }
            }

            "call_ice" -> {
                val c = cur ?: return
                if (c.id != e.optString("callId")) return
                val o = e.optJSONObject("candidate") ?: return
                val sdp = o.optString("candidate")
                if (sdp.isBlank()) return
                val ic = IceCandidate(o.str("sdpMid"), o.optInt("sdpMLineIndex", 0), sdp)
                val pc = c.pc
                if (pc != null && c.remoteSet) pc.addIceCandidate(ic) else c.pendingRemote.add(ic)
            }

            "call_taken" -> {
                val c = cur ?: return
                if (c.id == e.optString("callId") && c.pc == null) {
                    toast("Звонок принят на другом устройстве")
                    cleanup()
                }
            }

            "call_ended" -> {
                val c = cur
                val reason = REASONS[e.optString("reason")]
                if (e.isNull("callId")) {
                    // ответ на наш вызов, который не получился
                    reason?.let(toast)
                    if (c != null && c.outgoing && c.id == null) cleanup()
                } else if (c != null && c.id == e.optString("callId")) {
                    if (reason != null && c.outgoing) toast(reason)
                    cleanup()
                }
            }

            "call_error" -> {
                toast("Не удалось позвонить")
                val c = cur
                if (c != null && c.outgoing && c.id == null) cleanup()
            }
        }
    }

    // =============================================================== внутреннее

    private fun begin(call: Call, p: CallPhase, status: String) {
        peer = call.peer
        video = call.video
        statusText = status
        seconds = 0
        weak = false
        muted = false
        camOff = false
        localVideo = null
        remoteVideo = null
        phase = p
        if (p != CallPhase.Incoming) enterCallAudio(call.video)
    }

    private fun alive(call: Call) {
        if (cur !== call || call.dead) throw CancellationException("call finished")
    }

    private fun errText(e: Throwable) = when {
        e is SecurityException -> "Нет доступа к микрофону или камере"
        e.message == "offline" -> "Нет соединения с сервером"
        else -> "Не удалось начать звонок"
    }

    private fun fail(text: String) {
        toast(text)
        hangup()
    }

    private fun factory(): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /** ICE-серверы берём у сервера (/api/config), как это делает сайт. */
    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val list = try {
            api.config().getJSONArray("iceServers").objects().mapNotNull { o ->
                val urls = when (val u = o.opt("urls")) {
                    is String -> listOf(u)
                    is JSONArray -> u.strings()
                    else -> return@mapNotNull null
                }
                val b = PeerConnection.IceServer.builder(urls)
                o.str("username")?.let { b.setUsername(it) }
                o.str("credential")?.let { b.setPassword(it) }
                b.createIceServer()
            }
        } catch (_: Exception) {
            emptyList()
        }
        return list.ifEmpty {
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
    }

    /** Микрофон/камера + PeerConnection. */
    private suspend fun prepare(call: Call) {
        val servers = iceServers()
        alive(call)
        val f = factory()
        val rtc = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = f.createPeerConnection(rtc, observer(call)) ?: throw IllegalStateException("pc")
        call.pc = pc

        val asrc = f.createAudioSource(MediaConstraints())
        val atrack = f.createAudioTrack("mg_a0", asrc)
        atrack.setEnabled(true)
        call.audioSource = asrc
        call.audioTrack = atrack
        pc.addTrack(atrack, listOf("mg"))

        if (call.video) {
            val en = Camera2Enumerator(ctx)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) }
                ?: en.deviceNames.firstOrNull() ?: throw IllegalStateException("camera")
            val cap = en.createCapturer(name, null) ?: throw IllegalStateException("camera")
            val helper = SurfaceTextureHelper.create("mg_capture", eglBase.eglBaseContext)
            val vsrc = f.createVideoSource(false)
            cap.initialize(helper, ctx, vsrc.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val vtrack = f.createVideoTrack("mg_v0", vsrc)
            vtrack.setEnabled(true)
            call.capturer = cap
            call.helper = helper
            call.videoSource = vsrc
            call.videoTrack = vtrack
            pc.addTrack(vtrack, listOf("mg"))
            localVideo = vtrack
        }
    }

    private fun observer(call: Call) = object : PeerConnection.Observer {
        // Колбэки приходят из потока WebRTC — переносим в главный.
        override fun onIceCandidate(c: IceCandidate) { scope.launch { sendIce(call, c) } }
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            if (track is VideoTrack) scope.launch { if (!call.dead && cur === call) remoteVideo = track }
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { scope.launch { connState(call, s) } }
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun connState(call: Call, s: PeerConnection.PeerConnectionState) {
        if (call.dead || cur !== call) return
        when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                weak = false
                if (phase != CallPhase.Active) setActive(call)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> weak = true
            PeerConnection.PeerConnectionState.FAILED ->
                fail("Не удалось соединиться. Возможно, нужен TURN-сервер (см. README)")
            else -> {}
        }
    }

    private fun setActive(call: Call) {
        stopTone()
        Notifier.cancelCall(ctx)
        phase = CallPhase.Active
        call.startedAt = System.currentTimeMillis()
        call.timerJob = scope.launch {
            while (true) {
                seconds = ((System.currentTimeMillis() - call.startedAt) / 1000).toInt()
                delay(500)
            }
        }
    }

    // ---- ICE: локальные кандидаты копим, пока у звонка нет id / не отправлен ответ
    private fun sendIce(c: Call, x: IceCandidate) {
        if (c.dead) return
        val id = c.id
        if (c.canSend && id != null) {
            socket.send(
                JSONObject().put("type", "call_ice").put("callId", id).put(
                    "candidate",
                    JSONObject().put("candidate", x.sdp).put("sdpMid", x.sdpMid).put("sdpMLineIndex", x.sdpMLineIndex)
                )
            )
        } else {
            c.pendingLocal.add(x)
        }
    }

    private fun openIce(c: Call) {
        c.canSend = true
        val pending = c.pendingLocal.toList()
        c.pendingLocal.clear()
        for (x in pending) sendIce(c, x)
    }

    private fun flushRemote(c: Call) {
        val pc = c.pc ?: return
        val pending = c.pendingRemote.toList()
        c.pendingRemote.clear()
        for (x in pending) pc.addIceCandidate(x)
    }

    private fun cleanup() {
        val c = cur
        cur = null
        stopTone()
        Notifier.cancelCall(ctx)
        CallService.stop(ctx)
        phase = CallPhase.Idle
        localVideo = null
        remoteVideo = null
        weak = false
        leaveCallAudio()
        if (c == null) return
        c.dead = true
        c.timerJob?.cancel()
        runCatching { c.capturer?.stopCapture() }
        runCatching { c.pc?.close() }
        runCatching { c.videoTrack?.dispose() }
        runCatching { c.videoSource?.dispose() }
        runCatching { c.audioTrack?.dispose() }
        runCatching { c.audioSource?.dispose() }
        runCatching { c.capturer?.dispose() }
        runCatching { c.helper?.dispose() }
        runCatching { c.pc?.dispose() }
    }

    // =============================================================== звук

    private fun enterCallAudio(withVideo: Boolean) {
        if (!audioActive) { savedMode = audioManager.mode; audioActive = true }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker(withVideo)
    }

    private fun leaveCallAudio() {
        if (!audioActive) return
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = savedMode
        audioActive = false
        speaker = false
    }

    private fun applySpeaker(on: Boolean) {
        speaker = on
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
    }

    private fun startRing() {
        stopTone()
        try {
            val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ring = r
        } catch (_: Exception) {
        }
    }

    private fun startRingback() {
        stopTone()
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopTone() {
        runCatching { ring?.stop() }
        ring = null
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
    }

    private companion object {
        val REASONS = mapOf(
            "declined" to "Звонок отклонён",
            "busy" to "Абонент занят",
            "busy_self" to "Вы уже в звонке",
            "unavailable" to "Абонент не в сети",
            "missed" to "Нет ответа",
        )
    }
}
