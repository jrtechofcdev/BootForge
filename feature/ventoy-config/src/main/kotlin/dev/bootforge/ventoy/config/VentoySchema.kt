package dev.bootforge.ventoy.config

/**
 * Esquema do `ventoy.json` da versão 1.1.18.
 *
 * FONTE (nada inventado):
 *  - nomes dos plugins: tabela `g_plugin_entries` em `GRUB2/.../ventoy/ventoy_plugin.c`;
 *  - campos de cada plugin: funções `ventoy_plugin_*_entry` do mesmo arquivo;
 *  - opções do `control`, valores aceitos e descrições: página `plugson_control.html` do Plugson;
 *  - idiomas do menu: `/grub/menulang.cfg` da partição VTOYEFI do pacote 1.1.18.
 *
 * Cada plugin pode ter um sufixo de arquitetura (`control_uefi`, `theme_legacy`…): nesse caso a
 * configuração vale apenas naquele modo de boot e tem prioridade sobre a versão sem sufixo.
 */
object VentoySchema {

    val ARCH_SUFFIXES = listOf("legacy", "uefi", "ia32", "aa64", "mips")

    enum class FieldType { STRING, PATH, PATH_LIST, INT, ENUM, PASSWORD }

    data class Field(
        val key: String,
        val label: String,
        val type: FieldType,
        val help: String = "",
        val values: List<Pair<String, String>> = emptyList(),
        val required: Boolean = false,
    )

    /** Formas de plugin: lista de regras, objeto, lista de caminhos ou lista de opções `control`. */
    enum class Kind { CONTROL, OBJECT, RULES, STRING_LIST, PASSWORD, MENU_TIP }

    data class Plugin(
        val key: String,
        val title: String,
        val description: String,
        val kind: Kind,
        /** Para RULES: campos alternativos que identificam o alvo da regra (ex.: "image" OU "dir"). */
        val targets: List<Field> = emptyList(),
        val fields: List<Field> = emptyList(),
    )

    private val boolValues = listOf("0" to "Não (0)", "1" to "Sim (1)")

