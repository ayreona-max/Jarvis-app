package com.jarvis.app

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-Device-Weckwort-Erkennung "Hey Jarvis" ueber openWakeWord-Modelle
 * (github.com/dscripka/openWakeWord, Apache-2.0) - ERSETZT Porcupine, dessen
 * kostenloses Konto Picovoice zum 30.06.2026 abgeschafft hat. Kein Konto,
 * kein AccessKey, kein Ton verlaesst das Handy bis zum Weckwort.
 *
 * Pipeline pro 1280-Sample-Block (80 ms bei 16 kHz, 16-bit PCM) - am
 * 22.07.2026 lokal in Python gegen dieselben tflite-Dateien verifiziert
 * ("Hey Jarvis" Score 0,999 / Negativ-Satz 0,000):
 *   1. Rohpuffer: letzte 1760 Samples (1280 + 3*160 Fenster-Vorlauf)
 *   2. Mel-Modell [1,1760] -> [1,1,8,32], danach x/10+2 (openWakeWord-Norm)
 *   3. Ab 76 Mel-Frames: letzte 76 -> Embedding-Modell [1,76,32,1] -> 96er-Vektor
 *   4. Ab 16 Embeddings: letzte 16 -> Weckwort-Modell [1,16,96] -> Score 0..1
 */
class OpenWakeWord(context: Context) {

    companion object {
        const val BLOCK_SAMPLES = 1280          // 80 ms bei 16 kHz
        private const val RAW_LEN = 1760        // 1280 + 3*160
        private const val MEL_BINS = 32
        private const val MEL_FRAMES_PER_BLOCK = 8
        private const val MEL_WINDOW = 76
        private const val MEL_MAX = 970
        private const val EMB_DIM = 96
        private const val WAKE_FRAMES = 16
        private const val FEATURE_MAX = 120
    }

    private val melModell: Interpreter
    private val embeddingModell: Interpreter
    private val wakeModell: Interpreter

    // Rohpuffer (als float, wie das Mel-Modell es erwartet - int16-Wertebereich)
    private val roh = FloatArray(RAW_LEN)
    private var rohGefuellt = 0

    private val melFrames = ArrayDeque<FloatArray>()   // je 32 Werte
    private val features = ArrayDeque<FloatArray>()    // je 96 Werte

    // Wiederverwendete Puffer, damit pro 80-ms-Block nicht staendig
    // allokiert wird.
    private val melEin = Array(1) { FloatArray(RAW_LEN) }
    private val melAus = Array(1) { Array(1) { Array(MEL_FRAMES_PER_BLOCK) { FloatArray(MEL_BINS) } } }
    private val embEin = Array(1) { Array(MEL_WINDOW) { Array(MEL_BINS) { FloatArray(1) } } }
    private val embAus = Array(1) { Array(1) { Array(1) { FloatArray(EMB_DIM) } } }
    private val wakeEin = Array(1) { Array(WAKE_FRAMES) { FloatArray(EMB_DIM) } }
    private val wakeAus = Array(1) { FloatArray(1) }

    init {
        // XNNPACK AUS: Der standardmaessig zugeschaltete Beschleuniger
        // scheitert an der dynamischen Eingabegroesse des Mel-Modells
        // ("BytesRequired number of elements overflowed", CONV_2D failed
        // to prepare - live auf dem Galaxy aufgetreten, 22.07.2026). Die
        // Modelle sind winzig, normale CPU-Ausfuehrung reicht um
        // Groessenordnungen.
        val optionen = Interpreter.Options().setNumThreads(1).setUseXNNPACK(false)
        // WICHTIG: Die beiden Feature-Modelle in den Assets sind GEPATCHTE
        // Fassungen der openWakeWord-Originale - die Eingabeform ist fest
        // ins Modell geschrieben ([1,1760] bzw. [1,76,32,1]). Die Originale
        // tragen dynamische Dimensionen (-1), und die Android-Java-Runtime
        // bereitet das Modell schon im Konstruktor vor (anders als Python):
        // mit -1 laeuft die Groessenberechnung ueber ("BytesRequired number
        // of elements overflowed", live auf dem Galaxy, 22.07.2026). Ein
        // resizeInput() kaeme dafuer zu spaet. Patch-Skript und Verifikation
        // sind in CLAUDE.md des Orchestrator-Projekts dokumentiert.
        melModell = baue(context, "melspectrogram.tflite", optionen)
        embeddingModell = baue(context, "embedding_model.tflite", optionen)
        wakeModell = baue(context, "hey_jarvis_v0.1.tflite", optionen)
    }

