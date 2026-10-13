package xyz.kejiyu.hongguo.hooks

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 阅读字体库的行为回归。格式识别与列表读写是纯逻辑，直接覆盖；
 * 导入链路用真实 TTF（APK 里的 roboto_regular.ttf）走一遍文件复制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderFontStoreTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ReaderFontStore.resetForTest()
        ctx = RuntimeEnvironment.getApplication()
        ReaderFontStore.attach(ctx)
    }

    @Test
    fun classifyAcceptsTrueTypeAndCffRejectsCollectionsAndWebFonts() {
        assertEquals("ttf", ReaderFontStore.classify(byteArrayOf(0, 1, 0, 0, 0x0E, 0, 0x80.toByte(), 0), 8))
        assertEquals("otf", ReaderFontStore.classify("OTTO1234".toByteArray(), 8))
        assertEquals("ttf", ReaderFontStore.classify("true1234".toByteArray(), 8))
        assertNull(ReaderFontStore.classify("ttcf1234".toByteArray(), 8))
        assertNull(ReaderFontStore.classify("wOFF1234".toByteArray(), 8))
        assertNull(ReaderFontStore.classify("wOF21234".toByteArray(), 8))
        assertNull(ReaderFontStore.classify("PK\u0003\u0004abcd".toByteArray(), 8))
        assertNull(ReaderFontStore.classify(byteArrayOf(0, 1), 2))
    }

    @Test
    fun itemsRoundTripThroughPrefs() {
        val a = ReaderFontStore.Item("id-a", "字体 A", "a.ttf")
        val b = ReaderFontStore.Item("id-b", "字体 B", "b.otf")
        ReaderFontStore.writeItems(listOf(a, b))
        assertEquals(listOf(a, b), ReaderFontStore.items())

        ReaderFontStore.resetForTest()
        ReaderFontStore.attach(ctx)
        assertEquals(listOf(a, b), ReaderFontStore.items())
    }

    @Test
    fun decodeSkipsEntriesWithoutIdOrFile() {
        val raw = """[{"id":"x","name":"X","file":"x.ttf"},""".trimEnd() +
            """{"id":"","name":"bad","file":"y.ttf"},{"name":"no-file"}]"""
        assertEquals(listOf(ReaderFontStore.Item("x", "X", "x.ttf")), ReaderFontStore.decodeItems(raw))
        assertTrue(ReaderFontStore.decodeItems("not-json").isEmpty())
    }

    @Test
    fun deleteDropsSelectionBackToHostFont() {
        ReaderFontStore.writeItems(listOf(ReaderFontStore.Item("id-a", "A", "gone.ttf")))
        ReaderFontStore.select("id-a")
        assertEquals("id-a", ReaderFontStore.selectedId())
        assertNotNull(ReaderFontStore.selectedItem())

        ReaderFontStore.delete("id-a")
        assertEquals("", ReaderFontStore.selectedId())
        assertTrue(ReaderFontStore.items().isEmpty())
    }

    @Test
    fun unsupportedReasonNamesTheContainerEvenWhenExtensionLies() {
        // 真机上踩到过：Songti.ttc 改名成 .ttf 后只按文件名判断，会退化成"不是有效的字体文件"。
        assertTrue(ReaderFontStore.unsupportedReason("ttcf0000".toByteArray(), 8)!!.contains("TTC"))
        assertTrue(ReaderFontStore.unsupportedReason("wOFF0000".toByteArray(), 8)!!.contains("WOFF"))
        assertTrue(ReaderFontStore.unsupportedReason("wOF20000".toByteArray(), 8)!!.contains("WOFF2"))
        assertNull(ReaderFontStore.unsupportedReason("OTTO0000".toByteArray(), 8))
        assertNull(ReaderFontStore.unsupportedReason(byteArrayOf(0, 1), 2))
    }

    @Test
    fun importRejectsCollectionDisguisedAsSingleFontFile() {
        val disguised = "ttcf".toByteArray() + ByteArray(4092) { 0x22 }
        val error = ReaderFontStore.import(ctx, Uri.fromFile(tempFile("Disguised.ttf", disguised)))
        assertNotNull(error)
        assertTrue(error!!.contains("TTC"))
        assertTrue(ReaderFontStore.items().isEmpty())
        assertTrue(File(ctx.filesDir, "hongguo_fonts").listFiles().isNullOrEmpty())
    }

    @Test
    fun importRejectsFontCollectionsAndNonFontFiles() {
        val ttc = tempFile("collection.ttc", ByteArray(4096) { 0x11 })
        val ttcError = ReaderFontStore.import(ctx, Uri.fromFile(ttc))
        assertNotNull(ttcError)
        assertTrue(ttcError!!.contains("TTC"))

        val junk = tempFile("junk.ttf", ByteArray(4096) { 0x5A })
        val junkError = ReaderFontStore.import(ctx, Uri.fromFile(junk))
        assertNotNull(junkError)
        assertTrue(junkError!!.contains("字体文件"))

        assertTrue(ReaderFontStore.items().isEmpty())
        assertTrue(File(ctx.filesDir, "hongguo_fonts").listFiles().isNullOrEmpty())
    }

    @Test
    fun importCopiesRealFontAndKeepsItSelectable() {
        assertNull(ReaderFontStore.import(ctx, Uri.fromFile(tempFile("MyFont.ttf", realFontBytes()))))

        val items = ReaderFontStore.items()
        assertEquals(1, items.size)
        assertEquals("MyFont", items[0].name)
        assertTrue(File(File(ctx.filesDir, "hongguo_fonts"), items[0].fileName).length() > 0)

        ReaderFontStore.select(items[0].id)
        ReaderFontStore.setEnabled(true)
        assertTrue(ReaderFontStore.isEnabled())
        assertEquals(items[0], ReaderFontStore.selectedItem())
    }

    @Test
    fun disabledFeatureNeverHandsOutATypeface() {
        ReaderFontStore.writeItems(listOf(ReaderFontStore.Item("id-a", "A", "missing.ttf")))
        ReaderFontStore.select("id-a")
        ReaderFontStore.setEnabled(false)
        assertNull(ReaderFontStore.currentTypeface())

        ReaderFontStore.setEnabled(true)
        assertNull(ReaderFontStore.currentTypeface())
    }

    private fun tempFile(name: String, bytes: ByteArray): File =
        File(ctx.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private fun realFontBytes(): ByteArray =
        javaClass.getResourceAsStream("/roboto_regular.ttf")!!.use { it.readBytes() }
}
