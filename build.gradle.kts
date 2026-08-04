// Wurzel-Buildskript: legt nur die Plugin-Versionen fest, die das
// app-Modul dann anwendet. Bewusst konservative, gut zusammenpassende
// Versionen (AGP 8.5.2 <-> Gradle 8.9 <-> Kotlin 1.9.24) fuer einen
// zuverlaessigen ersten Cloud-Build.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
