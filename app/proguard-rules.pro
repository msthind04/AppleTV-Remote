# BouncyCastle's lightweight API is reached only through our own wrappers, but
# it registers engines reflectively in places; keep the crypto engines we use.
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**

# jmDNS is desktop-only and never reaches the app, but it can appear on the
# classpath transitively.
-dontwarn javax.jmdns.**

# Protocol model classes are constructed from decoded wire data.
-keep class dev.atvremote.protocol.hap.Credentials { *; }
