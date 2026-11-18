# 03 — Arquitetura

## Visão geral

```
┌──────────────────────────────── app (Android, Jetpack Compose) ────────────────────────────────┐
│ MainActivity · navegação · telas (Home, Pendrives, Ventoy, Instalar, Histórico, Configurações) │
│ AppViewModel: executa operações em segundo plano, progresso real, cancelamento, histórico      │
└───────────────┬───────────────────────────────────────────────┬───────────────────────────────┘
                │                                               │
      ┌─────────▼─────────┐                         ┌───────────▼────────────┐
      │ android:usb       │                         │ feature:ventoy-core    │  ← Kotlin puro (JVM)
      │ UsbDriveManager   │                         │ VentoyPackage          │
      │ UsbBlockDevice    │──── implementa ───┐     │ VentoyLayout           │
      │ (libaums SCSI)    │                   │     │ VentoyDetector         │
      └───────────────────┘                   │     │ VentoyInstaller        │
                                              │     │ VentoyUpdater          │
                                              │     └──┬──────────┬──────────┘
                                   ┌──────────▼────────▼┐  ┌──────▼─────┐ ┌──────────────┐
                                   │ core:blockdev      │  │core:fs-fat │ │core:fs-exfat │
                                   │ BlockDevice (LBA)  │◄─┤ leitor     │ │ format+leitor│
                                   │ File/Memory/Slice  │  └────────────┘ └──────────────┘
                                   │ BlockTransfer      │  ┌────────────────────────────┐
                                   │ (progresso, hash)  │◄─┤ core:partition             │
                                   └─────────┬──────────┘  │ MBR · GPT · FsProbe        │
                                             │             └────────────────────────────┘
                                   ┌─────────▼──────────┐
                                   │ core:integrity     │  SHA-256/512, arquivos de checksum,
                                   └────────────────────┘  cancelamento
```

## Decisões

| Decisão | Motivo |
|---|---|
| **Kotlin + Jetpack Compose** | Escolha do usuário; nativo, melhor desempenho em operações longas |
| **Licença GPLv3** | Permite embutir o Ventoy (GPLv3) e usar bibliotecas GPL no futuro (xorriso, wimlib, ntfs-3g) |
| **Núcleo em Kotlin/JVM puro** | O mesmo código roda no Android, no PC (CLI) e nos testes automatizados com imagens de disco, fsck e QEMU |
| **Abstração `BlockDevice`** | Pendrive USB, arquivo `.img` e partição são intercambiáveis; operações de baixo nível isoladas |
| **Sem root** | Requisito do projeto; FS próprios substituem `mount` |
| **libaums só na camada SCSI** | Biblioteca consolidada para o protocolo; o resto (partições, FS) é nosso e testável |
| **Reimplementar o Ventoy2Disk** em vez de executar binários | Os `.exe` são Win32; os binários Linux exigem `/dev/sdX` (root). A lógica é pequena e foi conferida linha a linha com o código oficial |
| **Componentes do Ventoy usados sem modificação** | Preserva a compatibilidade e a autenticidade; SHA-256 conferido antes de gravar |
| **Tabela de partições gravada por último** | Igual ao oficial: se algo falhar no meio, o disco não "parece" Ventoy |
| **Toda gravação é conferida por releitura** | Não existe `SYNCHRONIZE CACHE` na libaums; e o prompt exige não declarar sucesso sem verificar |
| **Telas = conteúdo sem estado + ligação ao ViewModel** | Permite capturas de tela automáticas (Paparazzi) e manutenção mais simples |
| **Assets do Ventoy gerados no build** a partir de `third_party/` | Um único lugar com os arquivos oficiais; nada duplicado |

Independências pedidas pelo prompt:
- Catálogo de sistemas ≠ motor de gravação (módulos separados, Etapas 3 e 6).
- Gerenciamento de arquivos do Ventoy ≠ instalação (`ExFatVolume` é independente do `VentoyInstaller`).
- Editor de ISO terá módulo próprio (`core:iso`, Etapa 5).

## Módulos adicionados na Etapa 4

| Módulo | Conteúdo |
|---|---|
| `core:fs-exfat` (escrita) | `ClusterMap` (bitmap+FAT, alocação contígua), `UpcaseTable`, conjuntos de entradas, verificador |
| `core:fs-fat` (escrita mínima) | substituir/excluir arquivos na VTOYEFI (Secure Boot) |
| `core:fs-ext` | `ExtFormatter` ext2/ext3 (persistência) |
| `core:iso` | `IsoFileSystem`, `ImageInspector` |
| `feature:ventoy-config` | `VentoySchema` (15 plugins, 25 opções), `VentoyConfig`, `VentoyConfigValidator` |
| `feature:ventoy-core` | `ImageCatalog`, `VentoyConfigStore`, `ThemeManager`/`GrubTheme`, `VentoyMaintenance`, `VentoyPersistence`, `VentoyReleaseClient` |
| `app` | `AppGraph`, `DriveSession` (conexão única e exclusiva), `OperationRunner` + `OperationService` (primeiro plano), `VentoyViewModel`, telas `ui/screens/ventoy/*` |

## Módulos futuros previstos

| Módulo | Etapa | Conteúdo |
|---|---|---|
| `feature:iso-writer` | 3 | análise de ISO, DD híbrido, Windows (FAT32 + WIM dividido / UEFI:NTFS) |
| `core:iso` + `feature:iso-editor` | 5 | ISO9660/Joliet/UDF, edição não destrutiva, reconstrução |
| `feature:catalog`, `feature:downloads` | 6 | metadados oficiais centralizados (JSON atualizável), downloads com retomada, checksums |

## Dependências

| Biblioteca | Versão | Licença | Uso |
|---|---|---|---|
| Kotlin / kotlinx.coroutines | 2.1.21 / 1.10.2 | Apache-2.0 | Linguagem, tarefas em segundo plano |
| AndroidX (core, activity, lifecycle, navigation), Compose BOM | 2025.06.01 | Apache-2.0 | Interface |
| libaums core | 0.10.0 | Apache-2.0 | Protocolo USB Mass Storage/SCSI |
| XZ for Java (org.tukaani:xz) | 1.10 | 0BSD | Descompactar `core.img.xz` e `ventoy.disk.img.xz` |
| kotlinx.serialization (JSON) | 1.8.1 | Apache-2.0 | Leitura/gravação do ventoy.json preservando a ordem |
| AndroidX DocumentFile | 1.1.0 | Apache-2.0 | Importar/exportar pastas inteiras do celular (SAF) |
| Paparazzi (somente testes) | 2.0.0-alpha02 | Apache-2.0 | Capturas de tela na JVM |
| Ventoy | 1.1.18 | GPLv3 (+ componentes) | Componentes de boot gravados no pendrive |

## Compatibilidade

- Android **8.0+** (minSdk 26), alvo Android 15 (API 35).
- Celular com **USB Host/OTG**.
- Pendrives com setor lógico de **512 bytes**, até **1 TiB** (limite atual do driver SCSI).
