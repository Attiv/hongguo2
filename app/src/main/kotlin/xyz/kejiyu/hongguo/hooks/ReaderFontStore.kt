package xyz.kejiyu.hongguo.hooks

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import xyz.kejiyu.hongguo.LogUtil
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * 阅读器自定义字体库。
 *
 * 字体文件落在宿主进程的 filesDir/hongguo_fonts/，元数据存模块 SharedPreferences
 * （lspilot_kejiyu，与模块其余设置同库）。所有调用都发生在宿主进程内，
 * 因此不存在跨进程文件访问问题。方案与证据见 docs/plans/2026-10-13-reader-custom-font.md。
 */
object ReaderFontStore {

    /** 与面板开关共用同一个偏好键（由 Hooks.savePref 写入）。 */
    const val PREF_ENABLED = "reader_font_enabled"

    private const val PREF_SELECTED = "reader_font_selected"
    private const val PREF_LIST = "reader_font_list"
    private const val PREFS_NAME = "lspilot_kejiyu"
    private const val DIR_NAME = "hongguo_fonts"

    /** Typeface 是 mmap 的，同时持有过多会明显吃内存，按最近使用保留少量。 */
    private const val CACHE_MAX = 5
    private const val MIN_FILE_SIZE = 1024L

    data class Item(val id: String, val name: String, val fileName: String)

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedItems: List<Item>? = null

