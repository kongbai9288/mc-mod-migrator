# ModMigrator 2.0 模块对应关系表

> 用途：大换底分批重写时的**唯一事实来源**。
> 每改一批，必须同步更新本表的「状态」列，避免分批之间口径不一致。
>
> 状态标记：`待做` / `进行中` / `已重写` / `已验证`

---

## 一、目标应用功能对照（每个功能对准"正常应用"的对应部分）

| 本应用模块 | 对标的应用 | 该应用怎么做的 | 我们当前的差距 |
|---|---|---|---|
| 迁移 | MultiMC / Prism Launcher 的实例导出导入 | 按实例目录结构复制 mods/config/saves，识别加载器 | 依赖反查靠 Modrinth，网络失败就整批失败 |
| 市场 | Modrinth App / CurseForge App | 分页搜索 + 版本筛选 + 图标加载 + 详情页 | 分页参数错、图标解不了 SVG、详情页逻辑混乱 |
| 模组管理 | Mod Menu（游戏内）/ Prism 的 Mod 页 | 读 jar 元数据，显示图标/版本/作者，启用禁用 | 元数据靠正则 grep，Forge 路径判错，图标经常失败 |
| 商店下载 | 上述两者的下载器 | 后台下载、断点续传、进度、并发 | 自写 Downloader，续传与并发问题多 |
| 服务器面板 | Pterodactyl 官方 App | 列目录、文件管理 | 目录靠手填，无浏览器 |
| 设置 | 任意成熟 App | 分层设置、状态持久化 | 返回栈混乱、主题色未贯穿 |

---

## 二、模块 → 文件 → 关键函数 对照表

### 1. 基础层

| 模块 | 文件 | 关键函数/定义 | 依赖 | 状态 |
|---|---|---|---|---|
| 网络 | `Http.kt` | `client`（普通 20/120s）、`shortClient`（批量 6/12s）、`get/call/postJson/put`、`describeError` | OkHttp | 已重写 |
| JSON | `Json.kt` | `obj / arr / s / l / b` | Gson | 待重写（改用 Gson 直读，去掉正则） |
| 日志 | `App.kt` + `CrashTree` | `Timber.plant`，日志进 `LogCenter` | Timber | 已重写 |
| 崩溃 | `CrashHandler.kt` `CrashShare.kt` | `install`、分享/保存日志 | — | 待验证 |
| 偏好 | `Prefs.kt` / `K` | 全部常量键 | — | 待整理（键名散乱） |
| 主题 | `ThemePrefs.kt` | 主题色 / 深色模式 | Material | 待重写（未贯穿全部页面） |

### 2. 数据层

| 模块 | 文件 | 关键函数/定义 | 依赖 | 状态 |
|---|---|---|---|---|
| 模组元数据 | `ModMeta.kt` | `read(uri)` / `readFile` / `parse` | **Gson + tomlj** | 已重写（Gson + tomlj） |
| 模组图标 | `ModIcons.kt` | `of(ctx, file)` | ZipFile + BitmapFactory | 已重写（ZipFile+采样+LRU） |
| 加载器图标 | `LoaderIcons.kt` | `res(loader)` / `imageLoader` | Coil + SVG/GIF | 已重写 |
| 依赖图 | `ModDepGraph.kt` | `analyze` / `resolveDeep` / `topoSort` | ModMeta | 已重写 |
| 启停 | `ModToggle.kt` | `disable/enable/toggle` | — | 已重写 |
| 回收站 | `Trash.kt` | `moveToTrash/restore/deleteForever/empty/purgeExpired` | Prefs | 待验证 |
| 模型 | `Models.kt` | `ModEntry` `MarketMod` `PanelFile` `ModFile` | — | 待整理 |

### 3. API 层

| 模块 | 文件 | 关键函数 | 端点 | 状态 |
|---|---|---|---|---|
| Modrinth | `ModrinthApi.kt` | `search` `versions` `lookupHash` `title` | **/v3/search + new_filters** | 已重写（v3 + new_filters + offset + loaders 字段） |
| CurseForge | `CurseForgeApi.kt` | `search` `files` | CF API，需补 `index` | 已重写（index 可变偏移） |
| 聚合 | `AggregateSearch.kt` | `search` `recommend` | 上述两者 | 待重写 |
| 后端（用户自有） | `BackendApi.kt` | `me/loginUrl/fetchToken/search` | modmarket workers | 待重写 |
| 面板 | `ServerPanelApi.kt` | `auth/servers/listFiles/listRaw/probeDirs` | Pterodactyl Client API | 待重写 |

