# 02 — Análise completa do pacote Ventoy 1.1.18

> Fonte analisada: `ventoy-1.1.18-windows.zip` fornecido pelo usuário
> (SHA-256 `082c478e8c432e1ac653899fcf50e3a4077a2851bc6112b5ddd30545962164a9`), preservado intacto em
> [`third_party/ventoy-1.1.18/`](../third_party/ventoy-1.1.18).
> Referência cruzada: código-fonte oficial `github.com/ventoy/Ventoy`, tag **v1.1.18**
> (commit `6116894ab8abdf3ef8d43411a6732d34ee19214e`), em especial `Ventoy2Disk/Ventoy2Disk/*.c`,
> `INSTALL/tool/ventoy_lib.sh`, `INSTALL/tool/VentoyWorker.sh` e `vtoycli/*.c`.

## 1. Inventário recursivo do pacote (53 entradas)

| Caminho | Tamanho | Finalidade | Destino no BootForge |
|---|---:|---|---|
| `boot/boot.img` | 512 B | Código de boot do MBR (GRUB stage 1 para BIOS). Contém o "carimbo" de 16 bytes em 0x180 (UUID do disco) | ✅ **Usado como está** (gravado no setor 0) |
| `boot/core.img.xz` | 455 KB (1.048.064 B descompactado) | GRUB `core.img` i386-pc (estágio que carrega o GRUB da VTOYEFI em BIOS) | ✅ **Usado como está** (setor 1 em MBR; setor 34 em GPT com byte 500 = 35, igual ao oficial) |
| `ventoy/ventoy.disk.img.xz` | 13,4 MB (32 MiB descompactado) | Imagem completa da partição **VTOYEFI** (FAT16): GRUB, EFI, temas, drivers, utilitários | ✅ **Usado como está** (gravado na partição 2) |
| `ventoy/ventoy_4k.disk.img.xz` | 5,7 KB | Variante FAT16 com setor de 4096 B (estrutura base) | 📦 Preservado; o Ventoy oficial **recusa** discos 4Kn e nós também |
| `ventoy/version` | 7 B | `1.1.18` | ✅ Usado (identifica o pacote embutido) |
| `ventoy/plugson.tar.xz` | 1 MB (182 arquivos) | Interface web do **VentoyPlugson** (editor de `ventoy.json`): 16 páginas, uma por plugin | 📚 **Referência** para o editor visual de configurações (Etapa 4): define nomes e formatos válidos das opções |
| `ventoy/languages.json` | 293 KB | Traduções da interface do Ventoy2Disk (53 idiomas) | 📚 Referência de textos/termos |
| `plugin/ventoy/ventoy.json` | 1,5 KB | **Exemplo** oficial de configuração (`control`, `theme`, `menu_class`, `menu_alias`, `auto_install`) | 📚 Exemplo para o editor; não é gravado automaticamente |
| `plugin/ventoy/ventoy_grub.cfg` | 509 B | Exemplo de menu GRUB personalizado (F6) | 📚 Exemplo (Ferramentas, Etapa 4) |
| `plugin/ventoy/theme/` | 24 arquivos | Tema padrão: `theme.txt`, `background.png`, molduras `menu_*`, `select_c`, `slider_*`, `terminal_box_*`, ícones `vtoyiso/ubuntu/deepin/red-hat` | 🎨 Base do gerenciador de temas (Etapa 4) |
| `Ventoy2Disk.exe` (+ `altexe/*_X64/ARM/ARM64.exe`) | 0,6 MB cada | Instalador gráfico do Windows | 🔁 **Reimplementado** em Kotlin (Win32 não roda no Android) |
| `VentoyPlugson.exe` (+ `_X64`) | 0,4 MB | Servidor web local que serve o `plugson.tar.xz` | 🔁 Será substituído por editor nativo |
| `VentoyVlnk.exe` | 150 KB | Cria arquivos `.vlnk` (atalhos para ISOs em outros discos) | 🔁 Formato a reimplementar (Etapa 4) |
| `FOR_X64_ARM.txt` | 967 B | Explica as versões ARM/X64 do `.exe` | 📚 Documentação |

