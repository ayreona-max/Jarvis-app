package com.jarvis.app

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Die vier Gespraechszustaende, die HudView zeichnen kann - siehe
 * PLAN-JARVIS-HUD-GESICHT.md fuer die Bedeutung jedes Zustands und wo
 * MainActivity sie auswaehlt.
 */
enum class HudZustand { RUHT, HOERT_ZU, DENKT_NACH, ANTWORTET }

/**
 * Ein Iron-Man/JARVIS-artiges HUD: Ring mit Tick-Marken und Ecken-
 * Klammern, das je nach HudZustand unterschiedlich animiert. Zeichnet
 * sich komplett selbst per Canvas - keine Bilddateien, keine
 * Animationsbibliothek (14.08.2026, siehe PLAN-JARVIS-HUD-GESICHT.md,
 * Abschnitt "Architektur").
 *
 * Geometrie ist auf eine 240x240-Referenzflaeche bezogen (wie im
 * Mockup aus dem Brainstorming) und wird in onDraw() auf die
 * tatsaechliche View-Groesse skaliert.
 *
 * Die Animationsphase kommt aus der VERSTRICHENEN ZEIT
 * (SystemClock.uptimeMillis() - startZeit), nicht aus einem
 * schleifenden 0..1-Animationswert - so kann jeder Zustand seine
 * eigene Geschwindigkeit haben, ohne beim Schleifenende sichtbar zu
 * "springen" (ein Animator mit fester Dauer und mehreren Frequenzen
 * darin haette genau dieses Problem gehabt).
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val REF = 240f
        private const val FARBE = 0xFF378ADD.toInt()
        private const val PERIODE_RUHT_MS = 3600L
        private const val PERIODE_HOERT_ZU_MS = 500L
        private const val PERIODE_DENKT_NACH_MS = 1200L
        private const val PERIODE_ANTWORTET_MS = 600L
        private const val UEBERGANG_MS = 280L
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
    private val fuellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = FARBE
    }

    // Treibt nur das Neuzeichnen an (60fps-Takt) - der animierte Wert
    // selbst wird nicht benutzt, die eigentliche Phase kommt aus
    // phase(), siehe Klassenkommentar oben.
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    init {
        ticker.start()
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
        ticker.cancel()
    }

    /** Fortlaufende Phase 0f..1f fuer eine gegebene Zykluslaenge - beginnt
     *  nicht bei jedem Aufruf neu, deshalb kein Sprung beim
     *  Schleifenende (siehe Klassenkommentar). */
    private fun phase(periodeMs: Long): Float {
        val vergangen = SystemClock.uptimeMillis() - startZeit
        return (vergangen % periodeMs).toFloat() / periodeMs
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
        } else {
            zeichneZustand(canvas, zustand, 1f)
        }

        canvas.restore()
    }

    /** Chrome, die in allen vier Zustaenden gleich aussieht: Ecken-
     *  Klammern, gedrehter gestrichelter Aussenring, Basis-Ring,
     *  Tick-Marken. */
    private fun zeichneChrome(canvas: Canvas) {
        val mitte = REF / 2f

        ringPaint.pathEffect = null
        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 255
        val laenge = 20f
        val abstand = 14f
        // Vier Ecken-Klammern (L-Form) wie ein Kamera-Sucher/Fadenkreuz.
        // Jeder Eintrag: Eckpunkt (ex, ey), Richtung der beiden Schenkel.
        val ecken = listOf(
            Triple(abstand, abstand, Pair(1f, 1f)),
            Triple(REF - abstand, abstand, Pair(-1f, 1f)),
            Triple(REF - abstand, REF - abstand, Pair(-1f, -1f)),
            Triple(abstand, REF - abstand, Pair(1f, -1f)),
        )
        for ((ex, ey, richtung) in ecken) {
            val (dx, dy) = richtung
            canvas.drawLine(ex, ey, ex + dx * laenge, ey, ringPaint)
            canvas.drawLine(ex, ey, ex, ey + dy * laenge, ringPaint)
        }

        // Aeusserer gestrichelter, leicht gedrehter Ring - deutet eine
        // rotierende aeussere Schicht an (bewusst NICHT selbst animiert,
        // siehe Design). WICHTIG: als Path statt drawCircle gezeichnet -
        // Android ignoriert PathEffect (Strichelung) bei drawCircle/
        // drawOval unter Hardware-Beschleunigung, bei drawPath aber
        // nicht (bekannte Android-Einschraenkung).
        ringPaint.strokeWidth = 1f
        ringPaint.alpha = (255 * 0.5f).toInt()
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(2f, 10f), 0f)
        canvas.save()
        canvas.rotate(12f, mitte, mitte)
        val gestrichelterPfad = Path().apply {
            addCircle(mitte, mitte, 108f, Path.Direction.CW)
        }
        canvas.drawPath(gestrichelterPfad, ringPaint)
        canvas.restore()
        ringPaint.pathEffect = null

        // Basis-Ring
        ringPaint.strokeWidth = 0.75f
        ringPaint.alpha = (255 * 0.35f).toInt()
        canvas.drawCircle(mitte, mitte, 98f, ringPaint)

        // Acht Tick-Marken (Kompass-Striche)
        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 255
        for (i in 0 until 8) {
            val winkel = Math.toRadians((i * 45).toDouble())
            val innen = 88f
            val aussen = 98f
            val x1 = mitte + innen * cos(winkel).toFloat()
            val y1 = mitte + innen * sin(winkel).toFloat()
            val x2 = mitte + aussen * cos(winkel).toFloat()
            val y2 = mitte + aussen * sin(winkel).toFloat()
            canvas.drawLine(x1, y1, x2, y2, ringPaint)
        }
    }

    /** Zeichnet die zustandsabhaengigen Elemente (Segmente/Balken/Punkt)
     *  mit der gegebenen Deckkraft - deckkraft < 1 waehrend eines
     *  Uebergangs, sonst 1. */
    private fun zeichneZustand(canvas: Canvas, z: HudZustand, deckkraft: Float) {
        if (deckkraft <= 0f) return
        val mitte = REF / 2f
        when (z) {
            HudZustand.RUHT -> {
                val puls = (sin(phase(PERIODE_RUHT_MS) * 2 * Math.PI).toFloat() + 1f) / 2f
                ringPaint.strokeWidth = 1f
                ringPaint.alpha = (255 * (0.5f + puls * 0.2f) * deckkraft).toInt()
                canvas.drawCircle(mitte, mitte, 40f, ringPaint)
                ringPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(mitte, mitte, 30f, ringPaint)
                fuellPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(mitte, mitte, 3f, fuellPaint)
            }
            HudZustand.HOERT_ZU -> {
                ringPaint.strokeWidth = 3f
                val basisWinkel = floatArrayOf(-90f, 0f, 90f, 180f)
                val rect = RectF(mitte - 30f, mitte - 30f, mitte + 30f, mitte + 30f)
                val p = phase(PERIODE_HOERT_ZU_MS)
                for (i in 0 until 4) {
                    // Pseudo-zufaelliges, aber deterministisches Wackeln
                    // je Segment - kein echtes Audio, nur simulierte
                    // Reaktivitaet (bewusste Design-Entscheidung).
                    val wackeln = sin(p * 2 * Math.PI + i * 1.7).toFloat()
                    val bogenLaenge = 40f + wackeln * 20f
                    ringPaint.alpha =
                        (255 * (0.6f + 0.4f * kotlin.math.abs(wackeln)) * deckkraft).toInt()
                    canvas.drawArc(rect, basisWinkel[i], bogenLaenge, false, ringPaint)
                }
                fuellPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(mitte, mitte, 5f, fuellPaint)
            }
            HudZustand.DENKT_NACH -> {
                ringPaint.strokeWidth = 3f
                val drehung = phase(PERIODE_DENKT_NACH_MS) * 360f
                val deckkraefte = floatArrayOf(1f, 0.55f, 0.3f)
                val rect = RectF(mitte - 37f, mitte - 37f, mitte + 37f, mitte + 37f)
                for (i in 0 until 3) {
                    ringPaint.alpha = (255 * deckkraefte[i] * deckkraft).toInt()
                    val start = drehung + i * 120f
                    canvas.drawArc(rect, start, 35f, false, ringPaint)
                }
                fuellPaint.alpha = (255 * 0.6f * deckkraft).toInt()
                canvas.drawCircle(mitte, mitte, 5f, fuellPaint)
            }
            HudZustand.ANTWORTET -> {
                ringPaint.strokeWidth = 1.5f
                ringPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(mitte, mitte, 40f, ringPaint)
                ringPaint.strokeWidth = 1f
                canvas.drawCircle(mitte, mitte, 30f, ringPaint)

                fuellPaint.alpha = (255 * deckkraft).toInt()
                val basisHoehen = floatArrayOf(10f, 22f, 14f, 30f, 14f)
                val versaetze = floatArrayOf(0.3f, 1.1f, 0.6f, 2.0f, 0.9f)
                val p = phase(PERIODE_ANTWORTET_MS)
                for (i in 0 until 5) {
                    val wackeln = (sin(p * 2 * Math.PI + versaetze[i]).toFloat() + 1f) / 2f
                    val hoehe = basisHoehen[i] * (0.5f + wackeln)
                    val x = mitte - 18f + i * 9f
                    val rect = RectF(x, mitte - hoehe / 2f, x + 3f, mitte + hoehe / 2f)
                    canvas.drawRoundRect(rect, 1.5f, 1.5f, fuellPaint)
                }
            }
        }
    }
}
