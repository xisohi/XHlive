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

    // 添加一个Map来缓存URL和UA的对应关系
    private val uaCache = mutableMapOf<String, String>()

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

        if (cacheChannels.isEmpty()) {
            Log.i(TAG, "cacheChannels isEmpty")
            cacheChannels =
                context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
        }

        Log.i(TAG, "cacheChannels $cacheFile $cacheChannels")

        try {
            str2Channels(cacheChannels)
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

    private suspend fun importFromUrl(url: String, id: String = "", ua: String = "") {
        val urls = getUrls(url).map { Pair(it, url) }

        var err = 0
        var shouldBreak = false
        for ((a, b) in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    // 创建带有 UA 的请求
                    val requestBuilder = okhttp3.Request.Builder().url(a)
                    if (ua.isNotEmpty()) {
                        requestBuilder.addHeader("User-Agent", ua)
                        Log.i(TAG, "Using UA for request: $ua")
                    }
                    val request = requestBuilder.build()

                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val str = response.bodyAlias()?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            tryStr2Channels(str, null, b, id, ua)
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

    fun importFromUri(uri: Uri, id: String = "", ua: String = "") {
        Log.i(TAG, "=== importFromUri ===")
        Log.i(TAG, "uri: $uri")
        Log.i(TAG, "id: $id")
        Log.i(TAG, "ua: '$ua'")  // 注意这里用引号括起来，方便看到空字符串

        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }

            tryStr2Channels(str, file, uri.toString(), id, ua)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id, ua)
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
            // 保存到缓存
            uaCache[url] = ua

            // 保存到SharedPreferences
            val prefs = SP.getSharedPreferences()
            prefs.edit().putString("ua_${url.hashCode()}", ua).apply()
            Log.i(TAG, "✅ Saved UA for $url: $ua")
        } else {
            Log.w(TAG, "⚠️ Attempted to save empty UA for $url")
        }
    }

    /**
     * 获取URL对应的UA
     */
    fun getUAForUrl(url: String): String {
        Log.i(TAG, "=== getUAForUrl ===")
        Log.i(TAG, "Looking up UA for url: $url")

        // 先从缓存获取
        uaCache[url]?.let {
            Log.i(TAG, "✅ Found in cache: '$it'")
            return it
        }

        // 缓存没有，从SharedPreferences获取
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

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "", ua: String = "") {
        try {
            // 直接将ua参数传递给str2Channels
            if (str2Channels(str, url, ua)) {  // 添加ua参数
                Log.i(TAG, "write to cacheFile $cacheFile")
                cacheFile!!.writeText(str)
                Log.i(TAG, "cacheFile ${getCache()}")
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                    val source = Source(
                        id = id,
                        uri = url,
                        ua = ua
                    )
                    sources.addSource(source)

                    // 保存UA供以后使用
                    if (ua.isNotEmpty()) {
                        saveUAForUrl(url, ua)
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

    // 修改 str2Channels 方法，添加 directUA 参数
    private fun str2Channels(str: String, sourceUrl: String = "", directUA: String = ""): Boolean {
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
                            tv.headers = if (tv.headers == null) {
                                mapOf<String, String>(keyValue[0] to keyValue[1])
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
                for ((_, tvList) in tvMap) {
                    val uris = tvList.map { t -> t.uris }.flatten()
                    val t0 = tvList[0]
                    // 根据TV类的构造函数修正
                    val t1 = TV(
                        id = -1,
                        name = t0.name,
                        title = t0.title,
                        description = t0.description,
                        logo = t0.logo,
                        image = t0.image,
                        uris = uris,
                        videoIndex = t0.videoIndex,
                        headers = t0.headers,
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
                // TXT格式处理
                val lines = string.lines()
                var group = ""

                // 修改为存储：key -> Pair(分组, List<Pair<URL, UA>>)
                val tvMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

                // 🔥 优先使用直接传入的UA
                val sourceUA = if (directUA.isNotEmpty()) {
                    Log.i(TAG, "📢 Using direct UA from parameter: '$directUA'")
                    directUA
                } else if (sourceUrl.isNotEmpty()) {
                    getUAForUrl(sourceUrl)
                } else {
                    ""
                }

                Log.i(TAG, "Final sourceUA: '$sourceUA'")

                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            if (!trimmedLine.contains(",")) {
                                continue
                            }
                            // 只分割第一个逗号，保留后面的完整URL（包括所有参数）
                            val firstCommaIndex = trimmedLine.indexOf(',')
                            val title = trimmedLine.substring(0, firstCommaIndex).trim()
                            val fullUrl = trimmedLine.substring(firstCommaIndex + 1).trim()

                            val key = group + title
                            if (!tvMap.containsKey(key)) {
                                // 第一个元素存储分组信息
                                tvMap[key] = mutableListOf(Pair(group, sourceUA))
                            }

                            // 添加URL，并关联UA
                            tvMap[key]?.add(Pair(fullUrl, sourceUA))

                            Log.d(TAG, "TXT parse - Group: $group, Title: $title, URL: ${fullUrl.take(50)}..., UA: $sourceUA")
                        }
                    }
                }

                val l = mutableListOf<TV>()
                for ((key, items) in tvMap) {
                    if (items.size < 2) continue  // 至少需要分组信息和至少一个URL

                    val channelGroup = items[0].first
                    val channelUA = items[0].second  // 获取这个频道的UA

                    // 提取所有URL（跳过第一个分组信息）
                    val channelUris = items.drop(1).map { it.first }

                    // 创建headers，如果有UA的话
                    val headers = if (channelUA.isNotEmpty()) {
                        Log.i(TAG, "🎯 Adding UA to channel $key: $channelUA")
                        mapOf("User-Agent" to channelUA)
                    } else {
                        emptyMap()
                    }

                    // 根据TV类的构造函数修正
                    val tv = TV(
                        id = -1,
                        name = "",
                        title = key.removePrefix(channelGroup),
                        description = null,
                        logo = "",
                        image = null,
                        uris = channelUris,
                        videoIndex = 0,
                        headers = headers,
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
                    val ua = tv.headers?.get("User-Agent") ?: "无"
                    Log.d(TAG, "Channel $index: ${tv.title}, UA: $ua")
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

        // 全部频道
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