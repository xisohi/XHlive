package com.lizongying.mytv0


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lizongying.mytv0.data.Source

object SP {
    private const val TAG = "SP"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    private const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    private const val KEY_POSITION_GROUP = "position_group"

    private const val KEY_POSITION_SUB = "position_sub"

    private const val KEY_REPEAT_INFO = "repeat_info"

    private const val KEY_CONFIG = "config"

    private const val KEY_CONFIG_AUTO_LOAD = "config_auto_load"

    private const val KEY_CHANNEL = "channel"

    private const val KEY_DEFAULT_LIKE = "default_like"

    private const val KEY_DISPLAY_SECONDS = "display_seconds"

    private const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"

    private const val KEY_COMPACT_MENU = "compact_menu"

    private const val KEY_LIKE = "like"

    private const val KEY_PROXY = "proxy"

    private const val KEY_EPG = "epg"

    private const val KEY_VERSION = "version"

    private const val KEY_LOG_TIMES = "log_times"

    private const val KEY_SOURCES = "sources"

    const val DEFAULT_CONFIG_URL = ""
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_EPG = "https://live.fanmingming.com/e.xml"
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = true
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = false
    const val DEFAULT_LOG_TIMES = 10
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    val DEFAULT_SOURCES = Gson().toJson(listOf(
        "https://live.fanmingming.com/tv/m3u/ipv6.m3u",
        "https://live.fanmingming.com/tv/m3u/itv.m3u",
        "https://live.fanmingming.com/tv/m3u/index.m3u",

        "https://iptv-org.github.io/iptv/index.m3u",

        // https://github.com/Guovin/iptv-api
        "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u",

        // https://github.com/joevess/IPTV
        "https://raw.githubusercontent.com/joevess/IPTV/main/sources/iptv_sources.m3u",
        "https://raw.githubusercontent.com/joevess/IPTV/main/sources/home_sources.m3u",
        "https://raw.githubusercontent.com/joevess/IPTV/main/iptv.m3u8",
        "https://raw.githubusercontent.com/joevess/IPTV/main/home.m3u8",

        // https://github.com/zbefine/iptv
        "https://raw.githubusercontent.com/zbefine/iptv/main/iptv.m3u",

        // https://github.com/YanG-1989/m3u
        "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u",

        // https://github.com/YueChan/Live
        "https://raw.githubusercontent.com/YueChan/Live/main/APTV.m3u",
        "https://raw.githubusercontent.com/YueChan/Live/main/Global.m3u",
        "https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u",

        "https://freetv.fun/test_channels_new.m3u",

        // https://github.com/SPX372928/MyIPTV
        "https://raw.githubusercontent.com/SPX372928/MyIPTV/master/%E9%BB%91%E9%BE%99%E6%B1%9FPLTV%E7%A7%BB%E5%8A%A8CDN%E7%89%88.txt",

        // https://github.com/vbskycn/iptv
        "https://live.zbds.top/tv/iptv6.m3u",
        "https://ghp.ci/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.m3u",

        // https://github.com/yuanzl77/IPTV
        "http://175.178.251.183:6689/live.m3u",

        // https://github.com/BurningC4/Chinese-IPTV
        "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u",

        // https://github.com/Moexin/IPTV
        "https://raw.githubusercontent.com/Moexin/IPTV/Files/CCTV.m3u",
        "https://raw.githubusercontent.com/Moexin/IPTV/Files/CNTV.m3u",
        "https://raw.githubusercontent.com/Moexin/IPTV/Files/IPTV.m3u",
    ).map {
        Source(
            uri = it
        )
    }, object : TypeToken<List<Source>>() {}.type
    ) ?: ""

    private lateinit var sp: SharedPreferences

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(
            context.getString(R.string.app_name),
            Context.MODE_PRIVATE
        )

        Log.i(TAG, "group position $positionGroup")
        Log.i(TAG, "list position $position")
        Log.i(TAG, "default channel $channel")
        Log.i(TAG, "proxy $proxy")
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, false)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, true)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var positionGroup: Int
        get() = sp.getInt(KEY_POSITION_GROUP, DEFAULT_POSITION_GROUP)
        set(value) = sp.edit().putInt(KEY_POSITION_GROUP, value).apply()

    var position: Int
        get() = sp.getInt(KEY_POSITION, DEFAULT_POSITION)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var positionSub: Int
        get() = sp.getInt(KEY_POSITION_SUB, 0)
        set(value) = sp.edit().putInt(KEY_POSITION_SUB, value).apply()

    var repeatInfo: Boolean
        get() = sp.getBoolean(KEY_REPEAT_INFO, true)
        set(value) = sp.edit().putBoolean(KEY_REPEAT_INFO, value).apply()

    var config: String?
        get() = sp.getString(KEY_CONFIG, DEFAULT_CONFIG_URL)
        set(value) = sp.edit().putString(KEY_CONFIG, value).apply()

    var configAutoLoad: Boolean
        get() = sp.getBoolean(KEY_CONFIG_AUTO_LOAD, false)
        set(value) = sp.edit().putBoolean(KEY_CONFIG_AUTO_LOAD, value).apply()

    var channel: Int
        get() = sp.getInt(KEY_CHANNEL, DEFAULT_CHANNEL)
        set(value) = sp.edit().putInt(KEY_CHANNEL, value).apply()

    var compactMenu: Boolean
        get() = sp.getBoolean(KEY_COMPACT_MENU, DEFAULT_COMPACT_MENU)
        set(value) = sp.edit().putBoolean(KEY_COMPACT_MENU, value).apply()

    var showAllChannels: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL_CHANNELS, DEFAULT_SHOW_ALL_CHANNELS)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ALL_CHANNELS, value).apply()

    var defaultLike: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_LIKE, false)
        set(value) = sp.edit().putBoolean(KEY_DEFAULT_LIKE, value).apply()

    var displaySeconds: Boolean
        get() = sp.getBoolean(KEY_DISPLAY_SECONDS, DEFAULT_DISPLAY_SECONDS)
        set(value) = sp.edit().putBoolean(KEY_DISPLAY_SECONDS, value).apply()

    fun getLike(id: Int): Boolean {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())
        return stringSet?.contains(id.toString()) ?: false
    }

    fun setLike(id: Int, liked: Boolean) {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (liked) {
            stringSet.add(id.toString())
        } else {
            stringSet.remove(id.toString())
        }

        sp.edit().putStringSet(KEY_LIKE, stringSet).apply()
    }

    fun deleteLike() {
        sp.edit().remove(KEY_LIKE).apply()
    }

    var proxy: String?
        get() = sp.getString(KEY_PROXY, "")
        set(value) = sp.edit().putString(KEY_PROXY, value).apply()

    var epg: String?
        get() = sp.getString(KEY_EPG, DEFAULT_EPG)
        set(value) = sp.edit().putString(KEY_EPG, value).apply()

    var version: String?
        get() = sp.getString(KEY_VERSION, "")
        set(value) = sp.edit().putString(KEY_VERSION, value).apply()

    var logTimes: Int
        get() = sp.getInt(KEY_LOG_TIMES, DEFAULT_LOG_TIMES)
        set(value) = sp.edit().putInt(KEY_LOG_TIMES, value).apply()

    var sources: String?
        get() = sp.getString(KEY_SOURCES, DEFAULT_SOURCES)
        set(value) = sp.edit().putString(KEY_SOURCES, value).apply()
}