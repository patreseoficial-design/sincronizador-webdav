package com.sincronizadorwebdav.app

import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Erro com o código HTTP do servidor */
class ErroServidor(val codigo: Int, mensagem: String) : IOException("HTTP $codigo $mensagem")

/** Um arquivo ou pasta no servidor. [caminho] é relativo à pasta base, sem barra no começo. */
class ItemRemoto(val nome: String, val caminho: String, val pasta: Boolean, val tamanho: Long, val data: Long)

/** Cliente WebDAV simples: listar, baixar, enviar, criar pasta e excluir. */
class WebDav(base: String, usuario: String, senha: String) {

    /** Endereço base, sempre terminando com "/" */
    val base: String = if (base.endsWith("/")) base else "$base/"
    private val basePath: String = URLDecoder.decode(URI(this.base).rawPath ?: "/", "UTF-8")
    private val credencial = Credentials.basic(usuario, senha, Charsets.UTF_8)

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    companion object {
        private val CORPO_PROPFIND = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
            </d:prop></d:propfind>""".trimIndent()

        fun codificar(caminho: String): String =
            caminho.split('/').filter { it.isNotEmpty() }.joinToString("/") { parte ->
                java.net.URLEncoder.encode(parte, "UTF-8").replace("+", "%20")
            }
    }

    fun url(caminho: String, pasta: Boolean = false): String {
        val c = codificar(caminho)
        return base + c + if (pasta && c.isNotEmpty()) "/" else ""
    }

    private fun requisicao(url: String) = Request.Builder().url(url).header("Authorization", credencial)

    private fun checar(codigo: Int, mensagem: String) {
        if (codigo !in 200..299) throw ErroServidor(codigo, mensagem)
    }

    /** Confere endereço e senha (lança ErroServidor ou IOException se der errado). */
    fun testar() {
        val r = requisicao(base).header("Depth", "0")
            .method("PROPFIND", CORPO_PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        cliente.newCall(r).execute().use { checar(it.code, it.message) }
    }

    /** Conteúdo de uma pasta (sem ela mesma). */
    fun listar(caminho: String): List<ItemRemoto> {
        val r = requisicao(url(caminho, pasta = true)).header("Depth", "1")
            .method("PROPFIND", CORPO_PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        cliente.newCall(r).execute().use { resp ->
            checar(resp.code, resp.message)
            val corpo = resp.body ?: return emptyList()
            val propria = caminho.trim('/')
            return lerMultistatus(corpo.byteStream()).filter { it.caminho.trim('/') != propria }
        }
    }

    private fun lerMultistatus(entrada: InputStream): List<ItemRemoto> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(entrada, null)
        val itens = mutableListOf<ItemRemoto>()
        var href: String? = null
        var nome: String? = null
        var tamanho = 0L
        var data = 0L
        var pasta = false
        val formatoData = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "response" -> { href = null; nome = null; tamanho = 0L; data = 0L; pasta = false }
                    "href" -> href = p.nextText().trim()
                    "displayname" -> nome = p.nextText().trim()
                    "getcontentlength" -> tamanho = p.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> data = try { formatoData.parse(p.nextText().trim())?.time ?: 0L } catch (e: Exception) { 0L }
                    "collection" -> pasta = true
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "response") {
                val h = href ?: continue
                // href pode vir como caminho (/dav/x) ou endereço completo (https://.../dav/x)
                val caminhoUrl = try { URI(h).rawPath ?: h } catch (e: Exception) { h }
                val decodificado = URLDecoder.decode(caminhoUrl.replace("+", "%2B"), "UTF-8")
                if (!decodificado.startsWith(basePath.trimEnd('/'))) continue
                val relativo = decodificado.removePrefix(basePath.trimEnd('/')).trim('/')
                val n = nome?.takeIf { it.isNotEmpty() } ?: relativo.substringAfterLast('/')
                itens += ItemRemoto(n.ifEmpty { "/" }, relativo, pasta, tamanho, data)
            }
        }
        return itens
    }

    /** Lista tudo dentro da pasta (todas as subpastas). Os caminhos continuam relativos à base. */
    fun listarTudo(caminho: String, cancelado: () -> Boolean = { false }): List<ItemRemoto> {
        val todos = mutableListOf<ItemRemoto>()
        val fila = ArrayDeque<String>()
        fila.add(caminho)
        while (fila.isNotEmpty()) {
            if (cancelado()) break
            for (item in listar(fila.removeFirst())) {
                todos += item
                if (item.pasta) fila.add(item.caminho)
            }
        }
        return todos
    }

    fun baixar(caminho: String, saida: OutputStream, aoProgresso: (Int) -> Unit = {}) {
        val r = requisicao(url(caminho)).get().build()
        cliente.newCall(r).execute().use { resp ->
            checar(resp.code, resp.message)
            val corpo = resp.body ?: return
            val total = corpo.contentLength()
            val buf = ByteArray(64 * 1024)
            var lidos = 0L
            var ultimo = -1
            corpo.byteStream().use { entrada ->
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    saida.write(buf, 0, n)
                    lidos += n
                    if (total > 0) {
                        val pc = (lidos * 100 / total).toInt()
                        if (pc != ultimo) { ultimo = pc; aoProgresso(pc) }
                    }
                }
            }
        }
    }

    fun enviar(caminho: String, abrir: () -> InputStream, tamanho: Long, mime: String?, aoProgresso: (Int) -> Unit = {}) {
        val tipo = (mime ?: "application/octet-stream").toMediaType()
        val corpo = object : RequestBody() {
            override fun contentType() = tipo
            override fun contentLength() = if (tamanho > 0) tamanho else -1L
            override fun writeTo(sink: BufferedSink) {
                abrir().use { entrada ->
                    val fonte = entrada.source()
                    var enviados = 0L
                    var ultimo = -1
                    while (true) {
                        val n = fonte.read(sink.buffer, 64 * 1024)
                        if (n < 0) break
                        sink.emitCompleteSegments()
                        enviados += n
                        if (tamanho > 0) {
                            val pc = (enviados * 100 / tamanho).toInt()
                            if (pc != ultimo) { ultimo = pc; aoProgresso(pc) }
                        }
                    }
                }
            }
        }
        val r = requisicao(url(caminho)).put(corpo).build()
        cliente.newCall(r).execute().use { checar(it.code, it.message) }
    }

    /** Cria a pasta (e as de cima, se faltarem). */
    fun criarPasta(caminho: String) {
        val partes = caminho.split('/').filter { it.isNotEmpty() }
        var atual = ""
        for (parte in partes) {
            atual = if (atual.isEmpty()) parte else "$atual/$parte"
            val r = requisicao(url(atual, pasta = true)).method("MKCOL", null).build()
            cliente.newCall(r).execute().use {
                // 405 = já existe
                if (it.code != 405) checar(it.code, it.message)
            }
        }
    }

    fun excluir(caminho: String, pasta: Boolean) {
        val r = requisicao(url(caminho, pasta)).delete().build()
        cliente.newCall(r).execute().use { checar(it.code, it.message) }
    }
}
