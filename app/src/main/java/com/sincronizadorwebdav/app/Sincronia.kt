package com.sincronizadorwebdav.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Uma pasta do celular ligada a uma pasta do servidor. */
class Par(
    val id: Long,
    val pastaLocal: String,
    val nomeLocal: String,
    val pastaRemota: String,
    val direcao: Int,
    var automatico: Boolean,
    var ultimo: Long = 0L,
    var enviados: Int = 0,
    var baixados: Int = 0,
    var erros: Int = 0,
) {
    companion object {
        const val ENVIAR = 0
        const val BAIXAR = 1

        fun deJson(o: JSONObject) = Par(
            o.getLong("id"), o.getString("pastaLocal"), o.optString("nomeLocal"), o.optString("pastaRemota"),
            o.optInt("direcao"), o.optBoolean("automatico"), o.optLong("ultimo"),
            o.optInt("enviados"), o.optInt("baixados"), o.optInt("erros"),
        )
    }

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("pastaLocal", pastaLocal).put("nomeLocal", nomeLocal).put("pastaRemota", pastaRemota)
        .put("direcao", direcao).put("automatico", automatico).put("ultimo", ultimo)
        .put("enviados", enviados).put("baixados", baixados).put("erros", erros)
}

/** Arquivo de uma pasta do celular escolhida pelo seletor do Android */
class ArquivoLocal(val uri: Uri, val caminho: String, val tamanho: Long, val mime: String?)

object Pastas {

    fun nome(ctx: Context, arvore: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(arvore, DocumentsContract.getTreeDocumentId(arvore))
        try {
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) {
        }
        return DocumentsContract.getTreeDocumentId(arvore).substringAfterLast(':').substringAfterLast('/')
    }

    private class Filho(val id: String, val nome: String, val mime: String, val tamanho: Long)

    private fun filhos(ctx: Context, arvore: Uri, idPai: String): List<Filho> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(arvore, idPai)
        val colunas = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        val lista = mutableListOf<Filho>()
        ctx.contentResolver.query(uri, colunas, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                lista += Filho(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return lista
    }

    /** Todos os arquivos (das subpastas também) e o id de cada pasta pelo caminho relativo. */
    fun listarTudo(ctx: Context, arvore: Uri, pastas: MutableMap<String, String>): List<ArquivoLocal> {
        val raiz = DocumentsContract.getTreeDocumentId(arvore)
        pastas[""] = raiz
        val arquivos = mutableListOf<ArquivoLocal>()
        val fila = ArrayDeque<Pair<String, String>>()
        fila.add("" to raiz)
        while (fila.isNotEmpty()) {
            val (caminho, id) = fila.removeFirst()
            for (f in filhos(ctx, arvore, id)) {
                if (f.nome.startsWith(".")) continue
                val rel = if (caminho.isEmpty()) f.nome else "$caminho/${f.nome}"
                if (f.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pastas[rel] = f.id
                    fila.add(rel to f.id)
                } else {
                    arquivos += ArquivoLocal(DocumentsContract.buildDocumentUriUsingTree(arvore, f.id), rel, f.tamanho, f.mime)
                }
            }
        }
        return arquivos
    }

    /** Id da pasta no caminho, criando as que faltarem. */
    fun garantirPasta(ctx: Context, arvore: Uri, caminho: String, pastas: MutableMap<String, String>): String {
        pastas[caminho]?.let { return it }
        val pai = caminho.substringBeforeLast('/', "")
        val idPai = garantirPasta(ctx, arvore, pai, pastas)
        val nome = caminho.substringAfterLast('/')
        val novo = DocumentsContract.createDocument(
            ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(arvore, idPai),
            DocumentsContract.Document.MIME_TYPE_DIR, nome
        ) ?: throw IllegalStateException("não criou a pasta $caminho")
        val id = DocumentsContract.getDocumentId(novo)
        pastas[caminho] = id
        return id
    }
}

object Sincronia {

    private const val PREFS = "sincronias"
    private const val JOB = 1
    private const val CANAL = "sincronia"