### 4. 功能层

| 模块 | 文件 | 关键函数 | 状态 |
|---|---|---|---|
| 迁移 | `MigrationFragment.kt` | `scan` `doMigrate` `retryFailed` `retryDownloads` | 已重写（跳过+汇总+重试） |
| 市场 | `MarketFragment.kt` | `search` `render` `install` `loadMore` | 已重写（流式+分页） |
| 模组管理 | `ModManagerFragment.kt` | `load` `render` `showInfo` 批量操作 | 已重写（ModMeta+批量） |
| 整合包更新 | `ModpackFragment.kt` | `checkUpdates` `downloadOne` | 已重写 |
| 服务器 | `ServerFragment.kt` | `connect` `scanFiles` `browseDir` `checkUpdates` | 已重写（目录浏览器+本地存放） |
| 工具箱 | `ToolsFragment.kt` | `doctor` `diff` `depCheck` `crossLoader` 等 | 已重写（用 ModMeta） |
| 回收站页 | `TrashFragment.kt` | `render` `doRestore` 批量 | 已重写 |
| 设置 | `SettingsMainFragment.kt` 等 | 分层设置 + 账号行整行可点 | 已重写 |
| 登录 | `LoginDiag.kt` | `run`（4 步诊断） | 已重写 |

### 5. 工具层

| 模块 | 文件 | 关键函数 | 状态 |
|---|---|---|---|
| 文件 | `Fs.kt` | `tree` `find` `copyInto` `sha1` `ensureDir` | 已重写（子目录缓存） |
| 下载 | `Downloader.kt` `DownloadService.kt` | `download` | 待重写（原子提交 .part） |
| 延迟下载 | `DelayedDownload.kt` | CF 读秒页后台捕获 | 已重写 |
| 实例扫描 | `InstanceScanner.kt` | `scanFiles` `scanFrom` | 待重写 |
| 导出 | `MrpackExport.kt` | `export` | 已重写 |
| 硬链接 | `HardLink.kt` | `linkOrCopy` | 已重写 |
| 跨加载器 | `CrossLoader.kt` | `plan` `report` | 已重写 |
| 代码迁移 | `CodeMigrator.kt` | `scan` `apply` | 已重写 |
| 翻译 | `Translator.kt` | ML Kit + 网页注入 | 待重写 |
| 浏览 | `WebActivity.kt` | 内置浏览器 | 待重写 |

---

## 三、已引入的第三方库（全部 Apache-2.0 / MIT，无 GPL）

| 库 | 版本 | 许可 | 用途 | 替代了什么 |
|---|---|---|---|---|
| OkHttp | 4.12.0 | Apache-2.0 | 网络 | 自写 HttpURLConnection |
| Gson | 2.10.1 | Apache-2.0 | JSON | （待用于替代正则） |
| **tomlj** | 1.1.1 | Apache-2.0 | TOML（mods.toml） | 正则 grep mods.toml |
| Coil + svg + gif | 2.5.0 | Apache-2.0 | 图片（含 SVG/WebP/GIF） | 手写图片加载 |
| Timber | 5.0.1 | Apache-2.0 | 日志 | 静默 catch + println |
| Jsoup | 1.17.2 | MIT | HTML 解析 | — |
| ML Kit Translate | 17.0.3 | 见许可证页 | 离线翻译 | 自造词典 |
| okhttp logging-interceptor | 4.12.0 | Apache-2.0 | 请求日志 | — |

---

## 四、分批计划（每批结束必须更新本表）

- **第 1 批（本次）**：数据层重写 —— `ModMeta`（Gson + tomlj）、`ModIcons`（ZipFile + 采样）、`ModrinthApi`（v3 + new_filters）
- **第 2 批**：API 层 —— CurseForge 补 index、聚合、后端、面板
- **第 3 批**：功能层 —— 市场、迁移、模组管理
- **第 4 批**：设置/主题/导航、翻译、浏览器
- **第 5 批**：性能与权限、发布

