# 红果小说阅读页自定义字体 — 实现方案

**Goal:** 让用户在阅读红果免费短剧（`com.phoenix.read`）内的小说时，从手机本地选择 .ttf/.otf 字体文件加入字体列表，并随时切换正文字体；非阅读页（信息流、设置、我的等）字体完全不受影响。

**Architecture:** 本模块是 LSPosed 模块（libxposed API 102），所有 Hook 代码运行在宿主进程内，设置面板也是注入到宿主设置页的 Dialog（`Hooks.showPanel`）。因此字体功能全部落在宿主进程：字体文件存宿主 `filesDir/hongguo_fonts/`，列表元数据存宿主 SharedPreferences（`lspilot_kejiyu`），添加字体的文件选择器用 SAF + Hook 宿主 `Activity.onActivityResult` 回收 URI。替换通过 Hook 宿主阅读器的字体解析器 `com.dragon.read.reader.newfont.TypefaceManager`（其中 7 个返回 `Typeface` 的静态方法）实现——正文是自绘引擎（TTText）渲染的，`TextView.setTypeface` 根本触不到正文，详见下文「实现偏差」。

**Tech stack:** Kotlin、libxposed 102、JUnit/Robolectric、Gradle；已适配宿主版本 CN 7.3.1.32 / 7.3.2.32 / 7.3.3.18（另 7.3.7.32、7.3.9.32 单项适配）。

## 可行性结论

- **Android 层面：完全可行。** `Typeface.createFromFile()` 自 API 1 起支持 TTF/OTF，minSdk 29 无兼容问题；Xposed 可在字体工厂（`Typeface.create*`）、视图层（`TextView.setTypeface`）、绘制层（`Paint.setTypeface`，覆盖 StaticLayout 自绘路径）三个位置截击，任选或组合。
- **宿主层面：可行，核心不确定性在阅读页类名。** 宿主与番茄小说同代码体系（包内大量 `com.dragon.read.*`），小说阅读器就在本 APK 内。但阅读页相关类名混淆程度随版本变化，必须先做 Task 0 验证；验证失败时功能按仓库惯例降级（不命中、不崩溃、面板提示暂不支持）。
- **无需改动宿主 APK，无需额外权限**（SAF 选文件免存储权限）；LSPosed 环境即前提条件。

## Scope and evidence

- 本仓库现有 Hook 全部针对短剧信息流，**没有任何小说阅读页 Hook**，也没有任何字体相关代码——本功能是从零新增，不改动既有功能。
- 已确认的既有机制（复用它，不造轮子）：
  - Hook 安装模式：`module.hook(m).setId(...).setExceptionMode(PROTECTIVE).intercept(Hooker { chain -> ... })`（`Hooks.installBusinessHooks` 内 `ham/hac` 辅助函数）。
  - 设置持久化：`initPrefs/savePref` + SharedPreferences `lspilot_kejiyu`（宿主数据目录，模块代码在宿主进程内直接读写）。
  - 设置面板：`showPanel()` 内程序化 Dialog，`sectionTitle` / `addSwitch` / `makePanelRow` / `showXxxEditor` 系列辅助；入口已注入宿主原生设置页（"模块设置"项）。
  - 版本适配：`TargetNames.Names` per-version 映射表 + `namesFor()` 的 Class.forName 探测兜底。
- 未验证（必须实测，不许猜）：阅读页 Activity/Fragment 类名、阅读正文 View 类型（TextView 还是自定义 View + StaticLayout）、宿主自身字体设置的写入路径。
- 不做什么：不往模块里内置第三方商业字体（版权风险，仅可选内置 OFL 开源字体）；不做全局换字体；不做系统级 FontManager（需系统签名，不适用）。

## Task 0 — 阅读页定位验证（阻塞后续任务）

1. 取目标版本 APK（CN 7.3.1.32 / 7.3.2.32 / 7.3.3.18 各一），反编译查找阅读页类名：优先找未被混淆的 `com.dragon.read.pages.read.*`（本仓库已验证 `com.dragon.read.pages.main.MainFragmentActivity`、`com.dragon.read.widget.BottomTabFrameLayout` 等老代码类名保留，阅读页大概率同样保留）。
2. 确定正文渲染方式：正文 TextView 的类名、是否宿主自带字体列表（assets/res/font 中的字体文件与对应加载代码）。
3. 真机兜底：开一本小说，dump 视图层级（`uiautomator dump` / Android Studio Layout Inspector），确认正文 View 类型与所在 Activity。
4. 产出：每个版本一条 `readerPageClass` / `readerTextViewClass`（若有）候选，写入 `TargetNames.Names`；找不到则标记为空字符串走兜底策略。
5. 本机当前没有目标 APK，此任务需要用户提供 APK 或在其设备上执行 dump。

