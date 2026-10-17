package com.kongbai.modmigrator

import android.os.Build

/**
 * Android 各版本对共享存储、`Android/data` 的限制。
 *
 * ## 为什么单独抽一张表
 *
 * 本应用要读写**其他启动器**的游戏目录，而"某个路径能不能读写"取决于
 * **系统版本 + 访问方式 + 目录所在区域**三件事，只看路径必然判错。
 * 以前这些判断散在各页面，写法不一，有的地方还把"Android 14 起收紧"
 * 当成事实——**真正的分水岭是 Android 11**。对外发布后这类错误会直接
 * 表现为"用户点了没反应"，所以集中到一处。
 *
 * ## 依据（改动前请回查原文）
 *
 * - 分区存储强制执行、SAF 禁止选择项（Android 11）
 *   https://developer.android.com/about/versions/11/privacy/storage
 * - "所有文件访问"权限能做什么、不能做什么
 *   https://developer.android.com/training/data-storage/manage-all-files
 * - SAF 用法
 *   https://developer.android.com/training/data-storage/shared/documents-files
 * - Play 对该权限的审核要求
 *   https://support.google.com/googleplay/android-developer/answer/10467955
 *
 * ## 三条最容易搞错的
 *
 * **1. 分水岭是 Android 11（API 30），不是 14。**
 * 分区存储 Android 10 引入、11 强制，`requestLegacyExternalStorage` 从 11 起
 * 一律失效。SAF 从 11 起禁止选择内部存储根目录、`Android/data`、`Android/obb`。
 * Android 14 / 15 / 16 没有再新增单独禁令，适用的是同一套现行规则。
 * 所以"Android 14 以后 SAF 看不到 Android/data"这种说法不准确——
 * 准确说法是"从 11 起就不让选"。
 *
 * **2. 有了"所有文件访问"权限，也写不了 `/sdcard/Android/data`。**
 * 官方文档明确：该权限不含其他应用的专属目录，直接路径写入仍被禁止。
 * 更反直觉的是：**SAF 的可授权范围不会因为拿到这个权限而扩大**。
 * 所以"先申请全局权限再递归拷贝"这条路在技术上不可靠。
 *
 * **3. Shizuku 不是官方承诺。**
 * 它让应用借 adb/root 身份代读，社区常用，但没有跨版本的官方保证，
 * 设备、ROM、无线调试开关、厂商限制都会影响结果。
 * 只能当**可选**通道，不能当唯一路径，更不能在没有授权时直接访问私有目录。
 */
object StoreRules {

    /** 目录所在的区域。同一个路径在不同区域下，可用通道完全不同。 */
    enum class Zone {
        /** `/sdcard` 下的普通公共目录，如 `FCL/.minecraft` */
        PUBLIC,

        /** `/sdcard/Android/data/<包名>` —— 其他应用的专属目录 */
        ANDROID_DATA,

        /** `/sdcard/Android/obb` —— 同样被 SAF 禁选 */
        OBB,

        /** 本应用自己的专属目录（`getExternalFilesDir` / `filesDir`） */
        SELF,
    }

    /** 访问方式 */
    enum class Channel {
        /** 直接 `File` / NIO 路径 */
        FILE,

        /** 系统文件选择器授权（SAF） */
        SAF,

        /** 声明"所有文件访问"后的直接路径访问 */
        MANAGE_ALL,

        /** 借同步器（Shizuku / root）以高权限身份代读 */
        PRIVILEGED,
    }

    /** 当前系统 SDK */
    fun sdk(): Int = Build.VERSION.SDK_INT

    /** 分区存储是否已强制执行（Android 11 / API 30 起） */
    fun scopedForced(): Boolean = sdk() >= Build.VERSION_CODES.R

    /** 旧版遗留标志是否还有用 —— Android 11 起一律失效 */
    fun legacyFlagWorks(): Boolean = sdk() < Build.VERSION_CODES.R

    /**
     * 从路径判断区域。
     *
     * 只做字符串判断，不碰磁盘。认不出来一律按 [Zone.PUBLIC] 处理——
     * 公共目录的通道最多，按它走不会把本来能做的事判成不能做。
     */
    fun zoneOf(path: String?): Zone {
        if (path.isNullOrBlank()) return Zone.PUBLIC
        val p = path.replace('\\', '/')
        if (p.contains("/Android/obb/") || p.contains("/Android/obb")) return Zone.OBB
        if (p.contains("/Android/data/")) return Zone.ANDROID_DATA
        return Zone.PUBLIC
    }