---

## 五、已知易错点（防止分批时重复踩）

1. `android.R.attr.selectableItemBackground` 不能传给 `setBackgroundResource` → 必须用项目自带 drawable
2. Kotlin 字符串模板里 `$to` 会被解析成标准库 `to` 函数 → 变量名避免叫 `to`
3. `BottomNavigationView` 最多 5 项，超了启动即崩
4. 代码 `new Button()` 不跟主题色 → 必须用 `MaterialButton`
5. `lateinit var` 必须在 `onCreateView` 里赋值，否则 `onResume` 访问即崩
6. `safePost` 内部已检查 `isAdded`，内部不能再写 `return@post`
7. Modrinth v2 `facets` 已废弃 → v3 用 `new_filters`（MeiliSearch 语法）
8. `fabric.mod.json` 的 `icon` 可能是 string 也可能是 object
9. Forge 的 `mods.toml` 实际路径是 `META-INF/mods.toml`，不是根目录
10. CurseForge 的 `index` 是**基于 0 的偏移量**，不是页码

---

## 六、可达性审计（防止有代码没入口）

用户反馈的核心问题：功能写了代码但界面上没入口，用户拿到手就是这个功能是空的。
这类问题编译能过、也不崩，只有实际点才发现。

仓库内自带审计脚本：

=== 1. 孤儿界面（定义了但没人实例化，用户永远看不到）===
  无

=== 2. 孤儿布局（写了 xml 但没人 inflate）===
  无

=== 3. 未绑定控件（布局有 id，代码没 findViewById，点了可能没反应）===
  无

合计可疑点：0
说明：以上需要人工确认——有些是刻意的（如纯代码构建 UI 的页面），
      但每一个都应该有明确理由，不能是忘了接。

它检查三类问题：

1. **孤儿界面** —— 定义了 Fragment/Activity 但没有任何地方实例化
2. **孤儿布局** —— 写了 .xml 但没有任何代码 inflate
3. **未绑定控件** —— 布局有 id 但代码没 findViewById（按钮摆着点了没反应）

**每批改完必须跑一次，保持 0 可疑点。**

本次修复结果：
- （内置配置编辑器）之前完全没有入口 → 在模组详情页补「编辑」按钮
-  没人 inflate（DevsFragment 是纯代码构建 UI）→ 删除冗余布局
- （迁移页扫描提示）未绑定 → 绑定并动态显示扫描状态
- （设置页账号行）未绑定 → 绑定，整行可点/长按出登录菜单

### 第 5 批补充：导航与返回栈

- **MainActivity 补 onBackPressed**：之前完全没有返回键处理，
  子页栈和一级 tab 混在一起，从设置子页返回后会跳过一级页直接退出，
  或返回后底部高亮停在别处（用户说的返回到别的页面卡住了）。
- **tabHistory**：一级 tab 切换不进 FragmentManager 栈，改记入历史；
  返回顺序 = 子页栈 → tab 历史 → 真的退出。
- **syncNavSelection**：每次切换同步底部导航选中项，且先摘监听再设置再装回，
  防止 setSelectedItemId 反向触发 switchTo 造成循环。
- **切 tab 清子页栈**：避免 A tab 打开的子页在切到 B tab 后还留在栈里。
- **修逻辑错误**： 之前写在 tab 选择监听器内部，
  导致每切一次 tab 就弹一次公告，现移回 onCreate 只调一次。

### 第 5 批补充：网页翻译重写

调研参考了同类网页翻译实现的通行方案（MutationObserver 监听动态内容、
WeakMap 存原文、双重防抖、过滤纯数字与已翻译内容）。
**注意：AllTrans 是 GPLv3（高传染性），没有使用它的任何代码，也未引入该依赖。**

本次改动：

- **去掉 `window.__mmInstalled` 属性** —— 之前往 window 上挂全局属性，
  页面反自动化脚本扫 window 异常属性一抓一个准，这就是"被网站检测到"的原因。
  现在改用 `Object.defineProperty` 定义成 **不可枚举**，
  页面用 for...in / Object.keys 扫 window 时看不见。