## Task 1 — 字体仓库 FontStore（新增 `hooks/FontStore.kt`）

- 存储：字体文件复制到 `filesDir/hongguo_fonts/<id>.<ext>`；元数据以 JSON 字符串存 prefs（key `reader_font_list`，`org.json` 解析，无需新依赖）。
- 数据结构：`data class ReaderFont(id: String, name: String, fileName: String, ext: String, addedAt: Long)`；内置一项"跟随宿主（默认）"。
- 校验：扩展名白名单（.ttf/.otf）+ 文件头 magic（`0x00010000` 或 `OTTO`）；**TTC 集合文件明确不支持**（`createFromFile` 无法加载），给出提示而非静默失败。
- Typeface 缓存：LRU（上限 5 个），`Typeface.createFromFile(path)` 结果缓存；切换时避免重复加载大文件。
- 提供：`list() / add(uri) / remove(id) / setSelected(id) / selected() / typefaceFor(font)`。

## Task 2 — 面板 UI（`Hooks.kt` 内新增"阅读字体"分区）

- 复用 `showPanel()`：`content.addView(sectionTitle(act, "阅读字体", p))`。
- 行 1：总开关"自定义阅读字体"（`addSwitch`，pref `reader_font_enabled`），关闭时立即恢复宿主默认并清 flag。
- 行 2：当前字体（`makePanelRow` + 点击弹 `showReaderFontEditor`，仿照 `showVideoScaleEditor`/`showDefaultSpeedEditor` 的编辑器交互）：单选列表，每项显示名称，选中态打勾；底部"从本地添加字体"按钮。
- 行 3：管理入口——长按或每项右侧删除按钮，删除文件 + 元数据（正在使用的字体被删则回退默认）。
- 添加流程：`ACTION_OPEN_DOCUMENT`（`Intent.CATEGORY_OPENABLE`，`font/*` + `application/octet-stream` mime，多选关闭）→ Hook 宿主 `Activity.onActivityResult`（固定 requestCode 魔数，如 `0x4A47`，非本模块请求原样放行）→ 复制 URI 内容到 `filesDir/hongguo_fonts/` → 校验 → 入列表 → 自动选中。
- 切换字体后：立即刷新当前可见阅读页视图树（见 Task 3），并 Toast 简短反馈。

## Task 3 — Hook 替换（新增 `hooks/FontHooks.kt`，`installBusinessHooks` 内调用）

- 门控状态：`gReaderFontOn`（总开关）、`gReaderFontSelected`（当前字体 id）、`gReaderPageActive`（是否处于阅读页，`@Volatile`）。
- `gReaderPageActive` 维护：Hook Task 0 拿到的阅读页类 `onResume/onPause`（类名探测，多版本走 `TargetNames`）；**兜底策略**：类名未命中时按"全屏 Activity + 视图树中存在大字号文本（textSize ≥ 18sp）且无底部 Tab"启发式判定，仍不判定则功能自动关闭并在日志说明（不允许猜不到就全局替换）。
- 替换点（按优先级）：
  1. `TextView.setTypeface(Typeface, int)`（2 参版本，1 参版本最终会走到它）——覆盖所有标准文本控件，含 RecyclerView 复用 item（翻页/切章节自动保持）。
  2. `Paint.setTypeface(Typeface)`——兜底覆盖自定义 View + StaticLayout 自绘正文。**仅在实测确认存在该路径时启用对应开关**，避免全局绘制路径被无谓拦截。
- Hook 实现：`before` 拦截，flag 三连 false 直接 `chain.proceed()`；命中时把参数替换为 `FontStore.typefaceFor(gReaderFontSelected)` 后 proceed。
- 即时刷新：切换字体后 `mainHandler.post` 从 `gCurrentActivity.window.decorView` 递归遍历，对范围内 TextView 重新 `setTypeface` 触发重排。
- 异常模式统一 `PROTECTIVE`，与既有 Hook 一致；任何单点失败不得影响宿主。

## Task 4 — TargetNames 扩展

- `Names` 增加 `readerPageClass: String = ""`、`readerTextViewClass: String = ""`（默认空串 = 未适配）。
- 三个已适配 profile 填入 Task 0 验证结果；`namesFor()` 探测顺序放在既有探测之后，失败不影响既有功能。
- 未适配版本：面板该分区置灰并提示"当前版本暂不支持"，不安装字体 Hook。

