package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Die vier Gespraechszustaende, die HudView zeichnen kann - siehe
 * PLAN-JARVIS-HUD-GESICHT.md fuer die urspruengliche Bedeutung jedes
 * Zustands und wo MainActivity sie auswaehlt.
 */
enum class HudZustand { RUHT, HOERT_ZU, DENKT_NACH, ANTWORTET }

/**
 * Ein Iron-Man/JARVIS-artiges HUD: Ring mit Tick-Marken (unveraendert seit
 * PLAN-JARVIS-HUD-GESICHT.md/PLAN-JARVIS-HUD-KERN-UND-RAND.md) und einem
 * flachen, pulsierenden Bogen im Kern, der je nach HudZustand
 * unterschiedlich animiert. Der Kern war bis 23.08.2026 eine rotierende
 * Ikosaeder-Dreiecksnetz-Kugel - im Magenta-Redesign (siehe
 * PLAN-JARVIS-APP-REDESIGN.md) durch einen flacheren Bogen mit
 * Zustandstext ersetzt, angelehnt an das Referenzbild aus dem
 * Brainstorming. Die Chrome (Aussenring/Tick-Marken) und das
 * Zeit-/Choreographer-Geruest sind UNVERAENDERT aus der Vorgaengerfassung
 * uebernommen.
 *
 * Geometrie ist auf eine 240x240-Referenzflaeche bezogen und wird in
 * onDraw() auf die tatsaechliche View-Groesse skaliert.
 *
 * Die Animationsphase kommt aus der VERSTRICHENEN ZEIT
 * (SystemClock.uptimeMillis() - startZeit), nicht aus einem schleifenden
 * 0..1-Animationswert - so kann jeder Zustand seine eigene Geschwindigkeit
 * haben, ohne beim Schleifenende sichtbar zu "springen".
 *
 * Neuzeichnen laeuft ueber Choreographer statt ValueAnimator (Fund aus dem
 * Abschluss-Review vom 14.08.2026): ein ValueAnimator wird von
 * Settings.Global.ANIMATOR_DURATION_SCALE beeinflusst - bei Skala 0 haette
 * der Ring einfach eingefroren, ohne auf setZustand() zu reagieren.
 * Choreographer ist davon unabhaengig. Ueber onVisibilityAggregated()
 * an-/abgeschaltet, damit im Hintergrund kein Akku fuer unsichtbare Frames
 * verbraucht wird.
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val REF = 240f
        private const val MITTE = REF / 2f
        private const val FARBE = 0xFFE619B0.toInt()
        private const val UEBERGANG_MS = 280L
        private const val AUSSENRAND_RADIUS = 112f
        private const val BOGEN_RADIUS = 70f
        private const val BOGEN_STRICHBREITE = 10f

        /** Reine Rechenfunktion ohne Android-Abhaengigkeit, deshalb per
         *  JVM-Unit-Test pruefbar (siehe HudViewPhaseTest.kt). */
        internal fun phaseVon(vergangenMs: Long, periodeMs: Long): Float =
            (vergangenMs % periodeMs).toFloat() / periodeMs

        /** Puls-Faktor um 1 herum (1-staerke bis 1+staerke) fuer das
         *  "Atmen" des Bogens - unveraendert aus der Kugel-Fassung
         *  uebernommen (siehe HudViewBogenTest.kt). */
        internal fun pulsFaktor(vergangenMs: Long, pulsTempo: Float, pulsStaerke: Float): Float {
            val sekunden = vergangenMs / 1000f
            return 1f + sin(sekunden * pulsTempo * 2f * PI.toFloat()) * pulsStaerke
        }

        /** Rechnet den tatsaechlichen Sweep-Winkel (Grad) aus der
         *  Grund-Bogenbreite eines Zustands und dem Puls-Faktor - reine
         *  Rechenfunktion, JVM-testbar wie phaseVon()/pulsFaktor() (siehe
         *  HudViewBogenTest.kt). Auf [0, 360] begrenzt, falls ein
         *  Puls-Faktor > 1 die Basis ueber eine volle Umdrehung triebe. */
        internal fun bogenSweepGrad(basisGrad: Float, puls: Float): Float =
            (basisGrad * puls).coerceIn(0f, 360f)

        /** Richtwerte je Zustand fuer den Kern-Bogen - Umlaufdauer,
         *  Grund-Bogenbreite, Puls-Staerke/-Tempo, Basis-Deckkraft. */
        internal data class BogenZustandsWerte(
            val periodeMs: Long,
            val basisSweepGrad: Float,
            val pulsStaerke: Float,
            val pulsTempo: Float,
            val deckkraft: Float,
        )

        private val BOGEN_WERTE = mapOf(
            HudZustand.RUHT to BogenZustandsWerte(5200L, 300f, 0.06f, 1.0f, 0.55f),
            HudZustand.HOERT_ZU to BogenZustandsWerte(2600L, 110f, 0.10f, 2.4f, 0.85f),
            HudZustand.DENKT_NACH to BogenZustandsWerte(1400L, 70f, 0.05f, 1.6f, 0.95f),
            HudZustand.ANTWORTET to BogenZustandsWerte(1900L, 200f, 0.13f, 2.0f, 1.0f),
        )

        private val ZUSTANDSTEXT = mapOf(
            HudZustand.RUHT to "Ruht",
            HudZustand.HOERT_ZU to "Hört zu",
            HudZustand.DENKT_NACH to "Denkt nach",
            HudZustand.ANTWORTET to "Antwortet",
        )
    }

    private var zustand = HudZustand.RUHT
    private var vorherigerZustand = HudZustand.RUHT
    private val startZeit = SystemClock.uptimeMillis()
    private var uebergangStart = 0L

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = FARBE
        strokeCap = Paint.Cap.ROUND
    }

    private val zustandsFont: Typeface? = ResourcesCompat.getFont(context, R.font.rajdhani_bold)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FARBE
        textAlign = Paint.Align.CENTER
        textSize = 16f
        typeface = zustandsFont ?: Typeface.DEFAULT_BOLD
    }

    // --- Wiederverwendete Zeichen-Objekte -----------------------------

    private val gestrichelterPfad = Path().apply {
        addCircle(MITTE, MITTE, 108f, Path.Direction.CW)
    }
    private val dashEffect = DashPathEffect(floatArrayOf(2f, 10f), 0f)
    private val bogenRect = RectF(
        MITTE - BOGEN_RADIUS, MITTE - BOGEN_RADIUS,
        MITTE + BOGEN_RADIUS, MITTE + BOGEN_RADIUS,
    )

    // --- Zeit-getriebene Neuzeichnung -----------------------------------

    private var laeuft = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            if (laeuft) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (!laeuft) {
                laeuft = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        } else {
            laeuft = false
        }
    }

    /** Wechselt den angezeigten Zustand mit kurzem Ueberblenden statt
     *  hartem Sprung. Muss vom UI-Thread aus aufgerufen werden. */
    fun setZustand(neu: HudZustand) {
        if (neu == zustand) return
        vorherigerZustand = zustand
        zustand = neu
        uebergangStart = SystemClock.uptimeMillis()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        laeuft = false
    }

    private fun phase(periodeMs: Long): Float {
        val vergangen = SystemClock.uptimeMillis() - startZeit
        return phaseVon(vergangen, periodeMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val kante = min(width, height).toFloat()
        if (kante <= 0f) return
        val skala = kante / REF
        canvas.save()
        canvas.translate((width - kante) / 2f, (height - kante) / 2f)
        canvas.scale(skala, skala)

        zeichneChrome(canvas)

        val seitUebergang = SystemClock.uptimeMillis() - uebergangStart
        if (seitUebergang < UEBERGANG_MS) {
            val anteilNeu = seitUebergang.toFloat() / UEBERGANG_MS
            zeichneZustand(canvas, vorherigerZustand, 1f - anteilNeu)
            zeichneZustand(canvas, zustand, anteilNeu)
            zeichneText(canvas, vorherigerZustand, 1f - anteilNeu)
            zeichneText(canvas, zustand, anteilNeu)
        } else {
            zeichneZustand(canvas, zustand, 1f)
            zeichneText(canvas, zustand, 1f)
        }

        canvas.restore()
    }

    /** Chrome, die in allen vier Zustaenden gleich aussieht: glatter
     *  Aussenrand, gedrehter gestrichelter Aussenring, Basis-Ring,
     *  Tick-Marken. Unveraendert aus der Vorgaengerfassung, nur FARBE ist
     *  jetzt Magenta. */
    private fun zeichneChrome(canvas: Canvas) {
        ringPaint.pathEffect = null
        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 255
        canvas.drawCircle(MITTE, MITTE, AUSSENRAND_RADIUS, ringPaint)

        // WICHTIG: als Path statt drawCircle gezeichnet - Android
        // ignoriert PathEffect (Strichelung) bei drawCircle/drawOval unter
        // Hardware-Beschleunigung, bei drawPath aber nicht.
        ringPaint.strokeWidth = 1f
        ringPaint.alpha = (255 * 0.5f).toInt()
        ringPaint.pathEffect = dashEffect
        canvas.save()
        canvas.rotate(12f, MITTE, MITTE)
        canvas.drawPath(gestrichelterPfad, ringPaint)
        canvas.restore()
        ringPaint.pathEffect = null

        ringPaint.strokeWidth = 0.75f
        ringPaint.alpha = (255 * 0.35f).toInt()
        canvas.drawCircle(MITTE, MITTE, 98f, ringPaint)

        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 255
        for (i in 0 until 8) {
            val winkel = Math.toRadians((i * 45).toDouble())
            val innen = 88f
            val aussen = 98f
            val x1 = MITTE + innen * cos(winkel).toFloat()
            val y1 = MITTE + innen * sin(winkel).toFloat()
            val x2 = MITTE + aussen * cos(winkel).toFloat()
            val y2 = MITTE + aussen * sin(winkel).toFloat()
            canvas.drawLine(x1, y1, x2, y2, ringPaint)
        }
    }

    /** Zeichnet den Puls-Bogen im Kern mit der gegebenen Deckkraft -
     *  ersetzt seit 23.08.2026 die Ikosaeder-Kugel (siehe
     *  PLAN-JARVIS-APP-REDESIGN.md). Start-Winkel kommt aus der
     *  Zeit-Phase, Sweep-Breite aus bogenSweepGrad(). */
    private fun zeichneZustand(canvas: Canvas, z: HudZustand, deckkraft: Float) {
        if (deckkraft <= 0f) return
        val werte = BOGEN_WERTE.getValue(z)
        val vergangenMs = SystemClock.uptimeMillis() - startZeit
        val startGrad = phase(werte.periodeMs) * 360f
        val puls = pulsFaktor(vergangenMs, werte.pulsTempo, werte.pulsStaerke)
        val sweepGrad = bogenSweepGrad(werte.basisSweepGrad, puls)

        ringPaint.strokeWidth = BOGEN_STRICHBREITE
        ringPaint.pathEffect = null
        ringPaint.alpha = (255 * werte.deckkraft * deckkraft).toInt().coerceIn(0, 255)
        canvas.drawArc(bogenRect, startGrad, sweepGrad, false, ringPaint)
    }

    /** Zustandstext in der Mitte des Rings - neu seit 23.08.2026, angelehnt
     *  an die Textanzeige im Referenzbild aus dem Redesign-Brainstorming
     *  (siehe PLAN-JARVIS-APP-REDESIGN.md). Ueberblendet parallel zum
     *  Bogen. */
    private fun zeichneText(canvas: Canvas, z: HudZustand, deckkraft: Float) {
        if (deckkraft <= 0f) return
        textPaint.alpha = (255 * deckkraft).toInt().coerceIn(0, 255)
        canvas.drawText(ZUSTANDSTEXT.getValue(z), MITTE, MITTE + 5f, textPaint)
    }
}
