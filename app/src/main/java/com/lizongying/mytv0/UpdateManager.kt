package com.lizongying.mytv0

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateManager(
    private val context: Context,
    private val versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private var downloadReceiver: DownloadReceiver? = null
    private var release: ReleaseResponse? = null

    // 添加FileProvider路径资源ID
    companion object {
        private const val TAG = "UpdateManager"
        private const val VERSION_URL = "https://xhys.lcjly.cn/update/XHlive.json"
        private const val FILE_PROVIDER_AUTHORITY = ".fileprovider" // 需要与manifest中配置一致
    }

    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false
            try {
                release = getRelease()
                release?.let {
                    Log.i(TAG, "Local version: $versionCode, Remote version: ${it.version_code}")
                    if (it.version_code > versionCode) { // 仅当服务器版本更高时提示更新
                        text = "发现新版本：${it.version_name}"
                        update = true
                    } else {
                        text = "已是最新版本（v${it.version_name}）"
                    }
                } ?: run {
                    text = "获取版本信息失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking update: ${e.message}")
                text = "检查更新失败：${e.message}"
            }
            updateUI(text, update)
        }
    }

    private suspend fun getRelease(): ReleaseResponse? {
        return try {
            val urls = getUrls(VERSION_URL)
            if (urls.isEmpty()) return null

            urls.firstNotNullOfOrNull { url ->
                withContext(Dispatchers.IO) {
                    try {
                        val request = okhttp3.Request.Builder().url(url).build()
                        HttpClient.okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                response.body?.string()?.let {
                                    gson.fromJson(it, ReleaseResponse::class.java)
                                }
                            } else {
                                Log.e(TAG, "Request failed: ${response.code}")
                                null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching $url: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRelease error: ${e.message}")
            null
        }
    }

    private fun updateUI(text: String, showUpdateButton: Boolean) {
        (context as? FragmentActivity)?.let {
            ConfirmationFragment(this, text, showUpdateButton).show(
                it.supportFragmentManager,
                TAG
            )
        }
    }

    override fun onConfirm() {
        release?.let {
            if (it.apk_name.isNullOrEmpty() || it.apk_url.isNullOrEmpty()) {
                Toast.makeText(context, "无效的下载地址", Toast.LENGTH_SHORT).show()
                return
            }
            startDownload(it)
        }
    }

    private fun startDownload(release: ReleaseResponse) {
        // 清理之前的接收器
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered")
            }
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = Request(Uri.parse(release.apk_url)).apply {
            // 设置下载参数
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            dir?.mkdirs()
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                release.apk_name
            )
            setTitle("${context.getString(R.string.app_name)} ${release.version_name}")
            setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverRoaming(false)
            setMimeType("application/vnd.android.package-archive")

            // 可选：仅允许WiFi下载
            setAllowedNetworkTypes(Request.NETWORK_WIFI)
        }

        val downloadId = downloadManager.enqueue(request)
        Log.i(TAG, "Download started with ID: $downloadId")

        // 注册接收器和进度监控（保持原有实现）
        downloadReceiver = DownloadReceiver(context, release.apk_name, downloadId).apply {
            context.registerReceiver(
                this,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
        trackDownloadProgress(downloadId)
    }

    private fun trackDownloadProgress(downloadId: Long) {
        val handler = Handler(Looper.getMainLooper())
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        handler.post(object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        if (statusIndex != -1 && bytesDownloadedIndex != -1 && totalSizeIndex != -1) {
                            when (cursor.getInt(statusIndex)) {
                                DownloadManager.STATUS_RUNNING -> {
                                    val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                                    val totalSize = cursor.getLong(totalSizeIndex)
                                    if (totalSize > 0) {
                                        val progress = (bytesDownloaded * 100 / totalSize).toInt()
                                        Log.d(TAG, "Download progress: $progress%")
                                    }
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    Log.i(TAG, "Download completed successfully")
                                    return
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    Log.e(TAG, "Download failed")
                                    return
                                }
                            }
                        } else {
                            Log.e(TAG, "One or more columns are missing in the cursor")
                        }
                    }
                }
                handler.postDelayed(this, 1000)
            }
        })
    }
    private inner class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadId: Long
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (reference != downloadId) return

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex != -1) {
                        when (cursor.getInt(statusIndex)) {
                            DownloadManager.STATUS_SUCCESSFUL -> installApk()
                            else -> {
                                Log.e(TAG, "Download failed")
                                Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Log.e(TAG, "COLUMN_STATUS column is missing in the cursor")
                    }
                }
            }
        }

        private fun installApk() {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )

            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
                Toast.makeText(context, "安装文件丢失", Toast.LENGTH_SHORT).show()
                return
            }

            // Handle unknown sources permission for Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    return
                }
            }

            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}$FILE_PROVIDER_AUTHORITY",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        }
    }

    fun destroy() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered")
            }
        }
    }

    override fun onCancel() {
        Log.d(TAG, "User canceled update")
    }
}