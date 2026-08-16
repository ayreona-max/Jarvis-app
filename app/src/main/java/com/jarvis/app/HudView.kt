package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
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
 * Ein Iron-Man/JARVIS-artiges HUD: Ring mit Tick-Marken und einer
 * rotierenden, pulsierenden Kugel im Kern, das je nach HudZustand
 * unterschiedlich animiert. Zeichnet sich komplett selbst per Canvas -
 * keine Bilddateien, keine Animationsbibliothek (14.08.2026, siehe
 * PLAN-JARVIS-HUD-GESICHT.md, Abschnitt "Architektur"; Kern-Kugel und
 * glatter Aussenrand seit 16.08.2026, siehe PLAN-JARVIS-HUD-KERN-UND-
 * RAND.md).
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
 * Alle Zeichen-Hilfsobjekte (Path, DashPathEffect, die Kugel-Geometrie
 * aus Punkten/Kanten und ihre drei Projektions-FloatArrays) werden
 * einmalig als Instanzfelder angelegt statt bei jedem onDraw() neu zu
 * allozieren - bei ~60fps dauerhaft waere das sonst staendiger
 * Garbage-Collector-Druck (Fund aus dem Abschluss-Review vom
 * 14.08.2026, seit 16.08.2026 auch fuer die 162 Kugel-Punkte beachtet).
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val REF = 240f
        private const val MITTE = REF / 2f
        private const val FARBE = 0xFF378ADD.toInt()
        private const val UEBERGANG_MS = 280L
        private const val AUSSENRAND_RADIUS = 112f
        private const val KUGEL_GRUND_RADIUS = 46f
        private const val KUGEL_KIPPWINKEL_RAD = 0.35f

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

        /** Ein projizierter 2D-Bildschirmpunkt samt Tiefenwert (nach
         *  Drehung, vor Verschiebung/Skalierung ist er bereits
         *  eingerechnet) - der Tiefenwert steuert in Task 3 die
         *  Deckkraft beim Zeichnen (vorne heller als hinten). */
        internal data class ProjizierterPunkt(val x: Float, val y: Float, val tiefe: Float)

        /** Dreht einen Punkt um die Y-Achse (drehYRad) und danach um die
         *  X-Achse (drehXRad), projiziert ihn dann orthografisch auf den
         *  Bildschirm (Mittelpunkt mitteX/mitteY, Massstab skala). Reine
         *  Rechenfunktion zur Absicherung der Formel per Unit-Test - die
         *  eigentliche Zeichenschleife in zeichneZustand() (Task 3) rechnet
         *  dieselbe Formel direkt auf vorbereiteten FloatArrays nach, statt
         *  bei 162 Punkten pro Frame 162 ProjizierterPunkt-Objekte
         *  anzulegen (siehe Global Constraints). */
        internal fun gedrehtUndProjiziert(
            v: Vektor3,
            drehYRad: Float,
            drehXRad: Float,
            mitteX: Float,
            mitteY: Float,
            skala: Float,
        ): ProjizierterPunkt {
            val x1 = v.x * cos(drehYRad) + v.z * sin(drehYRad)
            val z1 = -v.x * sin(drehYRad) + v.z * cos(drehYRad)
            val y2 = v.y * cos(drehXRad) - z1 * sin(drehXRad)
            val z2 = v.y * sin(drehXRad) + z1 * cos(drehXRad)
            return ProjizierterPunkt(mitteX + x1 * skala, mitteY + y2 * skala, z2)
        }

        /** Puls-Faktor um 1 herum (1-staerke bis 1+staerke) fuer das
         *  "Atmen" der Kugel-Groesse je Zustand. */
        internal fun pulsFaktor(vergangenMs: Long, pulsTempo: Float, pulsStaerke: Float): Float {
            val sekunden = vergangenMs / 1000f
            return 1f + sin(sekunden * pulsTempo * 2f * PI.toFloat()) * pulsStaerke
        }

        /** Richtwerte je Zustand fuer die Kern-Kugel - Umlaufdauer,
         *  Puls-Staerke/-Tempo, Grund-Deckkraft. Werte 1:1 aus der mit
         *  Frank abgestimmten Vorschau uebernommen (siehe
         *  PLAN-JARVIS-HUD-KERN-UND-RAND.md, Abschnitt "Kern (Kugel)"). */
        internal data class KugelZustandsWerte(
            val periodeMs: Long,
            val pulsStaerke: Float,
            val pulsTempo: Float,
            val deckkraft: Float,
        )

        private val KUGEL_WERTE = mapOf(
            HudZustand.RUHT to KugelZustandsWerte(5200L, 0.06f, 1.0f, 0.55f),
            HudZustand.HOERT_ZU to KugelZustandsWerte(2600L, 0.10f, 2.4f, 0.75f),
            HudZustand.DENKT_NACH to KugelZustandsWerte(1400L, 0.05f, 1.6f, 0.85f),
            HudZustand.ANTWORTET to KugelZustandsWerte(1900L, 0.13f, 2.0f, 1.0f),
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
    private val fuellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = FARBE
    }

    // --- Wiederverwendete Zeichen-Objekte (siehe Klassenkommentar) -----

    private val gestrichelterPfad = Path().apply {
        addCircle(MITTE, MITTE, 108f, Path.Direction.CW)
    }
    private val dashEffect = DashPathEffect(floatArrayOf(2f, 10f), 0f)

    // --- HUD-Kern-Kugel (16.08.2026, siehe PLAN-JARVIS-HUD-KERN-UND-
    // RAND.md) - Geometrie wird HIER einmalig bei der View-Konstruktion
    // berechnet (zwei Unterteilungen eines Ikosaeders -> 162 Punkte/480
    // Kanten), NICHT in onDraw()/zeichneZustand(). Die drei FloatArrays
    // darunter sind selbst nur einmal angelegt, werden aber jeden Frame
    // neu befuellt - vermeidet die Objekt-Allokation pro Frame, die der
    // Abschluss-Review vom 14.08.2026 als Fund markiert hatte. ---

    private val kugelGeometrie: Pair<List<Vektor3>, List<IntArray>> = run {
        val erste = unterteile(ikosaederPunkte(), ikosaederFlaechen())
        val zweite = unterteile(erste.first, erste.second)
        Pair(zweite.first, kantenAusFlaechen(zweite.second))
    }
    private val kugelPunkte: List<Vektor3> = kugelGeometrie.first
    private val kugelKanten: List<IntArray> = kugelGeometrie.second
    private val kugelProjX = FloatArray(kugelPunkte.size)
    private val kugelProjY = FloatArray(kugelPunkte.size)
    private val kugelProjZ = FloatArray(kugelPunkte.size)

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

    /** Chrome, die in allen vier Zustaenden gleich aussieht: glatter
     *  Aussenrand, gedrehter gestrichelter Aussenring, Basis-Ring,
     *  Tick-Marken. */
    private fun zeichneChrome(canvas: Canvas) {
        ringPaint.pathEffect = null
        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 255
        canvas.drawCircle(MITTE, MITTE, AUSSENRAND_RADIUS, ringPaint)

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

    /** Zeichnet die HUD-Kern-Kugel mit der gegebenen Deckkraft - deckkraft
     *  < 1 waehrend eines Uebergangs, sonst 1. Ersetzt seit 16.08.2026 die
     *  vier vorher unterschiedlichen Zustandsformen (siehe PLAN-JARVIS-
     *  HUD-KERN-UND-RAND.md) - die vier Zustaende unterscheiden sich jetzt
     *  nur noch ueber KUGEL_WERTE (Tempo/Puls/Deckkraft), nicht mehr ueber
     *  unterschiedlichen Zeichen-Code. Rechnet die Dreh-/Projektions-
     *  Formel bewusst direkt auf kugelProjX/Y/Z nach statt
     *  gedrehtUndProjiziert() 162-mal aufzurufen (siehe Global
     *  Constraints - keine Objekt-Allokation pro Frame). */
    private fun zeichneZustand(canvas: Canvas, z: HudZustand, deckkraft: Float) {
        if (deckkraft <= 0f) return
        val werte = KUGEL_WERTE.getValue(z)
        val vergangenMs = SystemClock.uptimeMillis() - startZeit
        val drehYRad = phase(werte.periodeMs) * 2f * PI.toFloat()
        val puls = pulsFaktor(vergangenMs, werte.pulsTempo, werte.pulsStaerke)
        val skala = KUGEL_GRUND_RADIUS * puls

        val cosDreh = cos(drehYRad)
        val sinDreh = sin(drehYRad)
        val cosKipp = cos(KUGEL_KIPPWINKEL_RAD)
        val sinKipp = sin(KUGEL_KIPPWINKEL_RAD)
        for (i in kugelPunkte.indices) {
            val v = kugelPunkte[i]
            val x1 = v.x * cosDreh + v.z * sinDreh
            val z1 = -v.x * sinDreh + v.z * cosDreh
            val y2 = v.y * cosKipp - z1 * sinKipp
            val z2 = v.y * sinKipp + z1 * cosKipp
            kugelProjX[i] = MITTE + x1 * skala
            kugelProjY[i] = MITTE + y2 * skala
            kugelProjZ[i] = z2
        }

        ringPaint.strokeWidth = 1f
        for (kante in kugelKanten) {
            val a = kante[0]
            val b = kante[1]
            val tiefe = (kugelProjZ[a] + kugelProjZ[b]) / 2f
            val alpha = werte.deckkraft * (0.45f + 0.55f * ((tiefe + 1f) / 2f)) * deckkraft
            ringPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
            canvas.drawLine(kugelProjX[a], kugelProjY[a], kugelProjX[b], kugelProjY[b], ringPaint)
        }
    }
}
