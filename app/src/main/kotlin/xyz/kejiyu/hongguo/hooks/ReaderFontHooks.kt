package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 小说阅读页自定义字体的 Hook 层。
 *
 * 为什么挂在 TypefaceManager 而不是 TextView.setTypeface：
 * 阅读器正文由字节自研排版引擎（com.ttreader.tttext）直接绘制，
 * 正文在无障碍树里不可见（实测 7.3.9.32），TextView 那条路改不到正文。
 * 而阅读器的字体解析全部收敛在 com.dragon.read.reader.newfont.TypefaceManager，
 * 它在阅读器代码路径上被调用（ReaderDrawOpCache.loadFont / ReaderFontServiceImpl），
 * 天然自带范围隔离，不影响信息流、播放页等其他界面。
 *
 * 见 docs/plans/2026-10-13-reader-custom-font.md。
 */
object ReaderFontHooks {

    const val REQ_PICK_FONT = 0x4A47

    private const val TYPEFACE_MANAGER = "com.dragon.read.reader.newfont.TypefaceManager"

    /** 已实测（7.3.9.32）与同族简化版阅读页。 */
    private val READER_ACTIVITIES = setOf(
        "com.dragon.read.reader.ui.ReaderActivity",
        "com.dragon.read.reader.simplenesseader.SimpleReaderActivity",
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var module: MainHook? = null
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var installed = false
    @Volatile private var hookedSignatures: List<String> = emptyList()
    @Volatile private var readerForeground = false
    @Volatile private var pendingPick = false

    /** 字体库变化后的回调（由面板注册，用于刷新当前字体摘要）。 */
    @Volatile var libraryChanged: (() -> Unit)? = null

    // ---------------------------------------------------------------- 安装

    fun install(module: MainHook, cl: ClassLoader): Boolean {
        this.module = module
        this.classLoader = cl
        val clazz = try {
            Class.forName(TYPEFACE_MANAGER, false, cl)
        } catch (e: Throwable) {
            LogUtil.warn("阅读字体：未找到 $TYPEFACE_MANAGER（该版本暂不适用，功能自动关闭）")
            return false
        }

        // 按签名收集，不依赖混淆后的方法名（i/l/e/g/b 会随版本变化）。
        val candidates = clazz.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.returnType == Typeface::class.java
        }
        if (candidates.isEmpty()) {
            LogUtil.warn("阅读字体：$TYPEFACE_MANAGER 内无静态 Typeface 返回方法，跳过")
            return false
        }

        val sigs = mutableListOf<String>()
        candidates.forEachIndexed { index, method ->
            val params = method.parameterTypes
            try {
                module.hook(method)
                    .setId("readerFont_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain -> resolve(chain, params) })
                sigs += describe(method)
            } catch (e: Throwable) {
                LogUtil.warn("阅读字体：Hook ${describe(method)} 失败 | ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        hookedSignatures = sigs
        installed = sigs.isNotEmpty()
        if (installed) {
            LogUtil.info("阅读字体：已接管 ${sigs.size} 个字体解析入口 -> ${sigs.joinToString("; ")}")
        } else {
            LogUtil.warn("阅读字体：没有任何入口 Hook 成功，功能不可用")
        }
        return installed
    }

    private fun describe(method: Method): String {
        val params = method.parameterTypes.joinToString(",") { it.simpleName }
        return "${method.name}($params)"
    }

    fun hookedSummary(): String = hookedSignatures.joinToString("; ")

    fun isAvailable(): Boolean = installed

    // ---------------------------------------------------------------- 替换

    private fun resolve(chain: XposedInterface.Chain, params: Array<Class<*>>): Any? {
        val item = ReaderFontStore.selectedItem()
        if (item == null || !ReaderFontStore.isEnabled()) return chain.proceed()
        val base = ReaderFontStore.typefaceOf(item) ?: return chain.proceed()
        LogUtil.incr("readerFontResolve")
        return if (wantsBold(chain, params)) ReaderFontStore.boldOf(item, base) else base
    }

    /** 宿主用 int 权重或 FontStyle 枚举表达粗体，按签名位置读取，不猜方法名。 */
    private fun wantsBold(chain: XposedInterface.Chain, params: Array<Class<*>>): Boolean {
        for (i in params.indices) {
            val type = params[i]
            val arg = try {
                chain.getArg(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            when {
                type == Int::class.javaPrimitiveType || type == Integer::class.java ->
                    if ((arg as? Int ?: 0) > 400) return true
                type.isEnum -> {
                    val name = (arg as? Enum<*>)?.name ?: ""
                    if (name.contains("Bold", ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    // ---------------------------------------------------------------- 阅读页状态

    fun noteActivity(a: Activity?, resumed: Boolean) {
        if (a == null) return
        if (a.javaClass.name !in READER_ACTIVITIES) return
        if (readerForeground == resumed) return
        readerForeground = resumed
        LogUtil.info("阅读字体：阅读页 ${if (resumed) "进入" else "离开"} (${a.javaClass.simpleName})")
    }

    fun isReaderForeground(): Boolean = readerForeground

    /**
     * 切换字体后让阅读页立即生效：清掉双方的 Typeface 缓存，并在阅读页前台时重载它
     * （阅读位置由宿主自己持久化并恢复，见 ReaderDrawOpCache 的 page_/offset_ 记录）。
     */
    fun refreshReader(a: Activity?) {
        ReaderFontStore.refreshCache()
        clearHostCaches()
        val act = a ?: return
        if (act.javaClass.name !in READER_ACTIVITIES) return
        mainHandler.postDelayed({
            try {
                if (!act.isFinishing && !act.isDestroyed) {
                    LogUtil.info("阅读字体：重载阅读页以应用新字体")
                    act.recreate()
                }
            } catch (e: Throwable) {
                LogUtil.warn("阅读字体：重载阅读页失败 ${e.javaClass.simpleName}: ${e.message}")
            }
        }, 300L)
    }

    private fun clearHostCaches() {
        val cl = classLoader ?: return
        try {
            val clazz = Class.forName(TYPEFACE_MANAGER, false, cl)
            clazz.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
                .forEach { field ->
                    try {
                        field.isAccessible = true
                        (field.get(null) as? MutableMap<*, *>)?.clear()
                    } catch (_: Throwable) {
                    }
                }
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 文件选择

    fun pickFont(a: Activity) {
        try {
            pendingPick = true
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "font/ttf",
                        "font/otf",
                        "application/x-font-ttf",
                        "application/x-font-opentype",
                        "application/octet-stream",
                    ),
                )
            }
            hookResultBridge(a)
            a.startActivityForResult(intent, REQ_PICK_FONT)
        } catch (e: Throwable) {
            pendingPick = false
            LogUtil.error("阅读字体：打开文件选择器失败", e)
            toast(a.applicationContext, "无法打开文件选择器")
        }
    }

    /**
     * 结果回传：宿主 Activity 常常自己覆写 onActivityResult，只挂基类会漏。
     * 因此按需把当前 Activity 的覆写实现也挂上；pendingPick 保证只处理一次。
     */
    private fun hookResultBridge(a: Activity) {
        val mod = module ?: return
        var cls: Class<*>? = a.javaClass
        while (cls != null && cls != Activity::class.java) {
            try {
                val m = cls.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java,
                )
                try {
                    mod.hook(m)
                        .setId("readerFontResult_${cls.simpleName}")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain -> resultInterceptor(chain) })
                    LogUtil.info("阅读字体：结果回传已挂在 ${cls.name}")
                } catch (_: Throwable) {
                }
                return
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }

    /** 供 Hooks 里 android.app.Activity.onActivityResult 的基类 Hook 复用。 */
    fun resultInterceptor(chain: XposedInterface.Chain): Any? {
        val act = try {
            chain.thisObject as? Activity
        } catch (_: Throwable) {
            null
        }
        val requestCode = try {
            chain.getArg(0) as? Int ?: -1
        } catch (_: Throwable) {
            -1
        }
        val result = chain.proceed()
        if (requestCode == REQ_PICK_FONT) {
            val resultCode = try {
                chain.getArg(1) as? Int ?: Activity.RESULT_CANCELED
            } catch (_: Throwable) {
                Activity.RESULT_CANCELED
            }
            val data = try {
                chain.getArg(2) as? Intent
            } catch (_: Throwable) {
                null
            }
            handlePickResult(act, resultCode, data)
        }
        return result
    }

    private fun handlePickResult(act: Activity?, resultCode: Int, data: Intent?) {
        if (!pendingPick) return
        pendingPick = false
        val activity = act ?: return
        val ctx = activity.applicationContext ?: return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            LogUtil.info("阅读字体：已取消选择")
            return
        }
        toast(ctx, "正在导入字体…")
        Thread {
            val error = ReaderFontStore.import(ctx, uri)
            mainHandler.post {
                if (error == null) {
                    toast(ctx, "字体已添加，可在「阅读字体」里选用")
                    try {
                        libraryChanged?.invoke()
                    } catch (_: Throwable) {
                    }
                } else {
                    LogUtil.warn("阅读字体：导入失败 $error")
                    toast(ctx, error)
                }
            }
        }.start()
    }

    private fun toast(ctx: android.content.Context?, text: String) {
        if (ctx == null) return
        mainHandler.post {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
            }
        }
    }
}
