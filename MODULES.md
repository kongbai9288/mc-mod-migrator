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
| Modrinth | `ModrinthApi.kt` | `search` `versions` `lookupHash` `title` | **/v3/search + new_filters** | 待重写（v2 facets 已废弃） |
| CurseForge | `CurseForgeApi.kt` | `search` `files` | CF API，需补 `index` | 待重写 |
| 聚合 | `AggregateSearch.kt` | `search` `recommend` | 上述两者 | 待重写 |
| 后端（用户自有） | `BackendApi.kt` | `me/loginUrl/fetchToken/search` | modmarket workers | 待重写 |
| 面板 | `ServerPanelApi.kt` | `auth/servers/listFiles/listRaw/probeDirs` | Pterodactyl Client API | 待重写 |

### 4. 功能层

| 模块 | 文件 | 关键函数 | 状态 |
|---|---|---|---|
| 迁移 | `MigrationFragment.kt` | `scan` `doMigrate` `retryFailed` `retryDownloads` | 进行中 |
| 市场 | `MarketFragment.kt` | `search` `render` `install` | 待重写 |
| 模组管理 | `ModManagerFragment.kt` | `load` `render` `showInfo` 批量操作 | 进行中 |
| 整合包更新 | `ModpackFragment.kt` | `checkUpdates` `downloadOne` | 待重写 |
| 服务器 | `ServerFragment.kt` | `connect` `scanFiles` `browseDir` `checkUpdates` | 进行中 |
| 工具箱 | `ToolsFragment.kt` | `doctor` `diff` `depCheck` `crossLoader` 等 | 待整理 |
| 回收站页 | `TrashFragment.kt` | `render` `doRestore` 批量 | 已重写 |
| 设置 | `SettingsMainFragment.kt` 等 | 分层设置 | 待重写 |
| 登录 | `LoginDiag.kt` | `run`（4 步诊断） | 已重写 |

### 5. 工具层

| 模块 | 文件 | 关键函数 | 状态 |
|---|---|---|---|
| 文件 | `Fs.kt` | `tree` `find` `copyInto` `sha1` `ensureDir` | 待重写（子目录缓存） |
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
