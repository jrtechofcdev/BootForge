# 01 — Relatório técnico (Etapa 1: pesquisa e viabilidade)

> Projeto **BootForge** — *CREATE. MANAGE. BOOT.*
> Data: 18/11/2026 · Escopo: o que é possível fazer **no próprio Android, via USB OTG**, com e sem root.

Este relatório separa claramente **o que foi validado na prática** (✅), **o que é viável mas ainda
não foi implementado** (🟡) e **o que tem limitação real** (⛔). Nada aqui é suposição apresentada
como fato: quando algo depende de hardware que ainda não testamos, isso está escrito.

---

## 1. Resumo executivo

| Pergunta | Resposta | Situação |
|---|---|---|
| O Android consegue ler/gravar um pendrive **setor por setor** sem root? | **Sim**, pela USB Host API + protocolo Mass Storage (BOT/SCSI) | ✅ implementado (`android:usb`); ⏳ falta teste em celular real |
| Dá para **instalar o Ventoy de verdade** pelo celular? | **Sim**: o processo do Ventoy2Disk foi reimplementado em Kotlin | ✅ validado em imagens de disco + boot real em QEMU (BIOS e UEFI) |
| Dá para **reconhecer** um pendrive com Ventoy? | Sim, com as mesmas regras do instalador oficial | ✅ validado (inclusive em disco instalado pelo script oficial) |
| Dá para **montar** o pendrive como no PC? | **Não sem root** — o app precisa ter seus próprios drivers de sistema de arquivos | ✅ contornado: FAT e exFAT implementados em Kotlin puro |
| Gravar ISO **Linux** híbrida? | Sim (cópia setor a setor) | ✅ DD com análise automática e verificação (Etapa 3) |
| Gravar ISO do **Windows**? | Viável, mas exige montar FAT32/NTFS + dividir `install.wim` ou UEFI:NTFS | ✅ FAT32 + `install.wim` dividido em .swm, modo UEFI (Etapa 3); BIOS/Legacy via Ventoy |
| Editar e reconstruir ISO no celular? | Viável com motor próprio (ISO9660/Joliet/El Torito); UDF é o ponto difícil | 🟡 Etapa 5 |

**Conclusão:** o diferencial pedido — fazer tudo pelo Android, sem computador e **sem root** — é
tecnicamente viável. O preço é que o BootForge precisa trazer **seus próprios** drivers de partição
e sistemas de arquivos (o Android não deixa apps comuns montarem nada). Isso foi feito nesta etapa
para MBR, GPT, FAT12/16/32 (leitura) e exFAT (formatação + leitura).

---

## 2. Android USB Host API e USB Mass Storage

### 2.1 Como o acesso funciona (sem root)

1. O Android lista os dispositivos USB conectados (`UsbManager.getDeviceList()`).
2. Pendrives se identificam com a interface **classe 8 (Mass Storage), subclasse 6 (SCSI),
   protocolo 0x50 (Bulk-Only Transport)**.
3. O usuário concede permissão (`UsbManager.requestPermission`) — diálogo do sistema.
4. O app abre a conexão (`openDevice`) e **assume a interface** (`claimInterface(force = true)`).
   Nesse momento o kernel solta o driver `usb-storage` e o volume some do gerenciador de arquivos
   do Android — isso é esperado e necessário.
5. O app envia **comandos SCSI** encapsulados em CBW/CSW pelos endpoints *bulk*:
   `INQUIRY`, `TEST UNIT READY`, `READ CAPACITY(10)`, `READ(10)`, `WRITE(10)`, `REQUEST SENSE`.

Isso é exatamente o que um PC faz. Não exige root porque o app fala diretamente com o hardware USB,
sem passar pelo sistema de arquivos do Android.

### 2.2 Biblioteca utilizada

**libaums 0.10.0** (Apache-2.0) — biblioteca consolidada para USB Mass Storage no Android
(a mesma base usada por apps como o EtchDroid). Usamos **somente a camada de baixo nível**
(`UsbCommunicationFactory` + `ScsiBlockDevice`), inspecionada por `javap` antes do uso:

- `read(lba: Long, ByteBuffer)` / `write(lba: Long, ByteBuffer)` → offset em **setores** (LBA);
- o LBA é convertido para `Int` internamente (READ(10)/WRITE(10) têm LBA de 32 bits).

Não usamos o "nível alto" da libaums (`UsbMassStorageDevice.init()`), porque ele tenta montar
partições FAT32 e falharia em discos vazios, GPT do Ventoy ou exFAT.

### 2.3 Limitações reais encontradas

