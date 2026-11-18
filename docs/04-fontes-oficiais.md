# Fontes oficiais do catálogo (Etapa 6)

Pesquisa feita em **18/11/2026**. Regra do projeto: só fontes oficiais, priorizando **APIs e
feeds legíveis por máquina**; páginas HTML só quando não existe alternativa. Tudo fica
centralizado em `feature/catalog/src/main/resources/dev/bootforge/catalog/catalog.json`, que pode
ser **atualizado sem reconstruir o app** (Catálogo → *Atualizar catálogo*).

## Resumo por fonte

| Sistema | Como a versão é descoberta | De onde baixa | Verificação | Situação no teste ao vivo |
|---|---|---|---|---|
| **Ubuntu** Desktop/Server LTS | Feed `changelogs.ubuntu.com/meta-release-lts` (o mesmo usado pelo atualizador do Ubuntu) | `releases.ubuntu.com/<versão>/` | `SHA256SUMS` + **assinatura OpenPGP** `SHA256SUMS.gpg` | ✅ 26.04.1, assinatura válida |
| **Linux Mint** Cinnamon/Xfce | Índice de pastas `stable/` do espelho kernel.org | `mirrors.edge.kernel.org/linuxmint/stable/<versão>/` | `sha256sum.txt` + **assinatura** `.gpg` | ✅ 22.3, assinatura válida |
| **Debian** netinst / Live GNOME | Índice `cdimage.debian.org/debian-cd/current/…` | o próprio cdimage.debian.org | `SHA512SUMS` + **assinatura** `.sign` | ✅ 13.7.0, assinatura válida |
| **Fedora** Workstation / KDE | JSON oficial `fedoraproject.org/releases.json` (só versões finais, sem Beta) | `download.fedoraproject.org` (redireciona para espelho oficial) | SHA-256 + tamanho do JSON (HTTPS) | ✅ 45 |
| **Pop!_OS** Intel/NVIDIA | API `api.pop-os.org/builds/24.04/{intel,nvidia}` | `iso.pop-os.org` | `sha_sum` + tamanho da API (HTTPS) | ✅ 24.04 build 20/29 |
| **Zorin OS** Core | Página oficial de download (lista de espelhos oficiais) | `distro.ibiblio.org/zorinos/` (listado pelo zorin.com) | SHA-256 da página oficial de verificação (`help.zorin.com`) | ✅ 18.1 |
| **Alpine Linux** (extra, ferramentas) | `latest-releases.yaml` oficial | `dl-cdn.alpinelinux.org` | SHA-512 do YAML | ✅ 3.24.4; download real com pausa/retomada conferido |
| **Ventoy** | API de releases do GitHub (repositório oficial) | GitHub Releases | `sha256.txt` da própria release | ⚠️ GitHub bloqueado no ambiente de desenvolvimento (403); testado com servidor local; funciona no celular |
| **Windows 10/11** | — | Somente pelo navegador, no site da Microsoft | O usuário cola o SHA-256 que a Microsoft mostra em *Verify your download* | ⛔ Não automatizável (veja abaixo) |

## Por que o Windows é só pelo navegador

O site `microsoft.com/software-download` gera o link da ISO dentro do navegador, depois de
escolher a edição e o idioma, com proteções contra automação. Contornar isso violaria a regra de
não usar truques nem fontes alternativas. O BootForge abre a página oficial e oferece
**Verificar ISO baixada**, que compara o arquivo com o SHA-256 publicado pela Microsoft (sem
copiá-lo).

## Assinaturas digitais (OpenPGP)

As chaves públicas oficiais vão **embutidas no app**. Cada uma foi baixada da fonte oficial e
conferida contra a impressão digital publicada pelo projeto:

| Projeto | Chave | Impressão digital | Publicada em |
|---|---|---|---|
| Ubuntu | Ubuntu CD Image Automatic Signing Key (2012) | `8439 38DF 228D 22F7 B374 2BC0 D94A A3F0 EFE2 1092` | ubuntu.com/tutorials/how-to-verify-ubuntu |
| Ubuntu | Ubuntu CD Image Automatic Signing Key (2004) | `C598 6B4F 1257 FFA8 6632 CBA7 4618 1433 FBB7 5451` | idem |
| Debian | Debian CD signing key | `DF9B 9C49 EAA9 2984 3258 9D76 DA87 E80D 6294 BE9B` | debian.org/CD/verify |
| Debian | Debian CD signing key | `F41D 3034 2F35 4669 5F65 C669 4246 8F40 09EA 8AC3` | idem |
| Debian | Debian CD signing key | `1046 0DAD 7616 5AD8 1FBC 0CE9 9880 21A9 64E6 EA7D` | idem |
| Linux Mint | Linux Mint ISO Signing Key | `27DE B156 44C6 B3CF 3BD7 D291 300F 846B A25B AE09` | linuxmint-installation-guide.readthedocs.io/…/verify.html |

Fluxo: baixa o arquivo de checksums e a assinatura → verifica a assinatura com a chave oficial
(BouncyCastle) → só então usa o hash da ISO → baixa → recalcula o hash → compara. Se a assinatura
não confere, o download **não é oferecido**. Se o hash do arquivo baixado não confere, o arquivo é
**apagado**.

Os testes usam os arquivos de checksum e as assinaturas **reais** (salvos em
`feature/catalog/src/test/resources/real/`) e conferem o resultado também com o `gpgv`.

## Situação de verificação mostrada ao usuário

| Selo | Significado |
|---|---|
| **Assinatura oficial** | Checksum assinado pela chave oficial do projeto e conferido pelo app |
| **Checksum oficial** | Checksum publicado pela fonte oficial via HTTPS (sem assinatura disponível) |
| **Sem verificação automática** | A fonte não publica checksum consultável: a autenticidade não pode ser comprovada pelo app |

## Atualizar o catálogo sem reconstruir o app

1. Edite o `catalog.json` (mude, por exemplo, o padrão de link de um site que mudou) e aumente a data em `"updated"`.
2. Publique no repositório do projeto (ramo `main`). O app baixa de
   `https://raw.githubusercontent.com/jrtechofcdev/BootForge/main/feature/catalog/src/main/resources/dev/bootforge/catalog/catalog.json`.
   Para isso, o repositório precisa ser público.
3. No celular: Catálogo → *Atualizar catálogo*.

Limites de segurança: uma atualização só pode usar chaves que **já vêm no app**, e o arquivo é
validado (esquema, tipos de resolvedor e grupos de chaves) antes de ser aceito.

## Como detectar quando um site muda

`BOOTFORGE_LIVE=1 ./gradlew :feature:catalog:test` consulta todas as fontes reais e faz um
download real do Alpine com pausa e retomada. Pela linha de comando: `bootforge-cli catalog`.
