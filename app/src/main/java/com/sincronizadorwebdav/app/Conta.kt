package com.sincronizadorwebdav.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Endereço, usuário e senha do servidor. A senha fica criptografada com uma chave do Android. */
object Conta {

    private const val PREFS = "conta"
    private const val ALIAS = "senha_servidor"

    const val NEXTCLOUD = 0
    const val WEBDAV = 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun existe(ctx: Context) = prefs(ctx).getString("base", null) != null
    fun tipo(ctx: Context) = prefs(ctx).getInt("tipo", NEXTCLOUD)
    fun servidor(ctx: Context) = prefs(ctx).getString("servidor", "")!!
    fun usuario(ctx: Context) = prefs(ctx).getString("usuario", "")!!
    fun base(ctx: Context) = prefs(ctx).getString("base", "")!!

    /** Endereço WebDAV de verdade a partir do que o usuário digitou */
    fun montarBase(tipo: Int, servidor: String, usuario: String): String {
        var s = servidor.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.trimEnd('/')
        return if (tipo == NEXTCLOUD && !s.contains("/remote.php/")) {
            "$s/remote.php/dav/files/${WebDav.codificar(usuario)}/"
        } else "$s/"
    }

    fun salvar(ctx: Context, tipo: Int, servidor: String, usuario: String, senha: String, base: String) {
        prefs(ctx).edit()
            .putInt("tipo", tipo)
            .putString("servidor", servidor)
            .putString("usuario", usuario)
            .putString("base", base)
            .putString("senha", criptografar(senha))
            .apply()
    }

    fun sair(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) { }
    }

    fun cliente(ctx: Context): WebDav? {
        if (!existe(ctx)) return null
        val senha = try { descriptografar(prefs(ctx).getString("senha", "")!!) } catch (e: Exception) { return null }
        return WebDav(base(ctx), usuario(ctx), senha)
    }

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gerador.generateKey()
    }

    private fun criptografar(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave())
        val dados = c.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(dados, Base64.NO_WRAP)
    }

    private fun descriptografar(guardado: String): String {
        val (iv, dados) = guardado.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, iv))
        return String(c.doFinal(dados), Charsets.UTF_8)
    }
}

/** Mensagem amigável para um erro de conexão */
fun Context.mensagemDeErro(e: Throwable): String = when {
    e is ErroServidor && (e.codigo == 401 || e.codigo == 403) -> getString(R.string.erro_senha)
    e is ErroServidor -> getString(R.string.erro_webdav, e.codigo)
    e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
        getString(R.string.erro_servidor)
    e is javax.net.ssl.SSLException -> getString(R.string.erro_certificado)
    else -> getString(R.string.erro_geral, e.message ?: e.javaClass.simpleName)
}

@Suppress("DEPRECATION")
fun Activity.telaInteira(raiz: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
    raiz.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val teclado = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, teclado.bottom))
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }
        insets
    }
}

fun Context.dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()
