package cz.robotcockpit.app.lidar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class LdsBridgeDevice(val name: String, val address: String, val rssi: Int)

data class LdsBridgeState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val status: String = "Bridge nenalezen",
    val deviceName: String = "",
    val address: String = "",
    val packets: Long = 0,
    val scans: Long = 0,
    val rpm: Int = 0,
    val validPoints: Int = 0,
    val nearestMeters: Float? = null,
    val lastScan: MarosLdsScan? = null,
    val error: String? = null,
    val devices: List<LdsBridgeDevice> = emptyList()
)

/**
 * Direct BLE GATT client for the Xiaomi LDS Bridge defined by MAROS V24.7.2.
 * Android system-level Bluetooth bonding is not used or required.
 */
class LdsBridgeLink(
    context: Context,
    private val onState: (LdsBridgeState) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val assembler = MarosD3ScanAssembler()
    private val found = LinkedHashMap<String, LdsBridgeDevice>()
    @Volatile private var state = LdsBridgeState()

    private val serviceUuid = UUID.fromString(MarosD3ScanAssembler.SERVICE_UUID)
    private val characteristicUuid = UUID.fromString(MarosD3ScanAssembler.CHARACTERISTIC_UUID)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun publish(next: LdsBridgeState) {
        state = next
        main.post { onState(next) }
    }

    fun currentState(): LdsBridgeState = state
    @Synchronized fun devices(): List<LdsBridgeDevice> = found.values.toList()

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null || !adapter.isEnabled) {
            publish(state.copy(scanning = false, error = "Bluetooth je vypnutý nebo není podporován"))
            return
        }
        stopScan()
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            publish(state.copy(error = "Android neposkytl BLE scanner"))
            return
        }
        scanner = leScanner
        found.clear()
        publish(state.copy(scanning = true, status = "Vyhledávám Xiaomi LDS Bridge…", error = null, devices = emptyList()))
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val advertisedName = record?.deviceName
                val name = advertisedName ?: try { result.device.name } catch (_: SecurityException) { null } ?: ""
                val advertisesService = record?.serviceUuids?.any { it.uuid == serviceUuid } == true
                val normalized = name.lowercase()
                val isBridge = advertisesService || normalized.contains("xiaomi") ||
                    normalized.contains("lidar") || normalized.contains("lds") || normalized.contains("maros")
                if (!isBridge) return
                val device = LdsBridgeDevice(
                    name = name.ifBlank { "Xiaomi LDS Bridge" },
                    address = result.device.address,
                    rssi = result.rssi
                )
                val snapshot = synchronized(this@LdsBridgeLink) {
                    found[device.address] = device
                    found.values.sortedByDescending { it.rssi }
                }
                publish(state.copy(scanning = true, status = "Nalezen LDS Bridge", error = null, devices = snapshot))
            }

            override fun onScanFailed(errorCode: Int) {
                publish(state.copy(scanning = false, status = "BLE sken selhal", error = "Scan error $errorCode"))
            }
        }
        scanCallback = callback
        try {
            leScanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
        } catch (e: SecurityException) {
            publish(state.copy(scanning = false, error = "Chybí oprávnění BLUETOOTH_SCAN"))
            return
        } catch (e: Exception) {
            publish(state.copy(scanning = false, error = e.message ?: "BLE scan nelze spustit"))
            return
        }
        main.postDelayed({
            stopScan()
            if (!state.connected) publish(state.copy(status = if (found.isEmpty()) "Bridge nenalezen" else "Vyber nalezený Bridge", scanning = false, devices = synchronized(this@LdsBridgeLink) { found.values.sortedByDescending { it.rssi } }))
        }, 12000L)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val activeScanner = scanner
        val callback = scanCallback
        if (activeScanner != null && callback != null) {
            try { activeScanner.stopScan(callback) } catch (_: Exception) {}
        }
        scanCallback = null
        scanner = null
        if (state.scanning) publish(state.copy(scanning = false))
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        disconnect(keepStatus = true)
        if (adapter == null || !adapter.isEnabled) {
            publish(state.copy(error = "Bluetooth je vypnutý"))
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            publish(state.copy(status = "Připojuji BLE GATT…", address = address, deviceName = device.name ?: "Xiaomi LDS Bridge", error = null))
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
            if (gatt == null) publish(state.copy(status = "GATT spojení se nepodařilo zahájit", error = "connectGatt vrátil null"))
        } catch (e: SecurityException) {
            publish(state.copy(error = "Chybí oprávnění BLUETOOTH_CONNECT"))
        } catch (e: Exception) {
            publish(state.copy(status = "BLE připojení selhalo", error = e.message))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(keepStatus: Boolean = false) {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        notifyCharacteristic = null
        assembler.reset()
        if (!keepStatus) publish(state.copy(connected = false, notificationsEnabled = false, status = "BLE odpojeno", error = null))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                publish(state.copy(connected = false, notificationsEnabled = false, status = "GATT chyba", error = "Status $statusCode"))
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    publish(state.copy(connected = true, status = "BLE připojeno · hledám službu LDS", error = null))
                    try { g.discoverServices() } catch (e: Exception) {
                        publish(state.copy(error = e.message ?: "discoverServices selhalo"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    publish(state.copy(connected = false, notificationsEnabled = false, status = "Bridge odpojen", error = null))
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    notifyCharacteristic = null
                    assembler.reset()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                publish(state.copy(error = "Načtení BLE služeb selhalo: $statusCode"))
                return
            }
            val service = g.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                publish(state.copy(status = "Bridge nemá očekávanou LDS službu", error = "Chybí GATT $serviceUuid / $characteristicUuid"))
                return
            }
            val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!canNotify) {
                publish(state.copy(error = "LDS charakteristika nepodporuje NOTIFY"))
                return
            }
            notifyCharacteristic = characteristic
            @SuppressLint("MissingPermission")
            val localEnabled = try { g.setCharacteristicNotification(characteristic, true) } catch (_: Exception) { false }
            if (!localEnabled) {
                publish(state.copy(error = "Nelze zapnout lokální BLE notifikace"))
                return
            }
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor == null) {
                publish(state.copy(error = "LDS charakteristika nemá CCCD descriptor 0x2902"))
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val result = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    if (result != BluetoothGatt.GATT_SUCCESS) publish(state.copy(error = "Zápis CCCD selhal: $result"))
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    if (!g.writeDescriptor(descriptor)) publish(state.copy(error = "Nelze zapsat CCCD descriptor"))
                }
            } catch (e: Exception) {
                publish(state.copy(error = e.message ?: "Aktivace notifikací selhala"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (descriptor.uuid != cccdUuid) return
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                publish(state.copy(connected = true, notificationsEnabled = true, status = "LDS BLE stream aktivní", error = null))
            } else {
                publish(state.copy(notificationsEnabled = false, error = "CCCD notifikace odmítnuty: $statusCode"))
            }
        }

        @Deprecated("Deprecated by Android API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic, value)
        }
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != characteristicUuid) return
        val scan = assembler.accept(value) ?: run {
            publish(state.copy(packets = assembler.acceptedPackets, error = null))
            return
        }
        publish(state.copy(
            connected = true,
            notificationsEnabled = true,
            status = "Přijímám kompletní 360° sken",
            packets = assembler.acceptedPackets,
            scans = assembler.completedScans,
            rpm = scan.rpm,
            validPoints = scan.validPoints,
            nearestMeters = scan.nearestMeters,
            lastScan = scan,
            error = null
        ))
    }

    fun close() {
        stopScan()
        disconnect()
    }
}
