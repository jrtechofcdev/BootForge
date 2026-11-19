# Documento de acompanhamento — BootForge

Última atualização: 18/11/2026 · Versão do app: **0.6.1**

Legenda: ✅ implementado e testado · 🧪 implementado, falta teste em celular real · 🟡 pendente · ⛔ limitação técnica

## 0. Etapas do projeto (7 no total)

| # | Etapa | Situação |
|---|---|---|
| 1 | Pesquisa e viabilidade técnica | ✅ Concluída |
| 2 | Estrutura principal (app, USB, telas) | ✅ Concluída (🧪 falta teste no celular) |
| 3 | Gravação inteligente de ISOs | ✅ Concluída no núcleo e na interface (🧪 falta teste no celular) |
| 4 | Ventoy completo | ✅ Concluída no núcleo e na interface (🧪 falta teste no celular) |
| 5 | Editor de ISO | ✅ Concluída no núcleo e na interface (🧪 falta teste no celular) |
| 6 | Catálogo e downloads | ✅ Concluída no núcleo e na interface (🧪 falta teste no celular) |
| 7 | Testes e estabilidade | ✅ Parte automatizada concluída (injeção de falhas, captura de logs); 🧪 **testes em hardware real pendentes** |

## 1. Funcionalidades implementadas

### Núcleo (Kotlin puro, testado no PC)
| Funcionalidade | Status | Como foi testado |
|---|---|---|
| MBR/GPT (leitura/escrita, CRC32, backup), detecção de FS | ✅ | Testes; `sfdisk`, `sgdisk -v` |
| FAT12/16/32: leitura + escrita controlada (substituir/excluir) | ✅ | VTOYEFI real; `fsck.fat -n` |
| exFAT: formatar, ler e **gravar** (criar, pastas, renomear, mover, copiar, excluir, sobrescrever) | ✅ | 10 cenários + 400 operações aleatórias contra modelo; `fsck.exfat`; driver `mount.exfat-fuse` oficial do Ventoy (inclusive nomes Unicode) |
| exFAT: verificador de consistência e arquivo contíguo pré-alocado | ✅ | Teste com corrupção proposital |
| ext2/ext3 (persistência), journal jbd2 | ✅ | `e2fsck -fn`, `debugfs` (8 MiB a 1 GiB) |
| ISO9660/Joliet/Rock Ridge, El Torito BIOS/UEFI, UDF, híbrida, truncada, identificação do sistema | ✅ | ISO real gerada por `grub-mkrescue` |
| **Gravação de ISO — análise**: híbrida, Windows (UDF + boot.wim), carregadores UEFI, arquivos > 4 GB, nomes conflitantes, método recomendado + motivos de bloqueio | ✅ | ISOs geradas com `genisoimage`/`grub-mkrescue` |
| **Gravação DD** com apagamento do fim do disco e verificação por releitura (SHA-256) | ✅ | QEMU BIOS e UEFI (`docs/img/e3-dd-*.png`) |
| **Windows em FAT32 (UEFI)**: cópia dos arquivos + **divisão do install.wim em .swm** quando > 4 GB, MBR ou GPT | ✅ | `fsck.fat`, `sfdisk`, `sgdisk -v`, `wimlib verify/apply` nas partes extraídas do pendrive |
| **Extrair para FAT32 (UEFI)** — ISOs não híbridas com `efi/boot` | ✅ | QEMU UEFI com MBR e GPT: o GRUB da ISO encontrou a partição (`docs/img/e3-ext-*.png`) |
| Gerador FAT32 (nomes longos, arquivos contíguos, FSInfo, cópia do setor de boot) | ✅ | `fsck.fat`, `mtools` |
| Divisor de WIM (.swm, sem recompactar) e leitor UDF | ✅ | `wimlib-imagex verify/apply`; UDF do `genisoimage` |
| **Editor de ISO — abrir e explorar**: ISO9660, Joliet, Rock Ridge (com continuação CE), UDF, El Torito (imagens visíveis e ocultas), MBR/GPT híbridos | ✅ | ISOs do `grub-mkrescue`, `genisoimage -udf` e **ISO real do Alpine 3.20** |
| **Edição não destrutiva**: adicionar arquivos/pastas, substituir, editar texto, renomear, mover, copiar, excluir, criar pastas; desfazer/refazer ilimitados; histórico; alterações pendentes | ✅ | Testes da sessão |
| **Modo Preservar inicialização** (dados não modificados ficam no lugar; corrige descritores, catálogo, MBR com CHS, código isohybrid/GRUB2, GPT + cópia no fim, boot-info-table e grub2-boot-info) | ✅ | `isovfy` sem erros; `xorriso` reconhece El Torito/MBR/GPT; **QEMU: pendrive e CD × BIOS e UEFI**; Alpine editado iniciou até o **login** |
| **Reconstrução completa** (ISO9660 + Joliet + Rock Ridge + **UDF 1.02** + El Torito) | ✅ | `isovfy`, `udfinfo`; **driver UDF do kernel Linux** montou a ISO e o SHA-256 de todos os arquivos conferiu; QEMU (CD BIOS até o login, UEFI) |
| **Validação da ISO gerada** (árvores, SHA-256 de cada arquivo, tabelas de caminho, El Torito, imagens de boot, MBR/GPT, âncoras UDF) | ✅ | Pegou um erro real durante o desenvolvimento (ordem do grub2-boot-info) |
| Extração (tudo ou seleção) e cópia da ISO original | ✅ | Testes + CLI |
| **Catálogo de fontes oficiais** (15 sistemas): feed meta-release do Ubuntu, índices do Debian e do Mint, `releases.json` do Fedora, API do Pop!_OS, YAML do Alpine, páginas do Zorin, GitHub (Ventoy), Microsoft (navegador) | ✅ | Respostas reais salvas + **consulta ao vivo de todas as fontes** (`BOOTFORGE_LIVE=1`) |
| **Assinaturas OpenPGP** (Ubuntu, Debian, Mint) com chaves oficiais conferidas pelas impressões digitais publicadas | ✅ | Assinaturas reais; 1 caractere alterado é detectado; resultado igual ao `gpgv` |
| **Downloads**: retomada (Range/If-Range), retomada automática após queda, pausa (inclusive fechando o app), cancelamento, tamanho e checksum conferidos, arquivo adulterado apagado | ✅ | Servidor local simulando quedas, arquivo alterado, Range ignorado; **download real do Alpine com pausa/retomada e SHA-512 conferido** |
| Biblioteca de imagens (reutilizar sem baixar de novo) e verificação de ISO baixada pelo navegador | ✅ | Testes |
| Catálogo atualizável sem reconstruir o app (`catalog.json` validado) | ✅ | Testes de catálogo inválido |
| **Comandos USB adaptáveis** (64 KB, redução automática com reinício da porta USB, recuperação ao abrir, ajuste manual) — correção do 1º teste real | ✅ / 🧪 | Pendrive do log simulado (`AdaptiveChunkerTest`); **retestar no Galaxy A16** |
| **Teste de capacidade real** (pendrive falsificado), sem apagar dados | ✅ / 🧪 | Falsos simulados (capacidade exata) e verdadeiro (nenhum byte alterado) |
| **Estabilidade (injeção de falhas)**: pendrive arrancado no meio da instalação/atualização do Ventoy, da cópia para o exFAT, da gravação DD/FAT32; destino cheio no editor; ISO cortada; falta de espaço no download | ✅ | `FaultInjectingBlockDevice` + testes em 4 módulos ([05-testes-e-estabilidade.md](05-testes-e-estabilidade.md)) |
| Pendrive falsificado (devolve outro conteúdo) detectado pela verificação | ✅ | Teste "pendrive que mente" |
| Ventoy: instalar (MBR/GPT, 4K, reserva, **com ou sem Secure Boot**), detectar, atualizar | ✅ | Boot QEMU BIOS/UEFI; comparação com o instalador oficial |
| Catálogo de imagens com as regras oficiais do menu | ✅ | `.ventoyignore`, 32 KiB, lixeiras, `._`, pasta de busca, profundidade, FILE_FLT, wim/vhd, listas branca/negra |
| `ventoy.json`: esquema dos 15 plugins e 25 opções, validação, backups, restauração | ✅ | Exemplo oficial; menu do QEMU mostrou apelido e contagem configurados |
| Temas: original, importar .zip, aplicar (um ou sorteio), remover, imagens, leitor de `theme.txt` | ✅ | Tema aplicado apareceu no boot do QEMU |
| Secure Boot liga/desliga | ✅ | Resultado idêntico ao `vtoycli partresize -s` oficial (1046 entradas; EFI iguais); boot UEFI |
| Verificar instalação | ✅ | Detecta 1 byte alterado na VTOYEFI |
| Backup/restauração de `/ventoy` em .zip | ✅ | Ida e volta |
| Persistência (.dat ext2/ext3 + regra no ventoy.json) | ✅ | `e2fsck`, `debugfs` |
| Atualização online (GitHub oficial, retomada, sha256.txt) | ✅ / 🧪 | Servidor local imitando a API (retomada, checksum adulterado rejeitado). **API real do GitHub: só no celular** |

