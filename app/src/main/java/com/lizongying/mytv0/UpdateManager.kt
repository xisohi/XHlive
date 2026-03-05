package com.lizongying.mytv0

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

class UpdateManager(
    private val context: Context,  // Application Context，用于下载等操作
    private val versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private val okHttpClient = HttpClient.okHttpClient
    private var downloadJob: Job? = null
    private var lastLoggedProgress = -1

    private var release: ReleaseResponse? = null
    private var hasUpdate = false

    // 从 Github 获取文件名
    private val apkFileName = Github.APK_FILE_NAME

    // Activity 弱引用，用于显示对话框
    private var activityRef: WeakReference<FragmentActivity>? = null

    // 待显示的更新信息（当 Activity 不可用时暂存）
    private var pendingUpdateText: String? = null
    private var pendingHasUpdate: Boolean = false

    // 设置 Activity 引用，在 Activity onResume 时调用
    fun setActivity(activity: FragmentActivity?) {
        this.activityRef = activity?.let { WeakReference(it) }

        // 如果有待显示的更新，且 Activity 可用，立即显示
        if (activity != null && !activity.isFinishing && pendingUpdateText != null) {
            showUpdateDialog(activity, pendingUpdateText!!, pendingHasUpdate)
            pendingUpdateText = null
            pendingHasUpdate = false
        }
    }

    private fun hasWritePermission(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        return connectivityManager?.activeNetworkInfo?.isConnected == true
    }

    /**
     * 获取下载目录 - 使用系统标准下载目录
     */
    private fun getDownloadDirectory(): File {
        // 使用系统标准下载目录 /sdcard/Download/，更可靠
        return File(Environment.getExternalStorageDirectory(), "Download").apply {
            if (!exists()) {
                val created = mkdirs()
                Log.i(TAG, "创建下载目录: $created, 路径: $absolutePath")
            }
        }
    }

    fun cleanupApkFilesOnStart() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadDir = getDownloadDirectory()
                val deletedFiles = cleanupDownloadDirectory(downloadDir)

                if (deletedFiles.isNotEmpty()) {
                    Log.i(TAG, "Cleaned up ${deletedFiles.size} APK files on app start")
                    withContext(Dispatchers.Main) {
                        "已清理残留安装包".showToast()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up APK files on start", e)
            }
        }
    }

    private suspend fun getRelease(): ReleaseResponse? {
        return withContext(Dispatchers.IO) {
            try {
                if (!isNetworkAvailable()) {
                    withContext(Dispatchers.Main) {
                        "网络不可用，请检查网络连接".showToast()
                    }
                    return@withContext null
                }

                val versionUrl = Github.getVersionUrl()
                Log.i(TAG, "Checking version from: $versionUrl")

                val request = okhttp3.Request.Builder()
                    .url(versionUrl)
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "HTTP error: ${response.code()}")

                        if (response.code() == 404 || response.code() >= 500) {
                            Log.w(TAG, "Current proxy failed, trying next proxy...")
                            Github.switchToNextProxy()
                        }
                        return@withContext null
                    }
                    response.bodyAlias()?.let {
                        return@withContext gson.fromJson(it.string(), ReleaseResponse::class.java)
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "getRelease", e)

                if (e is IOException) {
                    Log.w(TAG, "Network error, switching to next proxy...")
                    Github.switchToNextProxy()
                }

                withContext(Dispatchers.Main) {
                    "版本检查失败: ${e.message}".showToast()
                }
                null
            }
        }
    }

    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")

        if (!hasWritePermission()) {
            "无存储权限，无法下载更新".showToast()
            return
        }

        Github.resetProxy()

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
                        hasUpdate = true
                    } else {
                        text = "已是最新版本，不需要更新"
                        hasUpdate = false
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

    private fun updateUI(text: String, update: Boolean) {
        val activity = activityRef?.get()

        if (activity == null || activity.isFinishing) {
            // Activity 不可用，暂存信息等待下次
            Log.w(TAG, "Activity not available, pending update UI")
            pendingUpdateText = text
            pendingHasUpdate = update
            return
        }

        showUpdateDialog(activity, text, update)
    }

    private fun showUpdateDialog(activity: FragmentActivity, text: String, update: Boolean) {
        try {
            // 确保使用 Activity 的 supportFragmentManager
            val dialog = ConfirmationFragment(this, text, update)
            dialog.show(activity.supportFragmentManager, TAG)
        } catch (e: Exception) {
            Log.e(TAG, "Show dialog failed", e)
            // 降级：使用 Toast 提示
            text.showToast()
        }
    }

    private fun startDownload(release: ReleaseResponse) {
        if (!hasWritePermission()) {
            "无存储权限，无法下载".showToast()
            return
        }

        if (!isNetworkAvailable()) {
            "网络不可用，请检查网络连接".showToast()
            return
        }

        val downloadDir = getDownloadDirectory()

        // 清理旧文件（包括临时文件）
        cleanupDownloadDirectory(downloadDir)

        val file = File(downloadDir, apkFileName)
        Log.i(TAG, "准备下载到: ${file.absolutePath}")

        val acceleratedApkUrl = Github.getApkUrl()
        Log.i(TAG, "下载URL: $acceleratedApkUrl")

        downloadJob = GlobalScope.launch(Dispatchers.IO) {
            downloadWithRetry(acceleratedApkUrl, file)
        }
    }

    /**
     * 清理下载目录 - 只删除临时文件和旧版本
     */
    private fun cleanupDownloadDirectory(directory: File?): List<String> {
        val deletedFiles = mutableListOf<String>()
        directory?.listFiles()?.forEach { f ->
            // 删除临时文件和任何旧的APK文件
            if (f.name.endsWith(".tmp") || f.name.contains("XHlive") && f.name.endsWith(".apk")) {
                if (f.delete()) {
                    Log.i(TAG, "清理文件: ${f.name}")
                    deletedFiles.add(f.name)
                }
            }
        }
        return deletedFiles
    }

    private suspend fun downloadWithRetry(url: String, file: File, maxRetries: Int = PROXY_RETRY_COUNT) {
        var retries = 0
        var currentUrl = url

        Log.i(TAG, "开始下载，代理: ${Github.getCurrentProxy()}")

        while (retries < maxRetries) {
            try {
                downloadFile(currentUrl, file)
                Log.i(TAG, "下载成功完成")
                break
            } catch (e: IOException) {
                Log.e(TAG, "下载失败 (尝试 ${retries + 1}/$maxRetries): ${e.message}")
                retries++

                if (retries >= maxRetries) {
                    Log.e(TAG, "所有代理均失败")
                    withContext(Dispatchers.Main) {
                        updateUI("下载失败，请检查网络后重试", false)
                    }
                } else {
                    Github.switchToNextProxy()
                    currentUrl = Github.getApkUrl()

                    Log.i(TAG, "切换代理: ${Github.getCurrentProxy()}")

                    withContext(Dispatchers.Main) {
                        "下载失败，切换代理重试 ($retries/$maxRetries)".showToast()
                    }

                    delay(RETRY_DELAY_BASE * retries)
                }
            }
        }
    }

    /**
     * 下载文件 - 使用临时文件确保完整性
     */
    private suspend fun downloadFile(url: String, file: File) {
        Log.i(TAG, "========== 开始下载 ==========")
        Log.i(TAG, "目标文件: ${file.absolutePath}")

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.android.package-archive")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP错误: ${response.code()}")
        }

        val body = response.bodyAlias() ?: throw IOException("响应体为空")
        val contentLength = body.contentLength()
        Log.i(TAG, "文件大小: $contentLength bytes")

        var bytesRead = 0L
        val startTime = System.currentTimeMillis()

        // 使用临时文件下载
        val tempFile = File(file.parent, "${file.name}.tmp")

        try {
            body.byteStream().use { input ->
                BufferedOutputStream(tempFile.outputStream()).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytes: Int
                    var lastProgress = -1

                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        bytesRead += bytes

                        // 每10%报告一次进度
                        val progress = if (contentLength > 0) {
                            (bytesRead * 100 / contentLength).toInt()
                        } else -1

                        if (progress != lastProgress && progress % 10 == 0) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) {
                                updateDownloadProgress(progress)
                            }
                        }
                    }

                    // 关键：强制刷新到磁盘
                    output.flush()
                }
            }

            val downloadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "下载完成: $bytesRead bytes, 耗时: ${downloadTime}ms")

            // 验证临时文件
            if (!tempFile.exists()) {
                throw IOException("临时文件不存在")
            }
            if (tempFile.length() == 0L) {
                throw IOException("临时文件为空")
            }
            Log.i(TAG, "临时文件验证通过: ${tempFile.length()} bytes")

            // 删除旧文件
            if (file.exists()) {
                val deleted = file.delete()
                Log.i(TAG, "删除旧文件: $deleted")
            }

            // 重命名为正式文件
            val renamed = tempFile.renameTo(file)
            if (!renamed) {
                throw IOException("重命名失败: ${tempFile.absolutePath} -> ${file.absolutePath}")
            }

            // 最终验证
            Log.i(TAG, "========== 下载验证 ==========")
            Log.i(TAG, "文件存在: ${file.exists()}")
            Log.i(TAG, "文件大小: ${file.length()} bytes")
            Log.i(TAG, "文件可读: ${file.canRead()}")
            Log.i(TAG, "文件路径: ${file.absolutePath}")

            if (!file.exists() || file.length() == 0L) {
                throw IOException("最终文件验证失败")
            }

        } catch (e: Exception) {
            // 清理临时文件
            tempFile.delete()
            throw e
        }

        withContext(Dispatchers.Main) {
            "下载完成，开始安装".showToast()
            installNewVersion(file)
        }
    }

    private fun updateDownloadProgress(progress: Int) {
        if (progress == -1) {
            Log.i(TAG, "下载中，大小未知")
        } else if (progress != lastLoggedProgress) {
            lastLoggedProgress = progress
            Log.i(TAG, "下载进度: $progress%")
            if (progress % 10 == 0) {
                "升级文件已下载: $progress%".showToast()
            }
        }
    }

    /**
     * 安装新版本 - 支持 API 19+（非协程函数）
     */
    private fun installNewVersion(apkFile: File) {
        Log.i(TAG, "========== 开始安装 ==========")
        Log.i(TAG, "APK路径: ${apkFile.absolutePath}")
        Log.i(TAG, "APK存在: ${apkFile.exists()}")
        Log.i(TAG, "APK大小: ${apkFile.length()} bytes")
        Log.i(TAG, "API级别: ${Build.VERSION.SDK_INT}")

        if (!apkFile.exists()) {
            Log.e(TAG, "APK文件不存在!")
            "安装文件不存在".showToast()
            return
        }

        if (apkFile.length() < 10000) {
            Log.e(TAG, "APK文件过小，可能损坏: ${apkFile.length()} bytes")
            "安装包损坏，请重新下载".showToast()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri: Uri

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // API 24+ 使用 FileProvider
                apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Log.i(TAG, "使用FileProvider: $apkUri")
            } else {
                // API 19-23 使用 file://
                apkUri = Uri.fromFile(apkFile)
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                // API 19也需要这个标志
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Log.i(TAG, "使用file协议: $apkUri")
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 检查是否有应用能处理
            val activities = context.packageManager.queryIntentActivities(intent, 0)
            Log.i(TAG, "找到 ${activities.size} 个安装器")

            activities.forEachIndexed { index, info ->
                Log.i(TAG, "  [$index] ${info.activityInfo.packageName}/${info.activityInfo.name}")
            }

            if (activities.isNotEmpty()) {
                // 使用第一个
                val first = activities[0].activityInfo
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // API 19-23 显式设置组件
                    intent.setClassName(first.packageName, first.name)
                }

                Log.i(TAG, "启动安装器: ${first.packageName}/${first.name}")
                context.startActivity(intent)
                Log.i(TAG, "安装意图已发送")
            } else {
                Log.e(TAG, "没有找到安装器")
                throw IOException("未找到系统安装器")
            }

            // 延迟清理
            startPostInstallCleanup(apkFile)

        } catch (e: Exception) {
            Log.e(TAG, "安装失败", e)

            // 降级方案：提示手动安装（使用Handler切换到主线程）
            val path = apkFile.absolutePath
            val message = """
                自动安装失败，请手动安装：
                
                路径: $path
                
                方法：
                1. 打开文件管理器
                2. 进入 Download 目录
                3. 点击 $apkFileName 安装
                """.trimIndent()

            Handler(Looper.getMainLooper()).post {
                message.showToast()
            }
        }
    }

    private fun startPostInstallCleanup(apkFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(POST_INSTALL_CLEANUP_DELAY)

            if (apkFile.exists()) {
                Log.i(TAG, "清理APK: ${apkFile.name}")
                if (apkFile.delete()) {
                    Log.i(TAG, "清理成功")
                } else {
                    Log.w(TAG, "清理失败，下次启动时清理")
                }
            }
        }
    }

    fun cleanupApkFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadDir = getDownloadDirectory()
                val deletedFiles = cleanupDownloadDirectory(downloadDir)

                withContext(Dispatchers.Main) {
                    if (deletedFiles.isNotEmpty()) {
                        "已清理 ${deletedFiles.size} 个文件".showToast()
                    } else {
                        "没有需要清理的文件".showToast()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理失败", e)
                withContext(Dispatchers.Main) {
                    "清理失败".showToast()
                }
            }
        }
    }

    override fun onConfirm() {
        if (hasUpdate) {
            release?.let { startDownload(it) }
        } else {
            Log.i(TAG, "用户确认，无更新")
        }
    }

    override fun onCancel() {
        Log.i(TAG, "用户取消更新")
    }

    fun destroy() {
        downloadJob?.cancel()
        activityRef?.clear()
        Log.i(TAG, "UpdateManager销毁")
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192
        private const val POST_INSTALL_CLEANUP_DELAY = 60000L
        private const val PROXY_RETRY_COUNT = 5
        private const val RETRY_DELAY_BASE = 3000L
    }
}