## 2. Conteúdo da partição VTOYEFI (dentro de `ventoy.disk.img`)

FAT16, rótulo `VTOYEFI`, 65536 setores, **1024 entradas** (lidas pelo nosso leitor FAT):

| Pasta | Conteúdo | Função |
|---|---|---|
| `/EFI/BOOT/` | `BOOTX64.EFI` (shim), `grubx64_real.efi`, `mmx64.efi` (MokManager), `BOOTIA32.EFI`, `grubia32*.efi`, `mmia32.efi`, `BOOTAA64.EFI`, `BOOTMIPS.EFI`, `fbx64.efi` | Boot UEFI x86_64, IA32, ARM64, MIPS64 — com **Secure Boot** via shim |
| `/ENROLL_THIS_KEY_IN_MOKMANAGER.cer` | certificado | Chave a cadastrar no MokManager no primeiro boot com Secure Boot |
| `/grub/grub.cfg` | 91 KB | Lógica principal do menu; contém `set VENTOY_VERSION="1.1.18"` (**fonte da versão instalada**) |
| `/grub/{i386-pc,x86_64-efi,i386-efi,arm64-efi,mips64el-efi}/` | 213/195/194/154/153 módulos | Módulos GRUB por arquitetura |
| `/grub/themes/ventoy/` | tema padrão | O mesmo de `plugin/ventoy/theme` (com fundo diferente) |
| `/grub/{checksum,debug,hwinfo,keyboard,localboot,menulang,power}.cfg`, `menu.tar.gz`, `fonts/`, `distro/` | | Menus auxiliares (F-teclas, idiomas, checksum, etc.) |
| `/ventoy/` | `ventoy_*.cpio`, `vtloopex.cpio`, `ventoy_unix.cpio`, `iso9660_*.efi`, `udf_*.efi`, `ventoy_*.efi`, `vtoyutil_*.efi`, `wimboot*`, `memdisk`, `ipxe.krn`, `vtoyjump32/64.exe`, `7z/`, `imdisk/`, `common_bcd.xz`, `common_bootmgr.xz`, `dragonfly.mfs.xz` | Injeção no initrd do Linux, drivers EFI de ISO/UDF, boot de WIM/VHD do Windows, utilitários |
| `/tool/` | `mount.exfat-fuse_*`, `create_ventoy_iso_part_dm.sh` | Ferramentas usadas pelo sistema já iniciado |

**Detecção de Secure Boot** (igual a `vtoycli fat -s` e `GetVentoyVerInPhyDrive`): existe
`/EFI/BOOT/grubx64_real.efi` → instalação com suporte a Secure Boot.

## 3. Classificação dos componentes

| Categoria | Componentes |
|---|---|
| **Integrado diretamente** (bytes originais) | `boot.img`, `core.img`, `ventoy.disk.img`, `version` |
| **Reimplementado** (lógica portada para Kotlin, a partir do código oficial) | Ventoy2Disk: cálculo de layout (`VentoyFillMBR`, `VentoyFillGpt`), MBR/GPT, formatação exFAT (`FormatPart1exFAT`), gravação do stage 1 (`WriteGrubStage1ToPhyDrive`), detecção (`IsVentoyPhyDrive`), leitura de versão (`ParseVentoyVersionFromString`), atualização (`VentoyWorker.sh` ramo *update*), correção de atributo GPT (`vtoycli gpt -f`) |
| **A adaptar** (Etapa 4) | Plugson (editor `ventoy.json`), temas, VentoyVlnk, modo sem Secure Boot (`partresize -s`: requer escrita FAT), instalação não destrutiva (`partresize`, exige redimensionar NTFS/ext) |
| **Não aplicável ao Android** | `.exe` do Windows, `imdisk`, `vtoyjump*.exe` (rodam no PC/WinPE durante o boot — continuam dentro da VTOYEFI, intocados) |

## 4. Procedimento de instalação reproduzido (Ventoy2Disk 1.1.18)

