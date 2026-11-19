# Testes e estabilidade (Etapa 7)

Situação em **18/11/2026**, versão **0.6.0**. Este documento reúne o que foi testado, como, e o
que ainda depende do teste em **celular e pendrives reais**.

## 1. Testes automáticos (`./gradlew test`)

| Módulo | Testes | O que cobre |
|---|---:|---|
| core/blockdev | 4 | dispositivo de setores, cópia com progresso e hash |
| core/integrity | 2 | SHA-256/512, arquivos de checksum |
| core/partition | 4 | MBR, GPT (CRC, cópia de segurança), detecção de sistemas de arquivos |
| core/fs-fat | 4 | leitura FAT12/16/32, gravação na VTOYEFI, gerador FAT32 (`fsck.fat`, `mtools`) |
| core/fs-exfat | 13 | formatar, ler e gravar exFAT (`fsck.exfat`, driver oficial do Ventoy), 400 operações aleatórias |
| core/fs-ext | 5 | ext2/ext3 para persistência (`e2fsck`, `debugfs`) |
| core/iso | 4 | ISO9660/Joliet/Rock Ridge/UDF, identificação de sistemas |
| core/wim | 2 | divisão do install.wim (`wimlib verify/apply`) |
| feature/ventoy-config | 6 | ventoy.json: esquema, validação, edição |
| feature/ventoy-core | 19 | instalar/atualizar/detectar Ventoy, catálogo, temas, ferramentas, **estabilidade** |
| feature/iso-writer | 6 | análise e gravação (DD, Windows FAT32, extração), **estabilidade** |
| feature/iso-editor | 9 | edição, reconstrução, validação, ISO real do Alpine, **estabilidade** |
| feature/catalog | 21 | fontes oficiais, assinaturas OpenPGP, downloads, biblioteca, falta de espaço |
| app (capturas Paparazzi) | 11 × 2 | telas renderizadas na JVM (debug e release) |
| **Total** | **121** | **0 falhas** |

Testes opcionais com internet: `BOOTFORGE_LIVE=1` (todas as fontes oficiais + download real do
Alpine com pausa/retomada) e `BOOTFORGE_ALPINE_ISO=/caminho/alpine.iso` (editor com ISO real).

## 2. Testes de estabilidade (injeção de falhas)

Um dispositivo de teste (`FaultInjectingBlockDevice`) simula o pendrive **arrancado no meio** de
cada gravação. Regra conferida em todos: **a operação falha com erro — nunca diz "concluída"** —
e o que já existia no pendrive não é corrompido.

| Situação simulada | Resultado verificado |
|---|---|
| Instalação do Ventoy interrompida em 512 bytes, 1%, 10%, 50%, 90% e 99% | Erro em todos; ao reconectar, o pendrive **não** aparece como Ventoy válido; reinstalar funciona |
| Atualização do Ventoy interrompida (3 pontos) | Erro; **a ISO já copiada continua intacta** (SHA-256) e o exFAT sem erros; atualizar de novo funciona |
| Cópia de ISO para o Ventoy interrompida | Erro; arquivos existentes intactos; o arquivo incompleto **não** aparece como pronto; só o aviso "volume sujo" (normal) |
| Gravação DD interrompida (3 pontos) | Erro em todos |
| **Pendrive que "mente"** (aceita gravar, mas devolve outro conteúdo — típico de pendrive falsificado) | A verificação por releitura **detecta** e a gravação é dada como falha |
| Gravação FAT32 interrompida (3 pontos) | Erro; a tabela de partições (gravada por último) **não** existe, então o pendrive não parece pronto |
| Gerar ISO com o destino cheio | Erro; a **ISO original continua idêntica** byte a byte |
| ISO cortada pela metade (download incompleto) / arquivo que não é ISO | Erro claro, sem travar |
| Download sem espaço livre | Avisa **antes** de começar ("faltam X MB"), sem tentar de novo sozinho; depois de liberar espaço, funciona |
| Download com queda de conexão, arquivo alterado no servidor, servidor que ignora "Range", checksum adulterado | Retoma sozinho / recomeça / **apaga** o arquivo adulterado (testes do catálogo) |

