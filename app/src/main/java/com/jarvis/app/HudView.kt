package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
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
 * "springen".
 *
 * Neuzeichnen laeuft ueber Choreographer statt ValueAnimator (Fund aus
 * dem Abschluss-Review, 14.08.2026): ein ValueAnimator wird von
 * Settings.Global.ANIMATOR_DURATION_SCALE beeinflusst (erreichbar ueber
 * Bedienungshilfen -> Animationen entfernen) - bei Skala 0 haette der
 * Ring einfach eingefroren, ohne auf setZustand() zu reagieren.
 * Choreographer ist davon unabhaengig. Ueber onVisibilityAggregated()
 * an-/abgeschaltet, damit im Hintergrund/mit ausgeschaltetem Bildschirm
 * kein Akku fuer unsichtbare Frames verbraucht wird.
 *
 * Alle Zeichen-Hilfsobjekte (Path, DashPathEffect, RectF, Eckenliste)
 * werden einmalig als Instanzfelder angelegt statt bei jedem onDraw()
 * neu zu allozieren - bei ~60fps dauerhaft waere das sonst staendiger
 * Garbage-Collector-Druck (ebenfalls Fund aus dem Abschluss-Review).
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val REF = 240f
        private const val MITTE = REF / 2f
        private const val FARBE = 0xFF378ADD.toInt()
        private const val PERIODE_RUHT_MS = 3600L
        private const val PERIODE_HOERT_ZU_MS = 500L
        private const val PERIODE_DENKT_NACH_MS = 1200L
        private const val PERIODE_ANTWORTET_MS = 600L
        private const val UEBERGANG_MS = 280L
        private const val ECKEN_LAENGE = 20f
        private const val ECKEN_ABSTAND = 14f

        /** Reine Rechenfunktion ohne Android-Abhaengigkeit, deshalb per
         *  JVM-Unit-Test pruefbar (siehe HudViewPhaseTest.kt) - phase()
         *  liest nur SystemClock aus und ruft das hier auf. */
        internal fun phaseVon(vergangenMs: Long, periodeMs: Long): Float =
            (vergangenMs % periodeMs).toFloat() / periodeMs

        // --- Ikosaeder-Kugel-Geometrie (16.08.2026, siehe PLAN-JARVIS-HUD-
        // KERN-UND-RAND.md) - reine Rechenfunktionen, werden nur EINMAL bei
        // der View-Konstruktion aufgerufen (siehe kugelPunkte/kugelKanten
        // weiter unten in der Klasse), NIE pro Frame. ---

        /** Ein Punkt im 3D-Raum - fuer die Kern-Kugel. Eigener winziger
         *  Werttyp statt drei einzelner Floats, damit die
         *  Geometrie-Erzeugung lesbar bleibt. */
        internal data class Vektor3(val x: Float, val y: Float, val z: Float)

        private fun normiert(v: Vektor3): Vektor3 {
            val laenge = kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
            return Vektor3(v.x / laenge, v.y / laenge, v.z / laenge)
        }

        /** Die zwoelf Eckpunkte eines Ikosaeders (goldener Schnitt), auf
         *  die Einheitskugel normiert - Ausgangsform fuer die Kern-Kugel. */
        internal fun ikosaederPunkte(): List<Vektor3> {
            val phi = (1f + kotlin.math.sqrt(5f)) / 2f
            return listOf(
                Vektor3(-1f, phi, 0f), Vektor3(1f, phi, 0f),
                Vektor3(-1f, -phi, 0f), Vektor3(1f, -phi, 0f),
                Vektor3(0f, -1f, phi), Vektor3(0f, 1f, phi),
                Vektor3(0f, -1f, -phi), Vektor3(0f, 1f, -phi),
                Vektor3(phi, 0f, -1f), Vektor3(phi, 0f, 1f),
                Vektor3(-phi, 0f, -1f), Vektor3(-phi, 0f, 1f),
            ).map { normiert(it) }
        }

        /** Die zwanzig Dreiecksflaechen des Ikosaeders, als Punkt-Indizes
         *  in die Liste von ikosaederPunkte(). Standard-Ikosaeder-
         *  Triangulierung. */
        internal fun ikosaederFlaechen(): List<IntArray> = listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7),
            intArrayOf(0, 7, 10), intArrayOf(0, 10, 11), intArrayOf(1, 5, 9),
            intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6),
            intArrayOf(7, 1, 8), intArrayOf(3, 9, 4), intArrayOf(3, 4, 2),
            intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10),
            intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )

        /** Unterteilt jede Dreiecksflaeche in vier kleinere (Kantenmitten
         *  werden neue, auf die Kugel zurueckprojizierte Eckpunkte) - eine
         *  Anwendung macht aus dem Ikosaeder (12/20) eine feinere Kugel
         *  (42 Punkte/80 Flaechen), zwei Anwendungen ergeben 162 Punkte/
         *  320 Flaechen (480 Kanten) - die mit Frank abgestimmte
         *  "dicht"-Stufe. Wird nur beim Start einmal aufgerufen. */
        internal fun unterteile(
            punkte: List<Vektor3>,
            flaechen: List<IntArray>,
        ): Pair<List<Vektor3>, List<IntArray>> {
            val neuePunkte = punkte.toMutableList()
            val mittelpunktCache = HashMap<Long, Int>()

            fun mittelpunkt(i1: Int, i2: Int): Int {
                val a = minOf(i1, i2).toLong()
                val b = maxOf(i1, i2).toLong()
                val schluessel = a * 100_000L + b
                mittelpunktCache[schluessel]?.let { return it }
                val p1 = neuePunkte[i1]
                val p2 = neuePunkte[i2]
                val mitte = normiert(
                    Vektor3((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f, (p1.z + p2.z) / 2f)
                )
                neuePunkte.add(mitte)
                val index = neuePunkte.size - 1
                mittelpunktCache[schluessel] = index
                return index
            }

            val neueFlaechen = mutableListOf<IntArray>()
            for (f in flaechen) {
                val ab = mittelpunkt(f[0], f[1])
                val bc = mittelpunkt(f[1], f[2])
                val ca = mittelpunkt(f[2], f[0])
                neueFlaechen.add(intArrayOf(f[0], ab, ca))
                neueFlaechen.add(intArrayOf(f[1], bc, ab))
                neueFlaechen.add(intArrayOf(f[2], ca, bc))
                neueFlaechen.add(intArrayOf(ab, bc, ca))
            }
            return Pair(neuePunkte, neueFlaechen)
        }

        /** Leitet die eindeutigen Kanten (Punkt-Index-Paare) aus einer
         *  Liste von Dreiecksflaechen ab - jede Kante taucht in zwei
         *  benachbarten Flaechen auf, wird hier nur einmal zurueckgegeben. */
        internal fun kantenAusFlaechen(flaechen: List<IntArray>): List<IntArray> {
            val gesehen = HashSet<Long>()
            val kanten = mutableListOf<IntArray>()
            for (f in flaechen) {
                val paare = listOf(f[0] to f[1], f[1] to f[2], f[2] to f[0])
                for ((x, y) in paare) {
                    val a = minOf(x, y)
                    val b = maxOf(x, y)
                    val schluessel = a.toLong() * 100_000L + b.toLong()
                    if (gesehen.add(schluessel)) {
                        kanten.add(intArrayOf(a, b))
                    }
                }
            }
            return kanten
        }
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

    // --- Wiederverwendete Zeichen-Objekte (siehe Klassenkommentar) -----

    private data class Ecke(val ex: Float, val ey: Float, val dx: Float, val dy: Float)
    private val ecken = listOf(
        Ecke(ECKEN_ABSTAND, ECKEN_ABSTAND, 1f, 1f),
        Ecke(REF - ECKEN_ABSTAND, ECKEN_ABSTAND, -1f, 1f),
        Ecke(REF - ECKEN_ABSTAND, REF - ECKEN_ABSTAND, -1f, -1f),
        Ecke(ECKEN_ABSTAND, REF - ECKEN_ABSTAND, 1f, -1f),
    )
    private val gestrichelterPfad = Path().apply {
        addCircle(MITTE, MITTE, 108f, Path.Direction.CW)
    }
    private val dashEffect = DashPathEffect(floatArrayOf(2f, 10f), 0f)

    private val hoertZuRect = RectF(MITTE - 30f, MITTE - 30f, MITTE + 30f, MITTE + 30f)
    private val hoertZuWinkel = floatArrayOf(-90f, 0f, 90f, 180f)

    private val denktNachRect = RectF(MITTE - 37f, MITTE - 37f, MITTE + 37f, MITTE + 37f)
    private val denktNachDeckkraefte = floatArrayOf(1f, 0.55f, 0.3f)

    private val antwortetBalkenRect = RectF()
    private val antwortetBasisHoehen = floatArrayOf(10f, 22f, 14f, 30f, 14f)
    private val antwortetVersaetze = floatArrayOf(0.3f, 1.1f, 0.6f, 2.0f, 0.9f)

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

    /** Fortlaufende Phase 0f..1f fuer eine gegebene Zykluslaenge - beginnt
     *  nicht bei jedem Aufruf neu, deshalb kein Sprung beim
     *  Schleifenende (siehe Klassenkommentar). */
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
        } else {
            zeichneZustand(canvas, zustand, 1f)
        }

        canvas.restore()
    }

    /** Chrome, die in allen vier Zustaenden gleich aussieht: Ecken-
     *  Klammern, gedrehter gestrichelter Aussenring, Basis-Ring,
     *  Tick-Marken. */
    private fun zeichneChrome(canvas: Canvas) {
        ringPaint.pathEffect = null
        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 255
        for (e in ecken) {
            canvas.drawLine(e.ex, e.ey, e.ex + e.dx * ECKEN_LAENGE, e.ey, ringPaint)
            canvas.drawLine(e.ex, e.ey, e.ex, e.ey + e.dy * ECKEN_LAENGE, ringPaint)
        }

        // WICHTIG: als Path statt drawCircle gezeichnet - Android
        // ignoriert PathEffect (Strichelung) bei drawCircle/drawOval
        // unter Hardware-Beschleunigung, bei drawPath aber nicht
        // (bekannte Android-Einschraenkung).
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

    /** Zeichnet die zustandsabhaengigen Elemente (Segmente/Balken/Punkt)
     *  mit der gegebenen Deckkraft - deckkraft < 1 waehrend eines
     *  Uebergangs, sonst 1. */
    private fun zeichneZustand(canvas: Canvas, z: HudZustand, deckkraft: Float) {
        if (deckkraft <= 0f) return
        when (z) {
            HudZustand.RUHT -> {
                val puls = (sin(phase(PERIODE_RUHT_MS) * 2 * Math.PI).toFloat() + 1f) / 2f
                ringPaint.strokeWidth = 1f
                ringPaint.alpha = (255 * (0.5f + puls * 0.2f) * deckkraft).toInt()
                canvas.drawCircle(MITTE, MITTE, 40f, ringPaint)
                ringPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(MITTE, MITTE, 30f, ringPaint)
                fuellPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(MITTE, MITTE, 3f, fuellPaint)
            }
            HudZustand.HOERT_ZU -> {
                ringPaint.strokeWidth = 3f
                val p = phase(PERIODE_HOERT_ZU_MS)
                for (i in 0 until 4) {
                    // Pseudo-zufaelliges, aber deterministisches Wackeln
                    // je Segment - kein echtes Audio, nur simulierte
                    // Reaktivitaet (bewusste Design-Entscheidung).
                    val wackeln = sin(p * 2 * Math.PI + i * 1.7).toFloat()
                    // 45-70 Grad Bogenlaenge laut Design - 57,5 +- 12,5
                    // trifft die Spanne exakt (Fund aus dem
                    // Abschluss-Review: die urspruengliche 40 +- 20 lag
                    // darunter).
                    val bogenLaenge = 57.5f + wackeln * 12.5f
                    ringPaint.alpha =
                        (255 * (0.6f + 0.4f * kotlin.math.abs(wackeln)) * deckkraft).toInt()
                    canvas.drawArc(hoertZuRect, hoertZuWinkel[i], bogenLaenge, false, ringPaint)
                }
                fuellPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(MITTE, MITTE, 5f, fuellPaint)
            }
            HudZustand.DENKT_NACH -> {
                ringPaint.strokeWidth = 3f
                val drehung = phase(PERIODE_DENKT_NACH_MS) * 360f
                for (i in 0 until 3) {
                    ringPaint.alpha = (255 * denktNachDeckkraefte[i] * deckkraft).toInt()
                    val start = drehung + i * 120f
                    canvas.drawArc(denktNachRect, start, 35f, false, ringPaint)
                }
                fuellPaint.alpha = (255 * 0.6f * deckkraft).toInt()
                canvas.drawCircle(MITTE, MITTE, 5f, fuellPaint)
            }
            HudZustand.ANTWORTET -> {
                ringPaint.strokeWidth = 1.5f
                ringPaint.alpha = (255 * deckkraft).toInt()
                canvas.drawCircle(MITTE, MITTE, 40f, ringPaint)
                ringPaint.strokeWidth = 1f
                canvas.drawCircle(MITTE, MITTE, 30f, ringPaint)

                fuellPaint.alpha = (255 * deckkraft).toInt()
                val p = phase(PERIODE_ANTWORTET_MS)
                for (i in 0 until 5) {
                    val wackeln = (sin(p * 2 * Math.PI + antwortetVersaetze[i]).toFloat() + 1f) / 2f
                    val hoehe = antwortetBasisHoehen[i] * (0.5f + wackeln)
                    // Fuenf 3 Einheiten breite Balken im Abstand von 9
                    // Einheiten spannen 39 Einheiten - Mitte des linken
                    // Rands liegt deshalb bei -19,5, nicht -18 (Fund aus
                    // dem Abschluss-Review: winziger Rundungsfehler,
                    // korrigiert fuer echte Symmetrie um die Mitte).
                    val x = MITTE - 19.5f + i * 9f
                    antwortetBalkenRect.set(x, MITTE - hoehe / 2f, x + 3f, MITTE + hoehe / 2f)
                    canvas.drawRoundRect(antwortetBalkenRect, 1.5f, 1.5f, fuellPaint)
                }
            }
        }
    }
}
