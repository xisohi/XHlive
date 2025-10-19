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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.core.content.FileProvider
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request as OkHttpRequest
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private var isDownloading = false
    private var downloadReceiver: DownloadReceiver? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    var release: ReleaseResponse? = null

    /* ------------------------------------------------ */
    /*  网络状态检查                                    */
    /* ------------------------------------------------ */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        return connectivityManager?.activeNetworkInfo?.isConnected == true
    }

    /* ------------------------------------------------ */
    /*  网络探测：HEAD 请求快速检查 apk_url 是否有效     */
    /* ------------------------------------------------ */
    private suspend fun probeUrl(url: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val req = OkHttpRequest.Builder().url(url).head().build()
                val rsp = HttpClient.okHttpClient.newCall(req).execute()
                rsp.code // 使用属性而不是方法
            } catch (e: Exception) {
                Log.e(TAG, "URL探测失败: ${e.message}", e)
                -1
            }
        }
    }

    /* ------------------------------------------------ */
    /*  获取版本信息（带重试机制）                      */
    /* ------------------------------------------------ */
    private suspend fun getRelease(): ReleaseResponse? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始获取版本信息，URL: $VERSION_URL")

                val request = OkHttpRequest.Builder()
                    .url(VERSION_URL)
                    .get()
                    .build()

                Log.d(TAG, "请求头: ${request.headers}")

                val response = HttpClient.okHttpClient.newCall(request).execute()

                // 使用 response.code 属性而不是方法
                val code = response.code
                Log.d(TAG, "HTTP响应码: $code")
                Log.d(TAG, "响应头: ${response.headers}")

                if (code != 200) {
                    Log.e(TAG, "HTTP错误: $code")
                    return@withContext null
                }

                // 使用 response.body 属性而不是方法
                val responseBody = response.body
                val jsonString = responseBody?.string()

                if (jsonString.isNullOrEmpty()) {
                    Log.e(TAG, "响应体为空")
                    return@withContext null
                }

                Log.d(TAG, "获取到JSON响应: $jsonString")

                // 解析JSON
                return@withContext try {
                    val releaseResponse = gson.fromJson(jsonString, ReleaseResponse::class.java)
                    Log.d(TAG, "JSON解析成功: version_code=${releaseResponse.version_code}, version_name=${releaseResponse.version_name}")
                    releaseResponse
                } catch (e: Exception) {
                    Log.e(TAG, "JSON解析失败: ${e.message}", e)
                    null
                }

            } catch (e: Exception) {
                Log.e(TAG, "获取版本信息失败: ${e.message}", e)
                null
            }
        }
    }

    /* ------------------------------------------------ */
    /*  带重试机制的版本获取                            */
    /* ------------------------------------------------ */
    private suspend fun getReleaseWithRetry(retryCount: Int = 2): ReleaseResponse? {
        repeat(retryCount) { attempt ->
            val result = getRelease()
            if (result != null) return result

            if (attempt < retryCount - 1) {
                Log.w(TAG, "获取版本信息失败，第${attempt + 1}次重试...")
                delay(2000) // 延迟2秒后重试
            }
        }
        return null
    }

    /* ------------------------------------------------ */
    /*  主入口：检查 + 弹窗（含 ModifyContent）         */
    /* ------------------------------------------------ */
    fun checkAndUpdate() {
        Log.i(TAG, "开始检查更新")

        if (!isNetworkAvailable()) {
            Toast.makeText(context, "网络不可用，请检查网络连接", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false

            try {
                val deferred = CoroutineScope(Dispatchers.IO).async { getReleaseWithRetry() }
                release = deferred.await()
                val r = release
                if (r != null) {
                    Log.d(TAG, "版本信息: version_code=${r.version_code}, version_name=${r.version_name}, apk_url=${r.apk_url}")

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
    /*  下载：重复点击保护 + 链接失效探测               */
    /* ------------------------------------------------ */
    private fun startDownload(release: ReleaseResponse) {
        if (isDownloading) {
            Toast.makeText(context, "已在下载中，请勿重复点击", Toast.LENGTH_SHORT).show()
            return
        }
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty()) {
            Toast.makeText(context, "下载地址无效", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "开始下载探测: ${release.apk_name}, URL: ${release.apk_url}")
            val code = probeUrl(release.apk_url)
            if (code != 200) {
                val errorMsg = "下载链接失效（HTTP $code）"
                Log.e(TAG, errorMsg)
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                return@launch
            }
            enqueueDownload(release)
        }
    }

    private fun enqueueDownload(release: ReleaseResponse) {
        try {
            isDownloading = true
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = Request(Uri.parse(release.apk_url))

            // 确保下载目录存在
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()

            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                release.apk_name
            )
            request.setTitle("${context.getString(R.string.app_name)} ${release.version_name}")
            request.setDescription("正在下载新版本")
            request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverRoaming(false)
            request.setMimeType(MIME_TYPE_APK)

            // 设置网络类型限制
            request.setAllowedNetworkTypes(Request.NETWORK_WIFI or Request.NETWORK_MOBILE)

            val downloadId = dm.enqueue(request)
            Log.d(TAG, "下载任务已创建, ID: $downloadId")

            downloadReceiver = DownloadReceiver(
                WeakReference(context),
                release.apk_name,
                downloadId
            )

            // 修复：使用条件编译处理不同Android版本的广播注册
            registerDownloadReceiverWithFlags()

            getDownloadProgress(context, downloadId) { progress ->
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
    /*  进度轮询（优化版本）                           */
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
                            val down = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val total = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            if (total >= 0 && down >= 0) {
                                val totalSize = it.getLong(total)
                                val downloaded = it.getLong(down)
                                if (totalSize > 0) {
                                    val progress = (downloaded * 100L / totalSize).toInt()
                                    progressListener(progress)
                                    if (progress < 100) {
                                        progressHandler?.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
                                    } else {
                                        Log.i(TAG, "下载完成，停止进度轮询")
                                    }
                                }
                            }
                        } else {
                            // 下载任务可能已被移除
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
    companion object {
        private const val TAG = "UpdateManager"
        private const val VERSION_URL = "https://xhys.lcjly.cn/update/XHlive.json"
        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"
        private const val PROGRESS_UPDATE_INTERVAL = 1000L // 1秒
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