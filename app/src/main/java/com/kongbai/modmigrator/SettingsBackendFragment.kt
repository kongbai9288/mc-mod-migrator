package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.util.concurrent.Executors

class SettingsBackendFragment : Fragment() {

    private lateinit var etBase: EditText
    private lateinit var etBackup: EditText
    private lateinit var btnProbe: Button
    private lateinit var tv: TextView
    private lateinit var tvCb: TextView
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_backend, container, false)
        etBase = v.findViewById(R.id.etBase)
        etBackup = v.findViewById(R.id.etBackup)
        btnProbe = v.findViewById(R.id.btnProbe)
        tv = v.findViewById(R.id.tvState)
        tvCb = v.findViewById(R.id.tvCb)

        val p = Prefs.get(requireContext())
        etBase.setText(p.getString(K.BACKEND_BASE, "") ?: "")
        etBackup.setText(p.getString(K.BACKEND_BACKUP, "") ?: "")

        fun watch(et: EditText) = et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.get(requireContext()).edit()
                    .putString(K.BACKEND_BASE, etBase.text.toString().trim())
                    .putString(K.BACKEND_BACKUP, etBackup.text.toString().trim())
                    .apply()
            }
        })
        watch(etBase)
        watch(etBackup)

        btnProbe.setOnClickListener { probe() }
        v.findViewById<Button>(R.id.btnCopyCb)?.setOnClickListener { copyCallback() }
        v.findViewById<Button>(R.id.btnGhOauth)?.setOnClickListener { openGhSettings() }
        probe()
        showCallback()
        return v
    }

    /**
     * 显示「后端入口 → 回调地址」的对应关系。
     * 后端按访问域名动态拼 redirect_uri，所以走哪个域名就得在 GitHub 登记哪条。
     * 报 redirect_uri 不匹配时，八成是走的域名没登记。
     */
    private fun showCallback() {
        val ctx = requireContext()
        exec.execute {
            val cb = BackendApi.callbackUrl(ctx)
            val all = BackendApi.allCallbackUrls()
            handler.post {
                val sb = StringBuilder()
                sb.append("当前连通的后端：").append(BackendApi.base(ctx)).append('\n')
                sb.append("实际回调地址：").append(cb).append("\n\n")
                sb.append("后端每个入口对应一条回调地址，都要登记：\n")
                for (u in all) sb.append("  · ").append(u).append('\n')
                sb.append("\nGitHub 白名单里缺当前这条，就会报 redirect_uri 不匹配。")
                tvCb.text = sb.toString()
            }
        }
    }

    private fun copyCallback() {
        val ctx = requireContext()
        exec.execute {
            val cb = BackendApi.callbackUrl(ctx)
            handler.post {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("callback", cb))
                android.widget.Toast.makeText(ctx, "已复制回调地址", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openGhSettings() {
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/settings/developers")
                )
            )
        } catch (t: Throwable) { Err.ignore(t, ")") }
    }

    private fun probe() {
        tv.text = "正在检测…"
        val ctx = requireContext()
        exec.execute {
            try {
                val r = BackendApi.probe(ctx)
                safePost(handler) {
                    tv.text = r.second
                    Toast.makeText(ctx, if (r.first) "后端可用" else "后端不可用", Toast.LENGTH_SHORT).show()
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
                     Err.ignore(t, "后台异常不崩进程")
                 }
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
