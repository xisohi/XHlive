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
    private var context: Context,
    private var versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private var isDownloading = false
    private var downloadReceiver: DownloadReceiver? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    var release: ReleaseResponse? = null

    private var currentProxyAttempt = 0
    private val maxRetryCount = 3
    private var lastReportedProgress = -1

    companion object {
        private const val TAG = "UpdateManager"
        private const val APK_FILE_NAME = "XHlive.apk"
        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        return connectivityManager?.activeNetworkInfo?.isConnected == true
    }

    private suspend fun probeUrl(url: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "探测URL: $url")
                val req = OkHttpRequest.Builder().url(url).head().build()
                val rsp = HttpClient.okHttpClient.newCall(req).execute()
                rsp.code
            } catch (e: Exception) {
                Log.e(TAG, "URL探测失败: ${e.message}", e)
                -1
            }
        }
    }

    private suspend fun getReleaseWithProxy(): ReleaseResponse? {
        Github.resetProxy()
        currentProxyAttempt = 0
        return tryGetReleaseWithRetry()
    }

    private suspend fun tryGetReleaseWithRetry(): ReleaseResponse? {
        var attempt = 0
        while (attempt < maxRetryCount) {
            try {
                Log.d(TAG, "尝试获取版本信息，代理: ${Github.getCurrentProxy()}, 尝试次数: ${attempt + 1}")

                val versionUrl = Github.getVersionUrl()
                Log.d(TAG, "请求URL: $versionUrl")

                val request = OkHttpRequest.Builder()
                    .url(versionUrl)
                    .get()
                    .build()

                val response = HttpClient.okHttpClient.newCall(request).execute()
                val code = response.code

                if (code == 200) {
                    val jsonString = response.body?.string()
                    if (!jsonString.isNullOrEmpty()) {
                        try {
                            val releaseResponse = gson.fromJson(jsonString, ReleaseResponse::class.java)
                            Log.i(TAG, "版本信息获取成功")
                            return releaseResponse
                        } catch (e: Exception) {
                            Log.e(TAG, "JSON解析失败: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "请求异常: ${e.message}", e)
            }

            attempt++
            if (attempt < maxRetryCount) {
                Github.switchToNextProxy()
                delay(1000)
            }
        }
        return null
    }

    fun checkAndUpdate() {
        Log.i(TAG, "開始檢查更新")

        if (!isNetworkAvailable()) {
            Toast.makeText(context, "網絡不可用", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false

            try {
                val deferred = CoroutineScope(Dispatchers.IO).async { getReleaseWithProxy() }
                release = deferred.await()
                val r = release
                if (r != null) {
                    if (r.version_code != null && r.version_code > versionCode) {
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
                text = "检查更新时发生错误：${e.message}"
                Log.e(TAG, "检查更新错误: ${e.message}", e)
            }

            if (update) {
                updateUI(text, update)
            } else {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUI(text: String, update: Boolean) {
        try {
            if (context is FragmentActivity && !(context as FragmentActivity).isFinishing) {
                val dialog = ConfirmationFragment(this@UpdateManager, text, update)
                dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
            } else {
                Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示更新对话框失败: ${e.message}", e)
            Toast.makeText(context.applicationContext, "显示更新界面失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDownload(release: ReleaseResponse) {
        if (isDownloading) {
            Toast.makeText(context, "已在下载中", Toast.LENGTH_SHORT).show()
            return
        }

        if (release.version_name.isNullOrEmpty()) {
            Toast.makeText(context, "版本名称无效", Toast.LENGTH_SHORT).show()
            return
        }

        Github.resetProxy()
        currentProxyAttempt = 0
        attemptDownload(release)
    }

    private fun attemptDownload(release: ReleaseResponse) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val downloadUrl = Github.getApkUrl()
                Log.i(TAG, "开始下载: $downloadUrl")

                val code = probeUrl(downloadUrl)
                if (code != 200) {
                    if (currentProxyAttempt < maxRetryCount - 1) {
                        currentProxyAttempt++
                        Github.switchToNextProxy()
                        Toast.makeText(context, "切换代理重试...", Toast.LENGTH_SHORT).show()
                        delay(1000)
                        attemptDownload(release)
                        return@launch
                    } else {
                        Toast.makeText(context, "下载链接失效", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }

                // ✅ 关键修改：Android 5.1.1 使用 OkHttp 直接下载，更可靠
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // API 22 及以下使用 OkHttp 直接下载
                    downloadWithOkHttp(downloadUrl, APK_FILE_NAME, release.version_name)
                } else {
                    // API 23+ 使用 DownloadManager
                    enqueueDownload(downloadUrl, APK_FILE_NAME, release.version_name)
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载探测异常: ${e.message}", e)
                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * ✅ 关键修复：Android 5.1.1 使用 OkHttp 直接下载到应用私有目录
     */
    private fun downloadWithOkHttp(downloadUrl: String, apkFileName: String, versionName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                isDownloading = true
                lastReportedProgress = -1

                // 获取应用私有下载目录
                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads").apply { mkdirs() }

                val apkFile = File(downloadDir, apkFileName)

                // 清理旧文件
                if (apkFile.exists()) apkFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "开始下载更新...", Toast.LENGTH_SHORT).show()
                }

                Log.i(TAG, "使用 OkHttp 直接下载到: ${apkFile.absolutePath}")

                val request = OkHttpRequest.Builder()
                    .url(downloadUrl)
                    .build()

                val response = HttpClient.okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP错误: ${response.code}")
                }

                val body = response.body ?: throw Exception("响应体为空")
                val totalLength = body.contentLength()

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            // 计算进度
                            if (totalLength > 0) {
                                val progress = (downloaded * 100 / totalLength).toInt()
                                if (progress % 10 == 0 && progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "下载进度: $progress%", Toast.LENGTH_SHORT).show()
                                        Log.i(TAG, "下载进度: $progress%")
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                Log.i(TAG, "下载完成: ${apkFile.absolutePath}, 大小: ${apkFile.length()}")

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载完成，准备安装", Toast.LENGTH_SHORT).show()
                    installNewVersion(apkFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "OkHttp 下载失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                    isDownloading = false
                }

                // 尝试切换代理重试
                if (currentProxyAttempt < maxRetryCount - 1) {
                    currentProxyAttempt++
                    Github.switchToNextProxy()
                    delay(1000)
                    attemptDownload(release!!)
                }
            }
        }
    }

    /**
     * API 23+ 使用 DownloadManager（保留原实现）
     */
    private fun enqueueDownload(downloadUrl: String, apkFileName: String, versionName: String) {
        try {
            isDownloading = true
            lastReportedProgress = -1

            cleanupOldApkFile(apkFileName)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val request = DownloadManager.Request(Uri.parse(downloadUrl))

            // API 23+ 使用公共下载目录
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )

            request.setTitle("${context.getString(R.string.app_name)} $versionName")
            request.setDescription("正在下载新版本")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverRoaming(false)
            request.setMimeType(MIME_TYPE_APK)
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)

            val downloadId = dm.enqueue(request)
            Log.i(TAG, "下载任务创建成功，ID: $downloadId")

            Toast.makeText(context, "开始下载更新...", Toast.LENGTH_SHORT).show()

            downloadReceiver = DownloadReceiver(
                WeakReference(context),
                apkFileName,
                downloadId
            )

            registerDownloadReceiver()
            startProgressPolling(downloadId)

        } catch (e: Exception) {
            Log.e(TAG, "创建下载任务失败: ${e.message}", e)
            Toast.makeText(context, "创建下载任务失败: ${e.message}", Toast.LENGTH_SHORT).show()
            isDownloading = false
        }
    }

    private fun cleanupOldApkFile(apkFileName: String) {
        try {
            // 清理应用私有目录
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val oldFile = File(downloadDir, apkFileName)
            if (oldFile.exists()) oldFile.delete()

            // 清理公共目录
            val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val publicOldFile = File(publicDownloadDir, apkFileName)
            if (publicOldFile.exists()) publicOldFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "清理旧文件失败: ${e.message}")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = downloadReceiver ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            Log.d(TAG, "广播接收器注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "注册广播接收器失败: ${e.message}", e)
        }
    }

    private fun startProgressPolling(downloadId: Long) {
        progressHandler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (!isDownloading) return

                queryDownloadProgress(downloadId) { status, progress ->
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.i(TAG, "轮询检测到下载完成")
                            isDownloading = false
                            onDownloadComplete()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            Log.e(TAG, "轮询检测到下载失败")
                            isDownloading = false
                            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            if (progress in 0..99 && progress % 10 == 0 && progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                Toast.makeText(context, "下载进度: $progress%", Toast.LENGTH_SHORT).show()
                            }
                            progressHandler?.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
                        }
                    }
                }
            }
        }

        progressRunnable = runnable
        progressHandler?.post(runnable)
    }

    private fun queryDownloadProgress(downloadId: Long, callback: (Int, Int) -> Unit) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor: Cursor? = dm.query(query)

            cursor?.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    val total = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

                    val progress = if (total > 0) {
                        (downloaded * 100L / total).toInt()
                    } else 0

                    callback(status, progress)
                } else {
                    callback(DownloadManager.STATUS_FAILED, -1)
                }
            } ?: callback(DownloadManager.STATUS_FAILED, -1)
        } catch (e: Exception) {
            Log.e(TAG, "查询下载进度失败: ${e.message}")
            callback(DownloadManager.STATUS_FAILED, -1)
        }
    }

    private fun onDownloadComplete() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }

        // 从公共目录获取文件
        val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkFile = File(publicDownloadDir, APK_FILE_NAME)

        if (apkFile.exists()) {
            installNewVersion(apkFile)
        } else {
            Toast.makeText(context, "APK文件不存在", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ✅ 关键修复：统一使用传入的 File 对象安装
     */
    private fun installNewVersion(apkFile: File) {
        try {
            Log.i(TAG, "准备安装: ${apkFile.absolutePath}")
            Log.i(TAG, "文件存在: ${apkFile.exists()}, 大小: ${apkFile.length()}")

            if (!apkFile.exists()) {
                Toast.makeText(context, "APK文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // API 24+ 使用 FileProvider
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )
                intent.setDataAndType(apkUri, MIME_TYPE_APK)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Log.i(TAG, "使用 FileProvider: $apkUri")
            } else {
                // ✅ Android 5.1.1 (API 22) 使用 file:// URI
                // 需要确保文件路径在应用私有目录，或者使用 chmod 修改权限
                val apkUri = Uri.fromFile(apkFile)
                intent.setDataAndType(apkUri, MIME_TYPE_APK)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // ✅ 关键：尝试修改文件权限，让系统安装器可以读取
                try {
                    Runtime.getRuntime().exec("chmod 644 ${apkFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "修改文件权限失败: ${e.message}")
                }

                Log.i(TAG, "使用 file:// URI: $apkUri")
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.i(TAG, "启动安装程序")
                Toast.makeText(context, "正在启动安装...", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "未找到安装程序")
                Toast.makeText(context, "未找到安装程序", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "安装失败: ${e.message}", e)
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private inner class DownloadReceiver(
        private val contextRef: WeakReference<Context>,
        private val apkFileName: String,
        private val downloadId: Long
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val ref = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (ref != downloadId) return

            Log.i(TAG, "收到下载完成广播")

            val strongContext = contextRef.get() ?: return

            val dm = strongContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)

            cursor?.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.i(TAG, "广播：下载成功")
                            isDownloading = false
                            progressRunnable?.let { runnable ->
                                progressHandler?.removeCallbacks(runnable)
                            }
                            onDownloadComplete()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            val reason = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_REASON))
                            Log.e(TAG, "广播：下载失败，原因: $reason")
                            Toast.makeText(strongContext, "下载失败 (代码: $reason)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onConfirm() {
        Log.d(TAG, "用户确认更新")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
        Log.d(TAG, "用户取消更新")
    }

    fun destroy() {
        Log.d(TAG, "销毁 UpdateManager")

        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "取消注册广播失败: ${e.message}")
            }
        }

        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressHandler = null

        isDownloading = false
    }
}