package com.lizongying.mytv0

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.DownloadManager.Request
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
import com.lizongying.mytv0.Github
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

    // 当前尝试的代理索引
    private var currentProxyAttempt = 0
    // 最大重试次数（包括代理切换）
    private val maxRetryCount = 3
    // 上次报告的进度，避免重复 Toast
    private var lastReportedProgress = -1

    companion object {
        private const val TAG = "UpdateManager"
        // APK文件名 - 固定为 XHLIVE.apk
        private const val APK_FILE_NAME = "XHLIVE.apk"
        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"
        private const val PROGRESS_UPDATE_INTERVAL = 1000L // 1秒
    }

    /* ------------------------------------------------ */
    /*  网络状态检查                                    */
    /* ------------------------------------------------ */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        return connectivityManager?.activeNetworkInfo?.isConnected == true
    }

    /* ------------------------------------------------ */
    /*  网络探测：HEAD 请求快速检查 URL 是否有效         */
    /* ------------------------------------------------ */
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

    /* ------------------------------------------------ */
    /*  获取版本信息（带重试机制和代理切换）            */
    /* ------------------------------------------------ */
    private suspend fun getReleaseWithProxy(): ReleaseResponse? {
        // 每次检查更新前重置代理
        Github.resetProxy()
        currentProxyAttempt = 0

        return tryGetReleaseWithRetry()
    }

    private suspend fun tryGetReleaseWithRetry(): ReleaseResponse? {
        var attempt = 0
        while (attempt < maxRetryCount) {
            try {
                Log.d(TAG, "尝试获取版本信息，代理: ${Github.getCurrentProxy()}, 尝试次数: ${attempt + 1}")

                // 统一使用 Github.getVersionUrl() 获取带代理的URL
                val versionUrl = Github.getVersionUrl()
                Log.d(TAG, "请求URL: $versionUrl")

                val request = OkHttpRequest.Builder()
                    .url(versionUrl)
                    .get()
                    .build()

                Log.d(TAG, "请求头: ${request.headers}")

                val response = HttpClient.okHttpClient.newCall(request).execute()
                val code = response.code

                Log.d(TAG, "HTTP响应码: $code")
                Log.d(TAG, "响应头: ${response.headers}")

                if (code == 200) {
                    val jsonString = response.body?.string()

                    if (!jsonString.isNullOrEmpty()) {
                        Log.d(TAG, "获取到JSON响应: $jsonString")

                        try {
                            val releaseResponse = gson.fromJson(jsonString, ReleaseResponse::class.java)
                            Log.i(TAG, "版本信息获取成功，代理: ${Github.getCurrentProxy()}, " +
                                    "version_code=${releaseResponse.version_code}, " +
                                    "version_name=${releaseResponse.version_name}")
                            return releaseResponse
                        } catch (e: Exception) {
                            Log.e(TAG, "JSON解析失败: ${e.message}", e)
                        }
                    } else {
                        Log.e(TAG, "响应体为空")
                    }
                } else {
                    Log.w(TAG, "HTTP错误: $code，代理: ${Github.getCurrentProxy()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "请求异常: ${e.message}，代理: ${Github.getCurrentProxy()}", e)
            }

            // 切换代理并重试
            attempt++
            if (attempt < maxRetryCount) {
                Github.switchToNextProxy()
                Log.w(TAG, "第${attempt}次重试，延迟1秒...")
                delay(1000) // 延迟1秒后重试
            }
        }

        Log.e(TAG, "所有代理尝试失败")
        return null
    }

    /* ------------------------------------------------ */
    /*  主入口：检查 + 弹窗                              */
    /* ------------------------------------------------ */
    fun checkAndUpdate() {
        System.out.println("UpdateManager: 進入 checkAndUpdate 方法")
        Log.i(TAG, "開始檢查更新，代理狀態: ${Github.getProxyStatus()}")

        if (!isNetworkAvailable()) {
            System.out.println("UpdateManager: 網絡不可用")
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
                    Log.d(TAG, "版本信息: version_code=${r.version_code}, version_name=${r.version_name}")

                    if (r.version_code != null && r.version_code > versionCode) {
                        text = buildString {
                            append("发现新版本：${r.version_name}")
                            if (!r.modifyContent.isNullOrBlank()) {
                                append("\n\n📋 升级内容：\n${r.modifyContent}")
                            }
                        }
                        update = true
                        Log.i(TAG, "发现新版本: ${r.version_name} (${r.version_code})")
                    } else {
                        text = "已是最新版本，不需要更新"
                        Log.i(TAG, "当前已是最新版本")
                    }
                } else {
                    text = "无法获取最新版本信息，请检查网络连接或稍后重试"
                    Log.w(TAG, "版本信息为空或格式错误")
                }
            } catch (e: Exception) {
                text = "检查更新时发生错误：${e.message}"
                Log.e(TAG, "检查更新错误: ${e.message}", e)
            }

            // 只有在需要更新时才显示对话框
            if (update) {
                updateUI(text, update)
            } else {
                // 不需要更新时，直接显示Toast信息
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUI(text: String, update: Boolean) {
        try {
            // 检查上下文是否有效
            if (context is FragmentActivity && !(context as FragmentActivity).isFinishing) {
                val dialog = ConfirmationFragment(this@UpdateManager, text, update)
                dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
            } else {
                Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示更新对话框失败: ${e.message}", e)
            Toast.makeText(context.applicationContext, "显示更新界面失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /* ------------------------------------------------ */
    /*  下载：支持代理切换的重试机制                    */
    /* ------------------------------------------------ */
    private fun startDownload(release: ReleaseResponse) {
        if (isDownloading) {
            Toast.makeText(context, "已在下载中，请勿重复点击", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查版本名称是否为空
        if (release.version_name.isNullOrEmpty()) {
            Toast.makeText(context, "版本名称无效", Toast.LENGTH_SHORT).show()
            return
        }

        // 重置代理和重试计数
        Github.resetProxy()
        currentProxyAttempt = 0

        // 开始带重试的下载
        attemptDownload(release)
    }

    private fun attemptDownload(release: ReleaseResponse) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 使用固定的APK下载地址
                val downloadUrl = Github.getApkUrl()

                Log.i(TAG, "=".repeat(50))
                Log.i(TAG, "开始下载探测")
                Log.i(TAG, "版本名称: ${release.version_name}")
                Log.i(TAG, "下载URL: $downloadUrl")
                Log.i(TAG, "当前代理: ${Github.getCurrentProxy()}")
                Log.i(TAG, "尝试次数: ${currentProxyAttempt + 1}/$maxRetryCount")
                Log.i(TAG, "=".repeat(50))

                val code = probeUrl(downloadUrl)
                if (code != 200) {
                    val errorMsg = "下载链接失效（HTTP $code）"
                    Log.e(TAG, errorMsg)

                    // 尝试切换代理
                    if (currentProxyAttempt < maxRetryCount - 1) {
                        currentProxyAttempt++
                        Github.switchToNextProxy()
                        Toast.makeText(context, "切换下载代理，重试中... (${currentProxyAttempt + 1}/$maxRetryCount)", Toast.LENGTH_SHORT).show()
                        delay(1000)
                        attemptDownload(release)
                        return@launch
                    } else {
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }

                // 下载URL探测成功，开始实际下载
                // 使用固定的APK文件名
                enqueueDownload(downloadUrl, APK_FILE_NAME, release.version_name)

            } catch (e: Exception) {
                Log.e(TAG, "下载探测异常: ${e.message}", e)

                // 异常情况也尝试切换代理
                if (currentProxyAttempt < maxRetryCount - 1) {
                    currentProxyAttempt++
                    Github.switchToNextProxy()
                    Toast.makeText(context, "下载异常，切换代理重试... (${currentProxyAttempt + 1}/$maxRetryCount)", Toast.LENGTH_SHORT).show()
                    delay(1000)
                    attemptDownload(release)
                } else {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun enqueueDownload(downloadUrl: String, apkFileName: String, versionName: String) {
        try {
            isDownloading = true
            lastReportedProgress = -1  // 重置进度记录

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = Request(Uri.parse(downloadUrl))

            // 确保下载目录存在
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()

            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )
            request.setTitle("${context.getString(R.string.app_name)} $versionName")
            request.setDescription("正在下载新版本")
            request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverRoaming(false)
            request.setMimeType(MIME_TYPE_APK)

            // 设置网络类型限制
            request.setAllowedNetworkTypes(Request.NETWORK_WIFI or Request.NETWORK_MOBILE)

            val downloadId = dm.enqueue(request)
            Log.i(TAG, "=".repeat(50))
            Log.i(TAG, "下载任务已创建")
            Log.i(TAG, "下载ID: $downloadId")
            Log.i(TAG, "下载URL: $downloadUrl")
            Log.i(TAG, "文件名: $apkFileName")
            Log.i(TAG, "当前代理: ${Github.getCurrentProxy()}")
            Log.i(TAG, "=".repeat(50))

            // 显示初始 Toast
            Toast.makeText(context, "开始下载更新...", Toast.LENGTH_SHORT).show()

            downloadReceiver = DownloadReceiver(
                WeakReference(context),
                apkFileName,
                downloadId
            )

            registerDownloadReceiverWithFlags()

            // 进度查询 - 添加 Toast 显示
            getDownloadProgress(context, downloadId) { progress ->
                when {
                    progress in 0..99 -> {
                        // 每 10% 更新一次 Toast，避免频繁闪烁
                        if (progress % 10 == 0 && progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            Toast.makeText(context, "下载进度: $progress%", Toast.LENGTH_SHORT).show()
                        }
                    }
                    progress == 100 -> {
                        if (lastReportedProgress != 100) {
                            lastReportedProgress = 100
                            Toast.makeText(context, "下载完成，准备安装", Toast.LENGTH_SHORT).show()
                        }
                    }
                    progress == -1 -> {
                        Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                    }
                }
                Log.i(TAG, "下载进度: $progress%")
            }

        } catch (e: Exception) {
            Log.e(TAG, "创建下载任务失败: ${e.message}", e)
            Toast.makeText(context, "创建下载任务失败", Toast.LENGTH_SHORT).show()
            isDownloading = false
        }
    }

    /* ------------------------------------------------ */
    /*  修复：使用条件编译处理广播注册                  */
    /* ------------------------------------------------ */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDownloadReceiverWithFlags() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = downloadReceiver ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 需要明确指定导出标志
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                Log.d(TAG, "使用RECEIVER_EXPORTED注册广播接收器")
            } else {
                // Android 12及以下使用传统方法
                context.registerReceiver(receiver, filter)
                Log.d(TAG, "使用传统方法注册广播接收器")
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册广播接收器失败: ${e.message}", e)
            Toast.makeText(context, "下载监控初始化失败", Toast.LENGTH_SHORT).show()
        }
    }

    /* ------------------------------------------------ */
    /*  进度轮询（优化版本）- 带 Toast 显示             */
    /* ------------------------------------------------ */
    private fun getDownloadProgress(
        context: Context,
        downloadId: Long,
        progressListener: (Int) -> Unit
    ) {
        progressHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor: Cursor? = dm.query(query)

                    cursor?.use {
                        if (it.moveToFirst()) {
                            val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))

                            when (status) {
                                DownloadManager.STATUS_PENDING -> {
                                    progressListener(0)
                                    // 继续轮询
                                    progressHandler?.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
                                }
                                DownloadManager.STATUS_RUNNING -> {
                                    val total = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    val downloaded = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

                                    if (total > 0) {
                                        val progress = (downloaded * 100L / total).toInt()
                                        progressListener(progress)
                                    }
                                    // 继续轮询
                                    progressHandler?.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    progressListener(100)
                                    // 下载完成，停止轮询
                                    return
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    progressListener(-1)  // 错误
                                    // 下载失败，停止轮询
                                    return
                                }
                                else -> {
                                    // 其他状态，继续轮询
                                    progressHandler?.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
                                }
                            }
                        } else {
                            // 下载任务不存在
                            progressHandler?.removeCallbacks(this)
                            Log.w(TAG, "下载任务已被移除，停止进度轮询")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "获取下载进度失败: ${e.message}", e)
                    progressHandler?.removeCallbacks(this)
                }
            }
        }
        progressRunnable = runnable
        progressHandler?.post(runnable)
    }

    /* ------------------------------------------------ */
    /*  文件清理方法                                    */
    /* ------------------------------------------------ */
    private fun cleanupDownloadedFile(apkFileName: String) {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadsDir, apkFileName)

            if (apkFile.exists()) {
                if (apkFile.delete()) {
                    Log.i(TAG, "APK文件已清理: ${apkFile.absolutePath}")
                } else {
                    Log.w(TAG, "APK文件删除失败: ${apkFile.absolutePath}")
                }
            } else {
                Log.d(TAG, "APK文件不存在，无需清理: $apkFileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理APK文件失败: ${e.message}", e)
        }
    }

    /* ------------------------------------------------ */
    /*  清理所有下载的APK文件                           */
    /* ------------------------------------------------ */
    fun cleanupAllDownloadedFiles() {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk")) {
                    if (file.delete()) {
                        Log.i(TAG, "清理APK文件: ${file.name}")
                    } else {
                        Log.w(TAG, "清理APK文件失败: ${file.name}")
                    }
                }
            }
            Log.i(TAG, "所有APK文件清理完成")
        } catch (e: Exception) {
            Log.e(TAG, "清理所有APK文件失败: ${e.message}", e)
        }
    }

    /* ------------------------------------------------ */
    /*  广播接收器：详细状态 + 失败原因 + 标志复位      */
    /* ------------------------------------------------ */
    private inner class DownloadReceiver(
        private val contextRef: WeakReference<Context>,
        private val apkFileName: String,
        private val downloadId: Long
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val strongContext = contextRef.get() ?: return
            val ref = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (ref != downloadId) return

            val dm = strongContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)

            cursor?.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.i(TAG, "下载成功")
                            isDownloading = false
                            installNewVersion(strongContext)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val msg = when (reason) {
                                DownloadManager.ERROR_CANNOT_RESUME -> "无法恢复下载"
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "外部存储未找到"
                                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
                                DownloadManager.ERROR_FILE_ERROR -> "文件 IO 错误"
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP 数据错误"
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
                                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向过多"
                                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "未处理 HTTP 状态"
                                else -> "下载失败（代码=$reason）"
                            }
                            Log.e(TAG, "下载失败: $msg")
                            Toast.makeText(strongContext, msg, Toast.LENGTH_LONG).show()

                            // 下载失败时也清理文件
                            cleanupDownloadedFile(apkFileName)
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            Log.w(TAG, "下载暂停，reason=$reason")
                        }
                        DownloadManager.STATUS_RUNNING -> {
                            Log.d(TAG, "下载进行中")
                        }
                    }
                } else {
                    Log.w(TAG, "下载任务不存在")
                }
            }
        }

        private fun installNewVersion(context: Context) {
            try {
                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val apkFile = File(downloadsDir, apkFileName)

                if (!apkFile.exists() || !apkFile.canRead()) {
                    Log.e(TAG, "APK 文件无法访问: ${apkFile.absolutePath}")
                    Toast.makeText(context, "APK 文件无法访问", Toast.LENGTH_SHORT).show()
                    return
                }

                Log.d(TAG, "准备安装 APK: ${apkFile.absolutePath}")

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, MIME_TYPE_APK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }

                // 检查是否有应用可以处理安装意图
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Log.i(TAG, "启动安装程序成功")

                    // 不立即清理文件，等待下次应用启动时自动清理
                    Log.i(TAG, "APK文件将在下次应用启动时自动清理: ${apkFile.absolutePath}")

                    // 可选：给用户一个提示
                    Toast.makeText(context, "正在启动安装程序...", Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = "无法找到安装程序"
                    Log.e(TAG, errorMsg)
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()

                    // 无法安装时清理文件
                    cleanupDownloadedFile(apkFileName)
                }

            } catch (e: Exception) {
                Log.e(TAG, "安装失败: ${e.message}", e)
                Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()

                // 安装失败时清理文件
                cleanupDownloadedFile(apkFileName)
            }
        }
    }

    /* ------------------------------------------------ */
    /*  生命周期 & 回调                               */
    /* ------------------------------------------------ */
    override fun onConfirm() {
        Log.d(TAG, "用户确认更新")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
        Log.d(TAG, "用户取消更新")
    }

    fun destroy() {
        Log.d(TAG, "销毁 UpdateManager")

        // 取消广播接收器
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "广播接收器已取消注册")
            } catch (e: Exception) {
                Log.w(TAG, "取消注册广播接收器失败: ${e.message}")
            }
            downloadReceiver = null
        }

        // 停止进度轮询
        progressRunnable?.let { runnable ->
            progressHandler?.removeCallbacks(runnable)
            Log.d(TAG, "进度轮询已停止")
        }
        progressHandler = null
        progressRunnable = null

        // 重置状态
        isDownloading = false
    }
}

// 扩展函数用于字符串重复
private operator fun String.times(count: Int): String = this.repeat(count)