| Limitação | Causa | Impacto | Alternativa |
|---|---|---|---|
| ⛔ Máx. 2 TiB endereçável | READ(10)/WRITE(10) usam LBA de 32 bits | Pendrives > 2 TiB | Implementar READ(16)/WRITE(16) próprio (Etapa 7) |
| ⛔ Na prática, máx. 1 TiB | `ScsiBlockDevice` guarda o último LBA em `Int` com sinal | Pendrives > 1 TiB têm capacidade lida errada | O app **recusa** o dispositivo (não arrisca); driver SCSI próprio resolve |
| ⛔ Só LUN 0 | API da libaums | Leitores multi-cartão mostram só a 1ª entrada | Iterar LUNs com driver próprio |
| ⛔ Sem `SYNCHRONIZE CACHE` | Não existe na libaums | Cache interno do pendrive | Todas as gravações são **conferidas por releitura + SHA-256** |
| 🟡 Celular sem OTG | Hardware/fabricante | App não vê o pendrive | Mensagem explicativa na tela |
| 🟡 App em segundo plano | Android pode suspender o processo | Operação longa interrompida | Tela fica ligada durante a operação; **serviço em primeiro plano** pendente |
| 🟡 Energia | Alguns celulares fornecem pouca corrente no OTG | Pendrives/HDs que consomem muito desconectam | Orientar uso de hub com alimentação |

### 2.4 Root

Com root seria possível usar `/dev/block/sdX`, `mount`, `mkfs`, `losetup` etc. **Decisão: o
BootForge não depende de root.** Tudo o que foi feito funciona em um Android comum. Um "modo root"
opcional pode ser estudado depois (por exemplo, para NTFS com `ntfs-3g`), mas não é necessário.

---

## 3. Partições e sistemas de arquivos

| Item | Situação | Como |
|---|---|---|
| MBR (leitura/escrita, CHS no estilo do Ventoy) | ✅ | `core:partition` |
| GPT (leitura/escrita, CRC32, backup, GUID misto) | ✅ | `core:partition`; validado com `sgdisk -v` e `sfdisk` |
| Detecção de FS por assinatura | ✅ | FAT12/16/32, exFAT, NTFS, ISO9660, UDF, ext2/3/4 |
| FAT12/16/32 — leitura (LFN) | ✅ | `core:fs-fat`; lê a VTOYEFI real |
| FAT — escrita | 🟡 | Necessária para instalar Ventoy **sem** Secure Boot e para editar `ventoy.json`/temas na VTOYEFI |
| exFAT — formatação | ✅ | `core:fs-exfat`; aprovado pelo `fsck.exfat` (exfatprogs 1.2.2) |
| exFAT — leitura (listar, espaço livre, ler arquivo) | ✅ | lido corretamente em volume criado pelo `mkexfatfs` oficial do Ventoy |
| exFAT — escrita de arquivos | 🟡 | Etapa 4 (gerenciador de arquivos) |
| NTFS | 🟡/⛔ | Leitura viável em Kotlin; escrita confiável é complexa (porte do `ntfs-3g`, GPL, via NDK) |
| ISO9660/Joliet/Rock Ridge | 🟡 | Leitor próprio (Etapa 5) |
| UDF | 🟡 | Necessário para ISOs do Windows (ponte ISO9660+UDF); leitura viável, escrita é a parte mais difícil |
| Montar/desmontar volumes | ⛔ sem root | Substituído pelos drivers próprios acima |

---

## 4. Execução de ferramentas nativas no Android

- Executáveis Linux **podem** rodar no Android sem root se forem compilados para a ABI do aparelho
  (arm64-v8a), empacotados como `lib*.so` em `nativeLibraryDir` (requisito do Android 10+: não é
  possível executar arquivos de pastas graváveis do app).
- Porém essas ferramentas esperam um **dispositivo de bloco** (`/dev/sdX`), que sem root não existe.
  Seria preciso um "dispositivo virtual" ligado ao nosso driver USB (ex.: via `fuse`, indisponível
  sem root, ou adaptando o código-fonte para ler/gravar por callbacks).
- **Conclusão:** para Ventoy, a reimplementação em Kotlin foi a solução mais limpa e testável.
  Para tarefas pesadas no futuro (ex.: `xorriso` para reconstruir ISOs, `wimlib` para dividir o
  `install.wim`), o caminho é compilar a **biblioteca** via NDK e ligá-la por JNI, operando sobre
  arquivos no armazenamento do app, não sobre o pendrive.

---

## 5. Gravação de ISOs (Etapa 3 — implementada)

