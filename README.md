# ModMigrator

Minecraft Java 版 **模组与配置迁移工具**（Android）。

Minecraft java edition 手机启动器的跨版本更新迁移工具

> 适用：安卓上的 FCL、Zalith、Pojav、Amethyst、HMCL-PE、澪-Ultimate 等(java eidition)启动器。
> 不适用：基岩版（Bedrock eidition）

注：本项目与各loader,启动器，mojang AB及其主公司Microsoft©没有所属关系！

---

## 快速开始

1. 装 APK（见下方「下载」）
2. 打开 **设置 → 存储**，选一个**工作目录**（导出包、下载缓存、备份都放这里）
3. 回到**迁移**页：
   - 选「**迁移前的版本**」目录（含 `mods` 的那个）
   - 选「**迁移后的版本**」目录
   - 填目标 MC 版本、加载器
   - 点**扫描** → 生成迁移方案
4. 勾选要迁移的内容，点**开始迁移**

## 隐私

- 代码里**不含任何凭据**如果你有自己的后台可以自己切换
- GitHub 登录是为了帮助你自己传到你的仓库里，这样的话方便配置，可以不登录
- 后端地址默认为120/分钟请求，请合理使用
- 如果你没法正常连接，你可能需要国际环境
- 完整的隐私政策见 App 内「设置 → 关于 → 隐私说明」

---

## 下载

Release 里提供已签名的 `ModMigrator-release.apk`，如果你已安装，可以通过检测更新来获得新版本

---

## 技术栈

Kotlin · Android SDK 34（minSdk 26）· Material Components 2 · OkHttp · Gson · Jsoup · Coil · WorkManager · AndroidX Security · **ML Kit Translate（离线翻译）**

## 许可

代码开源自用。第三方库许可见 App 内「设置 → 关于 → 第三方开源许可」。
图标使用 Material Icons（Apache 2.0）。
