package com.lizongying.mytv0.models

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.Source

class Sources {
    var version = 0

    private val _removed = MutableLiveData<Pair<Int, Int>>()
    val removed: LiveData<Pair<Int, Int>>
        get() = _removed

    private val _added = MutableLiveData<Pair<Int, Int>>()
    val added: LiveData<Pair<Int, Int>>
        get() = _added

    private val _changed = MutableLiveData<Int>()
    val changed: LiveData<Int>
        get() = _changed

    private val _sources = MutableLiveData<List<Source>>()
    val sources: LiveData<List<Source>>
        get() = _sources
    private val sourcesValue: List<Source>
        get() = _sources.value ?: emptyList()

    private val _checked = MutableLiveData<Int>()
    val checked: LiveData<Int>
        get() = _checked
    val checkedValue: Int
        get() = _checked.value ?: DEFAULT_CHECKED

    fun setChecked(position: Int) {
        _checked.value = position
    }

    fun setSourceChecked(position: Int, checked: Boolean): Boolean {
        val source = getSource(position) ?: return false
        if (source.checked == checked) {
            return false
        } else {
            source.checked = checked
            // 如果设置为选中，更新checked值
            if (checked) {
                Log.i(TAG, "setChecked $position")
                setChecked(position)
            }
            // 保存到SP
            SP.sources = gson.toJson(sourcesValue, typeSourceList) ?: ""
            return true
        }
    }

    private fun setSources(sources: List<Source>) {
        _sources.value = sources
        SP.sources = gson.toJson(sources, typeSourceList) ?: ""
    }

    fun addSource(source: Source) {
        // 检查是否已存在相同URI的源
        val index = sourcesValue.indexOfFirst { it.uri == source.uri }
        if (index == -1) {
            // 清除之前的选中状态
            if (checkedValue in 0 until sourcesValue.size) {
                setSourceChecked(checkedValue, false)
            }

            // 确保source有完整的字段
            val completeSource = source.copy(
                id = if (source.id.isNullOrEmpty()) java.util.UUID.randomUUID().toString() else source.id,
                name = if (source.name.isEmpty()) {
                    // 自动生成名称
                    try {
                        val uri = android.net.Uri.parse(source.uri)
                        uri.lastPathSegment?.substringBeforeLast(".") ?: uri.host ?: source.uri
                    } catch (e: Exception) {
                        source.uri.substringAfterLast("/").substringBefore("?").ifEmpty { source.uri }
                    }
                } else source.name
            )

            // 添加新源到列表开头
            _sources.value = sourcesValue.toMutableList().apply {
                add(0, completeSource)
            }

            // 选中新添加的源
            _checked.value = 0
            setSourceChecked(0, true)

            // 保存到SP
            SP.sources = gson.toJson(sourcesValue, typeSourceList) ?: ""

            // 打印UA信息以便调试
            Log.i(TAG, "Added source with UA: ${completeSource.ua}, Referrer: ${completeSource.referrer}")

            // 通知变化
            _changed.value = version
            version++

            // 通知添加
            _added.value = Pair(0, version)
        } else {
            Log.i(TAG, "Source with URI ${source.uri} already exists")
            // 如果存在，更新它的UA和Referrer
            val existingSource = sourcesValue[index]
            if (existingSource.ua != source.ua || existingSource.referrer != source.referrer) {
                val updatedSource = existingSource.copy(
                    ua = if (source.ua.isNotEmpty()) source.ua else existingSource.ua,
                    referrer = if (source.referrer.isNotEmpty()) source.referrer else existingSource.referrer
                )
                _sources.value = sourcesValue.toMutableList().apply {
                    set(index, updatedSource)
                }
                SP.sources = gson.toJson(sourcesValue, typeSourceList) ?: ""
                Log.i(TAG, "Updated source UA: ${updatedSource.ua}, Referrer: ${updatedSource.referrer}")
            }
        }
    }

    fun removeSource(id: String): Boolean {
        if (sourcesValue.isEmpty()) {
            Log.i(TAG, "sources is empty")
            return false
        }

        val index = sourcesValue.indexOfFirst { it.id == id }
        if (index != -1) {
            _sources.value = sourcesValue.toMutableList().apply {
                removeAt(index)
            }
            SP.sources = gson.toJson(sourcesValue, typeSourceList) ?: ""

            _removed.value = Pair(index, version)
            version++
            return true
        }

        Log.i(TAG, "sourceId is not exists")
        return false
    }

    fun getSource(idx: Int): Source? {
        if (idx < 0 || idx >= size()) {
            return null
        }

        return sourcesValue[idx]
    }

    fun getSourceNameForDisplay(idx: Int): String {
        val source = getSource(idx) ?: return ""

        // 优先使用 name 字段
        if (source.name.isNotEmpty()) {
            return source.name
        }

        // 否则从 URI 识别
        return try {
            val uri = android.net.Uri.parse(source.uri)
            val lastPathSegment = uri.lastPathSegment
            if (!lastPathSegment.isNullOrEmpty()) {
                // 去除文件扩展名
                lastPathSegment.substringBeforeLast(".").ifEmpty { uri.host ?: source.uri }
            } else {
                uri.host ?: source.uri
            }
        } catch (e: Exception) {
            // 如果URI解析失败，返回原始URI的简化版本
            source.uri.substringAfterLast("/").substringBefore("?").ifEmpty { source.uri }
        }
    }
    fun debugPrintSources() {
        Log.d(TAG, "=== Sources Debug ===")
        sourcesValue.forEachIndexed { index, source ->
            Log.d(TAG, "Source[$index]:")
            Log.d(TAG, "  id: ${source.id}")
            Log.d(TAG, "  uri: ${source.uri}")
            Log.d(TAG, "  name: ${source.name}")
            Log.d(TAG, "  ua: ${source.ua}")
            Log.d(TAG, "  referrer: ${source.referrer}")
            Log.d(TAG, "  checked: ${source.checked}")
        }
        Log.d(TAG, "===================")
    }
    fun init() {
        if (!SP.sources.isNullOrEmpty()) {
            try {
                val sources: List<Source> = gson.fromJson(SP.sources!!, typeSourceList)
                setSources(sources.map { it.apply { checked = false } })
            } catch (e: Exception) {
                e.printStackTrace()
                SP.sources = SP.DEFAULT_SOURCES
            }
        }

        if (size() > 0) {
            _checked.value = sourcesValue.indexOfFirst { it.uri == SP.configUrl }

            if (checkedValue > -1) {
                setSourceChecked(checkedValue, true)
            }
        }

        _changed.value = version
        version++
    }

    init {
        init()
    }

    fun size(): Int {
        return sourcesValue.size
    }

    companion object {
        const val TAG = "Sources"
        const val DEFAULT_CHECKED = -1
    }
}