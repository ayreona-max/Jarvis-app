plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fester Signatur-Schluessel: Der Cloud-Build bekommt ihn als GitHub-Secret
// (Umgebungsvariablen). Damit ist jede APK identisch signiert und Updates
// laufen OHNE Deinstallieren durch. Fehlen die Variablen (z. B. lokaler
// Build), wird ganz normal mit dem Standard-Debug-Key signiert.
val stableKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26          // Android 8.0 - deckt Doreens Galaxy locker ab
        targetSdk = 34
        versionCode = 31
        versionName = "0.31"
    }

    // Ab AGP 8 wird BuildConfig nicht mehr automatisch erzeugt. Wir brauchen
    // es, um die Versionsnummer in der Statuszeile anzuzeigen.
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("stable") {
            if (stableKeystorePath != null) {
                storeFile = file(stableKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = "jarvis"
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            if (stableKeystorePath != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // OkHttp fuer den multipart-Upload (Audio-Datei + Formfelder an
    // /assistant). Sehr etabliert, keine Versionskonflikte mit AGP 8.5.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Liest die EXIF-Ausrichtung von Kamerafotos - beim Herunterskalieren
    // geht das EXIF sonst verloren und Etiketten/Dokumente kaemen um 90
    // Grad gedreht bei der Vision-Auswertung an.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // TensorFlow Lite: fuehrt die openWakeWord-Modelle ("Hey Jarvis") lokal
    // aus - Ersatz fuer Porcupine, dessen kostenloses Konto Picovoice zum
    // 30.06.2026 abgeschafft hat. Kein Konto, kein AccessKey, kein Ton
    // verlaesst das Handy, bis das Weckwort erkannt wurde.
    // 2.16.1 statt 2.14.0: behebt "Didn't find op for builtin opcode ...
    // version ..."-Faelle (aeltere Runtimes kennen neuere Op-VERSIONEN
    // nicht - Hauptverdacht beim "FEHLER beim Laden der Erkennung" aus
    // dem ersten v0.7-Test).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // JUnit nur fuer den Format-Test der Verschluesselung
    // (KryptoFormatTest): Der Laptop hat keine Java-Werkzeugkette, Kotlin
    // laesst sich also nur hier im Cloud-Build ueberhaupt ausfuehren. Der
    // Test prueft gegen ein Chiffrat aus crypto_utils.py, dass beide Seiten
    // dasselbe Format sprechen - BEVOR eine APK entsteht.
    testImplementation("junit:junit:4.13.2")
}