## Task 5 — 测试与验证

- 单测（Robolectric）：`FontStoreTest`——JSON 序列化往返、magic 校验（构造合法 ttf 头/非法头）、删除正在使用字体回退默认、LRU 缓存命中；`ReaderFontGateTest`——flag 门控逻辑（开关关/不在阅读页 → 原样 proceed；在阅读页 → 替换）。
- 构建：`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`、`git diff --check`。
- 真机（每个已适配版本各一遍）：阅读页字体变为所选字体；信息流/设置/我的页字体不变；添加字体（含一个非法文件、一个 TTC）行为正确；切换即时生效且翻页/切章节/重启宿主后保持；关闭总开关恢复宿主默认；强停重进保持选择。

## Task 6 — 文档

- README 功能表新增一行"自定义阅读字体"；本文件随实现完成后补 "Validation results" 一节。

## 验收标准

1. 阅读页打开即用所选字体，翻页、切章节、重启 APP 后保持。
2. 非阅读页字体零变化（信息流、播放页、设置、模块面板自身）。
3. 支持从本地添加/删除/切换；非法格式与 TTC 有明确提示且不崩溃。
4. 总开关一键恢复宿主默认；未适配版本优雅降级不崩溃。
5. 模块面板自身 UI 字体不被误替换（面板 Dialog 属于模块自建视图，需在刷新遍历中排除，或靠 flag 时机避开）。

## 风险与边界

- **范围误伤是最大风险**：全局替换会让整个 APP 字体变形。缓解：默认关闭 + 阅读页 flag 门控 + 未探测到阅读页类名时禁用。
- **阅读页类名混淆**：Task 0 未完成前不写 Hook 代码；老代码类名保留是先验利好，但必须实测。
- **TTC 不支持**：提前校验拦截并提示，避免用户困惑。
- **与宿主自身字体设置互相覆盖**：本模块后置替换会赢；用户可随时用总开关退出接管。
- **性能**：Hook 入口 flag 早退；Typeface LRU 上限 5；刷新遍历仅限切换瞬间一次。
- **版权**：不内置商业字体；可选预设仅限 OFL 协议开源字体（思源宋体等），需单独评审后加。


---

## 实现偏差（相对本计划）

实施中 Task 0 的真机取证推翻了原定的替换点，后续任务随之调整：

| 计划 | 实际 | 原因 |
| :--- | :--- | :--- |
| Hook `TextView.setTypeface` / `Paint.setTypeface` 做替换 | Hook 宿主字体解析器 `TypefaceManager` 中 7 个返回 `Typeface` 的静态方法（`b(int)` / `e(String,FontStyle)` / `f(TypefaceManager,String)` / `g(String,FontStyle)` / `h(b,String)` / `i(String,String)` / `l(String,String)`） | 真机 `uiautomator dump` 证实阅读正文是**自绘**的（正文容器 `FrameLayout #h4w` 子 View 数为 0），TextView / Paint 那条路触不到正文 |
| `TargetNames` 增 `readerPageClass`，逐版本填混淆类名 | 不用：Hook 按**类是否存在**安装，并按「静态 + 返回类型为 `Typeface`」的**签名特征**匹配 | `TypefaceManager` 类名未被混淆；按签名匹配天然跨版本，规避了 `TargetNames` 逐版本维护的负担 |
| 用「是否处于小说阅读页」的进程级 flag 做门控 | 不用阅读页 flag | 只改字体解析入口，信息流 / 设置 / 播放页根本不走这条路径，误伤面天然收敛 |
| `hooks/FontStore.kt`，`ReaderFont` 数据类 | `hooks/ReaderFontStore.kt`（`Item`）+ `hooks/ReaderFontHooks.kt` | 存储层与 Hook 层拆开，便于单测 |
| 列表含「跟随宿主（默认）」内置项并占用存储 | 首项固定渲染为「跟随宿主（默认）」，选中即 `selectedId=""`，不占存储 | 避免把"未选择"编码成一条假记录 |

顺带修正一处事实：正文**不是** `TextView`，因此「Hook `Paint.setTypeface` 兜底」这条也没启用。

---

## Validation results

**环境**：真机 RMX3800（ColorOS，root + LSPosed）；宿主红果国内版 `com.phoenix.read` **7.3.9.32**（versionCode 73932）；模块 `xyz.kejiyu.hongguo` 1.0.1。日期 2026-10-13。

