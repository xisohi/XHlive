package com.lizongying.mytv0

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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
    private var hasUpdate = false // 新增：标记是否有更新

    /* ========== 权限和网络检查 ========== */
    private fun hasWritePermission(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        return connectivityManager?.activeNetworkInfo?.isConnected == true
    }

    /* ========== 获取下载目录 ========== */
    private fun getDownloadDirectory(): File {
        return if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
        } else {
            File(context.filesDir, "downloads")
        }.apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /* ========== 启动时清理APK文件 ========== */
    fun cleanupApkFilesOnStart() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadDir = getDownloadDirectory()
                val deletedFiles = cleanupDownloadDirectory(downloadDir, APK_NAME_PREFIX)

                if (deletedFiles.isNotEmpty()) {
                    Log.i(TAG, "Cleaned up ${deletedFiles.size} APK files on app start")
                    withContext(Dispatchers.Main) {
                        if (deletedFiles.size == 1) {
                            "已清理残留安装包".showToast()
                        } else {
                            "已清理 ${deletedFiles.size} 个残留安装包".showToast()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up APK files on start", e)
            }
        }
    }

    /* ========== 获取升级信息 ========== */
    private suspend fun getRelease(): ReleaseResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // 检查网络连接
                if (!isNetworkAvailable()) {
                    withContext(Dispatchers.Main) {
                        "网络不可用，请检查网络连接".showToast()
                    }
                    return@withContext null
                }

                val request = okhttp3.Request.Builder()
                    .url(VERSION_URL)
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "HTTP error: ${response.code()}")
                        return@withContext null
                    }
                    response.bodyAlias()?.let {
                        return@withContext gson.fromJson(it.string(), ReleaseResponse::class.java)
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "getRelease", e)
                withContext(Dispatchers.Main) {
                    "版本检查失败: ${e.message}".showToast()
                }
                null
            }
        }
    }

    /* ========== 主入口：检查并弹窗 ========== */
    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")

        // 检查存储权限
        if (!hasWritePermission()) {
            "无存储权限，无法下载更新".showToast()
            return
        }

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
                            // 使用正确的字段名：modifyContent（首字母小写）
                            if (!r.modifyContent.isNullOrBlank()) {
                                append("\n\n📋 升级内容：\n${r.modifyContent}")
                            }
                        }
                        update = true
                        hasUpdate = true // 设置标记为有更新
                    } else {
                        text = "已是最新版本，不需要更新"
                        hasUpdate = false // 设置标记为无更新
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred: ${e.message}", e)
                text = "版本检查异常: ${e.message}"
                hasUpdate = false
            }
            updateUI(text, update)
        }
    }

    /* ========== 弹窗 ========== */
    private fun updateUI(text: String, update: Boolean) {
        try {
            val dialog = ConfirmationFragment(this@UpdateManager, text, update)
            dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
        } catch (e: Exception) {
            Log.e(TAG, "Show dialog failed", e)
        }
    }

    /* ========== 下载相关 ========== */
    private fun startDownload(release: ReleaseResponse) {
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty()) {
            "下载信息不完整".showToast()
            return
        }

        // 再次检查权限和网络
        if (!hasWritePermission()) {
            "无存储权限，无法下载".showToast()
            return
        }

        if (!isNetworkAvailable()) {
            "网络不可用，请检查网络连接".showToast()
            return
        }

        val downloadDir = getDownloadDirectory()
        cleanupDownloadDirectory(downloadDir, APK_NAME_PREFIX)
        val file = File(downloadDir, release.apk_name)

        Log.i(TAG, "save dir $file")
        downloadJob = GlobalScope.launch(Dispatchers.IO) {
            downloadWithRetry(release.apk_url, file)
        }
    }

    private fun cleanupDownloadDirectory(directory: File?, apkNamePrefix: String): List<String> {
        val deletedFiles = mutableListOf<String>()
        directory?.listFiles()?.forEach {
            if (it.name.startsWith(apkNamePrefix) && it.name.endsWith(".apk")) {
                if (it.delete()) {
                    Log.i(TAG, "Deleted old APK: ${it.name}")
                    deletedFiles.add(it.name)
                } else {
                    Log.e(TAG, "Failed to delete old APK: ${it.name}")
                }
            }
        }
        return deletedFiles
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
                    withContext(Dispatchers.Main) {
                        "下载失败，${30 - (retries * 10)}秒后重试 ($retries/$maxRetries)".showToast()
                    }
                    delay(30000L - (retries * 10000L)) // 递减重试间隔
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
        if (!response.isSuccessful) throw IOException("Unexpected code ${response.code()}")

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

        withContext(Dispatchers.Main) {
            "下载完成，开始安装".showToast()
            installNewVersion(file)
        }
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
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist!")
            "安装文件不存在".showToast()
            return
        }

        try {
            val apkUri = Uri.fromFile(apkFile)
            Log.i(TAG, "apkUri $apkUri")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            // 启动安装后清理任务（可选）
            startPostInstallCleanup(apkFile)

        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            "安装失败，请检查是否允许安装未知来源应用".showToast()
        }
    }

    /* ========== 安装后清理（备用方案） ========== */
    private fun startPostInstallCleanup(apkFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            // 等待一段时间后检查并删除APK（如果用户回到应用）
            delay(POST_INSTALL_CLEANUP_DELAY)

            if (apkFile.exists()) {
                Log.i(TAG, "Performing post-install cleanup for: ${apkFile.name}")
                if (apkFile.delete()) {
                    Log.i(TAG, "Post-install cleanup successful: ${apkFile.name}")
                } else {
                    Log.w(TAG, "Post-install cleanup failed, will clean on next app start: ${apkFile.name}")
                }
            }
        }
    }

    /* ========== 手动清理APK文件 ========== */
    fun cleanupApkFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadDir = getDownloadDirectory()
                val deletedFiles = cleanupDownloadDirectory(downloadDir, APK_NAME_PREFIX)

                withContext(Dispatchers.Main) {
                    if (deletedFiles.isNotEmpty()) {
                        "已清理 ${deletedFiles.size} 个安装包文件".showToast()
                    } else {
                        "没有需要清理的安装包".showToast()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up APK files", e)
                withContext(Dispatchers.Main) {
                    "清理安装包失败".showToast()
                }
            }
        }
    }

    /* ========== 接口回调 ========== */
    override fun onConfirm() {
        // 只有在有更新的情况下才下载
        if (hasUpdate) {
            release?.let { startDownload(it) }
        } else {
            // 没有更新时，点击确认只是关闭对话框
            Log.i(TAG, "User confirmed no update available")
        }
    }

    override fun onCancel() {
        Log.i(TAG, "User canceled update")
    }

    fun destroy() {
        downloadJob?.cancel()
        Log.i(TAG, "UpdateManager destroyed")
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192
        private const val VERSION_URL =
            "https://xhys.lcjly.cn/update/XHlive-kitkat.json"

        // APK文件相关常量
        private const val APK_NAME_PREFIX = "XHlive-kitkat"
        private const val POST_INSTALL_CLEANUP_DELAY = 60000L // 60秒后清理
    }
}