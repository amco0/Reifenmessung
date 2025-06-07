package com.example.reifendruckapp2

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.reifendruckapp2.R
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt


class CalibrationFragment : Fragment(), SensorEventListener {
    // Member variablen
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var recording = false
    private var dataBuffer = mutableListOf<Float>()

    //private var currentPressure = 0.0f
    private var aufprallErkannt = false
    private lateinit var interpolateButton: Button
    private val successfulCalibrations = mutableSetOf<Float>()
    private var currentPressure: Float = -1f
    private var currentButton: MaterialButton? = null
    private var fallStartTimeNanos: Long = 0L

    // Anzahl Samples für gleitenden Mittelwert
    private val lpWindowSize = 5
    private val lpBuffer = ArrayDeque<Float>(lpWindowSize)

    /**  Simple Moving Average low-pass filter */
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
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_calibration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Sensoren vorbereiten
        sensorManager = (activity ?: requireContext())
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)


        // UI-Elemente holen
        val input0_5 = view.findViewById<EditText>(R.id.input0_5)
        val input1 = view.findViewById<EditText>(R.id.input1)
        val input2 = view.findViewById<EditText>(R.id.input2)
        val input3 = view.findViewById<EditText>(R.id.input3)
        val input4 = view.findViewById<EditText>(R.id.input4)

        val button0_5 = view.findViewById<MaterialButton>(R.id.buttonCal0_5)
        val button1 = view.findViewById<MaterialButton>(R.id.buttonCal1)
        val button2 = view.findViewById<MaterialButton>(R.id.buttonCal2)
        val button3 = view.findViewById<MaterialButton>(R.id.buttonCal3)
        val button4 = view.findViewById<MaterialButton>(R.id.buttonCal4)


        interpolateButton = view.findViewById(R.id.buttonInterpolate)
        interpolateButton.setOnClickListener {
            provideHapticFeedback()
            // Kontext sicher holen
            val ctx = activity ?: return@setOnClickListener

            // Profile generieren
            val result = generateInterpolatedProfiles(ctx)
            Log.i("INTERPOLATION", "Profile erzeugt: ${result.size} Stück")

            // Status-Text aktualisieren
            val statusText = view?.findViewById<TextView>(R.id.textInterpolationStatus)
            statusText?.text = "Interpoliert! (${result.size} Profile)"
            statusText?.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_green_dark))

            // Button-Hintergrund ändern
            interpolateButton.setBackgroundResource(R.drawable.border_green)

            // Haptisches Feedback (Vibration)
            provideHapticFeedback()

            // Kurzer Toast
            Toast.makeText(ctx, "Interpolation abgeschlossen!", Toast.LENGTH_SHORT).show()
        }


        // Button-Handler für Kalibrierungen
        button0_5.setOnClickListener {
            provideHapticFeedback()
            input0_5.text.toString().toFloatOrNull()?.let {
                startRecording(it, button0_5)
            }
        }
        button1.setOnClickListener {
            provideHapticFeedback()
            input1.text.toString().toFloatOrNull()?.let {
                startRecording(it, button1)
            }
        }
        button2.setOnClickListener {
            provideHapticFeedback()
            input2.text.toString().toFloatOrNull()?.let {
                startRecording(it, button2)
            }
        }
        button3.setOnClickListener {
            provideHapticFeedback()
            input3.text.toString().toFloatOrNull()?.let {
                startRecording(it, button3)
            }
        }
        button4.setOnClickListener {
            provideHapticFeedback()
            input4.text.toString().toFloatOrNull()?.let {
                startRecording(it, button4)
            }
        }


        val resetButton = view.findViewById<MaterialButton>(R.id.buttonResetVisuals)

        resetButton.setOnClickListener {
            provideHapticFeedback()
            // Ränder zurück auf rot
            view.findViewById<MaterialButton>(R.id.buttonCal1)
                ?.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.holo_red_dark
                )
            )

            view.findViewById<MaterialButton>(R.id.buttonCal2)
                ?.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.holo_red_dark
                )
            )

            view.findViewById<MaterialButton>(R.id.buttonCal3)
                ?.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.holo_red_dark
                )
            )

            // Interpolieren-Button deaktivieren
            interpolateButton.isEnabled = false

            // Statusanzeige zurücksetzen (optional)
            val statusText = view.findViewById<TextView>(R.id.textInterpolationStatus)
            statusText?.text = "Noch nicht interpoliert"
            statusText?.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.darker_gray
                )
            )

            Toast.makeText(requireContext(), "Visuals zurückgesetzt", Toast.LENGTH_SHORT).show()
        }

    }


    private fun startRecording(pressure: Float, button: MaterialButton) {
        if (recording) {
            Log.w("CALIBRATION", "Bereits eine Messung aktiv.")
            return
        }

        // State zurücksetzen
        currentPressure = pressure
        currentButton = button
        aufprallErkannt = false
        dataBuffer.clear()
        fallStartTimeNanos = 0L   // Für die neue Fallzeit-Messung
        recording = false         // Start erst in onSensorChanged()

        // Sensor mit maximaler Abtastrate registrieren
        registerAccelerometerAtMaxRate()

        Log.i("CALIBRATION", "Starte Messung für $pressure bar")
    }


    private fun stopRecording(fallTimeSec: Float) {
        recording = false
        sensorManager.unregisterListener(this)

        // Berechne Fallhöhe
        val g = 9.81f
        val fallHeight = 0.5f * g * fallTimeSec * fallTimeSec
        Log.i("CALIBRATION", "Stopp. Fallhöhe: %.2f m".format(fallHeight))
        Log.i("CALIBRATION", "Werte für $currentPressure bar:")
        dataBuffer.forEach { Log.d("CALIBRATION", it.toString()) }

        // Du könntest die Fallhöhe jetzt auch mit abspeichern, z.B.:
        // saveCalibrationToCsv(currentPressure, fallTimeSec, fallHeight, dataBuffer)

        saveCalibrationToCsv(requireContext(), currentPressure, dataBuffer)

        successfulCalibrations.add(currentPressure)
        // Knopf einfärben (egal, welcher es war)
        currentButton?.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
        )


        // Interpolieren erlauben, wenn mindestens 3 Kalibrierwerte vorhanden sind
        if (successfulCalibrations.size >= 5) {
            interpolateButton.isEnabled = true
            Log.i("CALIBRATION", "5 Kalibrierungen vorhanden – Interpolationsbutton aktiviert")
        }


    }


    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // 1) Multi-Axis-Fusion: Roh-magnitude berechnen
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val raw = sqrt(x * x + y * y + z * z)

        // 2) Kalman-Filter anwenden (adaptive Glättung)
        val filtered = kalman.update(raw)

        // 3) High-Pass für Impuls-Erkennung (oder alternativ raw-filtered)
        val impulse = raw - filtered

        // 4) Free-Fall erkennen (impulse nahe Null)
        if (!recording && impulse < 1.0f) {
            fallStartTimeNanos = System.nanoTime()
            dataBuffer.clear()
            recording = true
            aufprallErkannt = false
            Log.i("CALIBRATION", "Freier Fall erkannt (raw=$raw, impulse=$impulse) – Messung startet")
        }

        // 5) Während Recording: gefiltertes Signal buffern
        if (recording) {
            dataBuffer.add(filtered)

            // 6) Impact erkennen (impulse groß)
            if (!aufprallErkannt && impulse > 15.0f) {
                aufprallErkannt = true
                val fallEndTime   = System.nanoTime()
                val fallTimeSec   = (fallEndTime - fallStartTimeNanos) / 1e9f
                Log.i(
                    "CALIBRATION",
                    "Aufprall erkannt (raw=$raw, impulse=$impulse) – Fallzeit: %.3f s"
                        .format(fallTimeSec)
                )

                // 500 ms weiter aufnehmen, dann stoppen
                Handler(Looper.getMainLooper()).postDelayed({
                    stopRecording(fallTimeSec)
                }, 500)
            }
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // ignorieren
    }

    private fun registerAccelerometerAtMaxRate() {
        accelerometer?.let { sensor ->
            try {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
                Log.i("CALIBRATION", "Sensor mit SENSOR_DELAY_FASTEST registriert")
            } catch (e: SecurityException) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                Log.w("CALIBRATION", "FASTEST nicht erlaubt, nutze GAME-Rate")
            }
        }
    }

    private fun provideHapticFeedback() {
        // activity? ist immer verfügbar im Fragment
        val vibrator = activity
            ?.getSystemService(Vibrator::class.java)
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20)
            }
        }
    }
}

