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
    // 36 statt 34: androidx.health.connect:connect-client:1.1.0 (siehe
    // dependencies unten) verlangt compileSdk >= 36 und AGP >= 8.9.1 (siehe
    // build.gradle.kts) - erster echter Cloud-Build 17.08.2026 schlug mit
    // 34/8.5.2 fehl.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26          // Android 8.0 - deckt das Zielgeraet locker ab
        targetSdk = 34
        versionCode = 39
        versionName = "0.39"
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
    // AndroidX Material Components - einzige neue Abhaengigkeit im
    // Magenta-Redesign (23.08.2026, siehe PLAN-JARVIS-APP-REDESIGN.md):
    // liefert MaterialCardView mit per-Ecke abschneidbaren Formen
    // (CornerFamily.CUT), die ein reines <shape>-Drawable nicht kann.
    implementation("com.google.android.material:material:1.12.0")
    // App-Sperre: Fingerabdruck oder Geraete-PIN beim Oeffnen, unabhaengig
    // von der Handy-Entsperrung. Schuetzt vor kurzem Zugriff aufs entsperrte
    // Handy (04.08.2026, Franks Wunsch).
    implementation("androidx.biometric:biometric:1.1.0")
    // OkHttp fuer den multipart-Upload (Audio-Datei + Formfelder an
    // /assistant). Sehr etabliert, keine Versionskonflikte mit AGP 8.5.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Liest die EXIF-Ausrichtung von Kamerafotos - beim Herunterskalieren
    // geht das EXIF sonst verloren und Etiketten/Dokumente kaemen um 90
    // Grad gedreht bei der Vision-Auswertung an.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // WorkManager: wiederkehrender Hintergrundabruf des Postfachs (alle 15
    // Min.), UNABHAENGIG davon, ob der Weckwort-Dienst gerade laeuft - Frank
    // laesst "Hey Jarvis" nicht durchgehend an, ohne das kamen zeitgebundene
    // Nachrichten (z. B. das 5:30-Uhr-Wetter-Briefing) erst an, wenn er die
    // App zufaellig als naechstes geoeffnet hat (14.08.2026, siehe
    // PLAN-POSTFACH-HINTERGRUNDABRUF.md). Kein Firebase/Push noetig.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Health Connect: liest Fitness-/Gesundheitsdaten, die Garmin Connect
    // dort hinterlegt (Ersatz fuer Strava und Google Fit, siehe
    // docs/superpowers/specs/2026-08-17-fitness-dashboard-design.md - beide
    // sind keine gangbaren Wege mehr).
    implementation("androidx.health.connect:connect-client:1.1.0")
    // lifecycleScope fuer die asynchrone Berechtigungspruefung in MainActivity.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
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
    // Echte org.json-Implementierung NUR fuer die Unit-Tests: im
    // testDebugUnitTest-Klassenpfad liegt android.jar nur als Stub, dessen
    // org.json-Methoden absichtlich RuntimeException("Stub!") werfen. Ohne
    // das scheitert FitnessAggregationTest.baueSyncPayloadRundetAuf... (der
    // einzige Test, der org.json anfasst - die anderen pruefen reine
    // LocalDate-/Rechenlogik). Aendert nichts am App-Verhalten: auf dem
    // Handy liefert Android selbst die echte org.json-Implementierung.
    testImplementation("org.json:json:20240303")
}