### Aplicativo Android
| Funcionalidade | Status |
|---|---|
| USB OTG sem root, permissão, sessão mantida durante a navegação | 🧪 |
| Serviço em primeiro plano com notificação de progresso e WakeLock | 🧪 |
| Aba Ventoy com 8 áreas: Visão geral, Arquivos, ISOs, Configurações, Temas, Ferramentas, Atualizações, Dispositivo | 🧪 (capturas automáticas ✅) |
| Arquivos: navegar, ordenar, pesquisar, seleção múltipla, copiar/recortar/colar, renomear, excluir, nova pasta, novo texto, importar arquivos/pasta do celular, exportar, compartilhar, propriedades, espaço por tipo, duplicados | 🧪 |
| ISOs: no menu/oculta + motivo, identificação, integridade (SHA-256 informado ou arquivo de checksum ao lado), adicionar com verificação, renomear, mover, copiar, excluir, organizar por categoria (com prévia) | 🧪 |
| Configurações: editor visual (control + plugins por esquema), avançado com validação ao vivo, backups | 🧪 |
| Temas com pré-visualização aproximada | 🧪 (captura ✅) |
| Ferramentas: verificar, Secure Boot, backup/restauração, persistência | 🧪 |
| Atualizações: embutida, consulta/baixa do GitHub, atualizar com pacote baixado | 🧪 |
| **Gravar ISO**: escolher a ISO (sem copiar), análise, método recomendado e alternativas com motivos, MBR/GPT, verificação opcional, pendrive de destino com confirmação APAGAR | 🧪 (captura ✅) |
| **Editor de ISO**: abrir do celular/pendrive (seletor do Android), navegar, pesquisar, seleção múltipla, ver/editar texto, visualizador hexadecimal, propriedades, adicionar/substituir/renomear/mover/copiar/excluir, desfazer/refazer, alterações pendentes, gerar nova ISO (2 modos) com validação, extrair, copiar original, excluir original só após confirmação | 🧪 (captura ✅) |
| **Catálogo**: consultar versões, selo de verificação, baixar, página oficial; Windows pelo navegador + verificação | 🧪 (captura ✅) |
| **Downloads**: fila, progresso/velocidade/tempo restante, pausar/continuar/cancelar, notificação em segundo plano; biblioteca com Gravar, Copiar para o Ventoy, Abrir no editor, Salvar no celular, Excluir | 🧪 (captura ✅) |
| **Logs e diagnóstico**: log contínuo em arquivo com rotação, travamentos gravados e oferecidos na próxima abertura, botão *Enviar log do erro* nas falhas, tela Logs (compartilhar, salvar .txt, copiar, limpar, log detalhado SCSI) | 🧪 (captura ✅) |
| Diagnóstico (Etapas 1–2): teste de leitura, gravação RAW + verificação, setor 0 | 🧪 |

