package smartbin.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Wi-Fi setup over Bluetooth with the bin's own GATT service, as the firmware
 * (ble_setup.cpp) and Gen's INTEGRATION.md describe it:
 *
 *   b1a70001 service     advertised by SmartBin-01 and SmartBin-02
 *   b1a70002 SSID        write, the network name
 *   b1a70003 PASS        write, the password
 *   b1a70004 CTRL        write one byte: 1 joins with SSID and PASS, 2 turns Bluetooth off
 *   b1a70005 STAT        read and notify, {"state":"WAITING|CONNECTING|CONNECTED|FAILED", ...}
 *   b1a70006 SCAN        read, up to 6 networks, strongest first
 *
 * Every characteristic needs an encrypted, passkey-authenticated link, so the
 * phone bonds first and Android shows its own box for the 6-digit code the bin
 * shows. One GATT call runs at a time; each waits for its callback.
 */
@SuppressLint("MissingPermission")
class BinBle(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    class Found(val name: String, val address: String, val rssi: Int, val device: BluetoothDevice)
    class Net(val ssid: String, val rssi: Int?)

    /** A link that drops at once: the phone or the bin still remembers an old pairing. */
    class StaleBond : IOException("The bin dropped the link")

    private var gatt: BluetoothGatt? = null
    private var chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val ops = Mutex()
    private var op: CompletableDeferred<ByteArray>? = null
    private val linkUp = MutableStateFlow(false)
    private val bond = MutableStateFlow(BluetoothDevice.BOND_NONE)
    private var receiver: BroadcastReceiver? = null

    /** Each STAT message the bin sends, whole. */
    var onStat: ((JSONObject) -> Unit)? = null
    /** The link dropped while connected. */
    var onDrop: (() -> Unit)? = null

    val device: BluetoothDevice? get() = gatt?.device
    val connected get() = linkUp.value

    // ---------- permissions and the adapter ----------

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed() = permissions().all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    val present get() = adapter != null
    val on get() = adapter?.isEnabled == true

    // ---------- scan ----------

    /** Lists bins nearby for [millis]: by the service in the advert, or by a name that starts with SmartBin. */
    suspend fun scan(millis: Long, onFound: (Found) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: throw IOException("Bluetooth is off")
        val seen = HashSet<String>()
        val svc = ParcelUuid(SVC)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: ""
                val ours = result.scanRecord?.serviceUuids?.contains(svc) == true || name.startsWith("SmartBin", ignoreCase = true)
                if (ours && seen.add(d.address)) onFound(Found(name.ifBlank { "SmartBin" }, d.address, result.rssi, d))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        try {
            delay(millis)
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    // ---------- connect, pair, read and write ----------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkUp.value = true
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = linkUp.value
                linkUp.value = false
                op?.completeExceptionally(IOException("The Bluetooth link dropped"))
                runCatching { g.close() }
                if (gatt === g) gatt = null
                if (was) onDrop?.invoke()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = done(status, ByteArray(0))

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = done(BluetoothGatt.GATT_SUCCESS, ByteArray(0))

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") done(status, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) = done(status, value)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = done(status, ByteArray(0))

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = done(status, ByteArray(0))

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") changed(ch, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = changed(ch, value)
    }

    private fun done(status: Int, value: ByteArray) {
        val d = op ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) d.complete(value)
        else d.completeExceptionally(GattError(status))
    }

    /** A notification is cut to 20 bytes on a small link; one that does not parse is read in full. */
    private fun changed(ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (ch.uuid != STAT) return
        val j = runCatching { JSONObject(String(value)) }.getOrNull()
        if (j != null) onStat?.invoke(j) else needFullStat = true
    }

    /** Set when a cut-off STAT notification came in; the waiting loop reads STAT then. */
    @Volatile var needFullStat = false

    class GattError(val status: Int) : IOException("Bluetooth error $status") {
        /** 5, 15 and 137: the link is not encrypted with a code the bin accepts. */
        val auth get() = status == 5 || status == 15 || status == 137
    }

    /** Runs one GATT call and waits for its answer. */
    private suspend fun call(timeout: Long = 8_000, start: (BluetoothGatt) -> Boolean): ByteArray = ops.withLock {
        val g = gatt ?: throw IOException("Not connected")
        val d = CompletableDeferred<ByteArray>()
        op = d
        try {
            if (!start(g)) throw IOException("Bluetooth is busy")
            withTimeout(timeout) { d.await() }
        } finally {
            op = null
        }
    }

    /**
     * Connects to the bin and finds its characteristics. A phone that remembers
     * an old pairing often gets dropped at once, so it tries 3 times before
     * saying so with [StaleBond].
     */
    suspend fun connect(device: BluetoothDevice) {
        close()
        watchBond(device)
        repeat(3) { i ->
            val ok = runCatching {
                val g = if (Build.VERSION.SDK_INT >= 26) device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
                else device.connectGatt(app, false, callback)
                gatt = g
                withTimeout(12_000) { linkUp.first { it } }
                delay(400)
                // A larger packet size, so a whole status message fits in one notification.
                runCatching { call(3_000) { it.requestMtu(247) } }
                call(10_000) { it.discoverServices() }
                val svc = gatt?.getService(SVC) ?: throw IOException("This is not a Smart-Bin")
                chars = HashMap(svc.characteristics.associateBy { it.uuid })
                if (STAT !in chars || SSID !in chars) throw IOException("This bin has no Wi-Fi setup")
            }.isSuccess
            if (ok) return
            close()
            if (i < 2) delay(1200)
        }
        throw StaleBond()
    }

    /**
     * Pairs, so Android asks for the bin's 6-digit code in its own box. Waits as
     * long as the code lasts on the bin (60 s), then reads STAT to prove the
     * link is encrypted. Returns the STAT message.
     */
    suspend fun secure(): JSONObject {
        val d = gatt?.device ?: throw IOException("Not connected")
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            if (d.bondState != BluetoothDevice.BOND_BONDING && !d.createBond()) throw IOException("Android would not start pairing")
            val end = withTimeoutOrNull(75_000) { bond.first { it == BluetoothDevice.BOND_BONDED || it == BOND_FAILED } }
            if (end != BluetoothDevice.BOND_BONDED) throw IOException("No code was typed, or it was wrong")
            delay(600)
        }
        var last: Exception? = null
        repeat(3) {
            try {
                return readStat()
            } catch (e: GattError) {
                last = e
                if (e.auth) throw StaleBond()
                delay(1500)
            } catch (e: Exception) {
                last = e
                delay(1500)
            }
        }
        throw last ?: IOException("The bin did not answer")
    }

    suspend fun readStat(): JSONObject = JSONObject(String(read(STAT)))

    /** Turns on STAT notifications. */
    suspend fun listen() {
        val ch = chars[STAT] ?: return
        val g = gatt ?: return
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        call {
            if (Build.VERSION.SDK_INT >= 33) it.writeDescriptor(cccd, v) == 0
            else {
                @Suppress("DEPRECATION") cccd.value = v
                @Suppress("DEPRECATION") it.writeDescriptor(cccd)
            }
        }
    }

    /** The networks the bin found, strongest first. Empty while it is still scanning. */
    suspend fun networks(): List<Net> {
        if (SCAN !in chars) return emptyList()
        val a = JSONArray(String(read(SCAN)))
        return (0 until a.length()).mapNotNull { i ->
            when (val o = a.opt(i)) {
                is JSONObject -> o.optString("ssid").takeIf { it.isNotBlank() }?.let { Net(it, if (o.has("rssi")) o.optInt("rssi") else null) }
                is String -> o.takeIf { it.isNotBlank() }?.let { Net(it, null) }
                else -> null
            }
        }
    }

    /** Hands over the network and asks the bin to join it. The answer comes on STAT. */
    suspend fun join(ssid: String, pass: String) {
        write(SSID, ssid.toByteArray())
        write(PASS, pass.toByteArray())
        write(CTRL, byteArrayOf(1))
    }

    /** Turns Bluetooth off on the bin; it stays on its Wi-Fi. */
    suspend fun off() {
        runCatching { write(CTRL, byteArrayOf(2)) }
        close()
    }

    private suspend fun read(uuid: UUID): ByteArray {
        val ch = chars[uuid] ?: throw IOException("Missing $uuid")
        return call { it.readCharacteristic(ch) }
    }

    private suspend fun write(uuid: UUID, value: ByteArray) {
        val ch = chars[uuid] ?: throw IOException("Missing $uuid")
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        call {
            if (Build.VERSION.SDK_INT >= 33) it.writeCharacteristic(ch, value, type) == 0
            else {
                @Suppress("DEPRECATION") ch.value = value
                ch.writeType = type
                @Suppress("DEPRECATION") it.writeCharacteristic(ch)
            }
        }
    }

    fun close() {
        val g = gatt
        gatt = null
        linkUp.value = false
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
    }

    /** Ends everything, when the page or the app goes away. */
    fun release() {
        close()
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }

    private fun watchBond(device: BluetoothDevice) {
        bond.value = device.bondState
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d == null || d.address != gatt?.device?.address) return
                val now = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val before = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                bond.value = if (now == BluetoothDevice.BOND_NONE && before == BluetoothDevice.BOND_BONDING) BOND_FAILED else now
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(r, filter)
        receiver = r
    }

    /**
     * Makes Android forget the bin, for a pairing the bin no longer knows. There
     * is no public call for it, so this uses the hidden one most phones keep;
     * the caller opens Android's Bluetooth settings when it fails.
     */
    fun forget(device: BluetoothDevice): Boolean = runCatching {
        device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
    }.getOrDefault(false)

    companion object {
        private fun u(n: String) = UUID.fromString("b1a7000$n-5c2e-4f8a-9d33-0a9c2b6f17c1")
        val SVC: UUID = u("1")
        val SSID: UUID = u("2")
        val PASS: UUID = u("3")
        val CTRL: UUID = u("4")
        val STAT: UUID = u("5")
        val SCAN: UUID = u("6")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BOND_FAILED = -1
    }
}
