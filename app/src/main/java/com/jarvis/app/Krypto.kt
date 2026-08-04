package com.jarvis.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gegenstueck zu crypto_utils.py auf dem Server: AES-256-GCM.
 *
 * WARUM: main.py haengt am oeffentlich getunnelten ngrok-Port. ngrok
 * terminiert TLS an SEINEM Rand - zwischen Tunnel und Laptop laeuft der
 * Verkehr blank. Wer den Tunnel betreibt, koennte also mitlesen. Deshalb
 * werden die INHALTE selbst verschluesselt, unabhaengig vom Transportweg.
 *
 * GRENZE (mit Doreen so besprochen): "Ende zu Ende" heisst hier Handy <->
 * Laptop. Anthropic und ElevenLabs sehen die Inhalte weiterhin - dort findet
 * die Verarbeitung statt. Verborgen wird der Inhalt, nicht die Tatsache,
 * dass etwas gesendet wurde.
 *
 * FORMAT, exakt wie serverseitig:
 *   Text/JSON : base64( nonce[12] || ciphertext || tag[16] )
 *   Anhaenge  : dieselben Bytes OHNE base64 (ein 2-MB-Foto waere sonst
 *               2,7 MB - unnoetig ueber eine Mobilverbindung)
 *
 * Der Schluessel steht in den App-Einstellungen und ist BEWUSST ein anderer
 * als der Zugriffsschluessel: Der eine erlaubt das Anklopfen, dieser das
 * Mitlesen.
 */
object Krypto {

    private const val PREFS = "jarvis"
    const val FELD = "e2ekey"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    class KryptoFehler(nachricht: String) : Exception(nachricht)

    private fun schluessel(ctx: Context): ByteArray? {
        val roh = (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(FELD, "") ?: "").trim()
        if (roh.isEmpty()) return null
        return try {
            // NO_WRAP ist Pflicht: Androids DEFAULT setzt Zeilenumbrueche,
            // Pythons standard_b64decode stolpert darueber.
            val k = Base64.decode(roh, Base64.NO_WRAP)
            if (k.size != 32) null else k
        } catch (e: Exception) {
            null
        }
    }

    /** Ist ein brauchbarer Schluessel hinterlegt? Wenn nicht, laeuft alles
     *  unveraendert im Klartext - die Verschluesselung ist zuschaltbar. */
    fun aktiv(ctx: Context): Boolean = schluessel(ctx) != null

    fun verschluesselnRoh(ctx: Context, daten: ByteArray): ByteArray {
        val k = schluessel(ctx) ?: throw KryptoFehler("Kein E2E-Schlüssel hinterlegt.")
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + c.doFinal(daten)
    }

    fun entschluesselnRoh(ctx: Context, daten: ByteArray): ByteArray {
        val k = schluessel(ctx) ?: throw KryptoFehler("Kein E2E-Schlüssel hinterlegt.")
        if (daten.size <= NONCE_BYTES) throw KryptoFehler("Nachricht zu kurz.")
        val nonce = daten.copyOfRange(0, NONCE_BYTES)
        val rest = daten.copyOfRange(NONCE_BYTES, daten.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return try {
            c.doFinal(rest)
        } catch (e: Exception) {
            throw KryptoFehler("Entschlüsseln fehlgeschlagen – steht in der App derselbe Schlüssel wie auf dem Rechner?")
        }
    }

    fun verschluesselnText(ctx: Context, s: String): String =
        Base64.encodeToString(verschluesselnRoh(ctx, s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun entschluesselnText(ctx: Context, s: String): String =
        String(entschluesselnRoh(ctx, Base64.decode(s, Base64.NO_WRAP)), Charsets.UTF_8)

    /**
     * Packt eine Serverantwort aus. Der Server verpackt eine verschluesselte
     * Antwort als {"e2e":1,"data":"..."} - alles andere ist Klartext und
     * wird unveraendert durchgereicht. Damit versteht die App beide
     * Serverstaende, genau wie der Server beide App-Staende versteht.
     */
    fun auspacken(ctx: Context, js: JSONObject): JSONObject {
        if (js.optInt("e2e") != 1) return js
        return JSONObject(entschluesselnText(ctx, js.getString("data")))
    }
}
