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

1. Copie o arquivo `BootForge-0.1.0-poc.apk` para o celular.
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
