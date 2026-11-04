package cz.robotcockpit.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class LidarDevice(val device: BluetoothDevice, val name: String, val address: String)
data class LidarFrame(val rangesCm: IntArray, val rpm: Int, val receivedAtMs: Long)

@SuppressLint("MissingPermission")
class LidarBleClient(
    context: Context,
    private val onDevices: (List<LidarDevice>) -> Unit,
    private val onFrame: (LidarFrame) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a71d0001-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val DATA_UUID: UUID = UUID.fromString("a71d0002-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val FRAGMENTS = 23
        private const val BINS = 360
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter
    private val main = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, LidarDevice>()
    private var gatt: BluetoothGatt? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var scanning = false
    private var activeSequence = -1
    private val ranges = IntArray(BINS)
    private val received = BooleanArray(FRAGMENTS)
    private var receivedCount = 0
    private var currentRpm = 0

    fun startScan() {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            status("Bluetooth LE není dostupné")
            return
        }
        found.clear()
        publishDevices()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address ?: return
                val name = result.scanRecord?.deviceName ?: try { device.name } catch (_: SecurityException) { null } ?: "BLE zařízení"
                found[address] = LidarDevice(device, name, address)
                publishDevices()
            }
            override fun onScanFailed(errorCode: Int) {
                scanning = false
                status("BLE skenování selhalo ($errorCode)")
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(callback)
            scanning = true
            status("Vyhledávám BLE zařízení…")
        } catch (e: Exception) {
            status("Nelze spustit BLE scan: ${e.message}")
        }
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        scanCallback = null
        scanning = false
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        status("Připojuji k ${device.address}…")
        try {
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
        } catch (e: Exception) {
            status("GATT připojení selhalo: ${e.message}")
        }
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        dataCharacteristic = null
        resetFrame()
    }

    fun close() {
        stopScan()
        disconnect()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("GATT chyba: $statusCode")
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("BLE připojeno · hledám datovou službu")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dataCharacteristic = null
                status("BLE odpojeno")
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Nelze načíst BLE služby ($statusCode)")
                return
            }
            val service = g.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(DATA_UUID)
            if (characteristic == null) {
                status("Zařízení nemá očekávanou LDS BLE službu")
                return
            }
            dataCharacteristic = characteristic
            val enabled = g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (!enabled || descriptor == null) {
                status("Nelze zapnout LDS notifikace")
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (result != BluetoothGatt.GATT_SUCCESS) status("Zápis CCCD selhal ($result)")
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }

        @Deprecated("Deprecated on Android 13; retained for Android 8–12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            acceptPacket(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            acceptPacket(value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                status(if (statusCode == BluetoothGatt.GATT_SUCCESS) "LDS BLE stream aktivní" else "Aktivace notifikací selhala ($statusCode)")
            }
        }
    }

    @Synchronized
    private fun acceptPacket(packet: ByteArray) {
        if (packet.size < 20 || (packet[0].toInt() and 0xff) != 0xD3) return
        val sequence = packet[1].toInt() and 0xff
        val fragment = packet[2].toInt() and 0xff
        if (fragment !in 0 until FRAGMENTS) return
        if (sequence != activeSequence || fragment == 0) {
            activeSequence = sequence
            java.util.Arrays.fill(received, false)
            java.util.Arrays.fill(ranges, 0)
            receivedCount = 0
        }
        if (!received[fragment]) {
            received[fragment] = true
            receivedCount++
        }
        currentRpm = (packet[3].toInt() and 0xff) * 2
        val start = fragment * 16
        val count = if (fragment == FRAGMENTS - 1) 8 else 16
        for (i in 0 until count) {
            ranges[start + i] = (packet[4 + i].toInt() and 0xff) * 5
        }
        if (receivedCount == FRAGMENTS) {
            val frame = LidarFrame(ranges.copyOf(), currentRpm, System.currentTimeMillis())
            main.post { onFrame(frame) }
            resetFrame()
        }
    }

    @Synchronized
    private fun resetFrame() {
        activeSequence = -1
        receivedCount = 0
        java.util.Arrays.fill(received, false)
        java.util.Arrays.fill(ranges, 0)
    }

    private fun publishDevices() {
        val snapshot = found.values.sortedBy { it.name.lowercase() }
        main.post { onDevices(snapshot) }
    }

    private fun status(message: String) {
        main.post { onStatus(message) }
    }
}
