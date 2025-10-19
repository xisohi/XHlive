package com.lizongying.mytv0

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class UpdateManager(
    private val context: Context,
    private val versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private val okHttpClient = HttpClient.okHttpClient
    private var downloadJob: Job? = null
    private var lastLoggedProgress = -1

    private var release: ReleaseResponse? = null

    /* ========== 获取升级信息 ========== */
    private suspend fun getRelease(): ReleaseResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(VERSION_URL)
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.bodyAlias()?.let {
                        return@withContext gson.fromJson(it.string(), ReleaseResponse::class.java)
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "getRelease", e)
                null
            }
        }
    }

    /* ========== 主入口：检查并弹窗 ========== */
    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false
            try {
                release = getRelease()
                Log.i(TAG, "versionCode $versionCode ${release?.version_code}")
                val r = release
                if (r?.version_code != null) {
                    if (r.version_code > versionCode) {
                        text = buildString {
                            append("发现新版本：${r.version_name}")
                            if (!r.modifyContent.isNullOrBlank()) {
                                append("\n\n📋 升级内容：\n${r.modifyContent}")
                            }
                        }
                        update = true
                    } else {
                        text = "已是最新版本，不需要更新"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred: ${e.message}", e)
            }
            updateUI(text, update)
        }
    }

    /* ========== 弹窗 ========== */
    private fun updateUI(text: String, update: Boolean) {
        val dialog = ConfirmationFragment(this@UpdateManager, text, update)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    /* ========== 下载相关 ========== */
    private fun startDownload(release: ReleaseResponse) {
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty()) return

        var downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir == null) downloadDir = File(context.filesDir, "downloads")
        cleanupDownloadDirectory(downloadDir, release.apk_name)
        val file = File(downloadDir, release.apk_name)
        file.parentFile?.mkdirs()
        Log.i(TAG, "save dir $file")
        downloadJob = GlobalScope.launch(Dispatchers.IO) {
            downloadWithRetry(release.apk_url, file)
        }
    }

    private fun cleanupDownloadDirectory(directory: File?, apkNamePrefix: String) {
        directory?.listFiles()?.forEach {
            if (it.name.startsWith(apkNamePrefix) && it.name.endsWith(".apk")) {
                if (it.delete()) Log.i(TAG, "Deleted old APK: ${it.name}")
                else Log.e(TAG, "Failed to delete old APK: ${it.name}")
            }
        }
    }

    private suspend fun downloadWithRetry(url: String, file: File, maxRetries: Int = 3) {
        var retries = 0
        while (retries < maxRetries) {
            try {
                downloadFile(url, file)
                break
            } catch (e: IOException) {
                Log.e(TAG, "Download failed: ${e.message}")
                retries++
                if (retries >= maxRetries) {
                    withContext(Dispatchers.Main) {
                        updateUI("下载失败，请检查网络连接后重试", false)
                    }
                } else {
                    Log.i(TAG, "Retrying download ($retries/$maxRetries)")
                    delay(30000)
                }
            }
        }
    }

    private suspend fun downloadFile(url: String, file: File) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.android.package-archive")
            .build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        val body = response.bodyAlias() ?: throw IOException("Null response body")
        val contentLength = body.contentLength()
        var bytesRead = 0L

        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    bytesRead += bytes
                    val progress =
                        if (contentLength > 0) (bytesRead * 100 / contentLength).toInt() else -1
                    withContext(Dispatchers.Main) { updateDownloadProgress(progress) }
                }
            }
        }

        withContext(Dispatchers.Main) { installNewVersion(file) }
    }

    private fun updateDownloadProgress(progress: Int) {
        if (progress == -1) {
            Log.i(TAG, "Download in progress, size unknown")
        } else if (progress % 10 == 0 && progress != lastLoggedProgress) {
            lastLoggedProgress = progress
            Log.i(TAG, "Download progress: $progress%")
            "升级文件已经下载：${progress}%".showToast()
        }
    }

    private fun installNewVersion(apkFile: File) {
        if (apkFile.exists()) {
            val apkUri = Uri.fromFile(apkFile)
            Log.i(TAG, "apkUri $apkUri")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            Log.e(TAG, "APK file does not exist!")
        }
    }

    /* ========== 接口回调 ========== */
    override fun onConfirm() {
        release?.let { startDownload(it) }
    }

    override fun onCancel() {}

    fun destroy() {
        downloadJob?.cancel()
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192
        private const val VERSION_URL =
            "https://xhys.lcjly.cn/update/XHlive-kitkat.json"
    }
}