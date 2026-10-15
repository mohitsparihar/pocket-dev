package dev.pocket.app.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ComputeVm(private val accessToken: String) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun status(project: String, zone: String, instance: String): String {
        val body = get("https://compute.googleapis.com/compute/v1/projects/${enc(project)}/zones/${enc(zone)}/instances/${enc(instance)}")
        return body.jsonObject["status"]?.jsonPrimitive?.content ?: "UNKNOWN"
    }

    fun start(project: String, zone: String, instance: String) {
        post("https://compute.googleapis.com/compute/v1/projects/${enc(project)}/zones/${enc(zone)}/instances/${enc(instance)}/start")
    }

    fun resume(project: String, zone: String, instance: String) {
        post("https://compute.googleapis.com/compute/v1/projects/${enc(project)}/zones/${enc(zone)}/instances/${enc(instance)}/resume")
    }

    private fun get(url: String) = call(Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build())

    private fun post(url: String) {
        call(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post("".toRequestBody("application/json".toMediaType()))
                .build(),
        )
    }

    private fun call(request: Request): kotlinx.serialization.json.JsonElement {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw ComputeException(response.code, message ?: text.take(280).ifBlank { "Google API error ${response.code}" })
            }
            if (text.isBlank()) return kotlinx.serialization.json.JsonObject(emptyMap())
            return json.parseToJsonElement(text)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

class ComputeException(val status: Int, message: String) : Exception(message)
