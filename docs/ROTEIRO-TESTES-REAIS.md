# Roteiro de testes em hardware real (celular + pendrive)

Este roteiro valida a prova de conceito no **seu** aparelho. Leva cerca de 20 minutos.
Anote os resultados na tabela do final e me envie (ou cole no `docs/ACOMPANHAMENTO.md`).

## O que você precisa

- Celular Android 8 ou mais novo, com suporte a **OTG**.
- Adaptador **OTG** (USB-C ou micro-USB → USB-A) — ou pendrive USB-C.
- Um pendrive que **pode ser apagado** (de preferência 8 a 64 GB).
- Opcional: uma ISO Linux pequena (ex.: *Linux Lite* ou *Debian netinst*) salva no celular.
- Opcional: um PC/notebook para testar o boot no final.

> *Importante:* faça os testes **somente** com um pendrive sem nada importante. Os passos 4, 5 e 6 apagam o pendrive.

## Instalação do app

1. Copie o arquivo `BootForge-0.4.0.apk` para o celular.
2. Toque nele e permita "instalar apps desconhecidos" para o gerenciador de arquivos.
3. Abra o **BootForge**.

## Passo 1 — Detecção

1. Conecte o pendrive pelo OTG.
2. O Android pode perguntar *"Abrir BootForge?"* — toque em **OK**.
3. Na tela inicial deve aparecer **"1 pendrive conectado"**.
4. Toque nele → **Permitir acesso** → aceite o aviso do sistema.

✔️ Esperado: aparecem fabricante, modelo e nº de série.

## Passo 2 — Leitura (não altera nada)

1. Toque em **Inspecionar**.
2. Confira: capacidade, setores, esquema (MBR/GPT), partições e sistemas de arquivos.
3. Toque em **Ver setor 0 (hexadecimal)** — os dois últimos bytes da linha `01F0` devem ser `55 AA`
   se o pendrive tiver tabela de partições.

## Passo 3 — Teste de leitura (não altera nada)

1. Em **Diagnóstico**, toque em **Teste de leitura**.
2. Anote a velocidade final (MB/s).

## Passo 4 — Instalar o Ventoy (APAGA o pendrive)

1. Volte → **Ventoy** → o app mostra *"Ventoy não encontrado"* → **Instalar o Ventoy neste pendrive**.
2. Escolha **MBR**, deixe o alinhamento 4 KB ligado e reserva 0.
3. **Instalar** → confira os dados do pendrive na confirmação → digite `APAGAR` → **Instalar**.
4. Acompanhe as 7 etapas. Anote o tempo total.

✔️ Esperado: mensagem verde *"Ventoy 1.1.18 instalado (MBR) e verificado"*; ao fechar, a aba Ventoy mostra
**Ventoy 1.1.18 · MBR · Secure Boot** e o espaço livre.

## Passo 5 — Conferir no PC (opcional, mas muito importante)

1. Conecte o pendrive no PC. Deve aparecer uma unidade chamada **Ventoy**.
2. Copie uma ISO para ela (pode criar pastas, ex.: `Linux/`).
3. No celular, reconecte o pendrive → aba Ventoy → a ISO deve aparecer em **Imagens no pendrive**.
4. Dê boot no PC pelo pendrive: deve aparecer o **menu do Ventoy** com a ISO.
5. Opcional: no PC, abra o `Ventoy2Disk.exe` (pasta `third_party/ventoy-1.1.18`) e veja se ele reconhece
   o pendrive como *"Ventoy 1.1.18"*.

## Passo 6 — Atualização não destrutiva

1. Aba Ventoy → **Reinstalar componentes (preserva arquivos)** → confirmar.
2. Depois, confira que a ISO copiada no passo 5 **continua lá**.

## Passo 7 — Gravação RAW de ISO Linux (APAGA o pendrive)

1. Pendrives → Inspecionar → **Gravar imagem RAW** → escolha a ISO Linux.
2. Digite `APAGAR` e confirme.

✔️ Esperado: *"gravada e verificada (SHA-256 confere)"*. O pendrive deve dar boot no PC com a ISO.

## Passo 8 — Situações de erro (importante!)

- Durante uma gravação, **desconecte** o pendrive → o app deve mostrar **"A operação FALHOU"** (nunca "concluído").
- Toque em **Cancelar** durante uma gravação → deve mostrar "Operação cancelada".

## Etapa 4 — Ventoy completo (APK 0.2.0)

Use o pendrive com Ventoy do passo 4. Abra **Ventoy** na tela inicial — aparecem 8 abas.

| # | Teste | Como fazer | Esperado |
|---|---|---|---|
| 9 | Arquivos | Aba **Arquivos** → ⋮ → *Copiar arquivos do celular* → escolha uma ISO | Notificação com progresso; ao fim, o arquivo aparece na pasta |
| 10 | Cópia longa em segundo plano | Durante a cópia, bloqueie a tela ou troque de app | A cópia continua (notificação ativa) |
| 11 | Pasta, renomear, mover, excluir | Crie uma pasta, recorte e cole um arquivo nela, renomeie, exclua | Tudo refletido na lista |
| 12 | Para o celular / compartilhar | Selecione um arquivo → *Para o celular* e *Compartilhar* | Arquivo aparece no celular / abre o menu de compartilhar |
| 13 | ISOs | Aba **ISOs** → toque numa ISO | Mostra o sistema (ex.: Ubuntu), boot BIOS/UEFI, "No menu" |
| 14 | Integridade | *Verificar integridade* → cole o SHA-256 oficial da ISO | "SHA-256 CONFERE" |
| 15 | Organizar | *Organizar em pastas* → confirme | ISOs movidas para /Linux, /Windows… |
| 16 | ventoy.json | Aba **Configurações** → *Tempo do menu* = 10 → Salvar | No PC, o menu do Ventoy conta 10 s |
| 17 | Tema | Aba **Temas** → *Copiar tema original* → Pré-visualizar → Aplicar | No PC, o menu usa o tema |
| 18 | Verificar instalação | Aba **Ferramentas** → *Verificar agora* | Tudo ✓ |
| 19 | Secure Boot | *Desligar* e depois *Ligar* | Em cada caso o PC continua dando boot; ISOs preservadas |
| 20 | Backup | *Salvar backup (.zip)* → apague o ventoy.json → *Restaurar* | ventoy.json volta |
| 21 | Persistência | *Criar arquivo de persistência* (Ubuntu, 4096 MiB, vincular à ISO Ubuntu) | No PC, a ISO Ubuntu pergunta/usa persistência e mantém alterações após reiniciar |
| 22 | Atualizações | Aba **Atualizações** → *Consultar versões* → *Baixar* | Pacote "verificado"; *Atualizar o pendrive* mantém as ISOs |

