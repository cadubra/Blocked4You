package com.blockyou.firestick

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/** Código que o usuário digita no celular para autorizar a TV. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)

/**
 * Login com a conta do YouTube pelo fluxo de "ativação de TV" (o mesmo do app oficial e do SmartTube):
 * a TV mostra um código, o usuário confirma em youtube.com/activate e recebemos um token.
 * Guardamos só o refresh token; o access token é renovado quando expira.
 */
object YoutubeAuth {
    private const val TAG = "BlockYou"
    private const val OAUTH = "https://www.youtube.com/o/oauth2"
    private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
    const val TV_USER_AGENT =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
            "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"

    private const val PREFS = "auth"
    private val JSON = "application/json".toMediaType()

    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L

    fun isLoggedIn(context: Context) = prefs(context).getString("refresh_token", null) != null

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
        accessToken = null
        accessTokenExpiresAt = 0
    }

    /** Passo 1: pede ao YouTube um código de ativação. Bloqueante (chamar fora da thread principal). */
    fun requestDeviceCode(context: Context): DeviceCode {
        val (clientId, _) = clientCredentials(context)
        val body = JSONObject()
            .put("client_id", clientId)
            .put("device_id", deviceId(context))
            .put("model_name", "ytlr::")
            .put("scope", SCOPE)
        val json = post("$OAUTH/device/code", body)
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url", "https://www.youtube.com/activate"),
            intervalSec = json.optInt("interval", 5),
            expiresInSec = json.optInt("expires_in", 1800),
        )
    }

    /**
     * Passo 2: pergunta se o usuário já autorizou. Devolve true quando o login terminou,
     * false se ainda está pendente; lança exceção se o código expirou ou foi recusado.
     */
    fun pollForToken(context: Context, code: DeviceCode): Boolean {
        val (clientId, clientSecret) = clientCredentials(context)
        val body = JSONObject()
            .put("code", code.deviceCode)
            .put("client_id", clientId)
            .put("client_secret", clientSecret)
            .put("grant_type", "http://oauth.net/grant_type/device/1.0")
        val json = post("$OAUTH/token", body, allowErrors = true)

        val refreshToken = json.optString("refresh_token")
        if (refreshToken.isNotEmpty()) {
            prefs(context).edit().putString("refresh_token", refreshToken).apply()
            saveAccessToken(json)
            return true
        }
        when (val error = json.optString("error")) {
            "", "authorization_pending", "slow_down" -> return false
            else -> error("Login recusado: $error")
        }
    }

    /** Access token válido (renova com o refresh token quando necessário), ou null se deslogado. */
    @Synchronized
    fun accessToken(context: Context): String? {
        val refreshToken = prefs(context).getString("refresh_token", null) ?: return null
        accessToken?.let { if (System.currentTimeMillis() < accessTokenExpiresAt) return it }

        val (clientId, clientSecret) = clientCredentials(context)
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("client_id", clientId)
            .put("client_secret", clientSecret)
            .put("grant_type", "refresh_token")
        val json = post("$OAUTH/token", body, allowErrors = true)
        if (json.optString("access_token").isEmpty()) {
            Log.w(TAG, "Falha ao renovar o token: ${json.optString("error")}")
            if (json.optString("error") == "invalid_grant") logout(context) // acesso revogado pelo usuário
            return null
        }
        saveAccessToken(json)
        return accessToken
    }

    private fun saveAccessToken(json: JSONObject) {
        accessToken = json.getString("access_token")
        // Renova um minuto antes de expirar
        accessTokenExpiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000
    }

    /**
     * O client_id/secret do app de TV não é fixo: lemos do script do youtube.com/tv,
     * como o SmartTube faz. Fica em cache para não baixar o script toda hora.
     */
    private fun clientCredentials(context: Context): Pair<String, String> {
        val prefs = prefs(context)
        val cachedId = prefs.getString("client_id", null)
        val cachedSecret = prefs.getString("client_secret", null)
        if (cachedId != null && cachedSecret != null) return cachedId to cachedSecret

        val page = get("https://www.youtube.com/tv")
        val scriptPath = Regex("""id="base-js" src="(.*?)"""").find(page)?.groupValues?.get(1)
            ?: Regex("""\.src = '(.*?m=base)'""").find(page)?.groupValues?.get(1)
            ?: error("Script do YouTube TV não encontrado")
        val script = get(if (scriptPath.startsWith("http")) scriptPath else "https://www.youtube.com$scriptPath")

        // Só a primeira ocorrência funciona (as outras dão 401)
        val match = Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"(\w+)"""").find(script)
            ?: error("Credenciais do YouTube TV não encontradas")
        val (id, secret) = match.destructured
        prefs.edit().putString("client_id", id).putString("client_secret", secret).apply()
        return id to secret
    }

    private fun deviceId(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", TV_USER_AGENT).build()
        OkHttpDownloader.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} em $url" }
            return response.body?.string().orEmpty()
        }
    }

    private fun post(url: String, body: JSONObject, allowErrors: Boolean = false): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TV_USER_AGENT)
            .post(body.toString().toRequestBody(JSON))
            .build()
        OkHttpDownloader.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && !allowErrors) error("HTTP ${response.code} em $url")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