模块日志确认 Hook 已装上（tag `ZongHe`）：

```
阅读字体：已接管 7 个字体解析入口 -> b(int); e(String,FontStyle); f(TypefaceManager,String);
                                    g(String,FontStyle); h(b,String); i(String,String); l(String,String)
✓ onActivityResult（阅读字体文件选择回传）
```

### 构建与单测

- `:app:testDebugUnitTest` → **33 passed / 0 failed**（`ReaderFontStoreTest` 占 9 例）。
- `:app:assembleDebug` → 成功。

### 真机逐条验证（对应「验收标准」）

| 验收项 | 结果 | 证据 |
| :--- | :--- | :--- |
| 1. 阅读页正文使用所选字体 | ✅ | 选 PingFang HK / Noto Serif CJK SC 后，正文分别渲染为 PingFang 与宋体；正文区域每 150px 条带像素差 3%~6% |
| 2. 宿主 UI 不受影响 | ✅ | 同一对截图里，章节标题行 / 顶部 / 底栏条带像素差 **0.00%** |
| 3. 非阅读页零变化 | ✅ | 宋体生效期间截「我的」页，界面字体与切换前完全一致 |
| 4. 翻页 / 切章 / 重启后保持 | ✅ | 三种操作后正文仍为宋体；重启后仍生效 |
| 5. 切回宿主可恢复 | ✅ | 选「跟随宿主（默认）」后正文回到宿主黑体，`reader_font_selected` 置空 |
| 6. 本地添加 `.ttf` | ✅ | SAF 选 `PingFangHK.ttf`（25.2 MB）→ `字体库：已添加「PingFangHK」(ttf, 26415024 bytes)` |
| 7. 本地添加 `.otf`（CFF） | ✅ | 从宿主 `NotoSerifCJK-Regular.ttc` 拆出 SC 单字体（OTTO 内核）导入 → `已添加「NotoSerifSC」(otf, 24423176 bytes)`，验证了**按 magic 而非扩展名**判格式 |
| 8. 非法文件拒绝、不崩溃 | ✅ | 6 KB 随机字节 → `不是有效的字体文件（仅支持 .ttf / .otf）`；300 字节但头为 `00 01 00 00` → `文件过小，可能不是字体文件`；`Songti.ttc` 改名 `.ttf` → 被拒 |
| 9. 落盘位置 | ✅ | `files/hongguo_fonts/font_<ts>_<rand>.{ttf,otf}`；元数据在 `shared_prefs/lspilot_kejiyu.xml` |
| 10. 删除正在使用的字体回退默认 | ✅（单测） | `deleteDropsSelectionBackToHostFont` |

### 过程中发现并修掉的问题

1. **TTC 提示只看扩展名。** 真机上把 `Songti.ttc` 改名成 `SongtiFake.ttf` 导入，拿到的是通用提示「不是有效的字体文件」，而不是 TTC 专属提示。已改为按**文件头 magic** 判定（`ttcf` / `wOFF` / `wOF2` 各自给专用文案），并补两条回归：
   `unsupportedReasonNamesTheContainerEvenWhenExtensionLies`、`importRejectsCollectionDisguisedAsSingleFontFile`。
2. 验证工具链：本机没有 PIL / ImageMagick，已装入 `~/.workbuddy/binaries/python/envs/default`（Pillow 12.3.0）；`fontTools` 拉不到包，TTC 拆单字体改用自写的 `ttc_extract.py`（纯 struct 解析，已核对 `NotoSerifCJK-Regular.ttc` 内 5 个字体的 family 名）。

### 未覆盖 / 已知边界

- **仅在 CN 7.3.9.32 真机验证过。** `7.3.1.32 / 7.3.2.32 / 7.3.3.18 / 海外版` 未实测。Hook 不依赖混淆方法名，但依赖 `TypefaceManager` 这个**类名**；若某版本把它混淆了，会静默不生效（日志会显示「已接管 0 个字体解析入口」），届时需按类特征另找入口。
- **字重推断是启发式**：`wantsBold` 只依据入参 int > 400 或 `FontStyle` 枚举名含 `Bold`，未逐字体核对 Bold 变体是否存在。
- **字体文件在 App 私有目录**，卸载 / 清数据即丢失，不做跨设备同步。
- **未做**：字体效果预览、导入进度提示（24 MB 实测 1~2 s，无明显卡顿）。
- **中文字体普遍为全角等宽**，因此切换中文字体通常不改变断行；若换成西文/混合字体，行距与页数会变化，属预期。
