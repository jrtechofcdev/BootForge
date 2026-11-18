# Documento de acompanhamento — BootForge

Última atualização: 18/11/2026 · Versão do app: **0.1.0-poc**

Legenda: ✅ implementado e testado · 🧪 implementado, falta teste em celular real · 🟡 pendente · ⛔ limitação técnica

## 1. Funcionalidades implementadas

| Funcionalidade | Status | Como foi testado |
|---|---|---|
| Leitura/escrita MBR e GPT (CRC32, backup) | ✅ | Testes unitários; `sfdisk`, `sgdisk -v` |
| Detecção de sistemas de arquivos (FAT, exFAT, NTFS, ISO9660, UDF, ext) | ✅ | Testes + discos instalados |
| Leitor FAT12/16/32 com nomes longos | ✅ | VTOYEFI real do pacote 1.1.18 (1024 entradas) |
| Formatador exFAT | ✅ | `fsck.exfat -n` (exfatprogs 1.2.2) em 256 MiB, 512 MiB, 8 GiB e 64 GiB; montado pelo `mount.exfat-fuse` oficial |
| Leitor exFAT (listagem, espaço livre, leitura de arquivo) | ✅ | Volumes do BootForge e do `mkexfatfs` oficial |
| Pacote Ventoy embutido + verificação SHA-256 | ✅ | Teste unitário + tela Configurações |
| Instalação do Ventoy (MBR/GPT, alinhamento 4K, espaço reservado, Secure Boot) | ✅ / 🧪 | Imagens de disco; boot em QEMU (BIOS/UEFI); comparação com o instalador oficial. Falta pendrive real |
| Detecção automática do Ventoy (regras oficiais + versão + Secure Boot + estatísticas) | ✅ / 🧪 | Discos do BootForge e do instalador oficial |
| Atualização/reinstalação não destrutiva do Ventoy | ✅ / 🧪 | Hash da partição de dados antes/depois; sobre disco do instalador oficial |
| Listagem (somente leitura) das ISOs no Ventoy | ✅ / 🧪 | ISOs em subpastas detectadas (também aparecem no menu do Ventoy no QEMU) |
| USB OTG: detecção, permissão, acesso por setores (libaums SCSI) | 🧪 | Compila; **não testado em celular** |
| PoC: teste de leitura com velocidade real | 🧪 | — |
| PoC: gravação RAW de imagem + verificação por releitura | 🧪 | Lógica testada em arquivos (`BlockTransfer`) |
| Interface: Home, Pendrives, Detalhes, Ventoy, Instalar, Progresso, Histórico, Configurações | ✅ | Capturas Paparazzi (`app/src/test/snapshots`) |
| Confirmação de operações destrutivas (digitar APAGAR, proteção para vários pendrives, tamanho conferido) | ✅ | Revisão + teste da trava de tamanho |
| Progresso real (%, bytes, velocidade, tempo restante, etapa) e cancelamento | ✅ | — |
| Histórico local de operações | 🧪 | — |
| Ferramenta de linha de comando `bootforge-cli` | ✅ | Usada em todos os testes de imagem |

## 2. Funcionalidades pendentes (por etapa)

| Etapa | Itens |
|---|---|
| 3 — Gravação de ISOs | Análise da ISO (híbrida? Windows? UEFI/BIOS?), escolha automática do método, Windows (FAT32 + `install.wim` dividido ou NTFS + UEFI:NTFS), GPT/MBR na gravação, serviço em primeiro plano |
| 4 — Ventoy completo | Escrita exFAT (gerenciador de arquivos: copiar/mover/renomear/excluir/pastas/compartilhar/pesquisar/duplicados), gerenciador de ISOs com edição, escrita FAT (Secure Boot desligado), editor `ventoy.json` (visual + avançado, backup/restauração), temas com pré-visualização, VentoyVlnk, persistência |
| 5 — Editor de ISO | Leitor ISO9660/Joliet/Rock Ridge/UDF, extração, edição não destrutiva com desfazer/refazer, reconstrução preservando El Torito e tabela híbrida, validação |
| 6 — Catálogo e downloads | Metadados oficiais (arquivo JSON central atualizável), downloads com pausa/retomada, SHA-256/assinaturas, consulta de novas versões do Ventoy |
| 7 — Testes e estabilidade | Matriz de celulares × pendrives, driver SCSI próprio (READ(16), várias LUNs, SYNCHRONIZE CACHE) |