## 2. Pendências

| Etapa | Itens |
|---|---|
| 3 (restos) | Windows em modo BIOS/Legacy direto (hoje: via Ventoy); NTFS + UEFI:NTFS para `install.esd` > 4 GB; ajuste automático de rótulos (CDLABEL) ao extrair Linux |
| 4 (restos) | ext4/xfs/criptografia na persistência; VentoyVlnk; plugins com sufixo de arquitetura no editor visual (hoje: modo Avançado); pré-visualização com 9-slice completo |
| 5 (restos) | Recriar a tabela híbrida também na reconstrução completa; editar ISOs com `rr_moved` (pastas muito profundas); salvar a sessão para continuar depois de fechar o app |
| 6 (restos) | Assinatura do Fedora (arquivo CHECKSUM assinado) e do Alpine (.asc); lista de hashes oficiais do Windows no catálogo (hoje o usuário cola); vários downloads ao mesmo tempo |
| 7 (restos) | **Matriz de celulares × pendrives em hardware real** (depende de você); driver SCSI próprio (READ(16), várias LUNs) |

## 3. Limitações técnicas conhecidas

| Limitação | Detalhe / alternativa |
|---|---|
| Pendrives > 1 TiB recusados | Campo `Int` na libaums → driver SCSI próprio (Etapa 7) |
| Setor nativo 4096 B | Não suportado pelo próprio Ventoy |
| Montar o pendrive no Android | Impossível sem root; substituído por drivers próprios (FAT, exFAT, ext) |
| Partição de dados NTFS/FAT32 | Estatísticas e gerenciador só para exFAT (padrão do Ventoy) |
| Persistência | ext2/ext3 apenas (o script oficial também aceita ext4/xfs) |
| Pré-visualização de tema | Aproximada (fontes e detalhes do GRUB variam) |
| Compartilhar arquivo | Até 2 GiB (cópia para o cache do celular) |
| Windows gravado em FAT32 | Inicia só em **UEFI** (todo PC com Windows 8+). Para BIOS/Legacy, usar o Ventoy |
| `install.esd` > 4 GB | Compressão "sólida": não dá para dividir sem recompactar → usar o Ventoy (a ISO fica inteira no exFAT) |
| ISO na nuvem | O arquivo precisa estar no armazenamento do celular (leitura direta, sem cópia) |
| Editor: reconstrução completa | Não recria MBR/GPT híbrido (a ISO funciona em DVD, Ventoy e gravação por extração). Para manter, usar o modo Preservar |
| Editor: modo Preservar | Arquivos removidos/substituídos continuam ocupando espaço dentro da ISO (é o que garante a inicialização) |
| Editor: mapa Apple (APM) | Não é atualizado (afeta só Macs muito antigos) |
| Windows no catálogo | A Microsoft só entrega o link pelo navegador; o app abre o site oficial e verifica a ISO com o SHA-256 que o usuário cola |
| Downloads | Um por vez (fila); ficam na área do app (`Android/data/…/files/isos`) — use "Salvar no celular" para copiar para outra pasta |
| Editor: ISOs do Windows | Só a reconstrução completa (UDF). Boot de um instalador real do Windows ainda não testado (não há ISO da Microsoft no ambiente de desenvolvimento) |

