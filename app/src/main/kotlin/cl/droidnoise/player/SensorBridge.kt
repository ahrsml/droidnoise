package cl.droidnoise.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.puredata.core.PdBase

/**
 * Manda los sensores crudos a Pd. Sin suavizado: el craft va en el parche.
 * Throttle por receiver a [maxHz] usando el timestamp del evento.
 */
class SensorBridge(context: Context, maxHz: Int) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val minIntervalNs = 1_000_000_000L / maxHz
    private val thread = HandlerThread("sensors")
    private val lastSentNs = HashMap<String, Long>()
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    // Últimos valores de sensores "on change": se reenvían tras recargar el parche.
    @Volatile private var lastProx: Float? = null
    @Volatile private var lastProxNear: Float? = null
    @Volatile private var lastLight: Float? = null

    private val sensors: Map<String, Sensor?> = linkedMapOf(
        "acc" to (sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)),
        "grav" to sm.getDefaultSensor(Sensor.TYPE_GRAVITY),
        "gyro" to sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
        "ori" to sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        "prox" to sm.getDefaultSensor(Sensor.TYPE_PROXIMITY),
        "light" to sm.getDefaultSensor(Sensor.TYPE_LIGHT),
        "mag" to sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
    )
    private val receiverOf: Map<Sensor, String> =
        sensors.entries.mapNotNull { (k, s) -> s?.let { it to k } }.toMap()

    val available: List<String> get() = sensors.filterValues { it != null }.keys.toList()
    val missing: List<String> get() = sensors.filterValues { it == null }.keys.toList()

    fun start() {
        thread.start()
        val handler = Handler(thread.looper)
        sensors.forEach { (name, sensor) ->
            if (sensor == null) {
                Log.w(TAG, "sensor no disponible: $name")
            } else {
                Log.i(TAG, "sensor $name: ${sensor.name} (${sensor.vendor})")
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread.quitSafely()
    }

    fun resendLatched() {
        lastProx?.let { PdBase.sendFloat("prox", it) }
        lastProxNear?.let { PdBase.sendFloat("proxnear", it) }
        lastLight?.let { PdBase.sendFloat("light", it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val receiver = receiverOf[event.sensor] ?: return
        val last = lastSentNs[receiver] ?: 0L
        if (event.timestamp - last < minIntervalNs) return
        lastSentNs[receiver] = event.timestamp

        val v = event.values
        when (receiver) {
            "ori" -> {
                SensorManager.getRotationMatrixFromVector(rotation, v)
                SensorManager.getOrientation(rotation, orientation)
                for (i in 0..2) AudioService.Status.ori[i] = Math.toDegrees(orientation[i].toDouble()).toFloat()
                PdBase.sendList(
                    "ori",
                    Math.toDegrees(orientation[0].toDouble()).toFloat(),
                    Math.toDegrees(orientation[1].toDouble()).toFloat(),
                    Math.toDegrees(orientation[2].toDouble()).toFloat(),
                )
            }
            "prox" -> {
                val cm = v[0]
                val near = if (cm < event.sensor.maximumRange) 1f else 0f
                lastProx = cm
                lastProxNear = near
                PdBase.sendFloat("prox", cm)
                PdBase.sendFloat("proxnear", near)
            }
            "light" -> {
                lastLight = v[0]
                AudioService.Status.light = v[0]
                PdBase.sendFloat("light", v[0])
            }
            else -> {
                if (receiver == "gyro") AudioService.Status.gyro = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
                PdBase.sendList(receiver, v[0], v[1], v[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