    /**
     * 这个区域能不能走 SAF 让用户授权。
     *
     * `Android/data` 与 `Android/obb` 从 Android 11 起**禁止**
     * 出现在目录选择器里（也不让浏览其子目录）。
     * 注意这不是 Android 14 才有的新规。
     */
    fun safCanGrant(zone: Zone): Boolean = when (zone) {
        Zone.PUBLIC -> true
        Zone.SELF -> true
        Zone.ANDROID_DATA -> false
        Zone.OBB -> false
    }

    /**
     * 拿到"所有文件访问"权限后，能否用**直接路径**写这个区域。
     *
     * 官方原文把 `/Android/data/`、`/sdcard/Android` 及其多数子目录
     * 排除在外，所以这里对 [Zone.ANDROID_DATA] 一律返回 false，
     * **不管有没有那个权限**。
     */
    fun manageAllCanWrite(zone: Zone): Boolean = when (zone) {
        Zone.PUBLIC -> true
        Zone.SELF -> true
        Zone.ANDROID_DATA -> false
        Zone.OBB -> false
    }

    /**
     * 这个区域下，哪些通道有可能走通。
     *
     * 只回答"技术上允许"，不回答"这台设备上一定行"——
     * [Channel.PRIVILEGED] 尤其如此，能不能用要看同步器是否真的活着。
     * 调用方拿到结果后还要再问一次 [PrivSync]。
     */
    fun channels(zone: Zone): List<Channel> = when (zone) {
        Zone.PUBLIC -> listOf(Channel.SAF, Channel.FILE, Channel.MANAGE_ALL)
        Zone.SELF -> listOf(Channel.FILE, Channel.SAF)
        Zone.ANDROID_DATA -> listOf(Channel.PRIVILEGED, Channel.MANAGE_ALL)
        Zone.OBB -> listOf(Channel.PRIVILEGED, Channel.MANAGE_ALL)
    }

    /**
     * 给用户看的一句话：这个目录该怎么拿。
     *
     * 返回的是**结论**而不是系统术语，因为用户不关心渠道名，
     * 只想知道"我该做什么"。
     */
    fun advice(zone: Zone): String = when (zone) {
        Zone.PUBLIC ->
            "在系统文件管理器里选中这一层即可授权，之后本应用可长期访问。"
        Zone.SELF ->
            "这是本应用自己的目录，无需授权，但卸载时会一起清掉，别放唯一副本。"
        Zone.ANDROID_DATA ->
            "这是其他应用的私有目录，系统不允许应用之间直接读写。" +
                "可以让同步器（Shizuku）代读后打成整合包，或让启动器自己导入。"
        Zone.OBB ->
            "与 Android/data 同样受限，需要同步器代读。"
    }

    /**
     * 面向开发/排查的一句话：当前系统下该注意什么。
     * 给"内置数据""关于"这类能看详情的地方用。
     */
    fun systemNote(): String {
        val sdk = sdk()
        return when {
            sdk < Build.VERSION_CODES.Q ->
                "Android ${ver(sdk)}：尚未引入分区存储，传统文件权限可用。"
            sdk == Build.VERSION_CODES.Q ->
                "Android 10：分区存储已引入但可退回旧视图，属过渡期，不能当长期保证。"
            sdk == Build.VERSION_CODES.R ->
                "Android 11：分区存储强制生效，SAF 禁止选择 Android/data 与 Android/obb。"
            sdk >= Build.VERSION_CODES.R ->
                "Android ${ver(sdk)}：沿用 Android 11 起的现行规则——" +
                    "直接路径与 SAF 都不能访问其他应用的 Android/data。"
            else -> "Android ${ver(sdk)}"
        }
    }

    /**
     * SDK 号对应的常见版本名，查不到就回数字。
     *
     * 35 / 36 只能写字面量：本工程 compileSdk 是 34，
     * `VERSION_CODES.VANILLA_ICE_CREAM`、`BAKLAVA` 这两个常量在本工程的
     * 编译环境下**不存在**，直接引用会编译失败。
     */
    private fun ver(sdk: Int): String = when (sdk) {
        Build.VERSION_CODES.Q -> "10"
        Build.VERSION_CODES.R -> "11"
        Build.VERSION_CODES.S -> "12"
        Build.VERSION_CODES.TIRAMISU -> "13"
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "14"
        35 -> "15"
        36 -> "16"
        else -> "$sdk"
    }
}
