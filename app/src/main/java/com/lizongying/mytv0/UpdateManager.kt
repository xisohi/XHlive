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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.requests.ReleaseRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class UpdateManager(
    private val context: Context,
    private val versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private val releaseRequest = ReleaseRequest()
    private var release: ReleaseResponse? = null
    private var downloadReceiver: DownloadReceiver? = null

    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false
            try {
                release = releaseRequest.getRelease()
                Log.i(TAG, "Current version: $versionCode, Latest version: ${release?.version_code}")
                if (release?.version_code != null && release?.version_code!! > versionCode) {
                    text = "最新版本：${release?.version_name}"
                    update = true
                } else {
                    text = "已是最新版本，不需要更新"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred: ${e.message}", e)
            }
            updateUI(text, update)
        }
    }

    private fun updateUI(text: String, update: Boolean) {
        val dialog = ConfirmationFragment(this@UpdateManager, text, update)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    private fun startDownload(release: ReleaseResponse) {
        release.apkurl?.let { apkUrl ->
            val apkFileName = apkUrl.substringAfterLast("/")  // 从 URL 中提取 APK 文件名
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = Request(Uri.parse(apkUrl))

            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()
            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )
            request.setTitle("${context.getString(R.string.app_name)} ${release.version_name}")
            request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverRoaming(false)
            request.setMimeType("application/vnd.android.package-archive")

            val downloadReference = downloadManager.enqueue(request)

            // 注销旧的接收器
            downloadReceiver?.let { context.unregisterReceiver(it) }

            downloadReceiver = DownloadReceiver(context, apkFileName, downloadReference, this)

            val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    context,
                    downloadReceiver,
                    intentFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }

            getDownloadProgress(context, downloadReference) { progress ->
                Log.i(TAG, "Download progress: $progress%")
            }
        } ?: run {
            Log.e(TAG, "APK URL is missing in the release response.")
            updateUI("更新失败：APK 文件地址无效", false)
        }
    }

    private fun getDownloadProgress(
        context: Context,
        downloadId: Long,
        progressListener: (Int) -> Unit
    ) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val handler = Handler(Looper.getMainLooper())
        val intervalMillis: Long = 1000

        handler.post(object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = downloadManager.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val bytesDownloadedIndex = it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIndex = it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        val bytesDownloaded = it.getLong(bytesDownloadedIndex)
                        val bytesTotal = it.getLong(bytesTotalIndex)

                        if (bytesTotal > 0) {
                            val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                            progressListener(progress)
                        }
                    }
                }
                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    private class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadReference: Long,
        private val updateManager: UpdateManager
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.i(TAG, "Received download ID: $reference")

            if (reference == downloadReference) {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadReference)
                val cursor = downloadManager.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        Log.i(TAG, "Download status: $status")

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            installNewVersion(context, apkFileName)
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            Log.e(TAG, "Download failed")
                            updateManager.updateUI("更新失败：下载失败", false)
                        }
                    }
                }
            }
        }

        private fun installNewVersion(context: Context, apkFileName: String) {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            Log.i(TAG, "APK file path: $apkFile")

            if (apkFile.exists()) {
                val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        apkFile
                    )
                } else {
                    Uri.fromFile(apkFile)
                }

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(installIntent)
            } else {
                Log.e(TAG, "APK file does not exist!")
                updateManager.updateUI("更新失败：APK 文件不存在", false)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
    }

    override fun onConfirm() {
        Log.i(TAG, "User confirmed update")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
        Log.i(TAG, "User canceled update")
    }

    fun destroy() {
        downloadReceiver?.let {
            context.unregisterReceiver(it)
            Log.i(TAG, "Unregistered download receiver")
        }
    }
}