    private val cacheLock = Any()
    private val tfCache = object : LinkedHashMap<String, Typeface>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean =
            size > CACHE_MAX
    }

    fun attach(ctx: Context?) {
        if (ctx == null) return
        if (appContext == null) appContext = ctx.applicationContext ?: ctx
    }

    private fun prefs(): SharedPreferences? =
        try {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }

    private fun fontsDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR_NAME)
        if (dir.isDirectory) return dir
        return if (dir.mkdirs() || dir.isDirectory) dir else null
    }

    // ---------------------------------------------------------------- 开关与选择

    fun isEnabled(): Boolean =
        try {
            prefs()?.getBoolean(PREF_ENABLED, false) ?: false
        } catch (_: Throwable) {
            false
        }

    fun setEnabled(enabled: Boolean) {
        try {
            prefs()?.edit()?.putBoolean(PREF_ENABLED, enabled)?.apply()
        } catch (_: Throwable) {
        }
    }

    fun selectedId(): String =
        try {
            prefs()?.getString(PREF_SELECTED, "") ?: ""
        } catch (_: Throwable) {
            ""
        }

    fun select(id: String) {
        try {
            prefs()?.edit()?.putString(PREF_SELECTED, id)?.apply()
        } catch (_: Throwable) {
        }
    }

    fun selectedItem(): Item? {
        val id = selectedId()
        if (id.isEmpty()) return null
        return items().firstOrNull { it.id == id }
    }

    // ---------------------------------------------------------------- 字体列表

    @Synchronized
    fun items(): List<Item> {
        cachedItems?.let { return it }
        val loaded = readItems()
        cachedItems = loaded
        return loaded
    }

    private fun readItems(): List<Item> {
        val raw = try {
            prefs()?.getString(PREF_LIST, null)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        return decodeItems(raw)
    }

    internal fun writeItems(list: List<Item>) {
        try {
            prefs()?.edit()?.putString(PREF_LIST, encodeItems(list))?.apply()
        } catch (e: Throwable) {
            LogUtil.error("字体库：列表写入失败", e)
        }
        cachedItems = list
    }

    internal fun encodeItems(list: List<Item>): String {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("file", item.fileName)
            )
        }
        return arr.toString()
    }

    internal fun decodeItems(raw: String): List<Item> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            val fileName = o.optString("file", "")
            if (id.isEmpty() || fileName.isEmpty()) return@mapNotNull null
            Item(id, o.optString("name", "自定义字体"), fileName)
        }
    } catch (e: Throwable) {
        LogUtil.warn("字体库：列表解析失败 ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    /** 仅供测试：清掉进程内缓存与已绑定的 Context。 */
    internal fun resetForTest() {
        appContext = null
        cachedItems = null
        synchronized(cacheLock) { tfCache.clear() }
    }

    // ---------------------------------------------------------------- 增删

    /**
     * 从 SAF 返回的 uri 导入字体。返回 null 表示成功，否则返回给用户看的失败原因。
     * 需要自行保证不在主线程调用（会做文件 IO 与 Typeface 解析）。
     */
    fun import(ctx: Context, uri: Uri): String? {
        attach(ctx)
        val dir = fontsDir() ?: return "字体目录不可用"
        val resolver = ctx.contentResolver

        val displayName = queryDisplayName(ctx, uri)
        val lowerName = displayName.lowercase()
        if (lowerName.endsWith(".ttc") || lowerName.endsWith(".otc")) {
            return "不支持 TTC 字体集文件，请选择单个 .ttf / .otf"
        }

        val header = ByteArray(8)
        val headerLen = try {
            resolver.openInputStream(uri)?.use { readFully(it, header) } ?: 0
        } catch (e: Throwable) {
            LogUtil.warn("字体库：读取文件头失败 ${e.message}")
            0
        }
        val kind = classify(header, headerLen)
            ?: return unsupportedReason(header, headerLen)
            ?: "不是有效的字体文件（仅支持 .ttf / .otf）"

        val fileName = "font_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.$kind"
        val target = File(dir, fileName)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return "无法读取所选文件"
        } catch (e: Throwable) {
            target.delete()
            LogUtil.error("字体库：复制失败", e)
            return "复制失败：${e.javaClass.simpleName}"
        }

        if (target.length() < MIN_FILE_SIZE) {
            target.delete()
            return "文件过小，可能不是字体文件"
        }

        val usable = try {
            Typeface.createFromFile(target)
        } catch (e: Throwable) {
            LogUtil.warn("字体库：解析失败 ${e.message}")
            null
        }
        if (usable == null) {
            target.delete()
            return "系统无法解析该字体文件"
        }

        val name = displayName.substringBeforeLast('.').ifBlank { "自定义字体" }
        val item = Item(UUID.randomUUID().toString(), name, fileName)
        writeItems(listOf(item) + items())
        synchronized(cacheLock) { tfCache[item.id] = usable }
        LogUtil.info("字体库：已添加「$name」(${kind}, ${target.length()} bytes)")
        return null
    }

    fun delete(id: String) {
        val list = items()
        val target = list.firstOrNull { it.id == id } ?: return
        try {
            fontsDir()?.let { File(it, target.fileName).delete() }
        } catch (e: Throwable) {
            LogUtil.warn("字体库：删除文件失败 ${e.message}")
        }
        synchronized(cacheLock) { tfCache.remove(id) }
        if (selectedId() == id) select("")
        writeItems(list.filterNot { it.id == id })
        LogUtil.info("字体库：已删除「${target.name}」")
    }

    // ---------------------------------------------------------------- Typeface

    /** 需要替换时返回自定义 Typeface；返回 null 表示不干预宿主。 */
    fun currentTypeface(): Typeface? {
        if (!isEnabled()) return null
        val item = selectedItem() ?: return null
        return typefaceOf(item)
    }

    fun typefaceOf(item: Item): Typeface? {
        synchronized(cacheLock) { tfCache[item.id] }?.let { return it }
        val dir = fontsDir() ?: return null
        val file = File(dir, item.fileName)
        if (!file.isFile) {
            LogUtil.warn("字体库：文件缺失 ${item.fileName}")
            return null
        }
        return try {
            val tf = Typeface.createFromFile(file)
            if (tf == null) {
                LogUtil.warn("字体库：加载返回 null ${item.fileName}")
                null
            } else {
                synchronized(cacheLock) { tfCache[item.id] = tf }
                tf
            }
        } catch (e: Throwable) {
            LogUtil.warn("字体库：加载失败 ${item.fileName} | ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun boldOf(item: Item, base: Typeface): Typeface {
        val key = "${item.id}#bold"
        synchronized(cacheLock) { tfCache[key] }?.let { return it }
        val bold = try {
            Typeface.create(base, Typeface.BOLD)
        } catch (_: Throwable) {
            base
        }
        synchronized(cacheLock) { tfCache[key] = bold }
        return bold
    }

    fun refreshCache() {
        synchronized(cacheLock) { tfCache.clear() }
    }

    // ---------------------------------------------------------------- 工具

    private fun queryDisplayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.let { if (it.isNotBlank()) return it }
                }
            }
        } catch (e: Throwable) {
            LogUtil.warn("字体库：读取文件名失败 ${e.message}")
        }
        return uri.lastPathSegment ?: "自定义字体"
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    /**
     * 对"确实是字体、但不是 Android 能直接加载的容器"给出更具体的原因。
     *
     * classify 对这类格式统一返回 null（不可用），这里只负责把话说清楚——
     * 单看文件名判断不够：把 Songti.ttc 改名成 .ttf 后仍应命中 TTC 提示。
     */
    internal fun unsupportedReason(header: ByteArray, len: Int): String? {
        if (len < 4) return null
        return when (String(header, 0, 4, Charsets.US_ASCII)) {
            "ttcf" -> "不支持 TTC 字体集，请选择单个 .ttf / .otf 文件"
            "wOFF" -> "不支持 WOFF 网页字体，请先转换为 .ttf / .otf"
            "wOF2" -> "不支持 WOFF2 网页字体，请先转换为 .ttf / .otf"
            else -> null
        }
    }

    /** 识别字体容器格式；不支持的格式返回 null。 */
    internal fun classify(header: ByteArray, len: Int): String? {
        if (len < 4) return null
        val tag = String(header, 0, 4, Charsets.US_ASCII)
        return when {
            header[0] == 0x00.toByte() && header[1] == 0x01.toByte() &&
                header[2] == 0x00.toByte() && header[3] == 0x00.toByte() -> "ttf"
            tag == "OTTO" -> "otf"
            tag == "true" -> "ttf"
            tag == "typ1" -> "ttf"
            tag == "ttcf" -> null
            tag == "wOFF" || tag == "wOF2" -> null
            else -> null
        }
    }
}
