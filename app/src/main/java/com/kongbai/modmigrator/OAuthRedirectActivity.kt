package com.kongbai.modmigrator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * OAuth 回调落点。
 *
 * 授权完成后 GitHub 重定向到 mm://oauth/callback?code=..&state=..
 * 自定义 scheme 浏览器处理不了，会把它交回系统，
 * 系统按 intent-filter 送到这里。
 *
 * 这里拿到 code，配上之前存下的 code_verifier 去 GitHub 换 token，
 * 全程在后台线程做，做完把结果写回设置页能读到的位置。
 *
 * 顺带也接 https 那条（后端中转流程的回调地址），
 * 万一用户设备上 App Links 生效了，也能接住。
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val data: Uri? = i?.data
        if (data == null) {
            back("回调里没有地址")
            return
        }
        val err = data.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            back("GitHub 拒绝了授权：$err")
            return
        }
        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            back("回调里没有 code")
            return
        }
        val verifier = GhPkce.takeVerifier(this)
        if (verifier.isBlank()) {
            back("找不到上一步的验证码（可能应用被系统回收了），请重新点一次登录")
            return
        }

        Bg.run {
            var msg: String
            try {
                val token = GhPkce.exchange(this, code, verifier)
                val user = GitHubApi.verifyPat(token).first
                if (user == null) {
                    msg = "换到了令牌，但用它查不到账号信息"
                } else {
                    // 跟「用访问令牌登录」写同一处，设置页才能读到
                    Prefs.get(this).edit()
                        .putString(K.TOKEN, token)
                        .putString(K.GH_TOKEN_BACKEND, token)
                        .apply()
                    msg = "已登录：${user.login}"
                    LogCenter.i("Login", "PKCE 登录成功：${user.login}")
                }
            } catch (t: Throwable) {
                msg = Http.describeError(t)
                LogCenter.e("Login", "PKCE 换 token 失败：$msg")
            }
            Bg.post {
                if (msg.startsWith("已登录")) back(msg)
                else failDialog(code, verifier, msg)
            }
        }
    }

    /**
     * 换 token 失败时**当场给机会改 Client ID**。
     *
     * GitHub 报 "The client_id ... is incorrect" 时，用户的 code 还没被消耗，
     * 换一个正确的 client_id 直接重试用同一个 code 就行 ——
     * 否则他得回设置页翻菜单、再从头授权一遍。
     */
    private fun failDialog(code: String, verifier: String, msg: String) {
        val et = android.widget.EditText(this).apply { setSingleLine(true) }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        box.addView(android.widget.TextView(this).apply {
            text = msg
            textSize = 12f
        })
        box.addView(android.widget.TextView(this).apply {
            text = "\nClient ID 不对的话，在这里改：\n" +
                "注意要填 **OAuth App**（不是 GitHub App）的 Client ID。"
            textSize = 12f
        })
        box.addView(et)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("登录失败")
            .setView(box)
            .setNegativeButton("关闭") { _, _ -> back("登录失败") }
            .setNeutralButton("打开 GitHub 设置页") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/settings/developers")
                        )
                    )
                }
                failDialog(code, verifier, msg)
            }
            .setPositiveButton("改好，重试") { _, _ ->
                val id = et.text.toString().trim()
                if (id.isBlank()) {
                    back("没填 Client ID")
                    return@setPositiveButton
                }
                Prefs.get(this).edit().putString(K.GH_CLIENT_ID, id).apply()
                retry(code, verifier)
            }
            .setCancelable(false)
            .show()
    }

    private fun retry(code: String, verifier: String) {
        android.widget.Toast.makeText(this, "正在重试…", android.widget.Toast.LENGTH_SHORT).show()
        Bg.run {
            var msg: String
            try {
                val token = GhPkce.exchange(this, code, verifier)
                val user = GitHubApi.verifyPat(token).first
                if (user == null) {
                    msg = "换到了令牌，但用它查不到账号信息"
                } else {
                    Prefs.get(this).edit()
                        .putString(K.TOKEN, token)
                        .putString(K.GH_TOKEN_BACKEND, token)
                        .apply()
                    msg = "已登录：${user.login}"
                    LogCenter.i("Login", "PKCE 登录成功：${user.login}")
                }
            } catch (t: Throwable) {
                msg = Http.describeError(t)
                LogCenter.e("Login", "PKCE 重试失败：$msg")
            }
            Bg.post {
                if (msg.startsWith("已登录")) back(msg) else failDialog(code, verifier, msg)
            }
        }
    }

    private fun back(msg: String) {
        runCatching {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        // 回到主界面，让用户能直接看到账户状态
        val home = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(home) }
        finish()
    }
}
