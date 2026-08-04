# Jarvis App

Mobile Begleit-App fuer den lokalen Jarvis-Orchestrator – Sprache und Kamera
fuer unterwegs, spaeter mit Wake-Word "Hey Jarvis".

Die App enthaelt **keine Geheimnisse**: Sie spricht nur den `/assistant`-
Endpunkt des Orchestrators an. Server-URL und Zugangsschluessel werden in der
App selbst eingetragen, nichts davon liegt im Code.

## Bau

Die APK wird automatisch per GitHub Actions gebaut (`.github/workflows/build.yml`)
– kein lokales Android Studio noetig. Nach jedem Push auf `main` liegt die
fertige Debug-APK unter **Actions -> letzter Lauf -> Artifacts**.

## Stand

- **Schicht 0 (aktuell):** Textnachricht an Jarvis, Antwort anzeigen –
  beweist die Kette Handy -> Orchestrator -> Antwort.
- Geplant: Sprachaufnahme, Kamera ("auf Go"), Wake-Word "Hey Jarvis".
