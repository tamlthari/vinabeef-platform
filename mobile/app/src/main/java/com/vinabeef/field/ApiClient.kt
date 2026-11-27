package com.vinabeef.field

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Server address and the signed-in session. The password is never stored, only the token the server issued. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vinabeef_field_session", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value.trim().removeSuffix("/")).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    /** The barn's key as the server knows it. Kept for the sync; people are shown [barnLabel], the barn code. */
    var barnId: String
        get() = prefs.getString("barn_id", "") ?: ""
        set(value) = prefs.edit().putString("barn_id", value).apply()

    /** The name of the barn on the screen: its code, the same as on the web portal ("ALL" for every barn). */
    var barnLabel: String
        get() = prefs.getString("barn_label", "") ?: ""
        set(value) = prefs.edit().putString("barn_label", value).apply()

    /** What to show and to send when loading: the code when known, else the key a phone saved before codes were used. */
    val barnShown: String
        get() = barnLabel.ifEmpty { barnId }

    fun signOut() {
        prefs.edit().remove("token").apply()
    }

    companion object {
        /** Android emulator -> the development machine. A phone on the barn Wi-Fi uses the server address entered at sign-in. */
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080/api/v1"
    }
}

/** The three calls the field app makes. Blocking: call them from a background thread. */
object ApiClient : RecordUploader {

    data class Response(val code: Int, val body: String)

    fun login(baseUrl: String, username: String, password: String): Response {
        val body = JSONObject().put("username", username).put("password", password).toString()
        return request("POST", "$baseUrl/auth/login", null, body, 5000, 8000)
    }

    fun downloadBundle(baseUrl: String, token: String, barnId: String): Response =
        request("GET", "$baseUrl/mobile/sync-bundle?barnId=${java.net.URLEncoder.encode(barnId, "UTF-8")}", token, null, 5000, 15000)

    /** Sends a batch of saved records and reads the answer into [UploadReply]. */
    override fun upload(baseUrl: String, token: String, payloads: List<String>): UploadReply {
        val resp = request("POST", "$baseUrl/mobile/health-sync", token, payloads.joinToString(",", "[", "]"), 5000, 15000)
        if (resp.code == 401 || resp.code == 403) return UploadReply.SessionEnded
        if (resp.code !in 200..299) return UploadReply.ServerError(resp.code)
        val json = JSONObject(resp.body)
        val acknowledged = json.optJSONArray("acknowledgedClientIds") ?: JSONArray()
        val rejected = json.optJSONArray("rejected") ?: JSONArray()
        return UploadReply.Accepted(
            (0 until acknowledged.length()).map { acknowledged.getString(it) },
            (0 until rejected.length()).map {
                val r = rejected.getJSONObject(it)
                Rejection(r.optString("clientEventId"), r.optString("code"), r.optString("message"))
            }
        )
    }

    private fun request(
        method: String,
        url: String,
        token: String?,
        body: String?,
        connectMs: Int,
        readMs: Int
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
