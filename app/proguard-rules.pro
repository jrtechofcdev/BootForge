# libaums usa reflexão leve na fábrica de comunicação USB
-keep class me.jahnen.libaums.** { *; }
# XZ for Java
-dontwarn org.tukaani.xz.**
# BouncyCastle (assinaturas OpenPGP do catálogo): o provedor JCA registra os algoritmos
# pelo NOME da classe, então essas classes precisam ser mantidas.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Não renomeia classes/pacotes (o código não usado continua sendo removido):
#  - recursos carregados pelo nome do pacote continuam funcionando;
#  - os relatórios de log mostram nomes reais (dev.bootforge.…) em vez de "A1.a.b".
-dontobfuscate
