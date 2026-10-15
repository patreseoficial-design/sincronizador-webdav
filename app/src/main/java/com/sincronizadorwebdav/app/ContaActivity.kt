package com.sincronizadorwebdav.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Endereço do servidor, usuário e senha. */
class ContaActivity : Activity() {

    private lateinit var spTipo: Spinner
    private lateinit var campoServidor: EditText
    private lateinit var campoUsuario: EditText
    private lateinit var campoSenha: EditText
    private lateinit var mensagem: TextView
    private lateinit var btnConectar: Button
    private val principal = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conta)
        telaInteira(findViewById(R.id.raiz))

        spTipo = findViewById(R.id.spTipo)
        campoServidor = findViewById(R.id.campoServidor)
        campoUsuario = findViewById(R.id.campoUsuario)
        campoSenha = findViewById(R.id.campoSenha)
        mensagem = findViewById(R.id.mensagem)
        btnConectar = findViewById(R.id.btnConectar)

        spTipo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.tipos))
        spTipo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                campoServidor.hint = getString(if (pos == Conta.NEXTCLOUD) R.string.servidor_dica_nextcloud else R.string.servidor_dica_webdav)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        if (Conta.existe(this)) {
            spTipo.setSelection(Conta.tipo(this))
            campoServidor.setText(Conta.servidor(this))
            campoUsuario.setText(Conta.usuario(this))
            findViewById<View>(R.id.btnSair).visibility = View.VISIBLE
        }
        btnConectar.setOnClickListener { conectar() }
        findViewById<View>(R.id.btnSair).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.sair_pergunta)
                .setPositiveButton(R.string.sair) { _, _ ->
                    Sincronia.salvar(this, emptyList())
                    Conta.sair(this)
                    Sincronia.agendar(this)
                    recreate()
                }
                .setNegativeButton(R.string.cancelar, null)
                .show()
        }
    }

    private fun conectar() {
        val tipo = spTipo.selectedItemPosition
        val servidor = campoServidor.text.toString().trim()
        val usuario = campoUsuario.text.toString().trim()
        val senha = campoSenha.text.toString()
        if (servidor.isEmpty() || usuario.isEmpty() || senha.isEmpty()) return
        val base = try { Conta.montarBase(tipo, servidor, usuario) } catch (e: Exception) { return }
        mensagem.text = ""
        btnConectar.isEnabled = false
        btnConectar.setText(R.string.testando)
        Thread {
            val erro = try {
                WebDav(base, usuario, senha).testar()
                null
            } catch (e: Throwable) {
                mensagemDeErro(e)
            }
            principal.post {
                if (isDestroyed) return@post
                btnConectar.isEnabled = true
                btnConectar.setText(R.string.testar_salvar)
                if (erro != null) {
                    mensagem.text = erro
                    return@post
                }
                Conta.salvar(this, tipo, servidor, usuario, senha, base)
                Sincronia.agendar(this)
                Toast.makeText(this, R.string.conectado, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }.start()
    }
}
