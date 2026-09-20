# 模组迁移助手 ModMigrator

给 Minecraft Java 版启动器玩家的 Android 工具：**自动跨版本迁移 + 模组市场 + 跨设备同步**。
私有仓库，自用性质。应用内不含任何 Minecraft 游戏素材，图标来自第三方 Material Symbols（Apache-2.0）。

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

## 注意

- Android 8.0（API 26）以上，受分区存储限制，目录访问走系统文件选择器。
- 单个文件超过 100MB 时 GitHub contents API 会拒绝，配置包太大请在设置里减少勾选项。
- Modrinth 请求需要 User-Agent，已内置；请勿滥用 API。
