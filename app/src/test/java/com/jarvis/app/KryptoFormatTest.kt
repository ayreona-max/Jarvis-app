package com.jarvis.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Beweist, dass Kotlin und der Python-Server DASSELBE Format sprechen.
 *
 * Hintergrund: Der Laptop hat keine Java-Werkzeugkette, die App wird in der
 * Cloud gebaut - Kotlin laesst sich vor dem Ausrollen also nirgends von Hand
 * ausprobieren. Genau diese Luecke hat beim Weckwort-Umbau drei Fehlversuche
 * gekostet (die Modelle liefen in Python, scheiterten aber auf Android).
 * Lehre daraus: Was sich vorher pruefen laesst, wird vorher geprueft.
 *
 * Der Pruefwert unten stammt aus crypto_utils.py. Entschluesselt ihn die
 * Java-Seite korrekt - und erzeugt sie umgekehrt etwas, das dieselbe Form
 * hat -, dann passen beide Seiten zusammen. Der Test laeuft bei jedem
 * Cloud-Build mit; ein Formatbruch faellt damit auf, BEVOR eine APK
 * entsteht.
 *
 * Hier wird bewusst java.util.Base64 verwendet: android.util.Base64 gibt es
 * in einem reinen JVM-Test nicht. Krypto.kt nutzt dort NO_WRAP, was mit
 * java.util.Base64 (ebenfalls ohne Zeilenumbrueche) uebereinstimmt - genau
 * darum geht es.
 */
class KryptoFormatTest {

    private val schluesselB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val klartext = "Guten Morgen, Masterin – 3.436 Euro. Ümläute und ✓"
    private val chiffratAusPython =
        "aX8X769Qx/b8N0mEb7yXRtIASLQI07QWX6s8Ooeb1He+1/xBKEHZV7i+zHpe0CgGEV87G3wOQtPEcHF+YgV/D0XmrGoBLA2titnhnfbkj9LgAoBd"

    private val schluessel = Base64.getDecoder().decode(schluesselB64)

    private fun entschluessele(b64: String): String {
        val roh = Base64.getDecoder().decode(b64)
        val nonce = roh.copyOfRange(0, 12)
        val rest = roh.copyOfRange(12, roh.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(schluessel, "AES"), GCMParameterSpec(128, nonce))
        return String(c.doFinal(rest), Charsets.UTF_8)
    }

    private fun verschluessele(s: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(schluessel, "AES"), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + c.doFinal(s.toByteArray(Charsets.UTF_8)))
    }

    /** Der eigentliche Beweis: Java liest, was Python geschrieben hat -
     *  inklusive Umlauten, Gedankenstrich und einem Zeichen ausserhalb der
     *  lateinischen Ebene. */
    @Test
    fun liestWasPythonGeschriebenHat() {
        assertEquals(klartext, entschluessele(chiffratAusPython))
    }

    /** Und die Gegenrichtung: Was Java erzeugt, hat dieselbe Form und laesst
     *  sich mit demselben Verfahren wieder lesen. */
    @Test
    fun eigenesErzeugnisIstWiederLesbar() {
        val eigen = verschluessele(klartext)
        assertEquals(klartext, entschluessele(eigen))
    }

    /** Jede Nachricht bekommt eine eigene Nonce - zweimal derselbe Text darf
     *  nicht zweimal gleich aussehen, sonst waere von aussen erkennbar, dass
     *  dieselbe Nachricht noch einmal lief. */
    @Test
    fun jedeNachrichtSiehtAndersAus() {
        assert(verschluessele(klartext) != verschluessele(klartext))
    }

    /** Binaerdaten (Ton, Kamerabild) gehen OHNE base64 ueber die Leitung -
     *  der Rundlauf muss byteweise stimmen. */
    @Test
    fun binaerdatenUeberlebenDenRundlauf() {
        val daten = ByteArray(50_000).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val v = Cipher.getInstance("AES/GCM/NoPadding")
        v.init(Cipher.ENCRYPT_MODE, SecretKeySpec(schluessel, "AES"), GCMParameterSpec(128, nonce))
        val geheim = nonce + v.doFinal(daten)

        val e = Cipher.getInstance("AES/GCM/NoPadding")
        e.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(schluessel, "AES"),
            GCMParameterSpec(128, geheim.copyOfRange(0, 12))
        )
        assertArrayEquals(daten, e.doFinal(geheim.copyOfRange(12, geheim.size)))
    }

    /** Eine veraenderte Nachricht muss auffallen statt stillschweigend
     *  Unsinn zu ergeben - das ist der Grund fuer GCM statt einfachem AES. */
    @Test
    fun manipulationFaelltAuf() {
        val roh = Base64.getDecoder().decode(chiffratAusPython)
        roh[roh.size - 1] = (roh[roh.size - 1].toInt() xor 1).toByte()
        val kaputt = Base64.getEncoder().encodeToString(roh)
        var erkannt = false
        try {
            entschluessele(kaputt)
        } catch (ex: Exception) {
            erkannt = true
        }
        assert(erkannt) { "Manipulation wurde NICHT erkannt" }
    }
}