Correção feita durante esta etapa: no exFAT, quando a gravação falhava **e** a finalização
também falhava (pendrive arrancado), o segundo erro escondia o primeiro. Agora o erro original
é mantido (o outro vai junto como "suprimido").

## 3. Validações com boot real (QEMU, BIOS e UEFI)

| Etapa | O que iniciou | Capturas |
|---|---|---|
| 2/4 | Ventoy instalado pelo app (MBR/GPT × BIOS/UEFI; Secure Boot desligado); ISO copiada pelo app; tema e `ventoy.json` aplicados | `docs/img/qemu-*.png` |
| 3 | ISO híbrida em DD (BIOS e UEFI); ISO extraída para FAT32 (UEFI, MBR e GPT); aviso em BIOS | `docs/img/e3-*.png` |
| 5 | ISO real do Alpine editada (pendrive/CD × BIOS/UEFI, até o login); reconstrução completa; imagem de boot realocada; UDF montado pelo kernel Linux | `docs/img/e5-*.png` |

## 4. Captura de logs (para o teste no celular)

- O app grava um **log contínuo** em arquivo (até 5 × 1 MB, com rotação): conexão e remoção de
  pendrives (VID:PID, interfaces USB), abertura (capacidade, setor), cada operação (início,
  etapas, resultado, **erro com detalhes técnicos**), falhas de leitura/gravação com o setor (LBA),
  downloads e consultas ao catálogo.
- **Travamentos** são gravados na hora, com o rastreamento completo; ao abrir o app de novo,
  ele oferece **Enviar relatório**.
- Quando uma operação falha, o painel mostra **Enviar log do erro**.
- Tela **Logs** (tela inicial ou Configurações): ver, filtrar, **Compartilhar** (WhatsApp, e-mail),
  **Salvar .txt**, **Copiar**, **Limpar** e ligar o **Log detalhado** (cada comando enviado ao
  pendrive, para reproduzir um erro).
- O relatório inclui: aparelho e Android, versão do app, memória e espaço livre, suporte a OTG,
  pendrives conectados, travamentos, operação atual, histórico, downloads, log do app e o
  **logcat** do próprio app. Não inclui conteúdo de arquivos nem contas; números de série
  aparecem mascarados.

## 5. O que depende do hardware real (pendente)

O prompt pede testes com fabricantes e capacidades diferentes, imagens Windows e Linux,
MBR/GPT, Ventoy, arquivos, configurações, temas e editor. Isso só pode ser feito no seu celular.
Use o [roteiro](ROTEIRO-TESTES-REAIS.md) e preencha a matriz abaixo; para cada erro, envie o
relatório gerado em **Logs**.

| Celular / Android | Pendrive (marca, GB, USB 2/3) | Ventoy (instalar/atualizar) | Gravar Linux (DD) | Gravar Windows (FAT32) | Editor | Download | Observações |
|---|---|---|---|---|---|---|---|
| Samsung Galaxy A16 (SM-A165M) · Android 16 — app 0.6.0 (19/11/2026) | Genérico VID:PID 3207:0300, "816820090226", anuncia 64 GB | ❌ falhou na 1ª gravação | ❌ falhou na 1ª gravação | — | — | — | Detecção, permissão e leitura de 1 setor OK. **Todo comando de 1 MB recusado** (bulkTransfer −1) e o pendrive travava até ser reconectado. Corrigido na 0.6.1 (ver seção 7). |
| Samsung Galaxy A16 · Android 16 — app **0.6.1** (19/11/2026) | mesmo pendrive 3207:0300 | ❌ "Recurso upcase.bin ausente" (só no APK; corrigido na 0.6.2) | ✅ **Alpine 3.24.4 (353 MB) gravado e verificado** — gravação ~25 s (≈14 MB/s), verificação ~51 s | — | — | — | **A correção USB funcionou**: comandos de 64 KB, sem nenhuma falha. Teste de capacidade ainda não executado |
| | | | | | | | |