## 3. Limitações técnicas conhecidas

| Limitação | Tipo | Detalhe / alternativa |
|---|---|---|
| Pendrives > 1 TiB recusados | ⛔ atual | Campo `Int` no `ScsiBlockDevice` da libaums → driver SCSI próprio |
| Máx. 2 TiB mesmo com driver próprio usando READ(10) | ⛔ atual | READ(16)/WRITE(16) |
| Dispositivos com setor nativo 4096 B | ⛔ | O próprio Ventoy não suporta |
| Instalação sem Secure Boot | 🟡 | Requer escrita FAT na VTOYEFI |
| Instalação não destrutiva (sem apagar dados existentes) | 🟡 | Requer redimensionar NTFS/ext (oficial usa `ntfsresize`/`resize2fs`) |
| Montar o pendrive no Android | ⛔ sem root | Substituído por drivers próprios |
| Operação longa com app em segundo plano | 🟡 | Tela fica ligada; serviço em primeiro plano pendente |
| NTFS (escrita) | 🟡/⛔ | Porte do ntfs-3g via NDK a avaliar |

## 4. Dependências utilizadas

Ver [`03-arquitetura.md`](03-arquitetura.md#dependências) e [`THIRD_PARTY_LICENSES.md`](../THIRD_PARTY_LICENSES.md).

## 5. Testes realizados (ambiente de desenvolvimento, Linux x86_64)

| Teste | Resultado |
|---|---|
| `./gradlew test` (integrity, blockdev, partition, fs-fat, fs-exfat, ventoy-core: 23 testes) + 5 capturas Paparazzi | ✅ todos passando |
| Layout do Ventoy × fórmula do `ventoy_lib.sh` oficial (6 tamanhos de disco, MBR e GPT) | ✅ idênticos |
| Instalar 8 GiB MBR / 8 GiB GPT / 64 GiB GPT via CLI | ✅ (~1 s em arquivo) |
| `fsck.fat`, `fsck.exfat`, `sfdisk`, `sgdisk -v` nos discos instalados | ✅ sem erros |
| Boot QEMU: MBR+BIOS, MBR+UEFI, GPT+BIOS, GPT+UEFI | ✅ menu Ventoy 1.1.18 |
| ISOs em `/Linux` e `/Windows` aparecem no menu e são contadas pelo detector | ✅ |
| Instalação oficial (`Ventoy2Disk.sh`) × BootForge, byte a byte | ✅ layout e core.img idênticos (ver doc 02) |
| Atualização sobre disco do instalador oficial preserva dados | ✅ |
| Capturas de tela das telas principais (Paparazzi) | ✅ |
| `assembleDebug` / `assembleRelease` (R8) | ✅ APK release 15 MB |
| **Celular real** | ⏳ aguardando ([roteiro](ROTEIRO-TESTES-REAIS.md)) |

## 6. Problemas encontrados

| Problema | Solução |
|---|---|
| Textos sem cor explícita saíam pretos no tema escuro | `LocalContentColor` definido no tema (detectado pelas capturas) |
| Paparazzi 1.3.5 incompatível com Compose + API 35 | Atualizado para 2.0.0-alpha02 |
| Script oficial Linux gravou VTOYEFI truncada (leituras curtas do `dd` em pipe) | Não afeta o BootForge (stream + verificação por hash); documentado |
| Mensagem de erro técnica quando faltava o pacote do Ventoy na CLI | Mensagem clara; nenhuma gravação ocorre antes da verificação do pacote |

## 7. Próximas etapas (ordem sugerida)

1. **Você:** executar o [roteiro de testes reais](ROTEIRO-TESTES-REAIS.md) e me enviar a tabela.
2. Corrigir o que aparecer no hardware real.
3. Serviço em primeiro plano para operações longas.
4. Escrita exFAT → gerenciador de arquivos e de ISOs do Ventoy (Etapa 4).
5. Escrita FAT → `ventoy.json`, temas, modo sem Secure Boot.
6. Etapa 3 (gravação inteligente de ISOs).
