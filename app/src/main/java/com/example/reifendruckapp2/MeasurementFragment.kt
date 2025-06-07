package com.example.reifendruckapp2

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.*
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries

class MeasurementFragment : Fragment(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var recording = false
    private var aufprallErkannt = false
    private val dataBuffer = mutableListOf<Float>()

    private var fallStartTimeNanos: Long = 0L

    private lateinit var resultText: TextView
    private lateinit var calibInfoText: TextView

    // Hier eingefügt: gleitender Mittelwert (Low-Pass) und Buffer
    private val lpWindowSize = 5
    private val lpBuffer = ArrayDeque<Float>(lpWindowSize)

    // frei nach Experiment:
    private val FREE_FALL_THRESHOLD = 0.5f    // m/s² — ab hier gilt es als frei fallend
    private val IMPACT_THRESHOLD = 18.0f   // m/s² — ab hier gilt es als Aufprall

    // Puffer für zuletzt bis zu 10 Messschätzungen
    private val measurementBuffer = ArrayDeque<Float>(10)
    private val maxMeasurements = 10

    // Wurde der freie Fall bereits erkannt?
    private var freeFallDetected = false

    // Wurde der Aufprall bereits erkannt?
    private var impactDetected = false

    private lateinit var graph: GraphView

    // In der Fragment-Klasse, oben
    private lateinit var startButton: MaterialButton

    // Timeout nach dem Aufruf von startMeasurement (10 000 ms)
    private val measurementTimeoutMs = 10_000L
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        abortMeasurement()
    }


    /** Simple Moving Average low-pass filter */
    private fun lowPass(raw: Float): Float {
        if (lpBuffer.size == lpWindowSize) {
            lpBuffer.removeFirst()
        }
        lpBuffer.addLast(raw)
        return lpBuffer.sum() / lpBuffer.size
    }

    /** Einfacher high-pass: raw minus low-pass */
    private fun highPass(raw: Float): Float {
        val filteredLow = lowPass(raw)
        return raw - filteredLow
    }

    // Prozessrauschen klein, Messrauschen etwas größer wählen – justiere nach Bedarf
    private val kalman = SimpleKalmanFilter(
        q = 0.001f,
        r = 0.1f,
        initialX = 0f,
        initialP = 1f
    )


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_measurement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sensorManager = requireActivity()
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        resultText = view.findViewById(R.id.textResult)
        calibInfoText = view.findViewById(R.id.textCalibrationInfo)

        startButton = view.findViewById(R.id.buttonStartMeasurement)
        startButton.setOnClickListener {
            provideHapticFeedback()
            startMeasurement()
        }

        // Anzeige aktueller Kalibrierungszeit
        val latestDate = getLatestCalibrationDate(requireContext())
        calibInfoText.text = if (latestDate != null) {
            val fmt = java.text.SimpleDateFormat("dd. MMMM yyyy, HH:mm", Locale.getDefault())
            "Verwendete Kalibrierung: ${fmt.format(latestDate)}"
        } else {
            "Keine Kalibrierung gefunden"
        }

        // Reset-Button
        view.findViewById<MaterialButton>(R.id.buttonResetMeasurements)
            .setOnClickListener {
                measurementBuffer.clear()
                resultText.text = "Noch keine Messung"
                updateGraph()
                Toast.makeText(requireContext(), "Messungen zurückgesetzt", Toast.LENGTH_SHORT)
                    .show()
            }

        graph = view.findViewById(R.id.graph)
        // Grundkonfiguration
        graph.title = "Letzte 10 Druckwerte"
    }

    private fun startMeasurement() {
        // 1) UI: disable Start-Button
        startButton.isEnabled = false

        // 2) alte Listener rausschmeißen und neuen setzen
        sensorManager.unregisterListener(this)
        registerAccelerometerAtMaxRate()

        // 3) Flags & Buffer zurücksetzen
        freeFallDetected = false
        impactDetected   = false
        dataBuffer.clear()
        fallStartTimeNanos = 0L

        // 4) Timeout starten
        timeoutHandler.postDelayed(timeoutRunnable, measurementTimeoutMs)

        Log.i("MEASUREMENT", "startMeasurement(): Sensor aktiviert – warte auf freien Fall")
    }


    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // 1) Roh-Magnitude
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val raw = sqrt(x * x + y * y + z * z)

        // 2) Kalman-gefilterter Wert fürs Buffer
        val filtered = kalman.update(raw)

        // 3) Free-Fall-Erkennung auf raw
        if (!freeFallDetected) {
            if (raw < FREE_FALL_THRESHOLD) {
                freeFallDetected = true
                fallStartTimeNanos = System.nanoTime()
                Log.i("MEASUREMENT", "Freier Fall erkannt (raw=$raw)")
            }
            return  // bis Free-Fall erkannt ist, nichts weiter tun
        }

        // 4) Aufnahme-Phase bis Impact
        if (!impactDetected) {
            // 4a) Puffer mit gefiltertem Signal füllen
            dataBuffer.add(filtered)

            // 4b) Impact-Erkennung auf raw
            if (raw > IMPACT_THRESHOLD) {
                impactDetected = true
                val fallEnd = System.nanoTime()
                val fallTimeSec = (fallEnd - fallStartTimeNanos) / 1e9f
                val fallHeight = 0.5f * 9.81f * fallTimeSec * fallTimeSec
                Log.i(
                    "MEASUREMENT",
                    "Aufprall (raw=$raw) – Fallzeit: %.3f s Höhe: %.2f m"
                        .format(fallTimeSec, fallHeight)
                )

                // 500 ms weiter aufzeichnen, dann stoppen
                Handler(Looper.getMainLooper()).postDelayed({
                    stopMeasurement(fallTimeSec, fallHeight)
                }, 500)
            }
        }
    }

    /**
     * Berechnet den Pearson–Korrelationskoeffizienten zwischen a und b.
     * Wertebereich: –1 (full anticorrelation) … +1 (full correlation).
     */
    private fun correlation(a: List<Float>, b: List<Float>): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f

        // Mittelwerte
        val meanA = a.take(n).average().toFloat()
        val meanB = b.take(n).average().toFloat()

        // Zähler und Nenner
        var num = 0f
        var denA = 0f
        var denB = 0f
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val denom = sqrt(denA * denB)
        return if (denom != 0f) num / denom else 0f
    }


    private fun stopMeasurement(fallTimeSec: Float, fallHeight: Float) {
        // Sensor abmelden & Recording-Flag zurücksetzen
        sensorManager.unregisterListener(this)
        recording = false
        Log.i("MEASUREMENT", "Messung abgeschlossen mit ${dataBuffer.size} Werten")

        // 1) Interpolierte Profile laden
        val profiles = generateInterpolatedProfiles(requireContext())
        if (profiles.isEmpty()) {
            resultText.text = "Keine Kalibrierungsdaten!"
            return
        }

        // 2) Normierung auf Profil-Länge
        val targetLen = profiles.entries.first().value.size
        val measured = normalizeLength(dataBuffer, targetLen)

        // 3) Vergleich & Druckschätzung
        // Vergleich & Druckschätzung via Korrelations-Maximum
        var bestP = -1f
        var bestScore = Float.POSITIVE_INFINITY

        for ((pressure, profile) in profiles) {
            val normProf = normalizeLength(profile, targetLen)
            val score = dtwDistance(measured, normProf)
            Log.d("DTW", "P=$pressure bar → dtw=$score")
            if (score < bestScore) {
                bestScore = score
                bestP = pressure
            }
        }

        // 4) Puffer-Logik & Mittelwert wie gehabt …
        measurementBuffer.addLast(bestP)
        if (measurementBuffer.size > maxMeasurements) measurementBuffer.removeFirst()
        val avgPressure = measurementBuffer.average().toFloat()

        // 5) Anzeige
        val displayText = "Ø Druck (${measurementBuffer.size}): %.2f bar".format(avgPressure)
        resultText.text = displayText
        Log.i("MEASUREMENT", displayText)

        updateGraph()
        // Messung erfolgreich fertig → Timeout stoppen und Button re-aktivieren
        timeoutHandler.removeCallbacks(timeoutRunnable)
        startButton.isEnabled = true
    }


    private fun calculateDistance(a: List<Float>, b: List<Float>): Float {
        val n = minOf(a.size, b.size)
        var sum = 0f
        repeat(n) { i ->
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    private fun normalizeLength(data: List<Float>, target: Int): List<Float> {
        if (data.isEmpty()) return List(target) { 0f }
        if (data.size == target) return data
        val out = mutableListOf<Float>()
        val step = data.size.toFloat() / target
        var idx = 0f
        while (out.size < target) {
            out.add(data.getOrElse(idx.toInt()) { data.last() })
            idx += step
        }
        return out
    }

    private fun registerAccelerometerAtMaxRate() {
        accelerometer?.let { sensor ->
            // erst sicher abmelden
            sensorManager.unregisterListener(this, sensor)
            // dann neu registrieren mit 0 Latenz
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                /* maxReportLatencyUs = */ 0
            )
            Log.i("MEASUREMENT", "Listener registered FASTEST + no batching")
            // ab API19: Events direkt ausgeben
            sensorManager.flush(this)
        } ?: Log.e("MEASUREMENT", "Kein Accelerometer gefunden!")
    }


    private fun provideHapticFeedback() {
        val vib = context
            ?.getSystemService(Vibrator::class.java)
        vib?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(20)
            }
        }
    }

    private fun getLatestCalibrationDate(context: Context): Date? {
        val files = context.cacheDir.listFiles { _, name ->
            name.startsWith("calibration_") && name.endsWith(".csv")
        }
        return files?.maxByOrNull { it.lastModified() }?.let { Date(it.lastModified()) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Hier brauchst du nichts zu tun
    }

    private fun updateGraph() {
        // Alte Serie entfernen
        graph.removeAllSeries()
        // Neue DataPoint-Reihe
        val points = measurementBuffer.mapIndexed { idx, p ->
            // x: Index 1..n, y: Druckwert
            DataPoint(idx.toDouble() + 1.0, p.toDouble())
        }.toTypedArray()
        val series = LineGraphSeries(points)
        graph.addSeries(series)
        // Achsen anpassen
        graph.viewport.isXAxisBoundsManual = true
        graph.viewport.setMinX(1.0)
        graph.viewport.setMaxX(measurementBuffer.size.coerceAtLeast(1).toDouble())
        graph.viewport.isYAxisBoundsManual = true
        val minY = (measurementBuffer.minOrNull() ?: 0f) - 0.5
        val maxY = (measurementBuffer.maxOrNull() ?: 5f) + 0.5
        graph.viewport.setMinY(minY.toDouble())
        graph.viewport.setMaxY(maxY.toDouble())
    }

    private fun abortMeasurement() {
        // Listener abmelden
        sensorManager.unregisterListener(this)
        // Flags zurücksetzen
        freeFallDetected = false
        impactDetected   = false
        dataBuffer.clear()

        // Timeout entfernen (sicherheitshalber)
        timeoutHandler.removeCallbacks(timeoutRunnable)

        // UI zurücksetzen
        resultText.text = "Messung abgebrochen"
        updateGraph()  // leert Graph, wenn Buffer leer ist

        // Start-Button wieder aktivieren
        startButton.isEnabled = true

        Toast.makeText(requireContext(),
            "Messung abgebrochen: kein Fall erkannt",
            Toast.LENGTH_SHORT
        ).show()

        Log.w("MEASUREMENT", "Messung automatisch abgebrochen (Timeout)")
    }

    // Datei: MeasurementFragment.kt
// Platziere das in der Klasse MeasurementFragment, z.B. direkt nach correlation(...)
    private fun dtwDistance(a: List<Float>, b: List<Float>): Float {
        val n = a.size
        val m = b.size
        // Matrix mit „Unendlich“-Werten initialisieren
        val dtw = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dtw[0][0] = 0f

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = kotlin.math.abs(a[i - 1] - b[j - 1])
                // Minimum der drei Nachbarn plus Kosten
                dtw[i][j] = cost + minOf(dtw[i - 1][j],    // Insertion
                    dtw[i][j - 1],    // Deletion
                    dtw[i - 1][j - 1])// Match
            }
        }
        return dtw[n][m]
    }




}


