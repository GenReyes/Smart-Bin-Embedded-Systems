package smartbin.data

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Invented readings for bins that have no address yet, so the whole app
 * works with no hardware. The numbers move the way the dashboard's demo
 * moves them, and stay inside what the HC-SR04, HX711 and BME680 can report.
 * One demo minute fills a bin as much as one real hour would.
 */
object Demo {
    /** How many times faster than real life the demo bins fill. Demo options can change it; 60 is normal. */
    const val NORMAL_SPEED = 60.0
    var speed = NORMAL_SPEED

    fun bins(): List<Bin> = listOf(
        bin(0, "Bin 01", "Main Hall", fill = 34.0, weight = 1430.0, temp = 28.0, hum = 62.0, gas = 72.0, rate = 0.38),
        bin(1, "Bin 02", "Cafeteria", fill = 62.0, weight = 2570.0, temp = 28.1, hum = 63.0, gas = 118.0, rate = 3.1),
    )

    private val PLACES = listOf("Main Hall", "Cafeteria", "Library", "Gym", "Lobby", "Parking", "Admin", "Lab A", "Lab B", "Clinic", "Chapel", "Field")

    /** A new bin from the Add sheet: empty, demo data until it gets an address. */
    fun newBin(index: Int, id: String): Bin =
        bin(index, "Bin %02d".format(index + 1), "New location", fill = 0.0, weight = 0.0, temp = 27.6, hum = 58.0, gas = 40.0, rate = 0.0, id = id)

    fun placeFor(index: Int) = PLACES[index % PLACES.size]

    private fun bin(
        i: Int, name: String, place: String, fill: Double, weight: Double, temp: Double, hum: Double, gas: Double, rate: Double,
        id: String = "b${i + 1}",
    ): Bin {
        val cal = DEFAULT_CAL
        return Bin(
            id = id, cloudId = "bin%02d".format(i + 1), name = name, place = place, cal = cal, rate = rate,
            reading = Reading(
                fill = fill, weight = weight, temp = temp, tempRaw = temp - cal.v("temp_off"), hum = hum, press = 1008.4, gas = gas,
                distance = distanceFor(fill, cal), at = System.currentTimeMillis(),
            ),
        )
    }

    fun distanceFor(fill: Double, cal: Cal) = cal.v("d_empty") - fill / 100.0 * (cal.v("d_empty") - cal.v("d_full"))

    /** One second of the demo for one bin. [calibrating] is the app's calibration switch. */
    fun step(b: Bin, calibrating: Boolean, now: Long): Reading {
        val r = b.reading
        val s = b.sim
        val c = b.cal
        val sv = s.values
        // BME680: the air quality index drifts toward a level set by how full the bin is;
        // the raw temperature carries about 2 C of self-heating that the offset removes.
        val gas = if (s.on(2)) sv["gas"] ?: r.gas else (r.gas + ((35 + r.fill * 2.2) - r.gas) * 0.02 + (Random.nextDouble() - 0.5) * 5).coerceIn(10.0, 500.0)
        val temp: Double
        val tempRaw: Double
        val hum: Double
        if (s.on(3)) {
            temp = sv["temp"] ?: r.temp
            tempRaw = temp - c.v("temp_off")
            hum = sv["humidity"] ?: r.hum
        } else {
            tempRaw = (r.tempRaw + (Random.nextDouble() - 0.5) * 0.08).coerceIn(24.0, 34.0)
            temp = tempRaw + c.v("temp_off")
            hum = (r.hum + (Random.nextDouble() - 0.5) * 0.6).coerceIn(35.0, 95.0)
        }
        val press = (r.press + (Random.nextDouble() - 0.5) * 0.06).coerceIn(985.0, 1030.0)
        val iaqAcc = r.iaqAcc.let { if (it < 3 && Random.nextDouble() < 0.05) it + 1 else it }
        val base = r.copy(gas = gas, temp = temp, tempRaw = tempRaw, hum = hum, press = press, iaqAcc = iaqAcc, at = now)

        if (calibrating) {
            return base.copy(status = BinStatus.CALIBRATING, lidOpen = false, distance = c.v("d_empty") - Random.nextDouble() * 0.4)
        }
        val leaving = r.status == BinStatus.CALIBRATING
        var status = if (leaving) BinStatus.OK else r.status
        val fill = when {
            s.on(0) -> (sv["fill"] ?: r.fill).coerceIn(0.0, 100.0)
            status == BinStatus.COLLECTING -> r.fill
            else -> (r.fill + b.rate * speed / 3600.0 * (0.7 + Random.nextDouble() * 0.6)).coerceIn(0.0, 100.0)
        }
        val weight = if (s.on(1)) sv["weight"] ?: r.weight
        else (fill / 100 * c.v("weight_cap") * 0.92 + Random.nextDouble() * 25).coerceIn(0.0, 5000.0).roundToInt().toDouble()
        val tilt = if (s.on(4) && (sv["tamper"] ?: 0.0) > 0) 38.0 else Random.nextDouble() * 2.2
        val motion = if (s.on(5)) (sv["motion"] ?: 0.0) > 0 else Random.nextDouble() < 0.025
        val distance = floor(distanceFor(fill, c) * 10) / 10
        var lid: Boolean
        when {
            status == BinStatus.COLLECTING -> lid = true
            tilt > c.v("tilt_th") -> { status = BinStatus.TAMPER; lid = false }
            status == BinStatus.TAMPER -> { lid = false }
            status == BinStatus.LOCKED -> lid = false
            c.v("auto_lock") > 0 && fill >= c.v("fill_th") -> { status = BinStatus.LOCKED; lid = false }
            else -> lid = if (s.on(6)) (sv["lid"] ?: 0.0) > 0 else motion
        }
        return base.copy(fill = fill, weight = weight, tilt = tilt, motion = motion, distance = distance, lidOpen = lid, status = status)
    }

