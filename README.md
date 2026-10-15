# Sincronizador WebDAV

Sincroniza e faz backup de arquivos do Android com o **seu** servidor: Nextcloud, ownCloud, NAS (Synology, QNAP…) ou qualquer servidor WebDAV.

- **Conta**: Nextcloud/ownCloud (basta o endereço, usuário e senha) ou outro WebDAV; a senha fica criptografada no celular
- **Arquivos**: veja as pastas do servidor, baixe (vai para Downloads › Sincronizador), envie arquivos, crie pastas e exclua
- **Sincronizar** pastas do celular:
  - **Celular → Servidor** (backup): envia os arquivos novos ou que mudaram
  - **Servidor → Celular**: baixa os arquivos novos ou que mudaram
  - Nunca apaga nada sozinho
- **Automático** a cada 6 horas, com opção de **só no Wi-Fi** e **só carregando**
- Aviso quando a sincronização automática termina
- Em português, inglês e espanhol
- Sem anúncios; o app só conversa com o servidor que você configurar

Documentos:

- Como publicar na Play Store: [docs/PUBLICAR-NA-PLAY-STORE.md](docs/PUBLICAR-NA-PLAY-STORE.md)
- Política de privacidade: [docs/politica-de-privacidade.md](docs/politica-de-privacidade.md)

O GitHub Actions compila o app a cada envio para a `main`. O APK de teste fica em **Actions → (última execução) → Artifacts → app-debug**.
