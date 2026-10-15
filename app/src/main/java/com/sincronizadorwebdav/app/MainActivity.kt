package com.sincronizadorwebdav.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val PEDIDO_ENVIAR = 1
        const val PEDIDO_PASTA = 2
    }

    private lateinit var lista: ListView
    private lateinit var vazio: TextView
    private lateinit var caminhoTexto: TextView
    private lateinit var progresso: TextView
    private lateinit var listaSincronias: LinearLayout

    private var dav: WebDav? = null
    private var caminho = ""
    private var itens: List<ItemRemoto> = emptyList()
    private var abaSincronia = false
    private val adaptador = Adaptador()
    private val fundo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())

    /** Diálogo de nova sincronização esperando a pasta escolhida */
    private var aoEscolherPasta: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Conta.existe(this)) {
            startActivity(Intent(this, ContaActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        telaInteira(findViewById(R.id.raiz))

        lista = findViewById(R.id.lista)
        vazio = findViewById(R.id.vazio)
        caminhoTexto = findViewById(R.id.caminho)
        progresso = findViewById(R.id.progresso)
        listaSincronias = findViewById(R.id.listaSincronias)

        lista.adapter = adaptador
        lista.emptyView = vazio
        lista.setOnItemClickListener { _, v, pos, _ ->
            val item = itens[pos]
            if (item.pasta) abrirPasta(item.caminho) else baixar(item)
        }
        findViewById<View>(R.id.btnSubir).setOnClickListener {
            if (caminho.isNotEmpty()) abrirPasta(caminho.substringBeforeLast('/', ""))
        }
        findViewById<View>(R.id.btnConta).setOnClickListener { startActivity(Intent(this, ContaActivity::class.java)) }
        findViewById<View>(R.id.btnNovaPasta).setOnClickListener { novaPasta() }
        findViewById<View>(R.id.btnEnviar).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                PEDIDO_ENVIAR
            )
        }
        findViewById<View>(R.id.btnNovaSincronia).setOnClickListener { novaSincronia() }

        val soWifi = findViewById<Switch>(R.id.soWifi)
        val soCarregando = findViewById<Switch>(R.id.soCarregando)
        soWifi.isChecked = Sincronia.soWifi(this)
        soCarregando.isChecked = Sincronia.soCarregando(this)
        val aoMudar = { _: android.widget.CompoundButton, _: Boolean ->
            Sincronia.configurar(this, soWifi.isChecked, soCarregando.isChecked)
        }
        soWifi.setOnCheckedChangeListener(aoMudar)
        soCarregando.setOnCheckedChangeListener(aoMudar)

        montarChips()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Conta.existe(this)) {
            startActivity(Intent(this, ContaActivity::class.java))
            finish()
            return
        }
        // A conta pode ter mudado na tela de configuração
        val base = Conta.base(this)
        if (dav?.base != base.let { if (it.endsWith("/")) it else "$it/" }) {
            dav = Conta.cliente(this)
            caminho = ""
        }
        findViewById<TextView>(R.id.contaTexto).text = "${Conta.usuario(this)} @ ${Uri.parse(base).host ?: base}"
        abrirPasta(caminho)
        montarSincronias()
    }

    private fun montarChips() {
        val chips = findViewById<LinearLayout>(R.id.chips)
        chips.removeAllViews()
        for ((valor, nome) in listOf(false to getString(R.string.arquivos), true to getString(R.string.sincronizar))) {
            val sel = valor == abaSincronia
            chips.addView(TextView(this).apply {
                text = nome
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(18), 0, dp(18), 0)
                setBackgroundResource(if (sel) R.drawable.chip_selecionado else R.drawable.chip)
                setTextColor(if (sel) 0xFFFFFFFF.toInt() else getColor(R.color.texto))
                setOnClickListener {
                    abaSincronia = valor
                    montarChips()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
        findViewById<View>(R.id.painelArquivos).visibility = if (abaSincronia) View.GONE else View.VISIBLE
        findViewById<View>(R.id.painelSincronia).visibility = if (abaSincronia) View.VISIBLE else View.GONE
    }

    // ---------- Arquivos do servidor ----------

    private fun abrirPasta(novo: String) {
        val cliente = dav ?: return
        caminho = novo.trim('/')
        caminhoTexto.text = if (caminho.isEmpty()) getString(R.string.raiz) else "/ " + caminho.replace("/", " / ")
        itens = emptyList()
        adaptador.notifyDataSetChanged()
        vazio.setText(R.string.carregando)
        val pedido = caminho
        fundo.execute {
            val (r, erro) = try {
                cliente.listar(pedido).sortedWith(compareBy<ItemRemoto>({ !it.pasta }, { it.nome.lowercase(Locale.getDefault()) })) to null
            } catch (e: Throwable) {
                emptyList<ItemRemoto>() to mensagemDeErro(e)
            }
            principal.post {
                if (pedido != caminho) return@post
                itens = r
                adaptador.notifyDataSetChanged()
                vazio.text = erro ?: getString(R.string.pasta_vazia)
            }
        }
    }

    private fun mostrarProgresso(texto: String?) {
        progresso.visibility = if (texto == null) View.GONE else View.VISIBLE
        progresso.text = texto ?: ""
    }

    private fun baixar(item: ItemRemoto) {
        val cliente = dav ?: return
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 3)
            return
        }
        mostrarProgresso(getString(R.string.baixando, item.nome, 0))
        fundo.execute {
            val resultado: Any = try {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.nome.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val uri: Uri
                if (Build.VERSION.SDK_INT >= 29) {
                    val valores = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, item.nome)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Sincronizador")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores)!!
                    contentResolver.openOutputStream(uri)!!.use { s ->
                        cliente.baixar(item.caminho, s) { p -> principal.post { mostrarProgresso(getString(R.string.baixando, item.nome, p)) } }
                    }
                    valores.clear()
                    valores.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, valores, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val pasta = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Sincronizador")
                    pasta.mkdirs()
                    val f = File(pasta, item.nome)
                    f.outputStream().use { s ->
                        cliente.baixar(item.caminho, s) { p -> principal.post { mostrarProgresso(getString(R.string.baixando, item.nome, p)) } }
                    }
                    uri = Uri.fromFile(f)
                }
                uri to mime
            } catch (e: Throwable) {
                mensagemDeErro(e)
            }
            principal.post {
                mostrarProgresso(null)
                if (resultado is String) {
                    Toast.makeText(this, resultado, Toast.LENGTH_LONG).show()
                    return@post
                }
                Toast.makeText(this, R.string.baixado, Toast.LENGTH_SHORT).show()
                val par = resultado as Pair<*, *>
                val uri = par.first as Uri
                val mime = par.second as String
                if (uri.scheme == "content") try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun novaPasta() {
        val cliente = dav ?: return
        val campo = EditText(this).apply { setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(R.string.nome_pasta)
            .setView(FrameLayout(this).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(campo)
            })
            .setPositiveButton(R.string.criar) { _, _ ->
                val nome = campo.text.toString().trim().replace("/", "_")
                if (nome.isEmpty()) return@setPositiveButton
                val alvo = if (caminho.isEmpty()) nome else "$caminho/$nome"
                fundo.execute {
                    val erro = try { cliente.criarPasta(alvo); null } catch (e: Throwable) { mensagemDeErro(e) }
                    principal.post {
                        if (erro != null) Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
                        abrirPasta(caminho)
                    }
                }
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun enviar(uris: List<Uri>) {
        val cliente = dav ?: return
        val pasta = caminho
        fundo.execute {
            var erro: String? = null
            for (u in uris) {
                var nome = u.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
                var tamanho = -1L
                try {
                    contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            if (!c.isNull(0)) nome = c.getString(0)
                            if (!c.isNull(1)) tamanho = c.getLong(1)
                        }
                    }
                } catch (e: Exception) {
                }
                val destino = if (pasta.isEmpty()) nome else "$pasta/$nome"
                try {
                    cliente.enviar(destino, { contentResolver.openInputStream(u)!! }, tamanho, contentResolver.getType(u)) { p ->
                        principal.post { mostrarProgresso(getString(R.string.enviando, nome, p)) }
                    }
                } catch (e: Throwable) {
                    erro = mensagemDeErro(e)
                }
            }
            principal.post {
                mostrarProgresso(null)
                Toast.makeText(this, erro ?: getString(R.string.enviado), Toast.LENGTH_LONG).show()
                abrirPasta(caminho)
            }
        }
    }

    private fun menu(ancora: View, item: ItemRemoto) {
        val pm = PopupMenu(this, ancora)
        if (!item.pasta) pm.menu.add(0, 1, 0, R.string.baixar)
        pm.menu.add(0, 2, 1, R.string.excluir)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> baixar(item)
                2 -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.excluir_pergunta, item.nome))
                    .setPositiveButton(R.string.excluir) { _, _ ->
                        val cliente = dav ?: return@setPositiveButton
                        fundo.execute {
                            val erro = try { cliente.excluir(item.caminho, item.pasta); null } catch (e: Throwable) { mensagemDeErro(e) }
                            principal.post {
                                if (erro != null) Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
                                abrirPasta(caminho)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancelar, null)
                    .show()
            }
            true
        }
        pm.show()
    }

    // ---------- Sincronizações ----------

    private fun montarSincronias() {
        listaSincronias.removeAllViews()
        val pares = Sincronia.pares(this)
        if (pares.isEmpty()) {
            listaSincronias.addView(TextView(this).apply {
                setText(R.string.nenhuma_sincronia)
                setTextColor(getColor(R.color.texto2))
                textSize = 15f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }
        for (par in pares) {
            val v = layoutInflater.inflate(R.layout.item_sincronia, listaSincronias, false)
            v.findViewById<TextView>(R.id.titulo).text = par.nomeLocal
            val seta = if (par.direcao == Par.ENVIAR) "📱 ${par.nomeLocal}  →  ☁️ /${par.pastaRemota}"
            else "☁️ /${par.pastaRemota}  →  📱 ${par.nomeLocal}"
            v.findViewById<TextView>(R.id.rota).text = seta
            val ultimo = v.findViewById<TextView>(R.id.ultimo)
            ultimo.text = if (par.ultimo == 0L) getString(R.string.nunca) else getString(
                R.string.resultado,
                DateFormat.getDateFormat(this).format(Date(par.ultimo)) + " " + DateFormat.getTimeFormat(this).format(Date(par.ultimo)),
                par.enviados, par.baixados, par.erros
            )
            ultimo.setTextColor(getColor(if (par.erros > 0) R.color.erro else R.color.texto))
            val auto = v.findViewById<Switch>(R.id.automatico)
            auto.isChecked = par.automatico
            auto.setOnCheckedChangeListener { _, ligado ->
                par.automatico = ligado
                Sincronia.atualizar(this, par)
            }
            v.findViewById<Button>(R.id.btnAgora).setOnClickListener { sincronizarAgora(par, ultimo) }
            v.findViewById<ImageButton>(R.id.btnApagar).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(R.string.apagar_sincronia)
                    .setPositiveButton(R.string.excluir) { _, _ ->
                        Sincronia.salvar(this, Sincronia.pares(this).filter { it.id != par.id })
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(par.pastaLocal),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                        }
                        montarSincronias()
                    }
                    .setNegativeButton(R.string.cancelar, null)
                    .show()
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(10)
            listaSincronias.addView(v, params)
        }
    }

    private fun sincronizarAgora(par: Par, status: TextView) {
        if (!Sincronia.ocupado.compareAndSet(false, true)) return
        status.text = getString(R.string.sincronizando, "")
        Thread {
            val r = try {
                Sincronia.executar(this, par, { nome -> principal.post { status.text = getString(R.string.sincronizando, nome) } }, { false })
            } catch (e: Throwable) {
                val msg = mensagemDeErro(e)
                principal.post { status.text = msg }
                null
            } finally {
                Sincronia.ocupado.set(false)
            }
            if (r != null) {
                Sincronia.atualizar(this, r)
                principal.post { if (!isDestroyed) montarSincronias() }
            }
        }.start()
    }

    private fun novaSincronia() {
        var pastaEscolhida: Uri? = null
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun rotulo(t: Int) = TextView(this).apply {
            setText(t)
            textSize = 13f
            setTextColor(getColor(R.color.texto2))
            setPadding(0, dp(12), 0, dp(4))
        }
        val btnPasta = Button(this).apply {
            setText(R.string.escolher)
            isAllCaps = false
        }
        val campoRemoto = EditText(this).apply {
            setSingleLine()
            setText("Backup/")
        }
        val direcao = RadioGroup(this)
        val rEnviar = RadioButton(this).apply { id = View.generateViewId(); setText(R.string.enviar_servidor); isChecked = true }
        val rBaixar = RadioButton(this).apply { id = View.generateViewId(); setText(R.string.baixar_servidor) }
        direcao.addView(rEnviar)
        direcao.addView(rBaixar)
        val auto = Switch(this).apply { setText(R.string.automatico); isChecked = true }

        caixa.addView(rotulo(R.string.pasta_celular))
        caixa.addView(btnPasta)
        caixa.addView(rotulo(R.string.pasta_servidor))
        caixa.addView(campoRemoto)
        caixa.addView(rotulo(R.string.direcao))
        caixa.addView(direcao)
        caixa.addView(auto)

        btnPasta.setOnClickListener {
            aoEscolherPasta = { uri ->
                pastaEscolhida = uri
                val nome = Pastas.nome(this, uri)
                btnPasta.text = nome
                if (campoRemoto.text.toString().trim('/') == "Backup") campoRemoto.setText("Backup/$nome")
            }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PEDIDO_PASTA)
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.nova_sincronia)
            .setView(android.widget.ScrollView(this).apply { addView(caixa) })
            .setPositiveButton(R.string.salvar, null)
            .setNegativeButton(R.string.cancelar, null)
            .create()
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pasta = pastaEscolhida
                if (pasta == null) {
                    Toast.makeText(this, R.string.escolha_pasta, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val remoto = campoRemoto.text.toString().trim().trim('/').ifEmpty { "Backup" }
                val novo = Par(
                    System.currentTimeMillis(), pasta.toString(), Pastas.nome(this, pasta), remoto,
                    if (rBaixar.isChecked) Par.BAIXAR else Par.ENVIAR, auto.isChecked
                )
                Sincronia.salvar(this, Sincronia.pares(this) + novo)
                montarSincronias()
                dialogo.dismiss()
            }
        }
        dialogo.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PEDIDO_ENVIAR -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                if (uris.isNotEmpty()) enviar(uris)
            }
            PEDIDO_PASTA -> {
                val uri = data.data ?: return
                // Guarda o acesso à pasta para sincronizar depois, mesmo com o app fechado
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                aoEscolherPasta?.invoke(uri)
                aoEscolherPasta = null
            }
        }
    }

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = itens.size
        override fun getItem(posicao: Int) = itens[posicao]
        override fun getItemId(posicao: Int) = posicao.toLong()

        override fun getView(posicao: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_remoto, parent, false)
            val item = itens[posicao]
            val icone = v.findViewById<ImageView>(R.id.icone)
            icone.setImageResource(if (item.pasta) R.drawable.ic_pasta else R.drawable.ic_arquivo)
            icone.imageTintList = getColorStateList(if (item.pasta) R.color.pasta else R.color.texto2)
            v.findViewById<TextView>(R.id.nome).text = item.nome
            val partes = mutableListOf<String>()
            if (item.data > 0) partes += DateFormat.getDateFormat(this@MainActivity).format(Date(item.data))
            if (!item.pasta && item.tamanho > 0) partes += formatarTamanho(item.tamanho)
            v.findViewById<TextView>(R.id.detalhes).text = partes.joinToString("  •  ")
            v.findViewById<ImageButton>(R.id.btnMais).setOnClickListener { menu(it, item) }
            return v
        }
    }
}

fun formatarTamanho(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        kb < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", kb / 1024)
        else -> String.format(Locale.getDefault(), "%.2f GB", kb / 1024 / 1024)
    }
}