| Tipo de imagem | Método usado pelo BootForge | Situação |
|---|---|---|
| Linux **híbrida** (Ubuntu, Mint, Debian, Fedora, Pop!\_OS, Zorin, Kali…) | Cópia setor a setor ("DD") + apagamento do fim do disco + verificação SHA-256 por releitura | ✅ boot BIOS e UEFI no QEMU |
| ISO **não híbrida** com `efi/boot/boot*.efi` | Extrair arquivos para FAT32 (MBR ou GPT) | ✅ boot UEFI no QEMU (MBR e GPT) |
| **Windows 10/11** | FAT32 (UEFI) com `install.wim` > 4 GiB dividido em `.swm` — implementação própria (leitor UDF + gerador FAT32 + divisor WIM), sem `wimlib` no APK | ✅ validado com `wimlib verify/apply`; 🧪 boot do instalador real a testar no PC |
| Windows com `install.esd` > 4 GiB | NTFS + UEFI:NTFS (não implementado) | ⛔ o app explica e indica o Ventoy |
| Qualquer ISO via **Ventoy** | Copiar o arquivo para a partição exFAT | ✅ |

O app **analisa** cada ISO, recomenda o método e mostra os motivos quando um método não serve
(ex.: "ISOs do Windows não iniciam quando gravadas em modo DD").

---

## 6. Bibliotecas para manipulação de ISO (Etapa 5)

| Opção | Licença | Uso pretendido | Observação |
|---|---|---|---|
| Leitor ISO9660/Joliet/El Torito próprio (Kotlin) | GPLv3 (nosso) | Explorar/extrair | Formato simples e bem documentado (ECMA-119) |
| **xorriso / libisofs / libisoburn** (NDK) | GPLv2+/GPLv3 | Reconstruir preservando El Torito, MBR/GPT híbridos (`-boot_image any replay`) | Referência do mercado para reconstrução de ISOs Linux inicializáveis |
| UDF (leitura) próprio | GPLv3 | Abrir ISOs do Windows | Leitura viável; escrita UDF não tem biblioteca Java madura |
| `wimlib` (NDK) | LGPLv3/GPLv3 | Dividir `install.wim` | Para Windows em FAT32 |

Regra que será seguida: **uma ISO reconstruída só será apresentada como inicializável depois de
validar** El Torito, imagens de boot BIOS/UEFI e, quando houver, a tabela híbrida.

---

## 7. Prova de conceito (PoC) — o que existe e como foi validado

### 7.1 Validado nesta etapa (sem hardware Android)

| Teste | Resultado |
|---|---|
| Instalar Ventoy (MBR e GPT) em discos virtuais de 512 MiB, 8 GiB e 64 GiB | ✅ |
| `fsck.fat -n` na VTOYEFI, `fsck.exfat -n` na partição de dados, `sfdisk --dump`, `sgdisk -v` | ✅ sem erros |
| Boot real no **QEMU**: MBR+BIOS, MBR+UEFI, GPT+BIOS, GPT+UEFI (OVMF) | ✅ menu do Ventoy 1.1.18 ([capturas](img/)) |
| ISOs copiadas para subpastas aparecem no menu do Ventoy | ✅ (`docs/img/qemu-mbr-bios-isos.png`) |
| Comparação byte a byte com o `Ventoy2Disk.sh` **oficial** no mesmo disco | ✅ layout idêntico; `core.img` idêntico (ver doc 02) |
| Atualização não destrutiva sobre disco instalado pelo **instalador oficial** | ✅ dados preservados (hash) |
| exFAT do BootForge montado pelo `mount.exfat-fuse` oficial do Ventoy, arquivos gravados e `fsck` limpo | ✅ |

### 7.2 Pendente (precisa do seu celular)

O app contém a tela **Pendrives → Diagnóstico (prova de conceito)** com:

1. **Teste de leitura** — lê até 256 MiB e mede a velocidade real (não altera nada);
2. **Gravação RAW + verificação** — grava um arquivo `.img`/`.iso` híbrido e confere relendo;
3. **Setor 0 em hexadecimal**.

O roteiro está em [`ROTEIRO-TESTES-REAIS.md`](ROTEIRO-TESTES-REAIS.md). **Enquanto esses testes não
forem feitos, o acesso USB é considerado "implementado, não validado em hardware".**

---

## 8. Riscos e próximos passos técnicos

1. **Validar em hardware** (vários celulares e pendrives) — maior risco atual.
2. **Serviço em primeiro plano** para operações longas (evitar que o Android mate o processo).
3. **Escrita FAT** (VTOYEFI) → instalação sem Secure Boot, `ventoy.json`, temas.
4. **Escrita exFAT** → gerenciador de arquivos e de ISOs do Ventoy.
5. **Driver SCSI próprio** (READ(16)/WRITE(16), SYNCHRONIZE CACHE, várias LUNs) para remover os
   limites de 1–2 TiB.
