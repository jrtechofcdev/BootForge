# libaums usa reflexão leve na fábrica de comunicação USB
-keep class me.jahnen.libaums.** { *; }
# XZ for Java
-dontwarn org.tukaani.xz.**
# BouncyCastle (assinaturas OpenPGP do catálogo): o provedor JCA registra os algoritmos
# pelo NOME da classe, então essas classes precisam ser mantidas.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
