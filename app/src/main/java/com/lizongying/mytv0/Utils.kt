package com.lizongying.mytv0

import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lizongying.mytv0.ISP.CHINA_MOBILE
import com.lizongying.mytv0.ISP.CHINA_TELECOM
import com.lizongying.mytv0.ISP.CHINA_UNICOM
import com.lizongying.mytv0.ISP.UNKNOWN
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ISP {
    UNKNOWN,
    CHINA_MOBILE,
    CHINA_UNICOM,
    CHINA_TELECOM,
    IPV6,
}

data class IpInfo(
    val ip: String,
    val location: Location
)

data class Location(
    val city_name: String,
    val country_name: String,
    val isp_domain: String,
    val latitude: String,
    val longitude: String,
    val owner_domain: String,
    val region_name: String,
)


object Utils {
    const val TAG = "Utils"

    private var between: Long = 0

    private val _isp = MutableLiveData<ISP>()
    val isp: LiveData<ISP>
        get() = _isp

    fun getDateFormat(format: String): String {
        return SimpleDateFormat(
            format,
            Locale.CHINA
        ).format(Date(System.currentTimeMillis() - between))
    }

    fun getDateTimestamp(): Long {
        return (System.currentTimeMillis() - between) / 1000
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentTimeMillis = getTimestampFromServer()
                Log.i(TAG, "currentTimeMillis $currentTimeMillis")
                if (currentTimeMillis > 0) {
                    between = System.currentTimeMillis() - currentTimeMillis
                }
            } catch (e: Exception) {
                Log.e(TAG, "init", e)
            }

//            try {
//                withContext(Dispatchers.Main) {
//                    _isp.value = getISP()
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
        }
    }

    private suspend fun getTimestampFromServer(): Long {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://ip.ddnspod.com/timestamp")
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext 0
                    response.bodyAlias()?.string()?.toLong() ?: 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "getTimestampFromServer", e)
                0
            }
        }
    }

    private suspend fun getISP(): ISP {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://api.myip.la/json")
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext UNKNOWN
                    val string = response.bodyAlias()?.string()
                    val isp = gson.fromJson(string, IpInfo::class.java).location.isp_domain
                    when (isp) {
                        "ChinaMobile" -> CHINA_MOBILE
                        "ChinaUnicom" -> CHINA_UNICOM
                        "ChinaTelecom" -> CHINA_TELECOM
                        else -> UNKNOWN
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getISP", e)
                UNKNOWN
            }
        }
    }

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun formatUrl(url: String): String {
        // Check if the URL already starts with "http://" or "https://"
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return url
        }

        // Check if the URL starts with "//"
        if (url.startsWith("//")) {
            return "http://$url"
        }

        // Otherwise, add "http://" to the beginning of the URL
        return "http://${url}"
    }

    fun getUrls(url: String): List<String> {
        return if (url.startsWith("https://raw.githubusercontent.com") || url.startsWith("https://github.com")) {
            listOf(
                "https://gh.llkk.cc/",
                "https://github.moeyy.xyz/",
                "https://mirror.ghproxy.com/",
                "https://ghproxy.cn/",
                "https://ghproxy.net/",
                "https://ghproxy.click/",
                "https://ghproxy.com/",
                "https://github.moeyy.cn/",
                "https://gh-proxy.llyke.com/",
                "https://www.ghproxy.cc/",
                "https://cf.ghproxy.cc/",
                "https://ghp.ci/",
            ).map {
                "$it$url"
            }
        } else {
            listOf(url)
        }
    }
}