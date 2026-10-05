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
                msg = "登录失败：${Http.describeError(t)}"
                LogCenter.e("Login", "PKCE 换 token 失败：$msg")
            }
            Bg.post { back(msg) }
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