    /** Baut einen Interpreter und benennt im Fehlerfall, WELCHES Modell
     *  gescheitert ist - ohne das ist eine Fehlermeldung vom Handy kaum
     *  zuzuordnen. */
    private fun baue(
        context: Context,
        name: String,
        optionen: Interpreter.Options,
        vorbereiten: (Interpreter) -> Unit = {},
    ): Interpreter {
        try {
            val it = Interpreter(ladeModell(context, name), optionen)
            vorbereiten(it)
            it.allocateTensors()
            return it
        } catch (t: Throwable) {
            throw RuntimeException("Modell $name: $t", t)
        }
    }

    /** Laedt ein Modell aus den Assets in einen direkten ByteBuffer -
     *  unabhaengig davon, ob das Asset komprimiert gepackt wurde. */
    private fun ladeModell(context: Context, name: String): ByteBuffer {
        val bytes = context.assets.open(name).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    /**
     * Verarbeitet einen 1280-Sample-Block und liefert den Weckwort-Score
     * (0..1). Waehrend der Anlaufphase (Puffer noch nicht gefuellt, ~2 s
     * nach Start/Reset) kommt 0 zurueck.
     */
    fun verarbeite(block: ShortArray): Float {
        // Rohpuffer nach links schieben, neuen Block hinten anhaengen
        System.arraycopy(roh, BLOCK_SAMPLES, roh, 0, RAW_LEN - BLOCK_SAMPLES)
        for (i in 0 until BLOCK_SAMPLES) {
            roh[RAW_LEN - BLOCK_SAMPLES + i] = block[i].toFloat()
        }
        if (rohGefuellt < RAW_LEN) {
            rohGefuellt += BLOCK_SAMPLES
            if (rohGefuellt < RAW_LEN) return 0f
        }

        // 1. Mel-Spektrogramm (8 neue Frames pro Block)
        System.arraycopy(roh, 0, melEin[0], 0, RAW_LEN)
        melModell.run(melEin, melAus)
        for (frame in melAus[0][0]) {
            val f = FloatArray(MEL_BINS)
            for (i in 0 until MEL_BINS) f[i] = frame[i] / 10f + 2f
            melFrames.addLast(f)
        }
        while (melFrames.size > MEL_MAX) melFrames.removeFirst()
        if (melFrames.size < MEL_WINDOW) return 0f

        // 2. Embedding ueber die letzten 76 Mel-Frames
        var ndx = melFrames.size - MEL_WINDOW
        for (i in 0 until MEL_WINDOW) {
            val frame = melFrames[ndx + i]
            for (j in 0 until MEL_BINS) embEin[0][i][j][0] = frame[j]
        }
        embeddingModell.run(embEin, embAus)
        features.addLast(embAus[0][0][0].copyOf())
        while (features.size > FEATURE_MAX) features.removeFirst()
        if (features.size < WAKE_FRAMES) return 0f

        // 3. Weckwort-Score ueber die letzten 16 Embeddings
        ndx = features.size - WAKE_FRAMES
        for (i in 0 until WAKE_FRAMES) {
            System.arraycopy(features[ndx + i], 0, wakeEin[0][i], 0, EMB_DIM)
        }
        wakeModell.run(wakeEin, wakeAus)
        return wakeAus[0][0]
    }

    /** Leert alle Puffer - nach einer Frage-/Antwort-Runde aufrufen, damit
     *  Reste der eigenen Aufnahme keinen Fehlalarm ausloesen. */
    fun reset() {
        roh.fill(0f)
        rohGefuellt = 0
        melFrames.clear()
        features.clear()
    }

    fun schliessen() {
        try { melModell.close() } catch (_: Exception) {}
        try { embeddingModell.close() } catch (_: Exception) {}
        try { wakeModell.close() } catch (_: Exception) {}
    }
}
