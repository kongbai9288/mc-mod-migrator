package com.opentransfer.openlist

import org.json.JSONObject

/**
 * Configuration for OpenList cloud storage.
 * 
 * Supports multiple cloud providers:
 * - Aliyun Drive (阿里云盘)
 * - Baidu Netdisk (百度网盘)
 * - Quark (夸克网盘)
 * - Tianyi Cloud (天翼云盘)
 * - 123 Pan (123网盘)
 * - 115 Cloud (115网盘)
 * - Lanzou (蓝奏云)
 * - Google Drive
 * - OneDrive
 * - Dropbox
 * - S3 Compatible (AWS S3, MinIO, etc.)
 * - FTP/SFTP
 * - WebDAV
 */
data class OpenListConfig(
    /** Provider type identifier */
    val provider: String,
    /** Provider-specific configuration JSON */
    val configJson: String,
) {
    companion object {
        // ═══════════════════════════════════════════════════════
        // 🇨🇳 国内云盘
        // ═══════════════════════════════════════════════════════

        /**
         * 阿里云盘 (Aliyun Drive)
         * 
         * @param refreshToken 刷新令牌
         * @param apiUrl API 地址 (默认: https://api.oplist.org/alicloud/renewapi)
         * @param rootFolderId 根目录 ID (默认: "root")
         */
        fun aliyunDrive(
            refreshToken: String,
            apiUrl: String = "https://api.oplist.org/alicloud/renewapi",
            rootFolderId: String = "root",
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("refresh_token", refreshToken)
                put("use_online_api", true)
                put("api_url_address", apiUrl)
                put("drive_type", "default")
                put("root_folder_id", rootFolderId)
                put("remove_way", "trash")
            }
            return OpenListConfig("AliyundriveOpen", config.toString())
        }

        /**
         * 百度网盘 (Baidu Netdisk)
         * 
         * @param accessToken 访问令牌
         * @param refreshToken 刷新令牌
         * @param clientId 客户端 ID
         * @param clientSecret 客户端密钥
         */
        fun baiduNetdisk(
            accessToken: String,
            refreshToken: String = "",
            clientId: String = "",
            clientSecret: String = "",
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_token", accessToken)
                if (refreshToken.isNotEmpty()) put("refresh_token", refreshToken)
                if (clientId.isNotEmpty()) put("client_id", clientId)
                if (clientSecret.isNotEmpty()) put("client_secret", clientSecret)
            }
            return OpenListConfig("BaiduNetdisk", config.toString())
        }

        /**
         * 夸克网盘 (Quark)
         * 
         * @param cookie Cookie 字符串
         */
        fun quark(cookie: String): OpenListConfig {
            val config = JSONObject().apply {
                put("cookie", cookie)
            }
            return OpenListConfig("Quark", config.toString())
        }

        /**
         * 天翼云盘 (Tianyi Cloud)
         * 
         * @param accessToken 访问令牌
         * @param refreshToken 刷新令牌
         */
        fun tianyiCloud(
            accessToken: String,
            refreshToken: String = "",
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_token", accessToken)
                if (refreshToken.isNotEmpty()) put("refresh_token", refreshToken)
            }
            return OpenListConfig("TianyiCloud", config.toString())
        }

        /**
         * 123网盘 (123 Pan)
         * 
         * @param token 认证令牌
         */
        fun pan123(token: String): OpenListConfig {
            val config = JSONObject().apply {
                put("token", token)
            }
            return OpenListConfig("123Pan", config.toString())
        }

        /**
         * 115网盘 (115 Cloud)
         * 
         * @param cookie Cookie 字符串
         */
        fun cloud115(cookie: String): OpenListConfig {
            val config = JSONObject().apply {
                put("cookie", cookie)
            }
            return OpenListConfig("115Cloud", config.toString())
        }

        /**
         * 蓝奏云 (Lanzou)
         * 
         * @param username 用户名
         * @param password 密码
         * @param shareId 分享 ID (可选)
         */
        fun lanzou(
            username: String,
            password: String,
            shareId: String = "",
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("username", username)
                put("password", password)
                if (shareId.isNotEmpty()) put("share_id", shareId)
            }
            return OpenListConfig("Lanzou", config.toString())
        }

        /**
         * 阿里云盘 TV 版 (Aliyun Drive TV)
         * 
         * @param accessToken 访问令牌
         * @param refreshToken 刷新令牌
         */
        fun aliyunDriveTv(
            accessToken: String,
            refreshToken: String = "",
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_token", accessToken)
                if (refreshToken.isNotEmpty()) put("refresh_token", refreshToken)
            }
            return OpenListConfig("AliyundriveOpen", config.toString())
        }

        // ═══════════════════════════════════════════════════════
        // 🌍 国际云盘
        // ═══════════════════════════════════════════════════════

        /**
         * Google Drive
         * 
         * @param refreshToken 刷新令牌
         * @param clientId 客户端 ID
         * @param clientSecret 客户端密钥
         */
        fun googleDrive(
            refreshToken: String,
            clientId: String,
            clientSecret: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                put("client_secret", clientSecret)
            }
            return OpenListConfig("GoogleDrive", config.toString())
        }

        /**
         * OneDrive
         * 
         * @param refreshToken 刷新令牌
         * @param clientId 客户端 ID
         * @param clientSecret 客户端密钥
         */
        fun oneDrive(
            refreshToken: String,
            clientId: String,
            clientSecret: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                put("client_secret", clientSecret)
            }
            return OpenListConfig("OneDrive", config.toString())
        }

        /**
         * Dropbox
         * 
         * @param accessToken 访问令牌
         */
        fun dropbox(accessToken: String): OpenListConfig {
            val config = JSONObject().apply {
                put("access_token", accessToken)
            }
            return OpenListConfig("Dropbox", config.toString())
        }

        /**
         * Box
         * 
         * @param refreshToken 刷新令牌
         * @param clientId 客户端 ID
         * @param clientSecret 客户端密钥
         */
        fun box(
            refreshToken: String,
            clientId: String,
            clientSecret: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                put("client_secret", clientSecret)
            }
            return OpenListConfig("Box", config.toString())
        }

        // ═══════════════════════════════════════════════════════
        // ☁️ 对象存储
        // ═══════════════════════════════════════════════════════

        /**
         * S3 兼容存储 (AWS S3, MinIO, DigitalOcean Spaces, etc.)
         * 
         * @param accessKey 访问密钥
         * @param secretKey 秘密密钥
         * @param endpoint 端点地址
         * @param region 区域
         * @param bucket 桶名称
         * @param endpoint 桶名称
         */
        fun s3(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            region: String = "us-east-1",
            bucket: String,
            pathStyleAccess: Boolean = false,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_key_id", accessKey)
                put("secret_access_key", secretKey)
                put("endpoint", endpoint)
                put("region", region)
                put("bucket", bucket)
                put("path_style_access", pathStyleAccess)
            }
            return OpenListConfig("S3", config.toString())
        }

        /**
         * Google Cloud Storage
         * 
         * @param bucket 桶名称
         * @param serviceAccountJson 服务账号 JSON 内容
         */
        fun googleCloudStorage(
            bucket: String,
            serviceAccountJson: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("bucket_name", bucket)
                put("service_account_json", serviceAccountJson)
            }
            return OpenListConfig("GoogleCloudStorage", config.toString())
        }

        /**
         * Azure Blob Storage
         * 
         * @param accountName 账户名
         * @param accountKey 账户密钥
         * @param endpoint 端点地址
         * @param container 容器名称
         */
        fun azureBlob(
            accountName: String,
            accountKey: String,
            endpoint: String = "",
            container: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("account_name", accountName)
                put("account_key", accountKey)
                if (endpoint.isNotEmpty()) put("endpoint", endpoint)
                put("container", container)
            }
            return OpenListConfig("AzureBlob", config.toString())
        }

        /**
         * 阿里云 OSS (Alibaba Cloud OSS)
         * 
         * @param accessKey 访问密钥
         * @param secretKey 秘密密钥
         * @param endpoint 端点地址
         * @param bucket 桶名称
         */
        fun aliyunOss(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            bucket: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_key_id", accessKey)
                put("access_key_secret", secretKey)
                put("endpoint", endpoint)
                put("bucket_name", bucket)
            }
            return OpenListConfig("AliyunOSS", config.toString())
        }

        /**
         * 腾讯云 COS (Tencent Cloud COS)
         * 
         * @param accessKey 访问密钥
         * @param secretKey 秘密密钥
         * @param region 区域
         * @param bucket 桶名称
         */
        fun tencentCOS(
            accessKey: String,
            secretKey: String,
            region: String,
            bucket: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_key_id", accessKey)
                put("secret_access_key", secretKey)
                put("region", region)
                put("bucket_name", bucket)
            }
            return OpenListConfig("TencentCOS", config.toString())
        }

        /**
         * 华为云 OBS (Huawei Cloud OBS)
         * 
         * @param accessKey 访问密钥
         * @param secretKey 秘密密钥
         * @param endpoint 端点地址
         * @param bucket 桶名称
         */
        fun huaweiOBS(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            bucket: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_key_id", accessKey)
                put("secret_access_key", secretKey)
                put("endpoint", endpoint)
                put("bucket_name", bucket)
            }
            return OpenListConfig("HuaweiOBS", config.toString())
        }

        /**
         * Cloudflare R2
         * 
         * @param accessKey 访问密钥
         * @param secretKey 秘密密钥
         * @param endpoint 端点地址
         * @param bucket 桶名称
         */
        fun cloudflareR2(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            bucket: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("access_key_id", accessKey)
                put("secret_access_key", secretKey)
                put("endpoint", endpoint)
                put("bucket_name", bucket)
            }
            return OpenListConfig("S3", config.toString())
        }

        // ═══════════════════════════════════════════════════════
        // 📁 文件传输协议
        // ═══════════════════════════════════════════════════════

        /**
         * FTP
         * 
         * @param host 主机地址
         * @param port 端口 (默认: 21)
         * @param username 用户名
         * @param password 密码
         */
        fun ftp(
            host: String,
            port: Int = 21,
            username: String,
            password: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("host", host)
                put("port", port)
                put("username", username)
                put("password", password)
            }
            return OpenListConfig("FTP", config.toString())
        }

        /**
         * SFTP
         * 
         * @param host 主机地址
         * @param port 端口 (默认: 22)
         * @param username 用户名
         * @param password 密码
         * @param privateKey 私钥内容 (可选)
         */
        fun sftp(
            host: String,
            port: Int = 22,
            username: String,
            password: String = "",
            privateKey: String = "",
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("host", host)
                put("port", port)
                put("username", username)
                if (password.isNotEmpty()) put("password", password)
                if (privateKey.isNotEmpty()) put("private_key", privateKey)
            }
            return OpenListConfig("SFTP", config.toString())
        }

        /**
         * WebDAV
         * 
         * @param url WebDAV 服务器 URL
         * @param username 用户名
         * @param password 密码
         */
        fun webDav(
            url: String,
            username: String,
            password: String,
        ): OpenListConfig {
            val config = JSONObject().apply {
                put("url", url)
                put("username", username)
                put("password", password)
            }
            return OpenListConfig("WebDAV", config.toString())
        }

        /**
         * 本地存储 (Local)
         * 
         * @param rootPath 根目录路径
         */
        fun local(rootPath: String): OpenListConfig {
            val config = JSONObject().apply {
                put("root_folder_path", rootPath)
            }
            return OpenListConfig("Local", config.toString())
        }

        // ═══════════════════════════════════════════════════════
        // 🔧 通用配置
        // ═══════════════════════════════════════════════════════

        /**
         * 自定义配置
         * 
         * @param provider Provider 类型
         * @param configJson 配置 JSON 字符串
         */
        fun custom(provider: String, configJson: String): OpenListConfig {
            return OpenListConfig(provider, configJson)
        }

        /**
         * 从 JSON 字符串创建配置
         * 
         * @param json JSON 字符串，格式: {"provider": "...", "config": {...}}
         */
        fun fromJson(json: String): OpenListConfig {
            val obj = JSONObject(json)
            return OpenListConfig(
                provider = obj.getString("provider"),
                configJson = obj.getString("config"),
            )
        }
    }

    /**
     * 转换为 JSON 字符串
     */
    fun toJson(): String {
        return JSONObject().apply {
            put("provider", provider)
            put("config", JSONObject(configJson))
        }.toString()
    }

    /**
     * 获取配置值
     */
    fun getConfigValue(key: String): String? {
        return try {
            JSONObject(configJson).optString(key)
        } catch (_: Exception) {
            null
        }
    }
}
