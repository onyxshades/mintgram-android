package ru.mintgram.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

private fun JSONObject.flag(k: String): Boolean {
    val v = opt(k)
    return v == true || (v is Number && v.toInt() != 0)
}

fun previewOf(kind: String, text: String, deleted: Boolean): String = when {
    deleted -> "Сообщение удалено"
    kind == "image" -> if (text.isBlank()) "📷 Фото" else "📷 $text"
    kind == "call" -> "📞 Звонок"
    else -> text
}

class Prepared(val bytes: ByteArray, val w: Int, val h: Int)

class AppVM(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val api = Api(prefs)
    val socket = Socket(api, prefs, viewModelScope)
    val calls = CallManager(app, api, socket, viewModelScope) { toast = it }
    private var readyJob: Job? = null
    private val ctx = app.applicationContext

    // ---- авторизация
    var loggedIn by mutableStateOf(prefs.token != null)
    var me by mutableStateOf<User?>(null)
    var authBusy by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)
    var regUsername by mutableStateOf<String?>(null)   // ждём код из письма
    var regEmailMasked by mutableStateOf("")

    // ---- данные
    val users = mutableStateMapOf<String, User>()
    var online by mutableStateOf<Set<String>>(emptySet())
    val chatMeta = mutableStateMapOf<String, ChatItem>()
    val groups = mutableStateMapOf<Long, GroupInfo>()
    val messages = mutableStateMapOf<String, List<Msg>>()
    val hasMore = mutableStateMapOf<String, Boolean>()
    val peerRead = mutableStateMapOf<String, Long>()
    val myRead = mutableStateMapOf<String, Long>()
    val typingUntil = mutableStateMapOf<String, Long>()
    val typingWho = mutableStateMapOf<String, String>()

    var openChat by mutableStateOf<String?>(null)
    var loadingHistory by mutableStateOf(false)
    var uploading by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    @Volatile var inForeground = true

    val sortedChats: List<ChatItem>
        get() = chatMeta.values.sortedByDescending { it.lastTs }

    val meName: String get() = me?.username ?: prefs.username ?: ""

    private val loaded = mutableSetOf<String>()
    private val requested = mutableSetOf<String>()
    private val lastTypingSent = HashMap<String, Long>()
    private var eventsJob: Job? = null
    private var readyCount = 0

    init {
        if (loggedIn) startSession()
    }

    // =============================================================== авторизация

    fun setServer(url: String) {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
        if (u.isNotEmpty()) prefs.server = u
    }

    private fun authDone(res: JSONObject) {
        prefs.token = res.getString("token")
        val u = User.from(res.getJSONObject("user"))
        prefs.username = u.username
        me = u
        regUsername = null
        loggedIn = true
        startSession()
    }

    fun login(username: String, password: String) = authCall {
        authDone(api.login(username.trim(), password))
    }

    fun register(username: String, password: String, email: String) = authCall {
        val r = api.registerStart(username.trim(), password, email.trim())
        regUsername = username.trim()
        regEmailMasked = r.optString("email")
    }

    fun confirmCode(code: String) = authCall {
        authDone(api.registerConfirm(regUsername ?: return@authCall, code))
    }

    fun resendCode() = authCall {
        api.registerResend(regUsername ?: return@authCall)
        toast = "Код отправлен ещё раз"
    }

    fun cancelRegistration() { regUsername = null; authError = null }

    private fun authCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            authBusy = true; authError = null
            try { block() } catch (e: ApiException) { authError = e.message }
            finally { authBusy = false }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try { api.logout() } catch (_: Exception) {}
            localLogout()
        }
    }

    private fun localLogout() {
        calls.hangup()
        readyJob?.cancel()
        socket.close()
        eventsJob?.cancel()
        prefs.token = null
        me = null
        loggedIn = false
        openChat = null
        users.clear(); chatMeta.clear(); groups.clear(); messages.clear()
        hasMore.clear(); peerRead.clear(); myRead.clear()
        loaded.clear(); requested.clear(); readyCount = 0
    }

    // =============================================================== сессия

    private fun startSession() {
        viewModelScope.launch {
            try {
                val r = api.me()
                me = User.from(r.getJSONObject("user"))
                prefs.username = me?.username
            } catch (e: ApiException) {
                if (e.status == 401) { localLogout(); return@launch }
            }
            if (me == null) me = User(prefs.username ?: "", null, null, null, null, null)
            Notifier.ensureChannel(ctx)
            eventsJob?.cancel()
            eventsJob = viewModelScope.launch { socket.events.collect { handleEvent(it) } }
            readyJob?.cancel()
            readyJob = viewModelScope.launch { socket.ready.collect { if (!it) calls.onDisconnected() } }
            socket.connect()
            refresh()
        }
    }

    fun onForeground(fg: Boolean) {
        inForeground = fg
        if (fg && loggedIn) {
            socket.ensure()
            openChat?.let { markRead(it); Notifier.cancel(ctx, it) }
        }
    }

    suspend fun refresh() {
        try {
            val c = api.chats()
            online = c.arr("online")?.strings()?.toSet() ?: emptySet()
            val self = meName
            for (o in c.getJSONArray("chats").objects()) {
                val u = User.from(o.getJSONObject("peer"))
                users[u.username] = u
                val last = o.getJSONObject("last")
                chatMeta[u.username] = ChatItem(
                    key = u.username,
                    title = if (u.username.equals(self, true)) "Избранное" else u.title,
                    user = u, groupId = null, isChannel = false,
                    unread = o.optInt("unread"),
                    lastText = previewOf(last.optString("kind", "text"), last.optString("text"), last.flag("deleted")),
                    lastTs = last.optLong("ts"),
                )
                o.lng("peer_read")?.let { peerRead[u.username] = it }
            }
            val g = c.getJSONObject("general")
            val gl = g.obj("last")
            chatMeta[GENERAL] = ChatItem(
                key = GENERAL, title = "Общий чат", user = null, groupId = null, isChannel = false,
                unread = g.optInt("unread"),
                lastText = gl?.let { "${it.optString("from")}: " + previewOf(it.optString("kind", "text"), it.optString("text"), it.flag("deleted")) }
                    ?: "Пока нет сообщений",
                lastTs = gl?.optLong("ts") ?: 0,
            )
            myRead[GENERAL] = g.optLong("my_read")

            val gr = api.groups()
            for (o in gr.getJSONArray("groups").objects()) {
                val id = o.getLong("id")
                val info = GroupInfo(
                    id, o.getString("name"), o.str("description"),
                    o.flag("is_channel"), o.str("role"), o.optInt("member_count"),
                )
                groups[id] = info
                val last = o.obj("last")
                chatMeta[groupKey(id)] = ChatItem(
                    key = groupKey(id), title = info.name, user = null, groupId = id,
                    isChannel = info.isChannel, unread = o.optInt("unread"),
                    lastText = last?.let {
                        val who = it.optString("sender", it.optString("from", ""))
                        "$who: " + previewOf(it.optString("kind", "text"), it.optString("text"), it.flag("deleted"))
                    } ?: "Пока нет сообщений",
                    lastTs = last?.optLong("ts") ?: 0,
                )
                myRead[groupKey(id)] = o.optLong("my_read")
            }
        } catch (e: ApiException) {
            if (e.status == 401) localLogout() else toast = e.message
        }
    }

    // =============================================================== события WebSocket

    private fun handleEvent(e: JSONObject) {
        when (e.optString("type")) {
            "ready" -> {
                online = e.arr("online")?.strings()?.toSet() ?: online
                if (readyCount++ > 0) {
                    // переподключились: подтянуть пропущенное
                    viewModelScope.launch {
                        refresh()
                        openChat?.let { loadHistory(it, false) }
                    }
                }
            }
            "presence" -> {
                online = e.arr("online")?.strings()?.toSet() ?: online
                e.obj("gone")?.let { gone ->
                    val name = gone.getString("user")
                    users[name]?.let { users[name] = it.copy(lastSeen = gone.optLong("ts")) }
                }
            }
            "message", "group_message" -> onMessage(Msg.from(e.getJSONObject("message")), e.str("cid"))
            "message_update", "group_message_update" -> onUpdate(Msg.from(e.getJSONObject("message")))
            "typing" -> {
                val from = e.getString("from")
                typingUntil[from] = System.currentTimeMillis() + 4000
                typingWho[from] = from
            }
            "group_typing" -> {
                val key = groupKey(e.getLong("groupId"))
                typingUntil[key] = System.currentTimeMillis() + 4000
                typingWho[key] = e.getString("from")
            }
            "read" -> peerRead[e.getString("by")] = e.getLong("upTo")
            "read_self" -> selfRead(e.getString("with"), e.getLong("upTo"))
            "group_read_self" -> selfRead(groupKey(e.getLong("groupId")), e.getLong("upTo"))
            "profile" -> onProfile(User.from(e.getJSONObject("user")))
            "error" -> onError(e)
            "call_incoming", "call_ringing", "call_accepted", "call_ice", "call_taken", "call_ended", "call_error" -> {
                calls.handle(e)
                if (e.optString("type") == "call_incoming" && !inForeground && calls.phase == CallPhase.Incoming) {
                    Notifier.incomingCall(ctx, e.optString("from"), e.optBoolean("video"))
                }
            }
            "_auth_failed" -> localLogout()
        }
    }

    private fun selfRead(key: String, upTo: Long) {
        myRead[key] = maxOf(upTo, myRead[key] ?: 0)
        chatMeta[key]?.let { chatMeta[key] = it.copy(unread = 0) }
    }

    fun keyOf(m: Msg): String = when {
        m.groupId != null -> groupKey(m.groupId)
        m.to == GENERAL -> GENERAL
        m.from.equals(meName, true) -> m.to ?: m.from
        else -> m.from
    }

    private fun onMessage(m: Msg, cid: String?) {
        val key = keyOf(m)
        val mine = m.from.equals(meName, true)

        messages[key]?.let { list ->
            val idx = if (cid != null) list.indexOfFirst { it.pending && it.cid == cid } else -1
            messages[key] = when {
                idx >= 0 -> list.toMutableList().also { it[idx] = m }
                list.any { !it.pending && it.id == m.id } -> list
                else -> (list.filter { !it.pending } + m).sortedBy { it.id } + list.filter { it.pending }
            }
        }

        if (key.startsWith("g:") && !chatMeta.containsKey(key)) viewModelScope.launch { refresh() }
        val viewing = openChat == key && inForeground
        val prev = chatMeta[key] ?: makeItem(key)
        val who = when {
            key == GENERAL || m.groupId != null -> (users[m.from]?.title ?: m.from) + ": "
            mine -> "Вы: "
            else -> ""
        }
        chatMeta[key] = prev.copy(
            lastText = who + previewOf(m.kind, m.text, m.deleted),
            lastTs = m.ts,
            unread = prev.unread + if (!mine && !viewing) 1 else 0,
        )
        if (!mine) {
            ensureUser(m.from)
            typingUntil.remove(if (m.groupId != null) key else m.from)
            if (viewing) markRead(key)
            else Notifier.message(
                ctx,
                title = if (m.groupId != null || key == GENERAL) prev.title else (users[m.from]?.title ?: m.from),
                text = who.takeIf { m.groupId != null || key == GENERAL }.orEmpty() + previewOf(m.kind, m.text, m.deleted),
                chatKey = key,
            )
        }
    }

    private fun onUpdate(m: Msg) {
        val key = keyOf(m)
        messages[key]?.let { list ->
            messages[key] = list.map { if (!it.pending && it.id == m.id) m else it }
        }
    }

    private fun onProfile(u: User) {
        users[u.username] = u
        if (u.username.equals(meName, true)) me = u
        chatMeta[u.username]?.let {
            chatMeta[u.username] = it.copy(
                user = u, title = if (u.username.equals(meName, true)) "Избранное" else u.title,
            )
        }
    }

    private fun onError(e: JSONObject) {
        val text = when (e.optString("code")) {
            "rate_limit" -> "Слишком часто, подождите немного"
            "bad_text" -> "Некорректный текст сообщения"
            "no_user" -> "Пользователь не найден"
            "bad_file" -> "Не удалось отправить файл"
            "forbidden" -> "В канале писать могут только администраторы"
            "not_member" -> "Вы не состоите в этой группе"
            "cannot_edit" -> "Это сообщение нельзя изменить"
            "cannot_delete" -> "Это сообщение нельзя удалить"
            "cannot_react" -> "Нельзя поставить реакцию"
            else -> "Ошибка: ${e.optString("code")}"
        }
        toast = text
        val cid = e.str("cid") ?: return
        for ((key, list) in messages.entries.toList()) {
            if (list.any { it.pending && it.cid == cid }) {
                messages[key] = list.map { if (it.pending && it.cid == cid) it.copy(failed = true, pending = true) else it }
            }
        }
    }

    // =============================================================== чаты

    private fun makeItem(key: String): ChatItem {
        ensureUser(key)
        return ChatItem(
            key = key,
            title = if (key.equals(meName, true)) "Избранное" else (users[key]?.title ?: key),
            user = users[key], groupId = null, isChannel = false,
            unread = 0, lastText = "", lastTs = 0,
        )
    }

    fun ensureUser(name: String) {
        if (name.startsWith("g:") || name == GENERAL) return
        if (users.containsKey(name) || !requested.add(name)) return
        viewModelScope.launch {
            try {
                val r = api.profile(name)
                users[name] = User.from(r.getJSONObject("user"))
            } catch (_: Exception) { requested.remove(name) }
        }
    }

    fun open(key: String) {
        if (!key.startsWith("g:") && key != GENERAL && !chatMeta.containsKey(key)) {
            chatMeta[key] = makeItem(key)
        }
        openChat = key
        Notifier.cancel(ctx, key)
        viewModelScope.launch {
            if (key !in loaded) loadHistory(key, false)
            markRead(key)
        }
    }

    fun close() { openChat = null }

    fun loadOlder(key: String) {
        if (loadingHistory) return
        viewModelScope.launch { loadHistory(key, true) }
    }

    private suspend fun loadHistory(key: String, older: Boolean) {
        loadingHistory = true
        try {
            val before = if (older) messages[key]?.firstOrNull { !it.pending }?.id ?: 0L else 0L
            val res = if (key.startsWith("g:")) api.groupHistory(key.substring(2).toLong(), before)
            else api.history(key, before)
            val list = res.getJSONArray("messages").objects().map { Msg.from(it) }
            res.lng("my_read")?.let { myRead[key] = maxOf(it, myRead[key] ?: 0) }
            res.lng("peer_read")?.let { peerRead[key] = it }
            val old = messages[key] ?: emptyList()
            messages[key] = (old.filter { !it.pending } + list)
                .associateBy { it.id }.values.sortedBy { it.id } + old.filter { it.pending }
            if (older || key !in loaded) hasMore[key] = list.size >= 50
            loaded.add(key)
            list.map { it.from }.distinct().forEach { ensureUser(it) }
        } catch (e: ApiException) {
            toast = e.message
        } finally {
            loadingHistory = false
        }
    }

    fun markRead(key: String) {
        val last = messages[key]?.filter { !it.pending }?.maxOfOrNull { it.id } ?: return
        if (last <= (myRead[key] ?: 0)) return
        myRead[key] = last
        chatMeta[key]?.let { chatMeta[key] = it.copy(unread = 0) }
        if (key.startsWith("g:")) {
            socket.send(JSONObject().put("type", "group_read").put("groupId", key.substring(2).toLong()).put("upTo", last))
        } else {
            socket.send(JSONObject().put("type", "read").put("with", key).put("upTo", last))
        }
    }

    // =============================================================== отправка и действия

    private fun addTarget(o: JSONObject, key: String, group: String, direct: String) {
        if (key.startsWith("g:")) o.put("type", group).put("groupId", key.substring(2).toLong())
        else o.put("type", direct).put("to", key)
    }

    fun send(key: String, text: String, replyTo: Msg?) {
        val t = text.trim()
        if (t.isEmpty()) return
        val isGroup = key.startsWith("g:")
        val m = Msg(
            id = 0, from = meName, to = if (isGroup) null else key,
            groupId = if (isGroup) key.substring(2).toLong() else null,
            kind = "text", text = t, ts = System.currentTimeMillis(),
            reply = replyTo?.let { Reply(it.id, it.from, it.kind, false, it.text.take(120)) },
            cid = UUID.randomUUID().toString(), pending = true,
        )
        messages[key] = (messages[key] ?: emptyList()) + m
        dispatch(key, m)
    }

    private fun dispatch(key: String, m: Msg) {
        val o = JSONObject().put("cid", m.cid).put("text", m.text)
        addTarget(o, key, "group_send", "send")
        m.reply?.let { o.put("reply_to", it.id) }
        if (!socket.send(o)) markFailed(key, m.cid)
    }

    private fun markFailed(key: String, cid: String?) {
        messages[key]?.let { list ->
            messages[key] = list.map { if (it.cid == cid && it.pending) it.copy(failed = true) else it }
        }
    }

    fun retry(key: String, m: Msg) {
        messages[key]?.let { list ->
            messages[key] = list.map { if (it.cid == m.cid) it.copy(failed = false) else it }
        }
        dispatch(key, m.copy(failed = false))
    }

    fun discard(key: String, m: Msg) {
        messages[key]?.let { list -> messages[key] = list.filter { it.cid != m.cid } }
    }

    fun edit(key: String, m: Msg, text: String) {
        val o = JSONObject().put("id", m.id).put("text", text.trim())
        o.put("type", if (key.startsWith("g:")) "group_edit" else "edit")
        if (!socket.send(o)) toast = "Нет соединения"
    }

    fun delete(key: String, m: Msg) {
        val o = JSONObject().put("id", m.id)
        o.put("type", if (key.startsWith("g:")) "group_delete" else "delete")
        if (!socket.send(o)) toast = "Нет соединения"
    }

    fun react(m: Msg, emoji: String) {
        val mineNow = m.reactions.firstOrNull { r -> r.users.any { it.equals(meName, true) } }?.emoji
        val o = JSONObject().put("type", "react").put("id", m.id)
        o.put("emoji", if (mineNow == emoji) JSONObject.NULL else emoji)
        if (!socket.send(o)) toast = "Нет соединения"
    }

    fun onTyping(key: String) {
        if (key == GENERAL) return
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[key] ?: 0) < 3000) return
        lastTypingSent[key] = now
        val o = JSONObject()
        if (key.startsWith("g:")) o.put("type", "group_typing").put("groupId", key.substring(2).toLong())
        else o.put("type", "typing").put("to", key)
        socket.send(o)
    }

    fun sendImage(key: String, uri: Uri, caption: String, replyTo: Msg?) {
        viewModelScope.launch {
            uploading = true
            try {
                val img = withContext(Dispatchers.IO) { prepareImage(uri, 2048, true) }
                    ?: throw ApiException("Не удалось прочитать картинку", 0)
                val up = api.upload("image", img.bytes)
                val o = JSONObject()
                    .put("file", up.getString("id")).put("w", img.w).put("h", img.h)
                    .put("text", caption.trim()).put("cid", UUID.randomUUID().toString())
                addTarget(o, key, "group_send", "send")
                replyTo?.let { o.put("reply_to", it.id) }
                if (!socket.send(o)) toast = "Нет соединения"
            } catch (e: ApiException) {
                toast = e.message
            } finally {
                uploading = false
            }
        }
    }

    // =============================================================== группы, поиск, профиль

    fun searchUsers(q: String, onResult: (List<User>) -> Unit) {
        viewModelScope.launch {
            try {
                val r = api.users(q)
                val list = r.optJSONArray("users")?.objects()?.map { User.from(it) } ?: emptyList()
                list.forEach { users[it.username] = it }
                onResult(list)
            } catch (e: ApiException) { toast = e.message; onResult(emptyList()) }
        }
    }

    fun createGroup(name: String, description: String, channel: Boolean) {
        viewModelScope.launch {
            try {
                val r = api.createGroup(name, description, channel)
                refresh()
                open(groupKey(r.getJSONObject("group").getLong("id")))
            } catch (e: ApiException) { toast = e.message }
        }
    }

    fun addMember(groupId: Long, username: String) {
        viewModelScope.launch {
            try { api.addMember(groupId, username.trim().removePrefix("@")); toast = "Участник добавлен" }
            catch (e: ApiException) { toast = e.message }
        }
    }

    fun leaveGroup(groupId: Long) {
        viewModelScope.launch {
            try {
                api.leaveGroup(groupId)
                chatMeta.remove(groupKey(groupId)); groups.remove(groupId); messages.remove(groupKey(groupId))
                openChat = null
            } catch (e: ApiException) { toast = e.message }
        }
    }

    fun saveProfile(displayName: String, bio: String, accent: Int?) {
        viewModelScope.launch {
            try {
                val body = JSONObject().put("display_name", displayName).put("bio", bio)
                    .put("accent", accent ?: JSONObject.NULL)
                me = User.from(api.updateProfile(body).getJSONObject("user"))
                me?.let { users[it.username] = it }
                toast = "Профиль сохранён"
            } catch (e: ApiException) { toast = e.message }
        }
    }

    fun setAvatar(uri: Uri) {
        viewModelScope.launch {
            uploading = true
            try {
                val img = withContext(Dispatchers.IO) { prepareImage(uri, 512, false) }
                    ?: throw ApiException("Не удалось прочитать картинку", 0)
                val up = api.upload("avatar", img.bytes)
                me = User.from(api.updateProfile(JSONObject().put("avatar", up.getString("id"))).getJSONObject("user"))
                me?.let { users[it.username] = it }
            } catch (e: ApiException) { toast = e.message } finally { uploading = false }
        }
    }

    // =============================================================== картинки

    private fun prepareImage(uri: Uri, maxDim: Int, allowGif: Boolean): Prepared? {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: ""
        val raw = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (allowGif && mime == "image/gif" && raw.size <= 8_000_000) {
            return Prepared(raw, bounds.outWidth, bounds.outHeight)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val orientation = try {
            ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Prepared(out.toByteArray(), bmp.width, bmp.height)
    }

    override fun onCleared() {
        calls.release()
        socket.close()
    }
}
