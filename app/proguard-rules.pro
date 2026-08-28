# Release hardening (fix: "R8/minification is configured appropriately").
#
# The SecuGen FDx SDK Pro (app/libs/FDxSDKProFDAndroid.jar) and AlCamera.jar
# are closed-source native-backed SDKs that use JNI callbacks and reflection
# to reach into their own Java classes. R8 can't see those call sites, so
# without explicit keeps it will strip/rename classes the native side expects
# to find by exact name - which fails silently at runtime (usually as a
# scanner that mysteriously stops initializing) rather than a build error.
-keep class com.secugen.** { *; }
-keepclassmembers class com.secugen.** { *; }
-dontwarn com.secugen.**

# Room generates code at compile time that reflectively instantiates entities
# and DAOs; keep annotated classes intact.
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# org.json is part of the Android platform API surface - never strip it.
-keep class org.json.** { *; }

# Kotlin coroutines / Compose runtime metadata some tooling reads reflectively.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep this app's own data model classes intact: BackupManager/SyncManager
# serialize Member fields by name into JSON, and Room maps entity fields to
# columns by name - obfuscating either would silently corrupt backups, sync,
# and the database schema instead of failing loudly.
-keep class com.majorgym.app.data.Member { *; }
-keep class com.majorgym.app.data.HistoryEntry { *; }
