package ru.mintgram.app

import org.json.JSONArray
import org.json.JSONObject

const val GENERAL = "#general"
val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
fun JSONObject.lng(k: String): Long? = if (isNull(k)) null else getLong(k)
fun JSONObject.int(k: String): Int? = if (isNull(k)) null else getInt(k)
fun JSONObject.obj(k: String): JSONObject? = if (isNull(k)) null else optJSONObject(k)
fun JSONObject.arr(k: String): JSONArray? = if (isNull(k)) null else optJSONArray(k)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

data class User(
    val username: String,
    val displayName: String?,
    val bio: String?,
    val avatar: String?,
    val accent: Int?,
    val lastSeen: Long?,
) {
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: username

    companion object {
        fun from(o: JSONObject) = User(
            username = o.getString("username"),
            displayName = o.str("display_name"),
            bio = o.str("bio"),
            avatar = o.str("avatar"),
            accent = o.int("accent"),
            lastSeen = o.lng("last_seen"),
        )
    }
}

data class Reply(val id: Long, val from: String, val kind: String, val deleted: Boolean, val text: String)
data class Reaction(val emoji: String, val users: List<String>)

data class Msg(
    val id: Long,
    val from: String,
    val to: String?,        // для личных и общего чата
    val groupId: Long?,     // для групп
    val kind: String,       // text | image | call
    val text: String,
    val ts: Long,
    val edited: Long? = null,
    val deleted: Boolean = false,
    val fileId: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    val reply: Reply? = null,
    val reactions: List<Reaction> = emptyList(),
    val callStatus: String? = null,
    val callVideo: Boolean = false,
    val callDuration: Int = 0,
    // локальные поля для «отправляется…»
    val cid: String? = null,
    val pending: Boolean = false,
    val failed: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject): Msg {
            val meta = o.obj("meta")
            val file = o.obj("file")
            val reply = o.obj("reply")?.let {
                Reply(
                    id = it.getLong("id"), from = it.getString("from"),
                    kind = it.optString("kind", "text"), deleted = it.optBoolean("deleted"),
                    text = it.optString("text", ""),
                )
            }
            val reactions = o.arr("reactions")?.objects()?.map { r ->
                Reaction(r.getString("emoji"), r.getJSONArray("users").strings())
            } ?: emptyList()
            return Msg(
                id = o.getLong("id"),
                from = o.getString("from"),
                to = o.str("to"),
                groupId = o.lng("groupId"),
                kind = o.optString("kind", "text"),
                text = o.optString("text", ""),
                ts = o.optLong("ts"),
                edited = o.lng("edited"),
                deleted = o.optBoolean("deleted"),
                fileId = file?.str("id"),
                w = meta?.int("w"),
                h = meta?.int("h"),
                reply = reply,
                reactions = reactions,
                callStatus = meta?.str("status"),
                callVideo = meta?.optBoolean("video") ?: false,
                callDuration = meta?.optInt("duration") ?: 0,
            )
        }
    }
}

/** Строка списка чатов. key: имя собеседника, GENERAL или "g:<id>" */
data class ChatItem(
    val key: String,
    val title: String,
    val user: User?,
    val groupId: Long?,
    val isChannel: Boolean,
    val unread: Int,
    val lastText: String,
    val lastTs: Long,
)

fun groupKey(id: Long) = "g:$id"

data class GroupInfo(
    val id: Long,
    val name: String,
    val description: String?,
    val isChannel: Boolean,
    val role: String?,
    val memberCount: Int,
)
