# Media3, Guava and kotlinx-serialization all ship consumer rules; these cover the
# reflection-sensitive seams that remain.

# kotlinx-serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.noiz.**$$serializer { *; }
-keepclassmembers class com.noiz.** { *** Companion; }
-keepclasseswithmembers class com.noiz.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit interface methods with generics
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- manifest-instantiated components --------------------------------------
# Both services are created by the framework from a manifest string, so R8 cannot see
# the reference. Without these they were stripped from the release build and playback /
# downloads failed with a ClassNotFoundException at bind time.

-keep class com.noiz.playback.PlaybackService { *; }
-keep class com.noiz.download.NoizDownloadService { *; }
-keep class com.noiz.app.NoizApplication { *; }
-keep class com.noiz.app.MainActivity { *; }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Hilt generates these; the reference is only ever from generated code.
-keep class com.noiz.data.OutboxWorker { *; }
-keep class com.noiz.data.CatalogSyncWorker { *; }
-keep class com.noiz.data.UploadWorker { *; }
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltWorkerFactory { *; }

# Room entities are instantiated by generated code that R8 cannot always trace.
-keep class com.noiz.database.** { *; }

# kotlinx.serialization keeps working above, but the DTO classes themselves need
# their generated serializers retained through obfuscation.
-keep class com.noiz.network.** { *; }
-keep class com.noiz.model.** { *; }
