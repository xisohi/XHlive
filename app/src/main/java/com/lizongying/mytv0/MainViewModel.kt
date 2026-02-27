import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonSyntaxException
import com.lizongying.mytv0.ImageHelper
import com.lizongying.mytv0.MyTVApplication
import com.lizongying.mytv0.R
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils.getDateFormat
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.bodyAlias
import com.lizongying.mytv0.codeAlias
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeEPGMap
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.Global.typeTvList
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.Sources
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.showToast
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class MainViewModel : ViewModel() {
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var initialized = false

    private lateinit var cacheEPG: File
    private var epgUrl = SP.epg

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    // 添加Map来缓存URL和UA/Referrer的对应关系
    private val uaCache = mutableMapOf<String, String>()
    private val referrerCache = mutableMapOf<String, String>()

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG() {
        viewModelScope.launch {
            var success = false
            if (!epgUrl.isNullOrEmpty()) {
                success = updateEPG(epgUrl!!)
            }
            if (!success && !SP.epg.isNullOrEmpty()) {
                updateEPG(SP.epg!!)
            }
        }
    }

    fun updateConfig() {
        if (SP.configAutoLoad) {
            SP.configUrl?.let {
                if (it.startsWith("http")) {
                    viewModelScope.launch {
                        Log.i(TAG, "update config url: $it")
                        importFromUrl(it)
                        updateEPG()
                    }
                }
            }
        }
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        val application = context.applicationContext as MyTVApplication
        imageHelper = application.imageHelper

        groupModel.addTVListModel(TVListModel("我的收藏", 0))
        groupModel.addTVListModel(TVListModel("全部頻道", 1))

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        if (!cacheFile!!.exists()) {
            cacheFile!!.createNewFile()
        }

        cacheChannels = getCache()

        // 尝试找到当前配置URL对应的Source，获取UA和Referrer
        var currentUA = ""
        var currentReferrer = ""

        // 使用局部变量保存configUrl，避免智能转换问题
        val configUrl = SP.configUrl
        val sourcesJson = SP.sources

        if (!configUrl.isNullOrEmpty()) {
            // 方法1: 从SP.sources中查找
            if (!sourcesJson.isNullOrEmpty()) {
                try {
                    val sources: List<Source> = gson.fromJson(sourcesJson, typeSourceList)
                    val currentSource = sources.find { it.uri == configUrl }
                    if (currentSource != null) {
                        currentUA = currentSource.ua
                        currentReferrer = currentSource.referrer
                        Log.i(TAG, "Found source in SP.sources: ${currentSource.name}, ua='$currentUA', ref='$currentReferrer'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing SP.sources", e)
                }
            }

            // 方法2（关键修复）: 如果SP.sources中没有，从专用缓存获取
            if (currentUA.isEmpty()) {
                currentUA = getUAForUrl(configUrl)
                if (currentUA.isNotEmpty()) {
                    Log.i(TAG, "✅ Loaded UA from dedicated cache: '$currentUA'")
                }
            }
            if (currentReferrer.isEmpty()) {
                currentReferrer = getReferrerForUrl(configUrl)
                if (currentReferrer.isNotEmpty()) {
                    Log.i(TAG, "✅ Loaded Referrer from dedicated cache: '$currentReferrer'")
                }
            }
        }

        if (cacheChannels.isEmpty()) {
            Log.i(TAG, "cacheChannels isEmpty, loading default")
            cacheChannels = context.resources.openRawResource(DEFAULT_CHANNELS_FILE)
                .bufferedReader().use { it.readText() }
        }

        Log.i(TAG, "cacheChannels $cacheFile ${cacheChannels.take(100)}...")

        try {
            // 关键修复：应用UA和Referrer解析频道
            if (currentUA.isNotEmpty() || currentReferrer.isNotEmpty()) {
                Log.i(TAG, "Parsing channels with UA='$currentUA', Referrer='$currentReferrer'")
                str2Channels(cacheChannels, configUrl ?: "", currentUA, currentReferrer)
            } else {
                str2Channels(cacheChannels)
            }
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
            cacheFile!!.deleteOnExit()
            R.string.channel_read_error.showToast()
        }

        viewModelScope.launch {
            cacheEPG = File(appDirectory, CACHE_EPG)
            if (!cacheEPG.exists()) {
                cacheEPG.createNewFile()
            } else {
                Log.i(TAG, "cacheEPG exists")
                if (readEPG(cacheEPG.readText())) {
                    Log.i(TAG, "cacheEPG success")
                } else {
                    Log.i(TAG, "cacheEPG failure")
                }
            }
        }

        initialized = true
        _channelsOk.value = true
    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            Log.w(TAG, "imageHelper is not initialized")
            return
        }

        for (tvModel in listModel) {
            var name = tvModel.tv.name
            if (name.isEmpty()) {
                name = tvModel.tv.title
            }
            val url = tvModel.tv.logo
            var urls =
                listOf(
                    "https://live.fanmingming.cn/tv/$name.png"
                ) + getUrls("https://raw.githubusercontent.com/fanmingming/live/main/tv/$name.png")
            if (url.isNotEmpty()) {
                urls = (getUrls(url) + urls).distinct()
            }

            imageHelper.preloadImage(
                name,
                urls,
            )
        }
    }

    suspend fun readEPG(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)

            withContext(Dispatchers.Main) {
                val e1 = mutableMapOf<String, List<EPG>>()
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    for ((n, epg) in res) {
                        if (name.contains(n, ignoreCase = true)) {
                            m.setEpg(epg)
                            e1[name] = epg
                            break
                        }
                    }
                }
                cacheEPG.writeText(gson.toJson(e1))
            }
            Log.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>> = gson.fromJson(str, typeEPGMap)

            withContext(Dispatchers.Main) {
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    val epg = res[name]
                    if (epg != null) {
                        m.setEpg(epg)
                    }
                }
            }
            Log.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> getUrls(u) }

        var success = false
        for (a in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        if (readEPG(response.bodyAlias()!!.byteStream())) {
                            Log.i(TAG, "EPG $a success")
                            success = true
                        }
                    } else {
                        Log.e(TAG, "EPG $a ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EPG $a error")
                }
            }

            if (success) {
                break
            }
        }

        return success
    }

    private suspend fun importFromUrl(url: String, id: String = "", ua: String = "", referrer: String = "") {
        val urls = getUrls(url).map { Pair(it, url) }

        var err = 0
        var shouldBreak = false
        for ((a, b) in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val requestBuilder = okhttp3.Request.Builder().url(a)
                    if (ua.isNotEmpty()) {
                        requestBuilder.addHeader("User-Agent", ua)
                        Log.i(TAG, "Using UA for request: $ua")
                    }
                    if (referrer.isNotEmpty()) {
                        requestBuilder.addHeader("Referer", referrer)
                        Log.i(TAG, "Using Referrer for request: $referrer")
                    }
                    val request = requestBuilder.build()

                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val str = response.bodyAlias()?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            tryStr2Channels(str, null, b, id, ua, referrer)
                        }
                        err = 0
                        shouldBreak = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                        err = R.string.channel_status_error
                    }
                } catch (e: JsonSyntaxException) {
                    e.printStackTrace()
                    Log.e(TAG, "JSON Parse Error", e)
                    err = R.string.channel_format_error
                    shouldBreak = true
                } catch (e: NullPointerException) {
                    e.printStackTrace()
                    Log.e(TAG, "Null Pointer Error", e)
                    err = R.string.channel_read_error
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, "Request error $e")
                    err = R.string.channel_request_error
                }
            }
            if (shouldBreak) break
        }

        if (err != 0) {
            err.showToast()
        }
    }

    fun reset(context: Context) {
        val str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
            .use { it.readText() }

        try {
            str2Channels(str)
        } catch (e: Exception) {
            e.printStackTrace()
            R.string.channel_read_error.showToast()
        }
    }

    fun importFromUri(uri: Uri, id: String = "", ua: String = "", referrer: String = "") {
        Log.i(TAG, "=== importFromUri ===")
        Log.i(TAG, "uri: $uri")
        Log.i(TAG, "id: $id")
        Log.i(TAG, "ua: '$ua'")
        Log.i(TAG, "referrer: '$referrer'")

        var finalUA = ua
        var finalReferrer = referrer

        sources.sources.value?.find { it.uri == uri.toString() }?.let { source ->
            Log.i(TAG, "Found source in list: name=${source.name}, ua='${source.ua}', referrer='${source.referrer}'")

            if (finalUA.isEmpty() && source.ua.isNotEmpty()) {
                Log.i(TAG, "✅ Using source.ua='${source.ua}' instead of empty ua")
                finalUA = source.ua
            }

            if (finalReferrer.isEmpty() && source.referrer.isNotEmpty()) {
                Log.i(TAG, "✅ Using source.referrer='${source.referrer}' instead of empty referrer")
                finalReferrer = source.referrer
            }
        } ?: Log.w(TAG, "Source not found in list for uri: $uri")

        Log.i(TAG, "Final values - ua: '$finalUA', referrer: '$finalReferrer'")

        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }

            tryStr2Channels(str, file, uri.toString(), id, finalUA, finalReferrer)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id, finalUA, finalReferrer)
            }
        }
    }
    /**
     * 保存URL和UA的对应关系到SharedPreferences
     */
    private fun saveUAForUrl(url: String, ua: String) {
        Log.i(TAG, "=== saveUAForUrl ===")
        Log.i(TAG, "url: $url")
        Log.i(TAG, "ua: '$ua'")

        if (ua.isNotEmpty()) {
            uaCache[url] = ua
            val prefs = SP.getSharedPreferences()
            prefs.edit().putString("ua_${url.hashCode()}", ua).apply()
            Log.i(TAG, "✅ Saved UA for $url: $ua")
        } else {
            Log.w(TAG, "⚠️ Attempted to save empty UA for $url")
        }
    }

    /**
     * 保存URL和Referrer的对应关系到SharedPreferences
     */
    private fun saveReferrerForUrl(url: String, referrer: String) {
        Log.i(TAG, "=== saveReferrerForUrl ===")
        Log.i(TAG, "url: $url")
        Log.i(TAG, "referrer: '$referrer'")

        if (referrer.isNotEmpty()) {
            referrerCache[url] = referrer
            val prefs = SP.getSharedPreferences()
            prefs.edit().putString("referrer_${url.hashCode()}", referrer).apply()
            Log.i(TAG, "✅ Saved referrer for $url: $referrer")
        } else {
            Log.w(TAG, "⚠️ Attempted to save empty referrer for $url")
        }
    }

    /**
     * 获取URL对应的UA
     */
    fun getUAForUrl(url: String): String {
        Log.i(TAG, "=== getUAForUrl ===")
        Log.i(TAG, "Looking up UA for url: $url")

        uaCache[url]?.let {
            Log.i(TAG, "✅ Found in cache: '$it'")
            return it
        }

        val prefs = SP.getSharedPreferences()
        val key = "ua_${url.hashCode()}"
        val ua = prefs.getString(key, "") ?: ""

        Log.i(TAG, "Looking in SharedPreferences with key: $key")

        if (ua.isNotEmpty()) {
            uaCache[url] = ua
            Log.i(TAG, "✅ Found in prefs: '$ua'")
        } else {
            Log.w(TAG, "❌ No UA found for key: $key")
        }

        return ua
    }

    /**
     * 获取URL对应的Referrer
     */
    fun getReferrerForUrl(url: String): String {
        Log.i(TAG, "=== getReferrerForUrl ===")
        Log.i(TAG, "Looking up referrer for url: $url")

        referrerCache[url]?.let {
            Log.i(TAG, "✅ Found in cache: '$it'")
            return it
        }

        val prefs = SP.getSharedPreferences()
        val key = "referrer_${url.hashCode()}"
        val referrer = prefs.getString(key, "") ?: ""

        Log.i(TAG, "Looking in SharedPreferences with key: $key")

        if (referrer.isNotEmpty()) {
            referrerCache[url] = referrer
            Log.i(TAG, "✅ Found in prefs: '$referrer'")
        } else {
            Log.w(TAG, "❌ No referrer found for key: $key")
        }

        return referrer
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "", ua: String = "", referrer: String = "") {
        try {
            if (str2Channels(str, url, ua, referrer)) {
                Log.i(TAG, "write to cacheFile $cacheFile")
                cacheFile!!.writeText(str)
                Log.i(TAG, "cacheFile ${getCache()}")
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                    val source = Source(
                        id = id,
                        uri = url,
                        ua = ua,
                        referrer = referrer
                    )
                    sources.addSource(source)

                    if (ua.isNotEmpty()) {
                        saveUAForUrl(url, ua)
                    }
                    if (referrer.isNotEmpty()) {
                        saveReferrerForUrl(url, referrer)
                    }
                }
                _channelsOk.value = true
                R.string.channel_import_success.showToast()
                Log.i(TAG, "channel import success")
            } else {
                R.string.channel_import_error.showToast()
                Log.w(TAG, "channel import error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryStr2Channels", e)
            file?.deleteOnExit()
            R.string.channel_read_error.showToast()
        }
    }

    private fun str2Channels(str: String, sourceUrl: String = "", directUA: String = "", directReferrer: String = ""): Boolean {
        var string = str
        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val g = Gua()
        if (g.verify(str)) {
            string = g.decode(str)
        }

        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty")
            return false
        }

        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val list: List<TV>

        when {
            string.startsWith('[') -> {
                try {
                    list = gson.fromJson(string, typeTvList)
                    Log.i(TAG, "导入频道 ${list.size} $list")
                } catch (e: Exception) {
                    Log.e(TAG, "str2Channels", e)
                    return false
                }
            }

            string.startsWith('#') -> {
                val lines = string.lines()
                val nameRegex = Regex("""tvg-name="([^"]+)"""")
                val logRegex = Regex("""tvg-logo="([^"]+)"""")
                val numRegex = Regex("""tvg-chno="([^"]+)"""")
                val epgRegex = Regex("""x-tvg-url="([^"]+)"""")
                val groupRegex = Regex("""group-title="([^"]+)"""")

                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<TV>>()

                var tv = TV()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    if (trimmedLine.startsWith("#EXTM3U")) {
                        epgUrl = epgRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                    } else if (trimmedLine.startsWith("#EXTINF")) {
                        val key = tv.group + tv.name
                        if (key.isNotEmpty()) {
                            tvMap[key] =
                                if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
                        }
                        tv = TV()
                        val info = trimmedLine.split(",")
                        tv.title = info.last().trim()
                        var name = nameRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        tv.name = if (name.isNullOrEmpty()) tv.title else name
                        tv.logo = logRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                        tv.number =
                            numRegex.find(info.first())?.groupValues?.get(1)?.trim()?.toInt() ?: -1
                        tv.group = groupRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                    } else if (trimmedLine.startsWith("#EXTVLCOPT:http-")) {
                        val keyValue =
                            trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                        if (keyValue.size == 2) {
                            // 修复：使用mutableMap以便后续合并Source传入的headers
                            tv.headers = if (tv.headers == null) {
                                mutableMapOf<String, String>(keyValue[0] to keyValue[1])
                            } else {
                                tv.headers!!.toMutableMap().apply {
                                    this[keyValue[0]] = keyValue[1]
                                }
                            }
                        }
                    } else if (!trimmedLine.startsWith("#")) {
                        tv.uris = if (tv.uris.isEmpty()) {
                            listOf(trimmedLine)
                        } else {
                            tv.uris.toMutableList().apply {
                                this.add(trimmedLine)
                            }
                        }
                    }
                }
                val key = tv.group + tv.name
                if (key.isNotEmpty()) {
                    tvMap[key] = if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
                }

                // 修复：M3U解析完成后，应用从Source传入的UA和Referrer
                // 这些会覆盖或补充M3U文件中的设置
                val globalHeaders = mutableMapOf<String, String>()
                if (directUA.isNotEmpty()) {
                    globalHeaders["User-Agent"] = directUA
                }
                if (directReferrer.isNotEmpty()) {
                    globalHeaders["Referer"] = directReferrer
                }

                for ((_, tvList) in tvMap) {
                    val uris = tvList.map { t -> t.uris }.flatten()
                    val t0 = tvList[0]

                    // 合并headers：M3U中的headers为基础，Source传入的会覆盖
                    val mergedHeaders = t0.headers?.toMutableMap() ?: mutableMapOf()
                    mergedHeaders.putAll(globalHeaders)

                    val t1 = TV(
                        id = -1,
                        name = t0.name,
                        title = t0.title,
                        description = t0.description,
                        logo = t0.logo,
                        image = t0.image,
                        uris = uris,
                        videoIndex = t0.videoIndex,
                        headers = mergedHeaders.ifEmpty { null },
                        group = t0.group,
                        sourceType = SourceType.UNKNOWN,
                        number = t0.number,
                        child = emptyList()
                    )
                    l.add(t1)
                }
                list = l
                Log.i(TAG, "导入频道 ${list.size} $list")
            }

            else -> {
                // TXT格式处理 - 重大修复
                val lines = string.lines()
                var group = ""

                // 修复：使用Triple结构 (group, url, headers) 避免数据混乱
                val tvMap = mutableMapOf<String, MutableList<Triple<String, String, Map<String, String>>>>()

                // 构建全局headers（从Source传入的UA和Referrer）
                val globalHeaders = mutableMapOf<String, String>()
                if (directUA.isNotEmpty()) {
                    globalHeaders["User-Agent"] = directUA
                }
                if (directReferrer.isNotEmpty()) {
                    globalHeaders["Referer"] = directReferrer
                }

                Log.i(TAG, "Final headers for TXT parsing: $globalHeaders")

                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            if (!trimmedLine.contains(",")) {
                                continue
                            }
                            val firstCommaIndex = trimmedLine.indexOf(',')
                            val title = trimmedLine.substring(0, firstCommaIndex).trim()
                            val fullUrl = trimmedLine.substring(firstCommaIndex + 1).trim()

                            val key = group + title
                            if (!tvMap.containsKey(key)) {
                                tvMap[key] = mutableListOf()
                            }
                            // 修复：使用Triple存储 (group, url, headers)
                            tvMap[key]?.add(Triple(group, fullUrl, globalHeaders))

                            Log.d(TAG, "TXT parse - Group: $group, Title: $title, URL: ${fullUrl.take(50)}..., headers: $globalHeaders")
                        }
                    }
                }

                val l = mutableListOf<TV>()
                for ((key, items) in tvMap) {
                    if (items.isEmpty()) continue

                    val channelGroup = items[0].first
                    // 修复：从第一个item获取headers（所有item的headers都一样）
                    val channelHeaders = items[0].third
                    // 修复：提取所有URL
                    val channelUris = items.map { it.second }

                    val tv = TV(
                        id = -1,
                        name = "",
                        title = key.removePrefix(channelGroup),
                        description = null,
                        logo = "",
                        image = null,
                        uris = channelUris,
                        videoIndex = 0,
                        headers = channelHeaders,
                        group = channelGroup,
                        sourceType = SourceType.UNKNOWN,
                        number = -1,
                        child = emptyList()
                    )

                    l.add(tv)
                }
                list = l
                Log.i(TAG, "导入频道 ${list.size} 个")
                list.forEachIndexed { index, tv ->
                    Log.d(TAG, "Channel $index: ${tv.title}, headers: ${tv.headers}")
                }
            }
        }

        groupModel.initTVGroup()

        val map: MutableMap<String, MutableList<TVModel>> = mutableMapOf()
        for (v in list) {
            if (v.group !in map) {
                map[v.group] = mutableListOf()
            }
            map[v.group]?.add(TVModel(v))
        }

        val listModelNew: MutableList<TVModel> = mutableListOf()
        var groupIndex = 2
        var id = 0
        for ((k, v) in map) {
            val listTVModel = TVListModel(k.ifEmpty { "未知" }, groupIndex)
            for ((listIndex, v1) in v.withIndex()) {
                v1.tv.id = id
                v1.setLike(SP.getLike(id))
                v1.setGroupIndex(groupIndex)
                v1.listIndex = listIndex
                listTVModel.addTVModel(v1)
                listModelNew.add(v1)
                id++
            }
            groupModel.addTVListModel(listTVModel)
            groupIndex++
        }

        listModel = listModelNew

        groupModel.tvGroupValue[1].setTVListModel(listModel)

        if (string != cacheChannels && g.encode(string) != cacheChannels) {
            groupModel.initPosition()
        }

        groupModel.setChange()

        viewModelScope.launch {
            preloadLogo()
        }

        return true
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
        const val CACHE_EPG = "epg.xml"
        val DEFAULT_CHANNELS_FILE = R.raw.channels
    }
}