package ru.mintgram.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int) : Exception(message)

/** Хранилище настроек: адрес сервера, токен сессии. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("mintgram", Context.MODE_PRIVATE)
    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()
    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()
}

class Api(private val prefs: Prefs) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    val base: String get() = prefs.server

    fun fileUrl(id: String) = "$base/files/$id"

    private suspend fun exec(req: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = try { JSONObject(text.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                if (!resp.isSuccessful) {
                    throw ApiException(obj.optString("error", "Ошибка ${resp.code}"), resp.code)
                }
                obj
            }
        } catch (e: IOException) {
            throw ApiException("Нет соединения с сервером", 0)
        }
    }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(base + path)
        prefs.token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    suspend fun get(path: String): JSONObject = exec(builder(path).get().build())

    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject =
        exec(builder(path).post(body.toString().toRequestBody(json)).build())

    suspend fun delete(path: String): JSONObject = exec(builder(path).delete().build())

    suspend fun upload(kind: String, bytes: ByteArray): JSONObject =
        exec(builder("/api/upload?kind=$kind")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build())

    // ---- авторизация
    suspend fun login(username: String, password: String) =
        post("/api/login", JSONObject().put("username", username).put("password", password))

    suspend fun registerStart(username: String, password: String, email: String) =
        post("/api/register", JSONObject().put("username", username).put("password", password).put("email", email))

    suspend fun registerConfirm(username: String, code: String) =
        post("/api/register/confirm", JSONObject().put("username", username).put("code", code))

    suspend fun registerResend(username: String) =
        post("/api/register/resend", JSONObject().put("username", username))

    // ---- данные
    suspend fun me() = get("/api/me")
    suspend fun logout() = post("/api/logout")
    suspend fun chats() = get("/api/chats")
    suspend fun groups() = get("/api/groups")
    suspend fun users(q: String) = get("/api/users?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
    suspend fun config() = get("/api/config")
    suspend fun profile(name: String) = get("/api/profile/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
    suspend fun history(with: String, before: Long, limit: Int = 50) =
        get("/api/history?with=" + java.net.URLEncoder.encode(with, "UTF-8") + "&before=$before&limit=$limit")
    suspend fun groupHistory(id: Long, before: Long, limit: Int = 50) =
        get("/api/groups/$id/history?before=$before&limit=$limit")
    suspend fun createGroup(name: String, description: String, channel: Boolean) =
        post("/api/groups", JSONObject().put("name", name).put("description", description).put("is_channel", channel))
    suspend fun leaveGroup(id: Long) = post("/api/groups/$id/leave")
    suspend fun addMember(id: Long, username: String) =
        post("/api/groups/$id/members", JSONObject().put("username", username))
    suspend fun updateProfile(body: JSONObject) = post("/api/profile", body)
}