## 4. Dependências

Ver [`03-arquitetura.md`](03-arquitetura.md#dependências) e [`THIRD_PARTY_LICENSES.md`](../THIRD_PARTY_LICENSES.md).

## 5. Testes realizados (ambiente de desenvolvimento, Linux x86_64)

| Teste | Resultado |
|---|---|
| `./gradlew test` + capturas — **127 execuções** | ✅ 0 falhas |
| **Celular real — Samsung Galaxy A16 (Android 16), 19/11/2026** | ❌ na 0.6.0 (comandos de 1 MB recusados); corrigido na 0.6.1 — aguardando reteste |
| Etapa 6 ao vivo: 14 fontes consultadas; Ubuntu, Debian e Mint com assinatura válida; download real do Alpine pausado, retomado e verificado | ✅ (`BOOTFORGE_LIVE=1`) |
| Etapa 5 no QEMU: Alpine editado (Preservar) como pendrive e CD em BIOS e UEFI; boot completo até o login; reconstrução completa como CD até o login; imagem de boot realocada iniciando pelo MBR GRUB2 e pelo El Torito | ✅ (`docs/img/e5-*.png`) |
| Etapa 5: ISO UDF reconstruída montada pelo driver UDF do kernel Linux (SHA-256 de 246 arquivos; diferenças só nas edições) | ✅ (`docs/img/e5-udf-linux-kernel.log`) |
| Etapa 3 no QEMU: DD em BIOS e UEFI; extração FAT32 em UEFI com MBR e com GPT; mídia FAT32 em BIOS mostra aviso | ✅ (`docs/img/e3-*.png`) |
| Etapa 3: Windows FAT32 com install.wim dividido → `wimlib verify` e `apply` idênticos ao original | ✅ |
| Instalador × `Ventoy2Disk.sh` oficial (layout, core.img) | ✅ idênticos |
| Secure Boot desligado × `vtoycli partresize -s` oficial | ✅ mesmos arquivos; BOOTX64/BOOTIA32 idênticos |
| exFAT gravado pelo BootForge × `mount.exfat-fuse` oficial (ida e volta, Unicode) | ✅ |
| QEMU: MBR/GPT × BIOS/UEFI; UEFI sem Secure Boot | ✅ |
| QEMU: ISO real copiada pelo BootForge **deu boot** (GRUB da ISO) | ✅ (`docs/img/qemu-e4-iso-booted.png`) |
| QEMU: apelido (`menu_alias`) e contagem (`VTOY_MENU_TIMEOUT`) gravados pelo BootForge | ✅ (`docs/img/qemu-e4-alias-menu.png`) |
| QEMU: tema aplicado pelo BootForge | ✅ (`docs/img/qemu-e4-theme.png`) |
| `assembleRelease` (R8) | ✅ APK 19,6 MB (0.6.0) |
| **Celular real** | ⏳ aguardando ([roteiro](ROTEIRO-TESTES-REAIS.md)) |

## 6. Problemas encontrados e resolvidos

| Problema | Solução |
|---|---|
| **1º teste real (Galaxy A16, Android 16):** pendrive 3207:0300 recusava comandos de 1 MB e travava | 64 KB por comando + redução automática + recuperação sem desconectar (0.6.1). Diagnóstico feito pelo relatório de log |
| No exFAT, erro ao finalizar escondia o erro original (pendrive arrancado) | Erro original mantido; o da finalização vai como "suprimido" |
| Download começava mesmo sem espaço e falhava no meio | Verificação de espaço livre antes de começar, com mensagem "faltam X MB" |
| Destaques com asteriscos apareciam literalmente nas telas | Negrito de verdade (`BoldLead`) |
| `mirrors.kernel.org` alterna 503/conexão derrubada | Usar o destino oficial do redirecionamento (`mirrors.edge.kernel.org`), novas tentativas automáticas e cache por consulta (não pede o mesmo arquivo duas vezes) |
| GitHub bloqueado no ambiente de desenvolvimento (403) | Resolvedor testado com respostas simuladas; no celular usa a API pública normalmente |
| Conflito de empacotamento do Bouncy Castle no APK | Exclusão do `MANIFEST.MF` OSGi repetido; R8 mantém o pacote inteiro (+1,2 MB) para a verificação não quebrar no celular |
| boot-info-table inválida após mover a imagem de boot do GRUB | O grub2-boot-info fica dentro da área somada: aplicar primeiro o grub2-boot-info, depois a soma. O validador passou a conferir a soma |
| Partição e tamanho perdiam o alinhamento de 1 MiB do isohybrid | Tamanho final arredondado e CHS recalculado com a geometria original (64/32) |
| Alpine não monta ISO com UDF no boot UEFI | Limitação do initramfs do Alpine (a ISO original do genisoimage falha igual) — não é da reconstrução |
| ISO do Windows: arquivos reais só existem no UDF (ISO9660 tem só um README) | Leitor UDF próprio (ECMA-167/OSTA) |
| `install.wim` > 4 GB não cabe em FAT32 | Divisor .swm por cópia direta dos recursos (formato do DISM/wimlib), validado com o wimlib |
| PC em modo BIOS ligado com mídia só-UEFI travava sem explicação | Código de boot próprio no MBR e no setor de boot mostra o aviso |
| Pendrive usado com GPT antigo no fim do disco | Apaga o primeiro e o último MiB antes de gravar |
| Arquivo vazio com flag NoFatChain (reprovado pelo `fsck.exfat`) | Flag só para arquivos com dados |
| `menu_class` tratava `key`/`dir` como caminhos | São trechos do nome (`strstr` no código oficial); esquema corrigido |
| Curinga das listas branca/negra | No Ventoy `*` vale UM caractere (`ventoy_strncmp`); implementado igual |
| ISOs só com nomes Rock Ridge (ex.: `.disk/info`) | Leitura das entradas `NM` |
| `.wim`/`.vhd` não aparecem no menu | Exigem `ventoy_wimboot.img`/`ventoy_vhdboot.img`; o app explica o motivo |

## 7. Próximas etapas

1. **Você:** testar o APK 0.6.1 (seção "Reteste da 0.6.1" do [roteiro](ROTEIRO-TESTES-REAIS.md)) e enviar o **relatório de log**.
2. Corrigir o que aparecer no hardware real e preencher a matriz de compatibilidade.
3. Com os testes reais aprovados: versão **1.0.0**.
