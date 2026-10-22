package app.opengan.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

data class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * Coroutine wrapper around [BluetoothGatt].
 *
 * Android allows only one outstanding GATT operation at a time, so every
 * operation takes [opLock] and waits for its callback before returning.
 * Permission checks happen before a connection is created (see DeviceManager).
 */
@SuppressLint("MissingPermission")
class GattConnection private constructor(val device: BluetoothDevice) {

    private lateinit var gatt: BluetoothGatt
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Any?>? = null
    private val connected = CompletableDeferred<Unit>()

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256)
    val notifications: SharedFlow<Notification> = _notifications

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _isConnected.value = true
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _isConnected.value = false
                connected.completeExceptionally(GattException("Connection failed (status $status)"))
                pending?.completeExceptionally(GattException("Disconnected"))
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, Unit)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, value)

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish(status, c.value?.copyOf() ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status, Unit)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status, Unit)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.uuid, value.copyOf()))
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // The value buffer is reused by the stack, so copy it right away.
            if (Build.VERSION.SDK_INT < 33) _notifications.tryEmit(Notification(c.uuid, c.value?.copyOf() ?: return))
        }
    }

    private fun finish(status: Int, result: Any?) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(result) else p.completeExceptionally(GattException("GATT error $status"))
    }

    private suspend fun <T> op(timeoutMs: Long = 5000, start: () -> Boolean): T = opLock.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            if (!start()) throw GattException("GATT operation could not be started")
            @Suppress("UNCHECKED_CAST")
            withTimeout(timeoutMs) { d.await() } as T
        } finally {
            pending = null
        }
    }

    val services: List<BluetoothGattService> get() = gatt.services.orEmpty()

    fun service(uuid: String): BluetoothGattService? = gatt.getService(UUID.fromString(uuid))

    fun characteristic(service: String, characteristic: String): BluetoothGattCharacteristic =
        service(service)?.getCharacteristic(UUID.fromString(characteristic))
            ?: throw GattException("Characteristic $characteristic not found")

    suspend fun discoverServices() {
        op<Unit>(timeoutMs = 10_000) { gatt.discoverServices() }
    }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray = op { gatt.readCharacteristic(c) }

    @Suppress("DEPRECATION") // pre-Android 13 write API
    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean = true) {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op<Unit> {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = type
                c.value = value
                gatt.writeCharacteristic(c)
            }
        }
    }

    @Suppress("DEPRECATION") // pre-Android 13 write API
    suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(c, true)) throw GattException("Cannot enable notifications")
        val cccd = c.getDescriptor(CCCD) ?: return // some devices notify without a CCCD
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Unit> {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                cccd.value = value
                gatt.writeDescriptor(cccd)
            }
        }
    }

    fun disconnect() {
        if (::gatt.isInitialized) {
            gatt.disconnect()
            if (!_isConnected.value) gatt.close()
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun connect(context: Context, device: BluetoothDevice): GattConnection {
            val conn = GattConnection(device)
            conn.gatt = device.connectGatt(context, false, conn.callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(15_000) { conn.connected.await() }
                conn.discoverServices()
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            return conn
        }
    }
}
