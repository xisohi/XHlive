package com.lizongying.mytv0

import android.os.Build
import android.util.Log

object Github {
    private const val TAG = "Github"

    private val PROXY_URLS = arrayOf(
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://github.catvod.com/",
        "https://gh.xisohi.dpdns.org/",
        "https://mirror.ghproxy.com/"
    )

    @Volatile
    private var currentProxyIndex = 0

    // ========== 分支选择 ==========
    // kitkat 分支 minSdk=19，仅服务 Android 4.4 (API 19-20) 老设备；
    // API 21+ 直接引流到 main 分支完整版（minSdk=21），安装后由 main 自身的升级逻辑接管后续更新。
    private val useMainBranch: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    val APP_NAME: String
        get() = if (useMainBranch) "XHlive" else "XHlive-kitkat"
    val APK_FILE_NAME: String
        get() = "$APP_NAME.apk"
    val VERSION_FILE_NAME: String
        get() = "$APP_NAME.json"

    /**
     * APK 下载地址
     */
    fun getApkUrl(): String {
        val githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/$APP_NAME/$APK_FILE_NAME"
        return getAcceleratedUrl(githubUrl)
    }

    /**
     * 获取版本信息URL
     */
    fun getVersionUrl(): String {
        val githubUrl = "https://raw.githubusercontent.com/xisohi/XHYSosc/master/update/$VERSION_FILE_NAME"
        return getAcceleratedUrl(githubUrl)
    }

    /**
     * 核心方法：对GitHub URL进行代理加速
     */
    fun getAcceleratedUrl(githubUrl: String): String {
        val cleanUrl = githubUrl.trim()
        val acceleratedUrl = PROXY_URLS[currentProxyIndex] + cleanUrl

        Log.d(TAG, "原始URL: $cleanUrl")
        Log.d(TAG, "加速后URL: $acceleratedUrl (使用代理: ${PROXY_URLS[currentProxyIndex]})")
        return acceleratedUrl
    }

    @Synchronized
    fun switchToNextProxy(): String {
        currentProxyIndex = (currentProxyIndex + 1) % PROXY_URLS.size
        val newProxy = PROXY_URLS[currentProxyIndex]
        Log.w(TAG, "切换到下一个代理: $newProxy")
        return newProxy
    }

    @Synchronized
    fun resetProxy() {
        currentProxyIndex = 0
        Log.i(TAG, "代理已重置为: ${PROXY_URLS[currentProxyIndex]}")
    }

    fun getProxyStatus(): String {
        val status = StringBuilder("\n========== GitHub 代理状态 ==========\n")
        for (i in PROXY_URLS.indices) {
            status.append(if (i == currentProxyIndex) "→ [当前] " else "   [备用] ")
                .append("代理$i: ")
                .append(PROXY_URLS[i])
                .append("\n")
        }
        status.append("=====================================")
        return status.toString()
    }

    fun getCurrentProxy(): String = PROXY_URLS[currentProxyIndex]
}