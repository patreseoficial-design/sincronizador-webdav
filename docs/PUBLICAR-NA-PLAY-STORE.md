# Como publicar o Sincronizador WebDAV na Play Store

É o mesmo caminho do Gerenciador de Arquivos. Aqui fica só o que muda.

---

## 1. Chave de assinatura

Pode usar **a mesma chave** do Gerenciador de Arquivos. Neste repositório: **Settings → Secrets and variables → Actions → New repository secret** e crie os mesmos 4 segredos, com os mesmos valores do `LEIA-ME-CHAVE.txt`:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Depois disso, cada build gera o artefato **`app-release-playstore`** (o `.aab` da loja).

---

## 2. Criar o app no Play Console

- **Nome do app:** `Sincronizador WebDAV e Backup` (29 caracteres)
- **Idioma padrão:** Português (Brasil)
- **App**, **Gratuito**
- **Categoria:** Ferramentas

**Descrição curta** (até 80 caracteres):

```
Backup e sincronização de arquivos com Nextcloud, ownCloud, NAS e WebDAV.
```

**Descrição completa:**

```
Seus arquivos no SEU servidor. Sincronize e faça backup do celular com Nextcloud, ownCloud, NAS ou qualquer servidor WebDAV.

☁️ CONECTE EM SEGUNDOS
• Nextcloud e ownCloud: só o endereço, usuário e senha
• Qualquer servidor WebDAV (Synology, QNAP, hospedagens e outros)
• Senha guardada com criptografia no celular

📁 SEUS ARQUIVOS NO SERVIDOR
• Navegue pelas pastas
• Baixe, envie, crie pastas e exclua

🔄 SINCRONIZAÇÃO E BACKUP
• Celular → Servidor: backup das fotos, documentos e o que você quiser
• Servidor → Celular: receba os arquivos de uma pasta do servidor
• Só envia o que é novo ou mudou
• Nunca apaga nada sozinho

⏱️ AUTOMÁTICO
• A cada 6 horas, sem você precisar abrir o app
• Só no Wi-Fi e só carregando, se preferir

🔒 PRIVACIDADE
• Sem anúncios
• O app só conversa com o servidor que você configurar
```

**Imagens:**
- Ícone 512 × 512 px: está em `docs/icone-512.png`
- Imagem de destaque 1024 × 500 px
- Pelo menos 2 capturas de tela: a tela de conta, a lista de arquivos do servidor e a tela de sincronizações

**Política de privacidade (URL):**

```
https://github.com/patreseoficial-design/politica-privacidade/blob/main/sincronizador-webdav.md
```

---

## 3. Conteúdo do app

- **Segurança dos dados:** o app coleta ou compartilha dados? → **Não**. Os arquivos vão só para o servidor que o próprio usuário configura (não para o desenvolvedor). Se o Google pedir mais detalhes, marque também que os dados são **criptografados em trânsito** (quando o servidor usa https).
- **Anúncios:** → **Não**
- **Classificação de conteúdo:** Utilitário/Produtividade, sem violência, sem conteúdo sexual, sem interação entre usuários → **Livre**
- **Público-alvo:** 18 anos ou mais (ou 13+)

O app não usa permissões especiais: as pastas do celular são escolhidas pelo próprio usuário no seletor do Android.

**Dica para a análise do Google:** o revisor precisa de um servidor para testar. Em **Acesso ao app**, informe um endereço de teste com usuário e senha (por exemplo uma conta grátis de Nextcloud).

---

## 4. Teste fechado e publicação

Igual ao Gerenciador: teste fechado com 12 pessoas por 14 dias (se a conta pedir), depois **Produção → Criar versão → enviar o `.aab`**.