1. Verifica o SHA-256 dos arquivos do pacote (proteção extra do BootForge).
2. Zera 1 MiB no início e no fim do disco (equivale ao *clean* do VDS/diskpart).
3. Calcula o layout:
   - partição 1 começa no setor **2048**;
   - partição 2 (VTOYEFI) tem **65536 setores** e vem logo depois da 1;
   - MBR: `reservado += setores % 8` (alinhamento 4 KB); GPT: reserva 33 setores para a GPT de
     backup e alinha a contagem da partição 1 em múltiplos de 8; limite de 2 TiB no MBR.
4. Formata a partição 1 em **exFAT** (cluster 32 KiB até 32 GiB; 128 KiB acima), rótulo "Ventoy".
5. Grava `ventoy.disk.img` na partição 2.
6. Grava o `core.img`: MBR → setor 1 (2047 setores); GPT → setor 34 com `byte[500] = 35` (2014 setores).
7. Grava a tabela **por último**:
   - **MBR**: `boot.img` + UUID aleatório em 0x180 + assinatura (= 4 primeiros bytes do mesmo UUID)
     em 0x1B8; partição 1 tipo `0x07` **ativa**; partição 2 tipo `0xEF`. CHS no formato de *bitfield*
     do Ventoy2Disk.
   - **GPT**: MBR protetor (`0xEE`, `boot.img`, `byte[92] = 0x22`), cabeçalho + 128 entradas + backup;
     **ambas as partições com tipo "Microsoft Basic Data"** (decisão do Ventoy, comentário
     *"to fix windows issue"*); VTOYEFI com atributo `0x8000000000000000`; nomes "Ventoy"/"VTOYEFI".
8. Verifica: relê o primeiro 1 MiB e compara com o esperado; relê a VTOYEFI e compara o SHA-256;
   roda o detector e exige versão = pacote.

## 5. Comparação com o instalador oficial (mesmo disco virtual de 8 GiB, MBR)

O `Ventoy2Disk.sh` oficial (com as ferramentas `x86_64` do próprio repositório) foi executado em um
*loop device*, e o resultado comparado com o do BootForge:

| Região | Resultado |
|---|---|
| Início/tamanho das partições | **Idênticos** (2048–16711679 e 16711680–16777215) |
| `core.img` (setores 1–2047) | **Idêntico** (SHA-256 `183f3d54…`) |
| Código de boot (0x000–0x17F) | **Idêntico** |
| UUID (0x180), assinatura (0x1B8) | Diferentes — são **aleatórios** por instalação (esperado) |
| Campos CHS | Diferentes: o script Linux usa o `fdisk` (padrão IBM); o BootForge replica o Ventoy2Disk do Windows (bitfield). CHS é ignorado por firmwares atuais |
| Partição de dados exFAT | Formatadores diferentes (`mkexfatfs` × BootForge), ambos válidos no `fsck.exfat` |
| VTOYEFI | BootForge = SHA-256 exato do pacote (`f56c3cab…`). **Achado:** a do script oficial veio com os últimos ~2,3 MB zerados — `dd bs=512 count=65536` lendo de um *pipe* sem `iflag=fullblock` sofre leituras curtas. O BootForge grava em *stream* e confere por hash, o que evita esse problema. A atualização do BootForge corrigiu esse disco |

## 6. Configurações (`ventoy.json`) — referência para a Etapa 4

Os plugins válidos na 1.1.18 são exatamente os que têm página no Plugson:
`control`, `theme`, `menu_alias`, `menu_tip`, `menu_class`, `auto_install`, `persistence`,
`injection`, `conf_replace`, `password`, `image_list` (whitelist/blacklist), `auto_memdisk`, `dud`.
O editor visual usará **somente** esses plugins e os campos descritos nessas páginas e na
documentação oficial de cada plugin — nada será inventado.

## 7. Licenças

Ventoy é **GPLv3**; componentes internos têm licenças próprias (GRUB2 GPLv3, EDK2/shim BSD,
7-Zip LGPL, imdisk, wimboot GPLv2, etc. — textos em [`third_party/ventoy-licenses/`](../third_party/ventoy-licenses)).
Por isso o BootForge é distribuído como **GPLv3** e o código-fonte acompanha o app.