private fun saveCalibrationToCsv(context: Context, pressure: Float, data: List<Float>) {
    val filename = "calibration_${pressure}bar.csv"
    val file = File(context.applicationContext.cacheDir, filename)

    try {
        file.writeText(data.joinToString(separator = "\n"))
        Log.i("CALIBRATION", "CSV gespeichert: ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e("CALIBRATION", "Fehler beim Speichern: ${e.message}")
    }
}

private fun loadCalibrationCsv(context: Context, pressure: Float): List<Float>? {
    val filename = "calibration_${pressure}bar.csv"
    val file = File(context.applicationContext.cacheDir, filename)

    if (!file.exists()) {
        Log.e("CALIBRATION", "Datei nicht gefunden: $filename")
        return null
    }

    return try {
        file.readLines().map { it.toFloat() }
    } catch (e: Exception) {
        Log.e("CALIBRATION", "Fehler beim Einlesen: ${e.message}")
        null
    }
}

fun generateInterpolatedProfiles(context: Context): Map<Float, List<Float>> {
    // 1) Die fünf Kalibrierdrücke
    val expectedPressures = listOf(0.5f, 1.0f, 2.0f, 3.0f, 4.0f)

    // 2) CSVs einlesen und bei fehlendem File sofort abbrechen
    val data = mutableMapOf<Float, List<Float>>()
    for (p in expectedPressures) {
        val values = loadCalibrationCsv(context, p)
        if (values == null) {
            Log.e("INTERPOLATION", "Kalibrierdaten für $p bar fehlen – Interpolation abgebrochen")
            return emptyMap()
        }
        data[p] = values
    }

    // 3) Now we know we have exactly 5 lists → Interpolation
    val interpolated = mutableMapOf<Float, List<Float>>()
    // 0.1-bar-Schritte von 0.5 bis 4.0
    val allSteps = (5..40).map { it / 10f }  // 0.5, 0.6, … 4.0

    for (step in allSteps) {
        // passenden Intervall-Partner finden
        val (low, high) = when {
            step <= 1.0f -> 0.5f to 1.0f
            step <= 2.0f -> 1.0f to 2.0f
            step <= 3.0f -> 2.0f to 3.0f
            else -> 3.0f to 4.0f
        }

        val lowData = data[low]!!
        val highData = data[high]!!

        // sichere Kürzung auf gleiche Länge
        val minLen = minOf(lowData.size, highData.size)
        val trimmedLow = lowData.take(minLen)
        val trimmedHigh = highData.take(minLen)

        // lineare Interpolation
        val t = (step - low) / (high - low)
        val profile = trimmedLow.zip(trimmedHigh).map { (l, h) ->
            l + t * (h - l)
        }
        interpolated[step] = profile
    }

    Log.i("INTERPOLATION", "Erzeugt ${interpolated.size} interpolierte Profile (0.5–4.0 bar)")
    return interpolated
}