## 7. Primeiro teste real (19/11/2026) — diagnóstico pelo log

O relatório de log enviado mostrou:

- Leituras de **1 setor** (512 bytes) funcionavam: o app leu as tabelas de partição normalmente.
- O **primeiro comando de 1 MB** (gravação da instalação do Ventoy, da gravação DD e, no dia 18, da
  leitura do teste de velocidade) falhava em menos de 1 segundo com `bulkTransfer = -1`; a
  biblioteca USB tentava se recuperar, desistia ("MAX_RECOVERY_ATTEMPTS") e o pendrive só voltava a
  responder depois de desconectado.
- O pendrive se identifica como "SPC versão 0" (controlador muito simples) e tem nome genérico.

**Causa:** o BootForge 0.6.0 enviava 1 MB por comando USB; Windows e Linux usam 64–120 KB, e
controladores simples não aceitam comandos maiores.

**Correção (0.6.1):**

| Mudança | Detalhe |
|---|---|
| 64 KB por comando (padrão) | Igual ao Windows; `AdaptiveChunker` |
| Redução automática | Se um comando falhar: reinicia a porta USB e o protocolo do pendrive, reduz pela metade (até 4 KB) e repete o mesmo trecho — sem precisar desconectar |
| Recuperação ao abrir | Pendrive "travado" por uma falha anterior: reinício da porta USB e nova tentativa |
| Ajuste manual | Logs → *Compatibilidade USB* (64/32/16/4/128 KB) |
| Mensagem clara | "O pendrive parou de responder…" no lugar do erro técnico em inglês |
| **Teste de capacidade real** | Pendrives → detalhes → *Testar capacidade real*: grava marcas em ~128 pontos (espaçados em potências de 2), confere e devolve o conteúdo original. Detecta pendrive falsificado e calcula a capacidade real |

Testes automáticos novos: o pendrive do log foi simulado (recusa comandos grandes e trava até
reiniciar) — com 64 KB funciona sem recuperação; um pendrive ainda mais limitado (16 KB) é
recuperado sozinho e os dados ficam corretos. O teste de capacidade foi validado com pendrives
falsos simulados ("dá a volta" → capacidade exata; "joga fora" → detectado) e com um verdadeiro
(nenhum byte alterado).

## 8. Segundo teste real (19/11/2026, app 0.6.1)

- **Gravação DD funcionou de ponta a ponta**: 353 MB gravados e relidos com SHA-256 conferido,
  todos os comandos com 64 KB, nenhuma recuperação necessária.
- **Instalação do Ventoy falhou** ao formatar o exFAT: "Recurso upcase.bin ausente". Causa: o
  otimizador R8 do APK de release renomeia os pacotes, e a tabela up-case do exFAT era carregada por
  caminho *relativo* à classe. Nos testes no PC (sem R8) o erro não aparecia.
- **Correção (0.6.2):** caminho absoluto; `-dontobfuscate` no R8 (classes mantêm os nomes reais —
  isso também deixa os relatórios de log legíveis); teste novo que varre o código e falha se
  algum recurso for carregado por caminho relativo; conferência do APK final (recurso e classes
  presentes).

## 6. Limitações conhecidas

Ver [ACOMPANHAMENTO.md](ACOMPANHAMENTO.md#3-limitações-técnicas-conhecidas). Destaques: pendrives
até 1 TiB; setor de 512 bytes; Windows gravado em FAT32 inicia só em UEFI (BIOS via Ventoy);
Windows no catálogo só pelo navegador; GitHub (Ventoy) não testado ao vivo no ambiente de
desenvolvimento.
