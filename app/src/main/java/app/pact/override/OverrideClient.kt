package app.pact.override

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class OverrideClient(private val store: OverrideStore) {

    fun create(
        kind: RequestKind,
        targetId: String,
        label: String,
        minutes: Int,
        comment: String,
    ): RemoteRequest {
        val body = JSONObject()
            .put("device_id", store.deviceId())
            .put("kind", kind.wire)
            .put("target_id", targetId)
            .put("package_name", kind.packageNameField(targetId))
            .put("app_label", label)
            .put("minutes", minutes)
            .put("comment", comment)
        return parseRequest(post("/api/pact/requests", body))
    }

    fun create(
        packageName: String,
        appLabel: String,
        minutes: Int,
        comment: String,
    ): RemoteRequest = create(RequestKind.APP, packageName, appLabel, minutes, comment)

    fun get(requestId: String): RemoteRequest {
        return parseRequest(httpGet("/api/pact/requests/${requestId}"))
    }

    private fun httpGet(path: String): JSONObject = call("GET", path, null)

    private fun post(path: String, body: JSONObject): JSONObject = call("POST", path, body)

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val base = store.serverUrl()
        if (base.isBlank()) {
            throw OverrideException("no_server", "Set the Pact server URL in setup first.")
        }
        val url = URL(base + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            val json = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            if (code in 200..299) return json
            throw toError(code, json)
        } catch (e: OverrideException) {
            throw e
        } catch (e: IOException) {
            throw OverrideException("network", "Could not reach the Pact server.")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRequest(json: JSONObject): RemoteRequest {
        val nested = json.optJSONObject("request") ?: json
        return readRemote(nested)
    }

    private fun toError(code: Int, json: JSONObject): OverrideException {
        val detail = json.opt("detail")
        val obj = when (detail) {
            is JSONObject -> detail
            else -> json
        }
        val error = obj.optString("error").ifBlank {
            if (code == 409) "already_requested_today" else "http_$code"
        }
        val message = obj.optString("message").ifBlank {
            when (detail) {
                is String -> detail
                else -> obj.optJSONObject("request")?.optString("message").orEmpty()
            }.ifBlank { "Request failed." }
        }
        val remote = (obj.optJSONObject("request") ?: json.optJSONObject("request"))
            ?.let { runCatching { readRemote(it) }.getOrNull() }
        return OverrideException(error, message, remote)
    }

    private fun readRemote(obj: JSONObject): RemoteRequest {
        val id = obj.optString("id")
        if (id.isBlank()) throw OverrideException("invalid_response", "Server returned an empty request.")
        val kind = RequestKind.fromWire(obj.optString("kind")) ?: RequestKind.APP
        val packageName = obj.optString("package_name")
        val targetId = obj.optString("target_id").ifBlank {
            when (kind) {
                RequestKind.SETTINGS -> SETTINGS_TARGET
                RequestKind.GROUP -> packageName.removePrefix("group.")
                RequestKind.APP -> packageName
            }
        }
        return RemoteRequest(
            id = id,
            status = obj.optString("status"),
            kind = kind,
            targetId = targetId,
            packageName = packageName.ifBlank { kind.packageNameField(targetId) },
            appLabel = obj.optString("app_label"),
            minutes = obj.optInt("minutes"),
            comment = obj.optString("comment"),
            day = obj.optString("day"),
            expiresAtMs = obj.optLong("expires_at_ms"),
            live = obj.optBoolean("live"),
        )
    }
}
