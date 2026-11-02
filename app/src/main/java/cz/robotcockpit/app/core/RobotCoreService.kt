package cz.robotcockpit.app.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent Bluetooth Classic SPP connection owner.
 * Start explicitly while app is visible; keep the ESP32 watchdog enabled.
 */
class RobotCoreService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): RobotCoreService = this@RobotCoreService
    }

    private val binder = LocalBinder()
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastTelemetryJson: String? = null
        private set
    @Volatile var lastError: String? = null
        private set
    private val writeLock = Any()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MAROS HOME ROBOT")
            .setContentText("Robot Core běží – Bluetooth SPP")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address.isNullOrBlank()) {
            lastError = "Chybí Bluetooth adresa ESP32"
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            executor.execute { connectAndRead(address) }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectAndRead(address: String) {
        try {
            val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                ?: error("Bluetooth adaptér není dostupný")
            val candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = candidate
            candidate.connect()
            output = candidate.outputStream
            connected = true
            lastError = null
            send(RobotCommandProtocol.hello())
            send(RobotCommandProtocol.stop())

            val reader = BufferedReader(InputStreamReader(candidate.inputStream, Charsets.UTF_8))
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    lastTelemetryJson = line
                    // A malformed or unexpected packet is retained for diagnostics only.
                    runCatching { JSONObject(line) }
                }
            }
            if (running.get()) lastError = "Bluetooth SPP spojení bylo přerušeno"
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        } finally {
            connected = false
            running.set(false)
            closeSocket()
            stopSelf()
        }
    }

    fun sendDrive(throttle: Double, steer: Double) {
        if (!connected) return
        send(RobotCommandProtocol.drive(throttle, steer))
    }

    fun arm() {
        if (connected) send(RobotCommandProtocol.arm(true))
    }

    fun stopRobot() {
        // STOP is attempted even when telemetry has stopped, if the socket still exists.
        send(RobotCommandProtocol.stop())
    }

    private fun send(line: String) {
        try {
            synchronized(writeLock) {
                val stream = output ?: return
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        } catch (e: Exception) {
            lastError = "SPP zápis selhal: ${e.message}"
        }
    }

    override fun onDestroy() {
        stopRobot()
        running.set(false)
        closeSocket()
        executor.shutdownNow()
        connected = false
        super.onDestroy()
    }

    private fun closeSocket() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Robot Core", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val CHANNEL_ID = "maros_robot_core"
        private const val NOTIFICATION_ID = 701
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
