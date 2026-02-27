package com.lizongying.mytv0

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.Source
import io.github.lizongying.Gua

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

    private const val KEY_CONFIG_URL = "config"

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

    private const val KEY_SOFT_DECODE = "soft_decode"

    const val DEFAULT_CHANNEL_REVERSAL = false
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_TIME = true
    const val DEFAULT_BOOT_STARTUP = false
    const val DEFAULT_CONFIG_URL = ""
    const val DEFAULT_PROXY = ""
    const val DEFAULT_EPG =
        "https://live.fanmingming.cn/e.xml,https://raw.githubusercontent.com/fanmingming/live/main/e.xml"
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = false
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = true
    const val DEFAULT_LOG_TIMES = 10
    const val DEFAULT_SOFT_DECODE = false

    // 0 favorite; 1 all
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    const val DEFAULT_REPEAT_INFO = true
    const val DEFAULT_CONFIG_AUTO_LOAD = false
    var DEFAULT_SOURCES = ""
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(
            context.getString(R.string.app_name),
            Context.MODE_PRIVATE
        )

        context.resources.openRawResource(R.raw.sources).bufferedReader()
            .use {
                val str = it.readText()
                Log.i(TAG, "Raw sources.txt (encoded): ${str.take(100)}...")

                if (str.isNotEmpty()) {
                    val decoded = Gua().decode(str).trim()
                    Log.i(TAG, "Decoded sources.txt (${decoded.length} chars):")
                    Log.i(TAG, "Content preview:\n$decoded")

                    val sources = parseSources(decoded)

                    // 保存为JSON字符串
                    DEFAULT_SOURCES = gson.toJson(sources, typeSourceList) ?: ""
                    Log.i(TAG, "Saved to SP, loaded ${sources.size} sources")

                    // 验证每个源的UA和Referrer
                    sources.forEachIndexed { index, source ->
                        Log.i(TAG, "Source[$index]: name='${source.name}', uri='${source.uri.take(50)}...', ua='${source.ua}', referrer='${source.referrer}'")
                    }
                }
            }

        Log.i(TAG, "group position $positionGroup")
        Log.i(TAG, "list position $position")
        Log.i(TAG, "default channel $channel")
        Log.i(TAG, "proxy $proxy")
        Log.i(TAG, "DEFAULT_SOURCES length: ${DEFAULT_SOURCES.length}")
    }

    /**
     * 解析源列表，支持多种格式：
     * 1. JSON格式：以 [ 开头
     * 2. 带字段标识的TXT格式：name:名称,uri:URL,ua:UA,referrer:Referrer
     * 3. 传统TXT格式：名称,URL
     */
    private fun parseSources(content: String): List<Source> {
        return when {
            // JSON格式
            content.trimStart().startsWith('[') -> {
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<Source>>() {}.type
                    val sources: List<Source> = gson.fromJson(content, type)
                    Log.i(TAG, "Parsed JSON format, found ${sources.size} sources")
                    sources.forEachIndexed { index, source ->
                        Log.i(TAG, "  [$index] name=${source.name}, uri=${source.uri.take(50)}..., ua='${source.ua}', referrer='${source.referrer}'")
                    }
                    sources
                } catch (e: Exception) {
                    Log.e(TAG, "parse sources json error", e)
                    emptyList()
                }
            }

            // 带字段标识的TXT格式（包含冒号）
            content.contains(":") -> {
                parseTaggedFormat(content)
            }

            // 传统TXT格式（按行，逗号分隔）
            else -> {
                parseTraditionalFormat(content)
            }
        }
    }

    /**
     * 解析带字段标识的TXT格式
     * 格式：name:名称,uri:URL,ua:UA,referrer:Referrer
     * 支持URI后面有空格的情况，会自动trim
     */
    private fun parseTaggedFormat(content: String): List<Source> {
        Log.i(TAG, "========== parseTaggedFormat ==========")
        Log.i(TAG, "Content length: ${content.length}")

        // 先trim整个内容，去除首尾空白
        val trimmedContent = content.trim()

        val lines = if (trimmedContent.contains("\n")) {
            trimmedContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(trimmedContent)
        }

        Log.i(TAG, "Found ${lines.size} lines")

        val sources = mutableListOf<Source>()

        lines.forEachIndexed { lineIndex, line ->
            Log.i(TAG, "--- Processing line $lineIndex ---")
            Log.i(TAG, "Raw line: '${line}'")

            // 按逗号分割字段对，但要去掉每个部分的空格
            val pairs = line.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Log.i(TAG, "Split into ${pairs.size} pairs: $pairs")

            var name = ""
            var uri = ""
            var ua = ""
            var referrer = ""

            // 解析每个字段对
            pairs.forEach { pair ->
                Log.i(TAG, "  Parsing pair: '$pair'")

                // 找到第一个冒号的位置（不是最后一个，因为URL中可能有冒号）
                val colonIndex = pair.indexOf(':')

                if (colonIndex > 0) {
                    val key = pair.substring(0, colonIndex).trim().lowercase()
                    // 冒号后面的所有内容都是值（URL可能包含冒号，如http://）
                    val value = pair.substring(colonIndex + 1).trim()

                    Log.i(TAG, "    key='$key', value='$value'")

                    when (key) {
                        "name" -> {
                            name = value
                            Log.i(TAG, "      -> set name = '$name'")
                        }
                        "uri", "url" -> {
                            uri = value
                            Log.i(TAG, "      -> set uri = '$uri'")
                        }
                        "ua", "user-agent", "useragent" -> {
                            ua = value
                            Log.i(TAG, "      -> set ua = '$ua'")
                        }
                        "referrer", "referer" -> {
                            referrer = value
                            Log.i(TAG, "      -> set referrer = '$referrer'")
                        }
                        else -> Log.w(TAG, "      Unknown field: $key")
                    }
                } else {
                    Log.w(TAG, "    No colon found in pair: '$pair', skipping")
                }
            }

            // 验证URI不为空才创建Source
            if (uri.isNotEmpty()) {
                val source = Source(
                    uri = uri,
                    name = name.ifEmpty { "未命名" },
                    ua = ua,
                    referrer = referrer
                )
                sources.add(source)
                Log.i(TAG, "✅ Created source $lineIndex: name='$name', uri='$uri', ua='$ua', referrer='$referrer'")
            } else {
                Log.e(TAG, "❌ Skipping line without URI: $line")
            }
        }

        Log.i(TAG, "========== parseTaggedFormat finished ==========")
        Log.i(TAG, "Created ${sources.size} sources")
        sources.forEachIndexed { index, source ->
            Log.i(TAG, "  Source $index: name=${source.name}, uri=${source.uri.take(50)}..., ua='${source.ua}', referrer='${source.referrer}'")
        }

        return sources
    }

    /**
     * 解析传统TXT格式（兼容旧格式）
     * 格式：名称,URL 或 名称,URL,UA,Referrer
     */
    private fun parseTraditionalFormat(content: String): List<Source> {
        val sources = mutableListOf<Source>()
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        lines.forEach { line ->
            val parts = line.split(",", limit = 4).map { it.trim() }

            when (parts.size) {
                4 -> {
                    // 新格式：名称,URL,UA,Referrer
                    val source = Source(
                        name = parts[0],
                        uri = parts[1],
                        ua = parts[2],
                        referrer = parts[3]
                    )
                    sources.add(source)
                    Log.d(TAG, "Parsed traditional line (4 parts): ${parts[0]}, ${parts[1]}, ua=${parts[2]}, referrer=${parts[3]}")
                }
                3 -> {
                    // 可能是：名称,URL,UA 或 名称,URL,Referrer
                    // 通过判断是否是http开头来区分
                    val thirdPart = parts[2]
                    if (thirdPart.startsWith("http")) {
                        // 第三个是Referrer
                        val source = Source(
                            name = parts[0],
                            uri = parts[1],
                            referrer = thirdPart
                        )
                        sources.add(source)
                        Log.d(TAG, "Parsed traditional line (3 parts with referrer): ${parts[0]}, ${parts[1]}, referrer=$thirdPart")
                    } else {
                        // 第三个是UA
                        val source = Source(
                            name = parts[0],
                            uri = parts[1],
                            ua = thirdPart
                        )
                        sources.add(source)
                        Log.d(TAG, "Parsed traditional line (3 parts with ua): ${parts[0]}, ${parts[1]}, ua=$thirdPart")
                    }
                }
                2 -> {
                    // 旧格式：名称,URL
                    val source = Source(
                        name = parts[0],
                        uri = parts[1]
                    )
                    sources.add(source)
                    Log.d(TAG, "Parsed traditional line (2 parts): ${parts[0]}, ${parts[1]}")
                }
                else -> {
                    Log.w(TAG, "Skipping invalid line: $line")
                }
            }
        }

        Log.i(TAG, "Parsed traditional format, found ${sources.size} sources")
        return sources
    }

    fun getSharedPreferences(): SharedPreferences {
        return sp
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, DEFAULT_CHANNEL_REVERSAL)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, DEFAULT_TIME)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, DEFAULT_BOOT_STARTUP)
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
        get() = sp.getBoolean(KEY_REPEAT_INFO, DEFAULT_REPEAT_INFO)
        set(value) = sp.edit().putBoolean(KEY_REPEAT_INFO, value).apply()

    var configUrl: String?
        get() = sp.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL)
        set(value) = sp.edit().putString(KEY_CONFIG_URL, value).apply()

    var configAutoLoad: Boolean
        get() = sp.getBoolean(KEY_CONFIG_AUTO_LOAD, DEFAULT_CONFIG_AUTO_LOAD)
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

    var softDecode: Boolean
        get() = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
        set(value) = sp.edit().putBoolean(KEY_SOFT_DECODE, value).apply()

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
        get() = sp.getString(KEY_PROXY, DEFAULT_PROXY)
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