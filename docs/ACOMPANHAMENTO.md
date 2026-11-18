# Documento de acompanhamento — BootForge

Última atualização: 18/11/2026 · Versão do app: **0.2.0**

Legenda: ✅ implementado e testado · 🧪 implementado, falta teste em celular real · 🟡 pendente · ⛔ limitação técnica

## 0. Etapas do projeto (7 no total)

| # | Etapa | Situação |
|---|---|---|
| 1 | Pesquisa e viabilidade técnica | ✅ Concluída |
| 2 | Estrutura principal (app, USB, telas) | ✅ Concluída (🧪 falta teste no celular) |
| 3 | Gravação inteligente de ISOs | 🟡 Pendente (PoC de gravação RAW já existe) |
| 4 | Ventoy completo | ✅ Concluída no núcleo e na interface (🧪 falta teste no celular) |
| 5 | Editor de ISO | 🟡 Pendente (leitor ISO9660/Joliet/Rock Ridge já pronto em `core/iso`) |
| 6 | Catálogo e downloads | 🟡 Pendente (download verificado já usado para o Ventoy) |
| 7 | Testes e estabilidade | 🟡 Pendente |

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
| Diagnóstico (Etapas 1–2): teste de leitura, gravação RAW + verificação, setor 0 | 🧪 |

## 2. Pendências

| Etapa | Itens |
|---|---|
| 3 | Análise de ISO para escolha do método, Windows (FAT32 + WIM dividido ou NTFS + UEFI:NTFS), GPT/MBR na gravação |
| 4 (restos) | ext4/xfs/criptografia na persistência; VentoyVlnk; plugins com sufixo de arquitetura no editor visual (hoje: modo Avançado); pré-visualização com 9-slice completo |
| 5 | Editor de ISO: extração, edição não destrutiva, reconstrução preservando El Torito e híbrido |
| 6 | Catálogo de sistemas, downloads com retomada e checksum |
| 7 | Matriz de celulares × pendrives; driver SCSI próprio (READ(16), várias LUNs) |

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

## 4. Dependências

Ver [`03-arquitetura.md`](03-arquitetura.md#dependências) e [`THIRD_PARTY_LICENSES.md`](../THIRD_PARTY_LICENSES.md).

## 5. Testes realizados (ambiente de desenvolvimento, Linux x86_64)

| Teste | Resultado |
|---|---|
| `./gradlew test` — 55 testes do núcleo + 7 capturas (×2 variantes) = 69 execuções | ✅ 0 falhas |
| Instalador × `Ventoy2Disk.sh` oficial (layout, core.img) | ✅ idênticos |
| Secure Boot desligado × `vtoycli partresize -s` oficial | ✅ mesmos arquivos; BOOTX64/BOOTIA32 idênticos |
| exFAT gravado pelo BootForge × `mount.exfat-fuse` oficial (ida e volta, Unicode) | ✅ |
| QEMU: MBR/GPT × BIOS/UEFI; UEFI sem Secure Boot | ✅ |
| QEMU: ISO real copiada pelo BootForge **deu boot** (GRUB da ISO) | ✅ (`docs/img/qemu-e4-iso-booted.png`) |
| QEMU: apelido (`menu_alias`) e contagem (`VTOY_MENU_TIMEOUT`) gravados pelo BootForge | ✅ (`docs/img/qemu-e4-alias-menu.png`) |
| QEMU: tema aplicado pelo BootForge | ✅ (`docs/img/qemu-e4-theme.png`) |
| `assembleRelease` (R8) | ✅ APK 15,9 MB |
| **Celular real** | ⏳ aguardando ([roteiro](ROTEIRO-TESTES-REAIS.md)) |

## 6. Problemas encontrados e resolvidos nesta etapa

| Problema | Solução |
|---|---|
| Arquivo vazio com flag NoFatChain (reprovado pelo `fsck.exfat`) | Flag só para arquivos com dados |
| `menu_class` tratava `key`/`dir` como caminhos | São trechos do nome (`strstr` no código oficial); esquema corrigido |
| Curinga das listas branca/negra | No Ventoy `*` vale UM caractere (`ventoy_strncmp`); implementado igual |
| ISOs só com nomes Rock Ridge (ex.: `.disk/info`) | Leitura das entradas `NM` |
| `.wim`/`.vhd` não aparecem no menu | Exigem `ventoy_wimboot.img`/`ventoy_vhdboot.img`; o app explica o motivo |

## 7. Próximas etapas

1. **Você:** testar o APK 0.2.0 seguindo o [roteiro](ROTEIRO-TESTES-REAIS.md) (Etapas 2 e 4).
2. Corrigir o que aparecer no hardware real.
3. Etapa 3 — gravação inteligente de ISOs (inclusive Windows).
