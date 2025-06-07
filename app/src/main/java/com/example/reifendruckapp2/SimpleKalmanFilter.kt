package com.example.reifendruckapp2

class SimpleKalmanFilter(
    private val q: Float,        // Prozessrauschen
    private val r: Float,        // Messrauschen
    initialX: Float,              // Start-Schätzung
    initialP: Float               // Start-Fehlerkovarianz
) {
    private var x = initialX     // aktuelle Schätzung
    private var p = initialP     // Schätzfehlerkovarianz

    /**
     * @param  z  neue Messung
     * @return geschätzter, gefilterter Wert
     */
    fun update(z: Float): Float {
        // 1) Prediction
        p += q

        // 2) Kalman-Gain
        val k = p / (p + r)

        // 3) Update Schätzung
        x += k * (z - x)

        // 4) Update Kovarianz
        p *= (1 - k)

        return x
    }
}