    /** Evita duas sincronizações ao mesmo tempo (a automática e a do botão). */
    val ocupado = AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pares(ctx: Context): MutableList<Par> {
        val a = try { JSONArray(prefs(ctx).getString("pares", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { Par.deJson(a.getJSONObject(it)) }.toMutableList()
    }

    fun salvar(ctx: Context, pares: List<Par>) {
        val a = JSONArray()
        pares.forEach { a.put(it.json()) }
        prefs(ctx).edit().putString("pares", a.toString()).apply()
        agendar(ctx)
    }

    fun atualizar(ctx: Context, par: Par) {
        synchronized(this) {
            val todos = pares(ctx)
            val i = todos.indexOfFirst { it.id == par.id }
            if (i >= 0) {
                todos[i] = par
                salvar(ctx, todos)
            }
        }
    }

    fun soWifi(ctx: Context) = prefs(ctx).getBoolean("soWifi", true)
    fun soCarregando(ctx: Context) = prefs(ctx).getBoolean("soCarregando", false)
    fun configurar(ctx: Context, wifi: Boolean, carregando: Boolean) {
        prefs(ctx).edit().putBoolean("soWifi", wifi).putBoolean("soCarregando", carregando).apply()
        agendar(ctx)
    }

    /** Marca (ou desmarca) a sincronização automática a cada 6 horas. */
    fun agendar(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (pares(ctx).none { it.automatico } || !Conta.existe(ctx)) {
            js.cancel(JOB)
            return
        }
        val info = JobInfo.Builder(JOB, ComponentName(ctx, ServicoSincronia::class.java))
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setRequiredNetworkType(if (soWifi(ctx)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(soCarregando(ctx))
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    /** Sincroniza um par. Só manda/baixa o que é novo ou mudou de tamanho; nunca apaga nada. */
    fun executar(ctx: Context, par: Par, aoProgresso: (String) -> Unit, cancelado: () -> Boolean): Par {
        val dav = Conta.cliente(ctx) ?: throw IllegalStateException("sem conta")
        val arvore = Uri.parse(par.pastaLocal)
        val pastasLocais = HashMap<String, String>()
        val locais = Pastas.listarTudo(ctx, arvore, pastasLocais)
        val raizRemota = par.pastaRemota.trim('/')
        dav.criarPasta(raizRemota)
        val remotos = dav.listarTudo(raizRemota, cancelado)
            .associateBy { it.caminho.removePrefix(raizRemota).trim('/') }

        var enviados = 0
        var baixados = 0
        var erros = 0
        if (par.direcao == Par.ENVIAR) {
            val pastasCriadas = remotos.filterValues { it.pasta }.keys.toMutableSet()
            for (f in locais) {
                if (cancelado()) break
                val r = remotos[f.caminho]
                if (r != null && !r.pasta && r.tamanho == f.tamanho) continue
                try {
                    val pasta = f.caminho.substringBeforeLast('/', "")
                    if (pasta.isNotEmpty() && pasta !in pastasCriadas) {
                        dav.criarPasta(listOf(raizRemota, pasta).filter { it.isNotEmpty() }.joinToString("/"))
                        pastasCriadas += pasta
                    }
                    aoProgresso(f.caminho)
                    val destino = listOf(raizRemota, f.caminho).filter { it.isNotEmpty() }.joinToString("/")
                    dav.enviar(destino, { ctx.contentResolver.openInputStream(f.uri)!! }, f.tamanho, f.mime)
                    enviados++
                } catch (e: Exception) {
                    erros++
                }
            }
        } else {
            val porCaminho = locais.associateBy { it.caminho }
            for ((rel, r) in remotos) {
                if (cancelado()) break
                if (r.pasta || rel.isEmpty()) continue
                val l = porCaminho[rel]
                if (l != null && l.tamanho == r.tamanho) continue
                try {
                    aoProgresso(rel)
                    val doc = if (l != null) l.uri else {
                        val idPasta = Pastas.garantirPasta(ctx, arvore, rel.substringBeforeLast('/', ""), pastasLocais)
                        DocumentsContract.createDocument(
                            ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(arvore, idPasta),
                            android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(rel.substringAfterLast('.', "").lowercase())
                                ?: "application/octet-stream",
                            rel.substringAfterLast('/')
                        ) ?: throw IllegalStateException("não criou $rel")
                    }
                    ctx.contentResolver.openOutputStream(doc, "wt")!!.use { saida -> dav.baixar(r.caminho, saida) }
                    baixados++
                } catch (e: Exception) {
                    erros++
                }
            }
        }
        par.ultimo = System.currentTimeMillis()
        par.enviados = enviados
        par.baixados = baixados
        par.erros = erros
        return par
    }

    @Suppress("DEPRECATION")
    fun notificar(ctx: Context, enviados: Int, baixados: Int, erros: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CANAL, ctx.getString(R.string.canal), NotificationManager.IMPORTANCE_LOW))
        }
        val abrir = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANAL) else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(ctx.getString(if (erros > 0) R.string.notif_erro else R.string.notif_titulo))
            .setContentText(ctx.getString(R.string.resultado, "", enviados, baixados, erros).removePrefix(": "))
            .setColor(ctx.getColor(R.color.destaque))
            .setContentIntent(abrir)
            .setAutoCancel(true)
        nm.notify(1, b.build())
    }
}

/** Sincronização automática (a cada 6 horas, nas condições escolhidas). */
class ServicoSincronia : JobService() {

    @Volatile private var parar = false

    override fun onStartJob(params: JobParameters): Boolean {
        parar = false
        Thread {
            var enviados = 0
            var baixados = 0
            var erros = 0
            if (Sincronia.ocupado.compareAndSet(false, true)) {
                try {
                    for (par in Sincronia.pares(this).filter { it.automatico }) {
                        if (parar) break
                        try {
                            val r = Sincronia.executar(this, par, {}, { parar })
                            Sincronia.atualizar(this, r)
                            enviados += r.enviados
                            baixados += r.baixados
                            erros += r.erros
                        } catch (e: Exception) {
                            erros++
                        }
                    }
                } finally {
                    Sincronia.ocupado.set(false)
                }
                if (enviados + baixados + erros > 0) Sincronia.notificar(this, enviados, baixados, erros)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        parar = true
        return true
    }
}

class ReceptorInicio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Sincronia.agendar(ctx)
        }
    }
}