    /** As 25 opções do plugin `control` (Plugson 1.1.18). */
    val CONTROL_OPTIONS: List<Field> = listOf(
        Field("VTOY_MENU_LANGUAGE", "Idioma do menu", FieldType.ENUM, "Idioma do menu do Ventoy. Padrão: en_US.",
            listOf("zh_CN", "ar_AR", "bn_BN", "cs_CZ", "de_DE", "el_GR", "en_US", "es_ES", "fa_IR", "fr_FR", "hi_HI", "hr_HR",
                "hu_HU", "id_ID", "it_IT", "ja_JP", "ka_GE", "ko_KR", "lg_UG", "pl_PL", "pt_BR", "pt_PT", "ru_RU", "sl_SI",
                "sr_RS", "ta_IN", "tr_TR", "uk_UA", "vi_VN", "zh_TW").map { it to it }),
        Field("VTOY_DEFAULT_MENU_MODE", "Modo do menu", FieldType.ENUM, "Lista de arquivos ou árvore de pastas.",
            listOf("0" to "Lista (ListView)", "1" to "Árvore de pastas (TreeView)")),
        Field("VTOY_TREE_VIEW_MENU_STYLE", "Estilo do modo árvore", FieldType.ENUM, "0 mostra prefixo DIR e tamanhos; 1 oculta. Só vale no modo árvore.",
            listOf("0" to "Com DIR e tamanho", "1" to "Sem DIR e tamanho")),
        Field("VTOY_MENU_TIMEOUT", "Tempo do menu (s)", FieldType.INT, "Inicia a imagem selecionada após N segundos. 0 = sem contagem."),
        Field("VTOY_DEFAULT_IMAGE", "Imagem padrão", FieldType.PATH, "Imagem selecionada ao abrir o menu (use com o tempo do menu)."),
        Field("VTOY_DEFAULT_SEARCH_ROOT", "Pasta de busca", FieldType.PATH, "O Ventoy só procura imagens nesta pasta e subpastas (acelera pendrives com muitos arquivos)."),
        Field("VTOY_MAX_SEARCH_LEVEL", "Profundidade máxima", FieldType.STRING, "\"max\" (padrão) ou um número: 0 = só a raiz da busca, 1 = um nível de subpasta…"),
        Field("VTOY_SECURE_BOOT_POLICY", "Política de Secure Boot", FieldType.ENUM, "Como tratar a verificação de Secure Boot das imagens.",
            listOf("0" to "Ignorar verificação", "1" to "Seguir as regras do Secure Boot UEFI")),
        Field("VTOY_WIN_UEFI_RES_LOCK", "Resolução do Windows (UEFI)", FieldType.ENUM, "Trava de resolução ao iniciar o instalador do Windows em UEFI.",
            listOf("0" to "Não travar", "1" to "Maior resolução", "2" to "1024x768", "3" to "1024x768 ou maior")),
        Field("VTOY_WIN11_BYPASS_CHECK", "Ignorar requisitos do Windows 11", FieldType.ENUM, "Cria as chaves LabConfig (RAM, TPM, Secure Boot, CPU) ao instalar o Windows 11 oficial.", boolValues),
        Field("VTOY_WIN11_BYPASS_NRO", "Ignorar conta online (Windows 11)", FieldType.ENUM, "Cria a chave OOBE\\BypassNRO ao instalar o Windows 11 oficial.", boolValues),
        Field("VTOY_SECONDARY_BOOT_MENU", "Menu secundário", FieldType.ENUM, "Mostra o segundo menu (modo normal/grub2/memdisk) ao escolher uma imagem.",
            listOf("0" to "Não mostrar", "1" to "Mostrar")),
        Field("VTOY_SECONDARY_TIMEOUT", "Tempo do menu secundário (s)", FieldType.INT, "Só vale com o menu secundário ativo."),
        Field("VTOY_SHOW_PASSWORD_ASTERISK", "Mostrar * na senha", FieldType.ENUM, "", listOf("0" to "Ocultar", "1" to "Mostrar")),
        Field("VTOY_DEFAULT_KBD_LAYOUT", "Teclado", FieldType.ENUM, "Layout de teclado do menu.",
            listOf("QWERTY_USA", "AZERTY", "CZECH_QWERTY", "CZECH_QWERTZ", "DANISH", "DVORAK_USA", "FRENCH", "GERMAN", "ITALIANO",
                "JAPAN_106", "LATIN_USA", "PORTU_BRAZIL", "QWERTY_UK", "QWERTZ", "QWERTZ_HUN", "QWERTZ_SLOV_CROAT", "SPANISH",
                "SWEDISH", "TURKISH_Q", "VIETNAMESE").map { it to it }),
        Field("VTOY_FILT_DOT_UNDERSCORE_FILE", "Ocultar arquivos ._ (macOS)", FieldType.ENUM, "", boolValues),
        Field("VTOY_SORT_CASE_SENSITIVE", "Ordenar diferenciando maiúsculas", FieldType.ENUM, "", boolValues),
        Field("VTOY_VHD_NO_WARNING", "Sem aviso de VHD fora de NTFS", FieldType.ENUM, "", boolValues),
        Field("VTOY_LINUX_REMOUNT", "Remontar partição no Linux", FieldType.ENUM, "Opção presente no Plugson 1.1.18.", boolValues),
        Field("VTOY_FILE_FLT_ISO", "Ocultar .iso", FieldType.ENUM, "", boolValues),
        Field("VTOY_FILE_FLT_WIM", "Ocultar .wim", FieldType.ENUM, "", boolValues),
        Field("VTOY_FILE_FLT_EFI", "Ocultar .efi", FieldType.ENUM, "", boolValues),
        Field("VTOY_FILE_FLT_IMG", "Ocultar .img", FieldType.ENUM, "", boolValues),
        Field("VTOY_FILE_FLT_VHD", "Ocultar .vhd/.vhdx", FieldType.ENUM, "", boolValues),
        Field("VTOY_FILE_FLT_VTOY", "Ocultar .vtoy", FieldType.ENUM, "", boolValues),
    )

    private val image = Field("image", "Imagem", FieldType.PATH, "Caminho completo da imagem (aceita * como curinga).")
    private val dir = Field("dir", "Pasta", FieldType.PATH, "Caminho da pasta.")
    private val parent = Field("parent", "Pasta-mãe", FieldType.PATH, "Vale para todas as imagens desta pasta.")
    private val autosel = Field("autosel", "Seleção automática", FieldType.INT, "Índice escolhido automaticamente (1, 2…).")
    private val timeout = Field("timeout", "Tempo (s)", FieldType.INT)

