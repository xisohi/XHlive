package com.lizongying.mytv0

import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import MainViewModel.Companion.DEFAULT_CHANNELS_FILE
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.ReqSettings
import com.lizongying.mytv0.data.ReqSourceAdd
import com.lizongying.mytv0.data.ReqSources
import com.lizongying.mytv0.data.RespSettings
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.requests.HttpClient
import fi.iki.elonen.NanoHTTPD
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class SimpleServer(private val context: Context, private val viewModel: MainViewModel) :
    NanoHTTPD(PORT) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            start()
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/api/settings" -> handleSettings()
            "/api/sources" -> handleSources()
            "/api/import-text" -> handleImportText(session)
            "/api/import-uri" -> handleImportUri(session)
            "/api/proxy" -> handleProxy(session)
            "/api/epg" -> handleEPG(session)
            "/api/default-channel" -> handleDefaultChannel(session)
            "/api/remove-source" -> handleRemoveSource(session)
            else -> handleStaticContent()
        }
    }

    private fun handleSettings(): Response {
        val response: String
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            var str = if (file.exists()) {
                file.readText()
            } else {
                ""
            }
            if (str.isEmpty()) {
                str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
            }

            var history = mutableListOf<Source>()

            if (!SP.sources.isNullOrEmpty()) {
                try {
                    val sources: List<Source> = gson.fromJson(SP.sources!!, typeSourceList)
                    history = sources.toMutableList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    SP.sources = SP.DEFAULT_SOURCES
                }
            }

            val respSettings = RespSettings(
                channelUri = SP.configUrl ?: "",
                channelText = str,
                channelDefault = SP.channel,
                proxy = SP.proxy ?: "",
                epg = SP.epg ?: "",
                history = history
            )
            response = gson.toJson(respSettings) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "handleSettings", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private suspend fun fetchSources(url: String): String {
        val urls = getUrls(url)
        Log.i(TAG, "Fetching sources from: $urls")

        var sources = ""
        var success = false
        for (a in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        sources = response.bodyAlias()?.string() ?: ""
                        Log.i(TAG, "Response length: ${sources.length}")
                        success = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSources error", e)
                }
            }

            if (success) break
        }

        if (sources.isEmpty()) {
            Log.e(TAG, "All fetch attempts failed, returning empty string")
        }

        return sources
    }

    private fun handleSources(): Response {
        val response = runBlocking(Dispatchers.IO) {
            fetchSources("https://xhys.lcjly.cn/live.txt")
        }

        // 检查响应是否为空
        if (response.isEmpty()) {
            Log.e(TAG, "fetchSources returned empty response")
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "[]"  // 返回空数组
            )
        }

        // 解码
        val decoded = Gua().decode(response)
        Log.i(TAG, "Decoded sources: $decoded")

        // 检查解码后是否为空
        if (decoded.isEmpty()) {
            Log.e(TAG, "Decoded sources is empty")
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "[]"
            )
        }

        val sourcesList = try {
            decoded.trim().split("\n").mapIndexed { index, line ->
                if (line.isBlank()) return@mapIndexed null

                val trimmedLine = line.trim()
                Log.d(TAG, "Parsing line $index: $trimmedLine")

                // 按逗号分割
                val parts = trimmedLine.split(",").map { it.trim() }

                var name = ""
                var uri = ""
                var ua = ""
                var referrer = ""

                // 解析每个部分
                for (part in parts) {
                    when {
                        part.startsWith("name:") -> {
                            name = part.substringAfter("name:").trim()
                            Log.d(TAG, "  Found name: $name")
                        }
                        part.startsWith("uri:") -> {
                            uri = part.substringAfter("uri:").trim()
                            Log.d(TAG, "  Found uri: $uri")
                        }
                        part.startsWith("ua:") -> {
                            ua = part.substringAfter("ua:").trim()
                            Log.d(TAG, "  Found ua: $ua")
                        }
                        part.startsWith("referrer:") -> {
                            referrer = part.substringAfter("referrer:").trim()
                            Log.d(TAG, "  Found referrer: $referrer")
                        }
                    }
                }

                // 确保uri不为空
                if (uri.isEmpty()) {
                    Log.e(TAG, "  WARNING: uri is empty for line: $trimmedLine")
                    return@mapIndexed null
                }

                Source(
                    id = java.util.UUID.randomUUID().toString(),
                    uri = uri,
                    name = name,
                    ua = ua,
                    referrer = referrer,
                    checked = false
                )
            }.filterNotNull()
        } catch (e: Exception) {
            Log.e(TAG, "解析sources失败", e)
            e.printStackTrace()
            emptyList<Source>()
        }

        // 打印最终结果
        Log.i(TAG, "Returning ${sourcesList.size} sources:")
        sourcesList.forEachIndexed { index, source ->
            Log.i(TAG, "[$index] name=${source.name}, uri=${source.uri}, ua=${source.ua}, referrer=${source.referrer}")
        }

        val jsonResponse = gson.toJson(sourcesList)
        Log.i(TAG, "JSON response: $jsonResponse")

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            jsonResponse
        )
    }

    private fun handleImportText(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    viewModel.tryStr2Channels(it, null, "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImportText", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleImportUri(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                val req = gson.fromJson(it, ReqSourceAdd::class.java)
                val uri = Uri.parse(req.uri)

                val source = Source(
                    id = req.id,
                    uri = req.uri,
                    name = req.name,
                    ua = req.ua,
                    referrer = req.referrer
                )
                handler.post {
                    viewModel.sources.addSource(source)
                    viewModel.importFromUri(uri, req.id, req.ua, req.referrer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImportUri", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleProxy(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.proxy != null) {
                        SP.proxy = req.proxy
                        R.string.default_proxy_set_success.showToast()
                        Log.i(TAG, "set proxy success")
                    } else {
                        R.string.default_proxy_set_failure.showToast()
                        Log.i(TAG, "set proxy failure")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleProxy", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleEPG(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.epg != null) {
                        SP.epg = req.epg
                        viewModel.updateEPG()
                        R.string.default_epg_set_success.showToast()
                    } else {
                        R.string.default_epg_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleEPG", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleDefaultChannel(session: IHTTPSession): Response {
        R.string.start_set_default_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.channel != null && req.channel > -1) {
                        SP.channel = req.channel
                        R.string.default_channel_set_success.showToast()
                    } else {
                        R.string.default_channel_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleDefaultChannel", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleRemoveSource(session: IHTTPSession): Response {
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSources::class.java)
                    Log.i(TAG, "req $req")
                    if (req.sourceId.isNotEmpty()) {
                        val res = viewModel.sources.removeSource(req.sourceId)
                        if (res) {
                            Log.i(TAG, "remove source success ${req.sourceId}")
                        } else {
                            Log.i(TAG, "remove source failure ${req.sourceId}")
                        }
                    } else {
                        Log.i(TAG, "remove source failure, sourceId is empty")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRemoveSource", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun readBody(session: IHTTPSession): String? {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return null

        return try {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = session.inputStream.read(buffer, read, contentLength - read)
                if (r == -1) break
                read += r
            }
            String(buffer, 0, read, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "readBody error", e)
            null
        }
    }

    private fun handleStaticContent(): Response {
        val html = loadHtmlFromResource(R.raw.index)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun loadHtmlFromResource(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        return inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val TAG = "SimpleServer"
        const val PORT = 34567
    }
}