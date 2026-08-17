// Wurzel-Buildskript: legt nur die Plugin-Versionen fest, die das
// app-Modul dann anwendet. AGP 8.9.1 <-> Gradle 8.11.1 <-> Kotlin 1.9.24 -
// AGP 8.5.2 (urspruenglich fuer den ersten Cloud-Build gewaehlt) war zu alt
// fuer androidx.health.connect:connect-client:1.1.0, das compileSdk >= 36
// und AGP >= 8.9.1 verlangt (erster echter Cloud-Build 17.08.2026 schlug
// deshalb fehl - siehe compileSdk-Kommentar in app/build.gradle.kts).
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