    val PLUGINS: List<Plugin> = listOf(
        Plugin("control", "Controle global", "Opções gerais do menu do Ventoy.", Kind.CONTROL),
        Plugin(
            "theme", "Tema", "Tema gráfico do menu (formato de tema do GRUB2).", Kind.OBJECT,
            fields = listOf(
                Field("file", "Arquivo(s) theme.txt", FieldType.PATH_LIST, "Um caminho ou uma lista (vários temas)."),
                Field("default_file", "Tema padrão (índice)", FieldType.INT, "Com vários temas: 0 = aleatório, 1 = primeiro…"),
                Field("random", "Sorteio", FieldType.ENUM, "Com vários temas e índice 0.",
                    listOf("boot_second" to "A cada boot", "boot_day" to "Por dia", "boot_month" to "Por mês")),
                Field("resolution_fit", "Ajustar à resolução", FieldType.INT, "1 = escolhe o tema conforme a resolução da tela."),
                Field("gfxmode", "Resolução", FieldType.STRING, "Ex.: 1920x1080 ou max."),
                Field("display_mode", "Modo de exibição", FieldType.ENUM, "",
                    listOf("GUI" to "Gráfico (GUI)", "CLI" to "Texto (CLI)", "serial" to "Serial", "serial_console" to "Serial + console")),
                Field("serial_param", "Parâmetros seriais", FieldType.STRING),
                Field("ventoy_left", "Posição do texto Ventoy (esquerda)", FieldType.STRING, "Ex.: 5%"),
                Field("ventoy_top", "Posição do texto Ventoy (topo)", FieldType.STRING, "Ex.: 95%"),
                Field("ventoy_color", "Cor do texto Ventoy", FieldType.STRING, "Ex.: #0000ff"),
                Field("fonts", "Fontes (.pf2)", FieldType.PATH_LIST),
            ),
        ),
        Plugin("menu_alias", "Apelidos no menu", "Mostra um nome amigável no lugar do nome do arquivo.", Kind.RULES,
            targets = listOf(image, dir), fields = listOf(Field("alias", "Apelido", FieldType.STRING, required = true))),
        Plugin("menu_tip", "Dicas no menu", "Texto exibido ao selecionar uma imagem.", Kind.MENU_TIP,
            targets = listOf(image, dir), fields = listOf(Field("tip", "Dica", FieldType.STRING, required = true))),
        Plugin("menu_class", "Ícones (classes)", "Associa uma classe (ícone do tema) a imagens.", Kind.RULES,
            // "key" e "dir" são TRECHOS procurados no nome (strstr no ventoy_plugin.c), não caminhos.
            targets = listOf(
                Field("key", "Trecho do nome da imagem", FieldType.STRING, "Ex.: ubuntu → toda imagem com \"ubuntu\" no nome."),
                Field("dir", "Trecho do nome da pasta", FieldType.STRING, "Vale para pastas cujo nome contém o trecho (modo árvore)."),
                parent,
            ),
            fields = listOf(Field("class", "Classe", FieldType.STRING, required = true))),
        Plugin("auto_install", "Instalação automática", "Scripts de instalação automática (Windows XML, kickstart, preseed…).", Kind.RULES,
            targets = listOf(image, parent),
            fields = listOf(Field("template", "Script(s)", FieldType.PATH_LIST, required = true), autosel, timeout)),
        Plugin("persistence", "Persistência", "Arquivos de persistência para distros Linux live.", Kind.RULES,
            targets = listOf(image),
            fields = listOf(Field("backend", "Arquivo(s) .dat", FieldType.PATH_LIST, required = true), autosel, timeout)),
        Plugin("injection", "Injeção de arquivos", "Arquivo .7z/.tar injetado no sistema iniciado.", Kind.RULES,
            targets = listOf(image, parent), fields = listOf(Field("archive", "Arquivo", FieldType.PATH, required = true))),
        Plugin("conf_replace", "Substituir configuração", "Troca um arquivo de configuração dentro da ISO durante o boot.", Kind.RULES,
            targets = listOf(Field("iso", "Imagem", FieldType.PATH)),
            fields = listOf(
                Field("org", "Arquivo original (dentro da ISO)", FieldType.STRING, required = true),
                Field("new", "Arquivo novo (no pendrive)", FieldType.PATH, required = true),
                Field("img", "Arquivo novo é imagem", FieldType.INT, "1 quando o arquivo novo é uma imagem."),
            )),
        Plugin("dud", "Driver Update Disk", "Discos de atualização de drivers (RHEL e similares).", Kind.RULES,
            targets = listOf(image), fields = listOf(Field("dud", "Arquivo(s)", FieldType.PATH_LIST, required = true))),
        Plugin("custom_boot", "Boot personalizado", "Arquivo .vcfg com entrada de menu personalizada.", Kind.RULES,
            targets = listOf(Field("file", "Imagem", FieldType.PATH), dir),
            fields = listOf(Field("vcfg", "Arquivo .vcfg", FieldType.PATH, required = true))),
        Plugin("auto_memdisk", "Memdisk automático", "Imagens carregadas inteiras na memória.", Kind.STRING_LIST),
        Plugin("image_list", "Lista branca", "SOMENTE estas imagens aparecem no menu.", Kind.STRING_LIST),
        Plugin("image_blacklist", "Lista negra", "Estas imagens NÃO aparecem no menu.", Kind.STRING_LIST),
        Plugin("password", "Senhas", "Senhas para o menu ou imagens. Formatos: txt#senha, md5#hash ou md5#sal#hash.", Kind.PASSWORD,
            fields = listOf(Field("bootpwd", "Senha do menu (boot)", FieldType.PASSWORD)) +
                listOf("iso", "wim", "efi", "img", "vhd", "vtoy").map { Field("${it}pwd", "Senha para todos .$it", FieldType.PASSWORD) },
            targets = listOf(Field("file", "Imagem", FieldType.PATH), parent)),
    )

    fun plugin(key: String): Plugin? = PLUGINS.firstOrNull { it.key == key }

    /** Separa "theme_uefi" em ("theme", "uefi"). Retorna null se o plugin não existir. */
    fun parseKey(key: String): Pair<Plugin, String?>? {
        plugin(key)?.let { return it to null }
        val idx = key.lastIndexOf('_')
        if (idx > 0) {
            val base = key.substring(0, idx)
            val suffix = key.substring(idx + 1)
            if (suffix in ARCH_SUFFIXES) plugin(base)?.let { return it to suffix }
        }
        return null
    }
}
