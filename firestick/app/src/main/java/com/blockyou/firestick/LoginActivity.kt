package com.blockyou.firestick

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView

/** Mostra o código de ativação e espera o usuário confirmar no celular. */
class LoginActivity : Activity() {
    private lateinit var url: TextView
    private lateinit var code: TextView
    private lateinit var status: TextView

    @Volatile
    private var active = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        url = findViewById(R.id.login_url)
        code = findViewById(R.id.login_code)
        status = findViewById(R.id.login_status)
        start()
    }

    private fun start() {
        status.text = getString(R.string.loading)
        Thread {
            try {
                val deviceCode = YoutubeAuth.requestDeviceCode(this)
                runOnUiThread {
                    url.text = deviceCode.verificationUrl.removePrefix("https://")
                    code.text = deviceCode.userCode
                    code.visibility = View.VISIBLE
                    status.text = getString(R.string.login_waiting)
                }

                val deadline = System.currentTimeMillis() + deviceCode.expiresInSec * 1000L
                while (active && System.currentTimeMillis() < deadline) {
                    Thread.sleep(deviceCode.intervalSec * 1000L)
                    if (!active) return@Thread
                    if (YoutubeAuth.pollForToken(this, deviceCode)) {
                        Log.i(TAG, "Login concluído")
                        runOnUiThread {
                            setResult(RESULT_OK)
                            finish()
                        }
                        return@Thread
                    }
                }
                // Código expirou: gera outro
                if (active) runOnUiThread { start() }
            } catch (e: Exception) {
                Log.e(TAG, "Falha no login", e)
                runOnUiThread { status.text = getString(R.string.error, e.message) }
            }
        }.start()
    }

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockYou"
    }
}