## Etapa 3 — Gravar ISO (passos 23 a 27)

> Estes passos **apagam o pendrive**. Depois, reinstale o Ventoy se quiser voltar a usá-lo.

| Passo | Onde | O que fazer | Esperado |
|---|---|---|---|
| 23 | Tela inicial → **Gravar ISO** | *Escolher imagem* → uma ISO **Linux** (ex.: Ubuntu, Mint, Debian live) | Mostra sistema, "ISO híbrida: sim", método **Imagem direta (DD)** recomendado |
| 24 | Mesma tela | Escolha o pendrive → *Gravar* → digite APAGAR | Progresso real com velocidade; no fim, "Verificação concluída"; no PC, a ISO inicia (BIOS e/ou UEFI) |
| 25 | Mesma tela | *Escolher outra imagem* → ISO **oficial do Windows 10/11** (site da Microsoft) | Sistema "Windows", DD **indisponível** (com o motivo), **Instalador do Windows (FAT32, UEFI)** recomendado; se o install.wim passar de 4 GB, aparece o aviso de divisão |
| 26 | Mesma tela | Gravar com **MBR** (padrão) | No PC em modo **UEFI**, o instalador do Windows abre e chega na escolha de edição/disco (não precisa instalar) |
| 27 | Mesmo pendrive | Ligue um PC em modo **Legacy/BIOS** | Aparece a mensagem "esta midia inicia somente em modo UEFI" (não trava em tela preta) |

*Tempo de referência:* anote quanto levou a gravação + verificação da ISO do Windows (ela tem ~5–7 GB).

## Etapa 5 — Editor de ISO (passos 28 a 34)

> Estes passos **não apagam nada**: a ISO original nunca é alterada; o app gera uma nova.

| Passo | Onde | O que fazer | Esperado |
|---|---|---|---|
| 28 | Tela inicial → **Editor de ISO** | *Abrir ISO* → uma ISO Linux (ex.: Ubuntu, Mint) | Mostra formato (ex.: "ISO9660 + Rock Ridge + Joliet · El Torito · híbrida"), arquivos, pastas e imagens de boot |
| 29 | Mesma tela | Navegue até `/boot/grub/grub.cfg` → toque → mude o texto de um `menuentry` → *Salvar* | Selo **Novo**; chip "1 alteração" |
| 30 | Mesma tela | *Nova pasta* "BootForge" → *Adicionar arquivos* (uma foto) → renomeie, mova e exclua algo → **Desfazer** e **Refazer** | Lista e chip de alterações acompanham; histórico no chip |
| 31 | Mesma tela | *Gerar ISO* → **Preservar inicialização** → *Escolher onde salvar* | Progresso real; no fim, "ISO gerada e validada" com todas as conferências ✓ |
| 32 | PC | Grave a nova ISO (Etapa 3, modo DD) ou copie para o Ventoy e inicie | Aparece o menu com o texto que você editou; o sistema inicia normalmente |
| 33 | Editor | *Extrair tudo* (ou selecione itens → *Extrair*) para uma pasta do celular | Arquivos aparecem na pasta escolhida |
| 34 | Editor | Abra uma **ISO do Windows** → adicione um arquivo em `/sources` → *Gerar ISO* (**Reconstrução completa**) | Validação ✓ (inclui "UDF" e "Âncoras UDF"); no PC em UEFI o instalador abre |

*Tempo de referência:* anote quanto levou para gerar + validar (passo 31).

## Tabela de resultados

| Item | Resultado (OK / falhou + mensagem) |
|---|---|
| Modelo do celular / versão do Android | |
| Pendrive (marca, capacidade, USB 2/3) | |
| 1. Detecção e permissão | |
| 2. Leitura de partições | |
| 3. Velocidade de leitura | ___ MB/s |
| 4. Instalação do Ventoy (tempo) | |
| 5. PC reconhece / boot do Ventoy | |
| 6. Atualização preserva arquivos | |
| 7. Gravação RAW + boot | |
| 8. Desconexão / cancelamento tratados | |
| 9–12. Arquivos (cópia, segundo plano, organizar, compartilhar) | |
| 13–15. ISOs (identificação, integridade, organizar) | |
| 16–17. ventoy.json e tema refletidos no PC | |
| 18–20. Verificar, Secure Boot, backup | |
| 21. Persistência funcionando na distro | |
| 22. Atualização online | |
| 23–24. ISO Linux em DD + boot | |
| 25–26. Windows FAT32 (tempo: ___ min) + instalador abre | |
| 27. Aviso em modo BIOS | |
| 28–30. Editor: abrir, editar, desfazer | |
| 31–32. Nova ISO validada e iniciando no PC (tempo: ___ min) | |
| 33. Extração | |
| 34. Windows reconstruída + instalador abre | |
