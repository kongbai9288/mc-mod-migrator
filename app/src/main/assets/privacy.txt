隐私说明（MC 迁移器 / ModMigrator）

最后更新：2026-09-23

一、数据去向
本应用没有自建的开发者服务器。所有数据默认只留在你的手机上。
真正会离开手机的只有两类情况，且都由你主动触发：
1. 你在「跨设备」页主动上传，把配置包写入你自己在设置里填写的 GitHub 仓库。
2. 调用后端（见下）完成 GitHub 登录与模组搜索下载。

二、本机存储的内容
1. GitHub Personal Access Token：使用 AndroidX Security 的 EncryptedSharedPreferences
   （AES-256 加密）保存在应用私有目录，不会写入日志，不会随备份明文导出。
2. 仓库所有者 / 仓库名 / 分支 / CurseForge API Key / 面板地址与密钥：同上，加密保存。
3. 你通过系统文件选择器授权的目录 URI：保存的是授权标识（URI），不是文件内容。
4. 你手动标记的下载链接：保存在应用私有目录的 marked_links.json。
5. 翻译缓存与崩溃日志：翻译结果缓存在应用私有目录（离线时也能用），
   崩溃堆栈写入 crash.log，仅供你在设置页自查，不会自动上传。
6. 工作目录：仅在你主动授权后使用，导出包、下载、配置备份都放这里。

三、会发起的网络请求
1. api.modrinth.com —— 搜索模组、查询版本、按文件哈希识别模组。
2. api.curseforge.com —— 仅当你填写了自己的 CurseForge API Key 时才会直接调用；
   未填写时改走国内镜像（见第 5 条）。
3. api.github.com —— 读写你自己的仓库（上传配置包、拉取设备列表、检查应用更新）。
4. 后端（默认 api.kongbaisever.cc.cd，可在设置里改成你自己的地址）——
   用于 GitHub OAuth 登录与 CurseForge 代理。登录用签名 Cookie，
   本应用不接触也不保存你的 GitHub 密码或 OAuth Client Secret。
5. 国内镜像 mod.mcimirror.top / 更新镜像（ghfast、gh-proxy 等）——
   用于在官方源不可达时加速模组信息与文件下载、以及应用更新的下载。
   镜像是第三方公益服务，本应用只做地址替换，不向其提交任何个人信息。
6. 翻译服务 api.mymemory.translated.net —— 仅在你使用翻译功能时发送「模组简介文本」
   这一种内容，不含你的身份信息。开启离线模式后完全不发送。
7. 你的游戏服务器面板（Pterodactyl）—— 地址与 Client API Key 由你自己填写，
   本应用只读取文件列表并给出更新建议，不会自动上传任何文件到你的服务器。
8. 你手动标记的下载地址、以及你在「模组页面」里打开的网页 —— 由你决定访问哪个站点，
   本应用不代为收集这些站点返回的任何信息。

四、权限用途
- INTERNET / ACCESS_NETWORK_STATE：联网搜索与下载，判断网络可用性。
- POST_NOTIFICATIONS：显示下载、同步与更新提醒通知，可拒绝，不影响主要功能。
- FOREGROUND_SERVICE / WAKE_LOCK：保证大文件下载在后台不被中断。
- 存储访问权限：仅用于读写你主动选择的工作目录与游戏实例目录
  （Android 存储访问框架）。「所有文件访问」为可选项，默认不申请。
- QUERY_ALL_PACKAGES：仅用于列出已安装应用，让你自己选择哪个是 Minecraft 启动器。
  本应用不预置也不上传任何应用列表，读取结果只显示给你本人。

五、你的控制权
- 设置页可随时清空本机标记、翻译缓存与崩溃日志。
- 离线模式开启后，不再发起任何网络请求，仅使用本地词典与已缓存译文。
- 卸载应用会删除全部本机数据。
- 可随时到 GitHub Settings → Developer settings → Personal access tokens 撤销 Token；
  到 console.curseforge.com 删除 API Key；到服务器面板删除 Client API Key。

六、第三方组件
本应用使用了第三方开源库与第三方图标（Material Icons 等），
许可见「第三方开源许可」页面。这些组件各自可能发起其自身的网络请求（均为上述地址）。

七、儿童与未成年人
本应用不面向儿童，不会有意收集任何人的身份信息。

八、变更
本说明如有更新，会随应用内该页面与仓库中的 PRIVACY.md 同步更新。
