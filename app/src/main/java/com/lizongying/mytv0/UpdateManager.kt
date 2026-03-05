package com.lizongying.mytv0

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.*
import okhttp3.Request as OkHttpRequest
import java.io.File
import java.lang.ref.WeakReference

class UpdateManager(
    private val context: Context,
    private val versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private var isDownloading = false
    private var downloadReceiver: BroadcastReceiver? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    var release: ReleaseResponse? = null

    private var proxyAttempt = 0
    private var lastProgress = -1

    companion object {
        private const val TAG = "UpdateManager"
        private const val APK_NAME = "XHlive.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val POLL_INTERVAL = 1000L
        private const val MAX_RETRY = 3
    }

    fun checkAndUpdate() {
        if (!isNetworkAvailable()) {
            toast("网络不可用")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val (text, hasUpdate) = try {
                val r = withContext(Dispatchers.IO) { fetchRelease() }
                release = r
                when {
                    r == null -> "无法获取版本信息" to false
                    r.version_code > versionCode ->
                        "发现新版本：${r.version_name}\n\n📋 升级内容：\n${r.modifyContent.orEmpty()}" to true
                    else -> "已是最新版本" to false
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
                "检查更新失败：${e.message}" to false
            }

            when {
                hasUpdate -> showUpdateDialog(text)
                else -> toast(text)
            }
        }
    }

    override fun onConfirm() {
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
        Log.d(TAG, "用户取消更新")
    }

    fun destroy() {
        downloadReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        isDownloading = false
    }

    // ==================== 私有方法 ====================

    private fun isNetworkAvailable() =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?.activeNetworkInfo?.isConnected == true

    private fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private suspend fun fetchRelease(): ReleaseResponse? {
        Github.resetProxy()
        repeat(MAX_RETRY) { attempt ->
            try {
                val response = HttpClient.okHttpClient.newCall(
                    OkHttpRequest.Builder().url(Github.getVersionUrl()).build()
                ).execute()

                if (response.isSuccessful) {
                    return response.body?.string()?.let { gson.fromJson(it, ReleaseResponse::class.java) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取版本失败 (尝试${attempt + 1}/$MAX_RETRY): ${e.message}")
            }
            if (attempt < MAX_RETRY - 1) {
                Github.switchToNextProxy()
                delay(1000)
            }
        }
        return null
    }

    private fun showUpdateDialog(text: String) {
        try {
            (context as? FragmentActivity)?.takeIf { !it.isFinishing }?.let {
                ConfirmationFragment(this, text, true).show(it.supportFragmentManager, TAG)
            } ?: toast(text)
        } catch (e: Exception) {
            toast("显示对话框失败")
        }
    }

    private fun startDownload(release: ReleaseResponse) {
        if (isDownloading) {
            toast("已在下载中")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val url = Github.getApkUrl()

            // 探测URL可用性
            val code = withContext(Dispatchers.IO) {
                try {
                    HttpClient.okHttpClient.newCall(
                        OkHttpRequest.Builder().url(url).head().build()
                    ).execute().code
                } catch (_: Exception) { -1 }
            }

            if (code != 200) {
                handleDownloadError("下载链接不可用 (HTTP $code)")
                return@launch
            }

            // API 22及以下用OkHttp直接下载，更可靠
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                downloadWithOkHttp(url)
            } else {
                downloadWithManager(url, release.version_name.orEmpty())
            }
        }
    }

    private suspend fun downloadWithOkHttp(url: String) {
        isDownloading = true
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)

        try {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.delete()

                val response = HttpClient.okHttpClient.newCall(
                    OkHttpRequest.Builder().url(url).build()
                ).execute()

                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

                val total = response.body?.contentLength() ?: -1
                var downloaded = 0L

                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            reportProgress(total, downloaded)
                        }
                    }
                }
            }

            toast("下载完成")
            installApk(file)
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            handleDownloadError("下载失败: ${e.message}")
        } finally {
            isDownloading = false
        }
    }

    private suspend fun reportProgress(total: Long, downloaded: Long) {
        if (total <= 0) return
        val progress = (downloaded * 100 / total).toInt()
        if (progress % 10 == 0 && progress != lastProgress) {
            lastProgress = progress
            withContext(Dispatchers.Main) { toast("下载进度: $progress%") }
        }
    }

    private fun downloadWithManager(url: String, versionName: String) {
        isDownloading = true
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            setTitle("${context.getString(R.string.app_name)} $versionName")
            setDescription("正在下载新版本")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType(APK_MIME)
        }

        val downloadId = dm.enqueue(request)
        toast("开始下载更新...")

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return

                dm.query(DownloadManager.Query().setFilterById(downloadId))?.use {
                    if (it.moveToFirst() && it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                        onDownloadComplete()
                    } else {
                        toast("下载失败")
                        isDownloading = false
                    }
                }
            }
        }.also { registerReceiver(it) }

        // 进度轮询
        progressHandler = Handler(Looper.getMainLooper())
        progressRunnable = object : Runnable {
            override fun run() {
                if (!isDownloading) return
                queryProgress(dm, downloadId)?.let { (status, progress) ->
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> onDownloadComplete()
                        DownloadManager.STATUS_FAILED -> {
                            toast("下载失败")
                            isDownloading = false
                        }
                        else -> {
                            if (progress in 0..99 && progress % 10 == 0 && progress != lastProgress) {
                                lastProgress = progress
                                toast("下载进度: $progress%")
                            }
                            progressHandler?.postDelayed(this, POLL_INTERVAL)
                        }
                    }
                }
            }
        }.also { progressHandler?.post(it) }
    }

    private fun queryProgress(dm: DownloadManager, id: Long): Pair<Int, Int>? {
        return try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    val total = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    status to if (total > 0) (done * 100 / total).toInt() else 0
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun onDownloadComplete() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        isDownloading = false

        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        if (file.exists()) installApk(file) else toast("APK文件不存在")
    }

    private fun installApk(file: File) {
        try {
            Log.i(TAG, "安装APK: ${file.absolutePath}")

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else {
                // API 22: 修改权限并返回file:// URI
                try { Runtime.getRuntime().exec("chmod 644 ${file.absolutePath}") } catch (_: Exception) {}
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                toast("未找到安装程序")
            }
        } catch (e: Exception) {
            Log.e(TAG, "安装失败", e)
            toast("安装失败: ${e.message}")
        }
    }

    private suspend fun handleDownloadError(msg: String) {
        toast(msg)
        if (++proxyAttempt < MAX_RETRY) {
            Github.switchToNextProxy()
            delay(1000)
            release?.let { startDownload(it) }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver(receiver: BroadcastReceiver) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册广播失败", e)
        }
    }
}