    /** A day of made-up history so the charts have something to draw in demo mode. */
    fun history(b: Bin, now: Long): List<Sample> {
        val n = 24 * 60
        val rnd = Random(b.id.hashCode())
        val out = ArrayList<Sample>(n)
        var fill = b.reading.fill
        var temp = b.reading.temp
        var hum = b.reading.hum
        var press = b.reading.press
        var gas = b.reading.gas
        for (k in 0 until n) {
            out.add(Sample(now - k * 60_000L, fill.toFloat(), temp.toFloat(), hum.toFloat(), press.toFloat(), gas.toFloat()))
            // Backwards in time: the bin was emptier before, and was emptied every few hours.
            fill -= b.rate / 60.0 + (rnd.nextDouble() - 0.5) * 0.25
            if (fill < 2) fill = 60 + rnd.nextDouble() * 25
            temp = (temp + (rnd.nextDouble() - 0.5) * 0.3).coerceIn(22.0, 36.0)
            hum = (hum + (rnd.nextDouble() - 0.5) * 1.1).coerceIn(35.0, 95.0)
            press = (press + (rnd.nextDouble() - 0.5) * 0.18).coerceIn(990.0, 1025.0)
            gas = (gas + (rnd.nextDouble() - 0.5) * 7 - (fill - 30) * 0.004).coerceIn(15.0, 400.0)
        }
        return out.asReversed()
    }

    /** The mockup's example history, so the alert tools have something to act on. Only when no bin has an address. */
    fun alerts(bins: List<Bin>, now: Long): List<AlertRow> {
        val a = bins.getOrNull(0)?.name ?: "Bin 01"
        val b = bins.getOrNull(1)?.name ?: a
        val m = 60_000L
        val rows = listOf(
            Triple(2, Severity.CRITICAL, listOf("fill", "Fill threshold passed", "$b is at 81%")),
            Triple(9, Severity.WARNING, listOf("gas", "Air quality warning", "$b index 212, limit 200")),
            Triple(34, Severity.CRITICAL, listOf("tamper", "Tamper detected", "$a was moved or tilted")),
            Triple(41, Severity.WARNING, listOf("humid", "Humidity high", "$b at 88%, limit 85%")),
            Triple(60, Severity.INFO, listOf("lock", "Bin locked", "$a locked from the app")),
            Triple(62, Severity.SUCCESS, listOf("unlock", "Bin unlocked", "$a is open for use again")),
            Triple(180, Severity.SUCCESS, listOf("truck", "Collection complete", "$b is back to normal")),
            Triple(300, Severity.INFO, listOf("calib", "Calibration started", "$a is being calibrated")),
            Triple(302, Severity.SUCCESS, listOf("save", "Settings saved", "$a: empty 30 cm, full 5 cm, alert at 80%")),
            Triple(1260, Severity.CRITICAL, listOf("temp", "Temperature high", "$a at 41.2 °C")),
            Triple(4320, Severity.SUCCESS, listOf("truck", "Collection complete", "$a is back to normal")),
            Triple(17280, Severity.CRITICAL, listOf("fill", "Fill threshold passed", "$a is at 83%")),
        )
        return rows.mapIndexed { i, (mins, sev, x) ->
            val icon = x[0]
            val shown = when (icon) { "unlock" -> "check"; "save" -> "check"; else -> icon }
            AlertRow(
                id = (i + 1).toLong(), sev = sev, icon = shown, kind = kindFor(icon), title = x[1], meta = x[2],
                at = now - mins * m, read = i >= 3,
            )
        }
    }

    fun kindFor(icon: String): AlertKind = when (icon) {
        "fill" -> AlertKind.FULL
        "temp", "humid", "gas" -> AlertKind.SENSOR
        "tamper" -> AlertKind.TAMPER
        "lock", "unlock" -> AlertKind.LOCK
        "truck" -> AlertKind.PICKUP
        else -> AlertKind.SETUP
    }
}
