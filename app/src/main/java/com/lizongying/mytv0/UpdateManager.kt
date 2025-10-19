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
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.core.content.FileProvider
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import okhttp3.Request as OkHttpRequest

class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private var downloadReceiver: DownloadReceiver? = null
    var release: ReleaseResponse? = null

    /* ------------------------------------------------ */
    /*  网络请求：获取升级信息                           */
    /* ------------------------------------------------ */
    private suspend fun getRelease(): ReleaseResponse? {
        val urls = getUrls(VERSION_URL)
        for (u in urls) {
            try {
                return withContext(Dispatchers.IO) {
                    val request = OkHttpRequest.Builder().url(u).build()
                    HttpClient.okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        response.body?.string()?.let { json ->
                            gson.fromJson(json, ReleaseResponse::class.java)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getRelease $u failed", e)
            }
        }
        return null
    }

    /* ------------------------------------------------ */
    /*  主入口：检查 + 弹窗                             */
    /* ------------------------------------------------ */
    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false
            try {
                val deferredRelease = CoroutineScope(Dispatchers.IO).async {
                    getRelease()
                }
                release = deferredRelease.await()
                Log.i(TAG, "release object: $release")
                Log.i(TAG, "versionCode $versionCode ${release?.version_code}")

                val r = release
                if (r != null && r.version_code != null) {
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
                } else {
                    text = "无法获取最新版本信息"
                    Log.e(TAG, "release is null or version_code is null")
                }
            } catch (e: Exception) {
                text = "检查更新时发生错误：${e.message}"
                Log.e(TAG, "Error occurred: ${e.message}", e)
            }
            updateUI(text, update)
        }
    }

    /* ------------------------------------------------ */
    /*  弹窗                                            */
    /* ------------------------------------------------ */
    private fun updateUI(text: String, update: Boolean) {
        val dialog = ConfirmationFragment(this@UpdateManager, text, update)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    /* ------------------------------------------------ */
    /*  DownloadManager 下载 + 进度轮询 + 安装          */
    /* ------------------------------------------------ */
    private fun startDownload(release: ReleaseResponse) {
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty()) {
            Log.e(TAG, "APK 名称或 URL 为空")
            return
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = Request(Uri.parse(release.apk_url))
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            release.apk_name
        )
        request.setTitle("${context.getString(R.string.app_name)} ${release.version_name}")
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setAllowedOverRoaming(false)
        request.setMimeType("application/vnd.android.package-archive")

        val downloadId = downloadManager.enqueue(request)
        downloadReceiver = DownloadReceiver(context, release.apk_name, downloadId)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, intentFilter)
        }

        getDownloadProgress(context, downloadId) { progress ->
            Log.i(TAG, "Download progress: $progress%")
        }
    }

    private fun getDownloadProgress(
        context: Context,
        downloadId: Long,
        progressListener: (Int) -> Unit
    ) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val handler = Handler(Looper.getMainLooper())
        val interval: Long = 1000

        handler.post(object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val down =
                            it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val total =
                            it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        if (total >= 0 && down >= 0) {
                            val progress =
                                (it.getInt(down) * 100L / it.getInt(total)).toInt()
                            progressListener(progress)
                            if (progress == 100) return
                        }
                    }
                }
                handler.postDelayed(this, interval)
            }
        })
    }

    /* ------------------------------------------------ */
    /*  广播接收器：下载完成 -> 安装                     */
    /* ------------------------------------------------ */
    private class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadId: Long
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val ref = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (ref != downloadId) return

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)
            cursor?.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> installNewVersion()
                        DownloadManager.STATUS_FAILED -> {
                            Toast.makeText(context, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        private fun installNewVersion() {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            if (!apkFile.exists()) {
                Toast.makeText(context, "APK 文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /* ------------------------------------------------ */
    /*   companion & 回调                              */
    /* ------------------------------------------------ */
    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192
        private const val VERSION_URL = "https://xhys.lcjly.cn/update/XHlive.json"
    }

    override fun onConfirm() {
        release?.let { startDownload(it) }
    }

    override fun onCancel() {}

    fun destroy() {
        downloadReceiver?.let {
            context.unregisterReceiver(it)
            downloadReceiver = null
        }
    }
}