# Licenças de terceiros

O **BootForge** é distribuído sob a **GNU General Public License v3** (arquivo [LICENSE](LICENSE)).

## Componentes embutidos no aplicativo

| Componente | Versão | Licença | Onde | Observação |
|---|---|---|---|---|
| **Ventoy** (`boot.img`, `core.img`, `ventoy.disk.img`) | 1.1.18 | GPLv3 | `third_party/ventoy-1.1.18` → assets do APK | Usados **sem modificação**. Código-fonte: https://github.com/ventoy/Ventoy (tag v1.1.18). Componentes internos da partição VTOYEFI (GRUB2, shim, EDK2, wimboot, 7-Zip, imdisk, iPXE…) têm suas licenças em `third_party/ventoy-licenses/` |
| libaums core | 0.10.0 | Apache License 2.0 | dependência Maven | https://github.com/magnusja/libaums |
| XZ for Java | 1.10 | 0BSD | dependência Maven | https://tukaani.org/xz/java.html |
| Kotlin, kotlinx.coroutines | 2.1.21 / 1.10.2 | Apache License 2.0 | dependência Maven | |
| AndroidX / Jetpack Compose / Material 3 | BOM 2025.06.01 | Apache License 2.0 | dependência Maven | |
| Tabela up-case do exFAT | — | Especificação exFAT da Microsoft (publicada para implementação) | `core/fs-exfat/src/main/resources/.../upcase.bin` | Tabela recomendada pela especificação (5836 bytes, checksum 0xE619D30D) |

## Somente desenvolvimento/testes (não vão no APK)

| Ferramenta | Licença |
|---|---|
| Paparazzi (capturas de tela) | Apache License 2.0 |
| QEMU, OVMF, exfatprogs, dosfstools, gdisk, util-linux (validação) | GPL / BSD (ferramentas do sistema, não distribuídas) |