- **去掉 DOM 上的 `data-mm-orig` / `data-mm-pending` 属性** —— 同样会被页面检测到。
  原文改存 JS 闭包内的 **WeakMap**，节点移除即释放，也不泄漏内存。
- **严格跳过可编辑元素**：INPUT / TEXTAREA / SELECT / OPTION / contenteditable
  **及其整个祖先链**一个字节都不碰（之前改写 contenteditable 会丢光标、输不进字）。
- **JS 侧 800ms 防抖 + Kotlin 侧 1200ms 轮询**双重。
- **串行翻译**替代并发（之前 N 条同时发给同一引擎，后面的容易失败，
  表现就是"有的翻了有的没翻"）。
- 收集与写回都用 **TreeWalker**，顺序严格一致，避免错位。

---

## 七、AI 代码缺陷审计（针对"AI 常犯的错误"）

AI 生成的代码有个共同特点：**语法正确、能编译、能跑通主流程**，
所以编译器和常规 lint 都不报，但在边界、并发、资源、生命周期下会暴露。

仓库内自带审计脚本，专门扫这类问题：

```bash
python3 tools/ai_defect_audit.py
```

检查 12 类：异常黑洞 / 资源未释放 / 主线程 IO / 子线程更新 UI /
生命周期泄漏 / 并发不安全 / 边界未处理 / 硬编码 / API 版本 /
强制解引用 / 重复实现 / 过度宽泛捕获。

### 本轮结果：235 → 78（含已知误报）

| 类别 | 修复前 | 修复后 | 说明 |
|---|---|---|---|
| A 异常黑洞 | 122 | 7 | 87 处空 catch + 35 处注释型空 catch 全部加留痕 |
| E 生命周期泄漏 | 33 | 11 | 20 个 Fragment/Activity 的 Handler 加 removeCallbacksAndMessages |
| G 边界未处理 | 9 | 0 | 9 处 `x[0]` 未判空，改为先判 size 再取 |
| J 强制解引用 | 4 | 0 | 4 处 `!!` 改为安全处理 |
| I API 版本 | 6 | 0 | minSdk 26 已覆盖，脚本补上 minSdk 感知消除误报 |
| D 子线程更新UI | 6 | 0 | 已用 `main {}` 切主线程，脚本补识别消除误报 |

### 关键修复

**1. 新增 `Err.kt` —— 统一留痕**
122 处空 catch 是"为什么改不动、为什么只能猜"的直接原因：
失败被静默吞掉，排查时没有任何现场信息。
现在所有空 catch 改为 `Err.ignore(t, "做了什么")`，
写进 LogCenter（用户分享崩溃日志时能看到崩之前发生了什么）。

**2. LogCenter 并发安全**
`lines` / `listeners` / `errorHooks` 三个集合被多线程访问
（日志从任意后台线程写入，监听器由 UI 线程注册），
之前用普通 ArrayList，并发下会 ConcurrentModificationException 或读到半改状态
——这是"崩溃日志莫名其妙丢内容"的原因。改为 CopyOnWriteArrayList。

**3. ModrinthApi 单例缓存**
`titles` / `slugs` 是 object 单例的 HashMap，而搜索是并发的，
多线程同时 put 会导致 HashMap 内部结构损坏甚至死循环。改为 ConcurrentHashMap。

**4. Prefs 不再 `!!`**
`prefs!!` 在 init 没跑到时会 NPE 直接崩。改为退化到内存实现，
读不到配置只用默认值，不会让应用起不来。

**5. 集合越界**
9 处 `x[0]` 未判空（如 `latestFiles[0]`、`versions[0]`），
空数组时 IndexOutOfBounds 直接崩。改为先判 size。

### 已知误报（不修，有意为之）

- **H 中文硬编码**：本项目面向中文用户且已有 LangPack 多语言机制，代码内中文是已知取舍
- **F 部分并发**：UI 层的 Fragment 集合只在主线程访问
- **E 部分 Handler**：`Handler(Looper.getMainLooper())` 不持有 Activity，泄漏风险低
