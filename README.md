# 模组迁移助手 ModMigrator

给 Minecraft Java 版启动器玩家的 Android 工具：**自动跨版本迁移 + 模组市场 + 跨设备同步**。
私有仓库，自用性质。应用内不含任何 Minecraft 游戏素材，图标来自第三方 Material Symbols（Apache-2.0）。

## 导语
- 是的，ModMigrator是一款安卓mod迁移工具，安卓不是错，而是没有工具，在此本项目的前端开发yuan bao与审核、后端开发kongbai9288诚邀你开启高效版本之旅，更快的路，更好用的体验

## 能做什么

### 1. 版本迁移（自动）
- 选择源实例目录（SAF 授权），自动识别 MC 版本与加载器：
  MultiMC / Prism 的 `mmc-pack.json`、CurseForge 的 `manifest.json`、原版 `version.json`。
- 填写目标 MC 版本 + 加载器（auto / fabric / forge / neoforge / quilt），点「扫描并生成迁移方案」：
  逐个 jar 计算 SHA-1 → 调 Modrinth 按哈希反查项目 → 找到目标版本对应的文件。
- 点「执行迁移」：按勾选复制 `config/`、`scripts/`、`options.txt`、资源包/光影包、存档，
  并把解析到的模组 jar 直接下载进目标 `mods/` 目录。
- 可选「迁移完成后自动打开启动器」（Pojav / Zalith / MCinaBox 等）。

### 2. 模组市场（应用市场式）
- Modrinth 搜索（默认）；填了 CurseForge API Key 后可用 CurseForge。
- 卡片式列表、图标、下载量、一句话说明，点卡片进详情页，点「安装」自动下载到目标 `mods/`。
- **手动标记下载链接**：市场页可手动粘贴链接；在「模组页面」长按任意链接即可标为下载链接，
  也可以点「标记本页」用 jsoup 解析页面上的下载地址候选。
- 已标记的链接统一在市场页顶部列出，可一键下载或直接下载全部。

### 3. 跨设备迁移
- 设置里填 GitHub Token + 私有仓库（默认 `kongbai9288/mc-mod-migrator`）+ 分支。
- 「同步」页把自己这台设备（ID + 机型）的清单与配置包推到私有仓库：
  `modmigrator/devices/<设备ID>/manifest.json` 与 `config.zip`。
- 另一台设备用同一个 Token/仓库，点「刷新远程设备」即可看到其它设备，
  点「恢复」把配置解包到目标目录，并按清单把模组重新下载回来。
- 可开自动同步（WorkManager，约 15 分钟一次）。

### 5. 同设备版本迁移
- 迁移页「扫描本机实例」：授权一次根目录（如 `/storage/emulated/0/games`），
  自动向下最多 3 层扫描，识别出含 `mods`/`config`/`version.json`/`mmc-pack.json` 的实例。
- 从列表里选一个实例，弹窗问它是「源实例」还是「目标实例」，两边都在本机即可直接迁移。
- 会自动读出实例的 MC 版本与加载器（MultiMC/Prism、CurseForge、原版），填进目标栏。

### 6. 服务器面板（只读 + 手动上传）
- 服务器页填写面板地址与 **Pterodactyl Client API Key**（`ptlc_...`），
  点「连接」列出该账号下的服务器，选一台。
- 填目录（`/mods` 或 `/plugins`）点「扫描」，列出该目录下所有 jar。
- 填服务器 MC 版本 + 加载器点「检测更新」：按 jar 文件名去 Modrinth 匹配项目并取对应版本。
- 列表里每条都可单独下载，也可「一键下载全部更新」。
- **不会自动上传**：下载下来的文件放在目标目录的 `mods/`，由你自己通过面板/SFTP 上传。

### 7. 模组翻译
- **市场列表**：搜索结果自动翻译前 10 条简介（设置里可关）；每条右侧「译」按钮可单条翻译，再点一次切回原文。
- **模组叙述页**：顶部「翻译」按钮，可选微软翻译（国内可达）或谷歌翻译整页代理，也可恢复原文。
- 简介翻译走 MyMemory 免费接口（en→zh-CN），匿名约 5000 词/天/IP，超出会失败并提示；结果有内存缓存。

### 8. 崩溃日志
设置页「查看崩溃日志」可看到最近一次崩溃的堆栈，便于定位问题。

### 4. 设置与隐私
- 设置页：GitHub 仓库配置与连接测试、数据源偏好、CurseForge Key、默认版本/加载器、
  自动安装、自动打开启动器、自动同步、隐私说明、第三方许可、清除本机数据。
- Token 等敏感项用 `EncryptedSharedPreferences`（AES-256）加密保存。
- 完整隐私说明见 [PRIVACY.md](PRIVACY.md)，应用内「设置 → 隐私说明」同样可看。

## 构建

仓库自带 GitHub Actions：推送到 `main` 会自动编译 debug APK 并作为 artifact 上传。
本地构建：

```bash
./gradlew assembleDebug   # 需要 JDK 17 + Android SDK 34
```

CI 里没有 gradle wrapper 二进制，workflow 会直接下载 Gradle 8.2 再执行 `gradle assembleDebug`。

## 目录结构

```
app/src/main/java/com/kongbai/modmigrator/
  App.kt                应用入口，通知渠道
  Prefs.kt              加密偏好设置与键名
  Models.kt Store.kt    数据模型与本地存储
  Http.kt Json.kt       OkHttp 封装与 JSON 辅助
  ModrinthApi.kt        Modrinth v2：搜索/版本/哈希反查
  CurseForgeApi.kt      CurseForge 搜索与下载地址拼装
  PageParser.kt         jsoup 解析模组页面上的下载链接
  GitHubApi.kt          contents API 读写（base64）
  Fs.kt Downloader.kt   SAF 文件扫描/复制/哈希与下载
  BundleManager.kt      配置包 zip 打包与解包
  SyncManager.kt SyncWorker.kt  跨设备同步与周期任务
  Targets.kt LauncherHelper.kt  目标目录与启动器调起
  Adapters.kt           列表适配器
  MainActivity.kt + 4 个 Fragment + ModPageActivity/InfoActivity
```

## 数据源与镜像（免 CurseForge Key 也能用）

| 源 | 是否需要 Key | 说明 |
|---|---|---|
| Modrinth | 否 | 默认源，接口完全开放 |
| CurseForge 官方 | 是 | 需在 console.curseforge.com 免费申请 |
| 国内镜像 MCIM | 否 | 设置里开关，默认开启 |

镜像 `mod.mcimirror.top` 兼容官方 API 结构，直接替换域名即可：

- `api.curseforge.com` → `mod.mcimirror.top/curseforge`
- `edge.forgecdn.net` → `mod.mcimirror.top`（**文件下载也走镜像**）
- `cdn.modrinth.com` → `mod.mcimirror.top`

**没填 CurseForge Key 时会自动改用镜像**，所以不申请 Key 也能搜索并下载 CurseForge 的模组。
填了 Key 之后按设置里的开关决定走官方还是镜像；走官方时会在请求头带上 `x-api-key`
（CurseForge 官方 CDN 自 2024 年 7 月起强制 Key 认证，无 Key 直链会返回 401）。

镜像是第三方公益服务，可能限速或临时关停；出问题把开关关掉、填上自己的 Key 即可回退官方。

## 注意

- Android 8.0（API 26）以上，受分区存储限制，目录访问走系统文件选择器。
- 单个文件超过 100MB 时 GitHub contents API 会拒绝，配置包太大请在设置里减少勾选项。
- Modrinth 请求需要 User-Agent，已内置；请勿滥用 API。
