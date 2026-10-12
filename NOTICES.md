第三方开源许可 / Third-party notices

本应用站在这些开源项目之上，图标使用第三方图标集。特此致谢。

本应用自身的代码以 **MIT License**（宽松许可，见仓库根目录 LICENSE）发布：
允许自由使用、复制、修改、合并、发布、再许可和销售，只需保留版权与许可声明。
下面列出的是第三方组件的许可，它们各自独立生效。

—— 整合包格式与数据 ——

Modrinth Modpack Format（.mrpack）
  本应用的整合包导出、导入与更新检查按 Modrinth 发布的格式规范实现，
  规范中的 modrinth.index.json、overrides/、client-overrides/、server-overrides/
  等约定均照其定义处理。Modrinth 是该格式的发起者，其支持也最完整。
  规范：https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack
  许可：规范文档可自由实现；Modrinth 平台数据按其开放接口条款使用。

  使用限制（一并写入 LICENSE）：
  整合包更新检查功能 **只能用于用户主动导入并登记的 .mrpack 整合包**。
  不得对应用自行扫描到的目录、未登记的文件集合执行该检查；
  用户移除登记后必须立即停止对该包的检查。
  导出时 Modrinth 未收录的文件应随包保存在 overrides/ 中，不得静默丢弃。

Modrinth API
  用途：模组搜索、按哈希反查版本、批量查询最新兼容版本。
  条款：https://docs.modrinth.com

CurseForge API
  用途：需用户自备 API Key 时才直连。
  条款：https://docs.curseforge.com

GitHub REST API
  用途：读写用户自己的仓库。
  条款：https://docs.github.com

—— 图标 ——
Material Symbols（Google）
  许可：Apache License 2.0
  https://fonts.google.com/icons

—— 开源库 ——
OkHttp（Square）—— Apache-2.0
Gson（Google）—— Apache-2.0
jsoup（Jonathan Hedley）—— MIT
Coil（Coil-KT）—— Apache-2.0
Tomlj（Tomlj contributors）—— Apache-2.0，用于解析 mods.toml
Apache Commons Net —— Apache-2.0，FTP / FTPS
sshj（hierynomus）—— Apache-2.0，SFTP
Conscrypt（Google）—— Apache-2.0，sshj 在 Android 上的必需 provider
ML Kit Translate（Google）—— 离线翻译模型，按其 SDK 条款使用
Timber（Jake Wharton）—— Apache-2.0，日志
Rhino（Mozilla）—— MPL 2.0，插件系统的 JavaScript 引擎
LuaJ（LuaJ contributors）—— 插件系统的 Lua 引擎（按各自发行条款）

AndroidX（Google）
  core-ktx / appcompat / recyclerview / documentfile / security-crypto /
  work-runtime / lifecycle / preference / swiperefreshlayout / browser
  许可：Apache License 2.0

Material Components for Android（Google）
  许可：Apache License 2.0

—— 商标 ——
Minecraft 是 Mojang Studios 的商标。本应用为非官方工具，与 Mojang Studios 无关，
不包含任何 Minecraft 游戏本体、资源或受版权保护的素材。
Modrinth 名称与标识归其各自所有者所有，本应用仅作为兼容实现引用。
