/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Toast
import androidx.core.app.NotificationCompat
import gobind.Conduit
import gobind.DendriteMonolith
import gobind.Gobind
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

class DendriteService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var vectorPreferences = VectorPreferences(this)

    private var notificationChannel = NotificationChannel("im.vector.p2p", "Element P2P", NotificationManager.IMPORTANCE_DEFAULT)
    private var notificationManager: NotificationManager? = null
    private var notification: Notification? = null

    private val binder = DendriteLocalBinder()
    private var monolith: DendriteMonolith? = null
    private val serviceUUID = ParcelUuid(UUID.fromString("a2fda8dd-d250-4a64-8b9a-248f50b93c64"))
    private val psmUUID = UUID.fromString("15d4151b-1008-41c0-85f2-950facf8a3cd")

    private lateinit var manager: BluetoothManager
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var gattCharacteristic: BluetoothGattCharacteristic
    private var gattService = BluetoothGattService(serviceUUID.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    private var adapter = BluetoothAdapter.getDefaultAdapter()
    private lateinit var advertiser: BluetoothLeAdvertiser
    private lateinit var scanner: BluetoothLeScanner

    private lateinit var l2capServer: BluetoothServerSocket
    private lateinit var l2capPSM: ByteArray

    private var connecting: MutableMap<String, Boolean> = mutableMapOf<String, Boolean>()
    private var connections: MutableMap<String, DendriteBLEPeering> = mutableMapOf<String, DendriteBLEPeering>()
    private var conduits: MutableMap<String, Conduit> = mutableMapOf<String, Conduit>()

    private val bleReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return
            }
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> return
                    BluetoothAdapter.STATE_TURNING_OFF -> stopBluetooth()
                    BluetoothAdapter.STATE_ON -> startBluetooth()
                    BluetoothAdapter.STATE_TURNING_ON -> return
                }
            }
        }
    }

    inner class DendriteLocalBinder : Binder() {
        fun getService() : DendriteService {
            return this@DendriteService
        }
    }

    inner class DendriteBLEPeering(private var conduit: Conduit, private var socket: BluetoothSocket) {
        public val isConnected: Boolean
            get() = socket.isConnected

        private var bleInput: InputStream = socket.inputStream
        private var bleOutput: OutputStream = socket.outputStream

        public fun start() {
            thread {
                reader()
            }
            thread {
                writer()
            }

            updateNotification()
        }

        public fun close() {
            val device = socket.remoteDevice.address.toString()
            Timber.i("BLE: Closing connection to $device")

            conduit.close()
            socket.close()

            val conduit = conduits[device]
            if (conduit != null) {
                val port = conduit.port()
                if (port > 0) {
                    try {
                        monolith?.disconnectPort(port)
                    } catch (e: Exception) {
                        // no biggie
                    }
                }
            }

            connecting.remove(device)
            connections.remove(device)
            conduits.remove(device)

            updateNotification()
        }

        private fun reader() {
            val b = ByteArray(socket.maxReceivePacketSize)
            while (isConnected) {
                try {
                    val rn = bleInput.read(b)
                    if (rn < 0) {
                        continue
                    }
                    val r = b.sliceArray(0 until rn).clone()
                    conduit.write(r)
                } catch (e: Exception) {
                    Timber.e(e)
                    break
                }
            }
            close()
        }

        private fun writer() {
            val b = ByteArray(socket.maxTransmitPacketSize)
            while (isConnected) {
                try {
                    val rn = conduit.read(b).toInt()
                    if (rn < 0) {
                        continue
                    }
                    val w = b.sliceArray(0 until rn).clone()
                    bleOutput.write(w)
                } catch (e: Exception) {
                    Timber.e(e)
                    break
                }
            }
            close()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun registerUser(userID: String, password: String): String {
        return monolith?.registerUser(userID, password) ?: ""
    }

    fun registerDevice(userID: String): String {
        return monolith?.registerDevice(userID, "P2P") ?: ""
    }

    fun updateNotification() {
        if (notificationManager == null || monolith == null) {
            return
        }

        val remotePeers = monolith!!.peerCount(Gobind.PeerTypeRemote).toInt()
        val multicastPeers = monolith!!.peerCount(Gobind.PeerTypeMulticast).toInt()
        val bluetoothPeers = monolith!!.peerCount(Gobind.PeerTypeBluetooth).toInt()

        val title: String = "Peer-to-peer service running"
        val text: String

        text = if (remotePeers+multicastPeers+bluetoothPeers == 0) {
            "No connectivity"
        } else {
            val texts: MutableList<String> = mutableListOf<String>()
            if (bluetoothPeers > 0) {
                texts.add(0, "$bluetoothPeers BLE")
            }
            if (multicastPeers > 0) {
                texts.add(0, "$multicastPeers LAN")
            }
            if (remotePeers > 0) {
                texts.add(0, "static")
            }
            "Connected to " + texts.joinToString(", ")
        }

        notification = NotificationCompat.Builder(applicationContext, "im.vector.p2p")
                .setContentTitle(title)
                .setContentText(text)
                .setNotificationSilent()
                .setSmallIcon(R.drawable.ic_smartphone)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .build()
        notificationManager!!.notify(545, notification)
    }

    override fun onCreate() {
        if (monolith == null) {
            monolith = gobind.DendriteMonolith()
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        this.registerReceiver(bleReceiver, intentFilter)

        vectorPreferences.subscribeToChanges(this)

        notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager!!.createNotificationChannel(notificationChannel)

        monolith!!.storageDirectory = applicationContext.filesDir.toString()
        monolith!!.cacheDirectory = applicationContext.cacheDir.toString()
        monolith!!.start()

        if (vectorPreferences.p2pEnableStatic()) {
            monolith!!.setStaticPeer(vectorPreferences.p2pStaticURI())
        }
        monolith!!.setMulticastEnabled(vectorPreferences.p2pEnableMulticast())

        Timer().schedule(object : TimerTask() {
            override fun run() {
                updateNotification()
            }
        }, 0, 1000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startBluetooth()
        }

        super.onCreate()
    }

    private fun startBluetooth() {
        if (!vectorPreferences.p2pEnableBluetooth()) {
            return
        }
        if (adapter == null) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        if (!adapter.isEnabled) {
            return
        }

        manager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        val isCodedPHY = vectorPreferences.p2pBLECodedPhy() && manager.adapter.isLeCodedPhySupported

        advertiser.stopAdvertising(advertiseCallback)
        advertiser.stopAdvertisingSet(advertiseSetCallback)
        scanner.stopScan(scanCallback)

        connecting.clear()
        connections.clear()
        conduits.clear()

        val advertiseData = AdvertiseData.Builder()
                .addServiceUuid(serviceUUID)
                .build()

        l2capServer = adapter.listenUsingInsecureL2capChannel()
        l2capPSM = intToBytes(l2capServer.psm.toShort())

        if (isCodedPHY) {
            val parameters = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
                    .setConnectable(true)

            if (isCodedPHY) {
                parameters.setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                parameters.setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
                Toast.makeText(applicationContext, "Requesting Coded PHY + 1M PHY", Toast.LENGTH_SHORT).show()
            }

            advertiser.startAdvertisingSet(parameters.build(), advertiseData, null, null, null, advertiseSetCallback)
        } else {
            val advertiseSettings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setTimeout(0)
                    .setConnectable(true)
                    .build()

            advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        }

        gattCharacteristic = BluetoothGattCharacteristic(psmUUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        gattService.addCharacteristic(gattCharacteristic)

        gattServer = manager.openGattServer(applicationContext, gattServerCallback)
        gattServer.addService(gattService)

        val scanFilter = ScanFilter.Builder()
                .setServiceUuid(serviceUUID)
                .build()

        val scanFilters: MutableList<ScanFilter> = ArrayList()
        scanFilters.add(scanFilter)

        val scanSettingsBuilder = ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)

        if (isCodedPHY) {
            scanSettingsBuilder.setPhy(BluetoothDevice.PHY_LE_CODED)
        } else {
            scanSettingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }

        val scanSettings = scanSettingsBuilder.build()

        scanner.startScan(scanFilters, scanSettings, scanCallback)

        GlobalScope.launch {
            while (true) {
                Timber.i("BLE: Waiting for connection on PSM ${l2capServer.psm}")
                try {
                    val remote = l2capServer.accept() ?: continue
                    val device = remote.remoteDevice.address.toString()

                    val connection = connections[device]
                    if (connection != null) {
                        if (connection.isConnected) {
                            connection.close()
                        } else {
                            connections.remove(device)
                            conduits.remove(device)
                        }
                    }

                    Timber.i("BLE: Connected inbound $device PSM $l2capPSM")

                    Timber.i("Creating DendriteBLEPeering")
                    val conduit = monolith!!.conduit("ble", Gobind.PeerTypeBluetooth)
                    conduits[device] = conduit
                    connections[device] = DendriteBLEPeering(conduit, remote)
                    Timber.i("Starting DendriteBLEPeering")
                    connections[device]!!.start()

                    Timber.i("BLE: Created BLE peering with $device PSM $l2capPSM")
                    connecting.remove(device)
                } catch (e: Exception) {
                    Timber.i("BLE: Accept exception: ${e.message}")
                }
            }
        }
    }

    private fun stopBluetooth() {
        if (adapter.isEnabled()) {
            advertiser.stopAdvertising(advertiseCallback)
            advertiser.stopAdvertisingSet(advertiseSetCallback)
            scanner.stopScan(scanCallback)
        }

        connections.forEach { (_, c) ->
            if (c.isConnected) {
                c.close()
            }
        }

        connecting.clear()
        connections.clear()
        conduits.clear()
    }

    override fun onDestroy() {
        if (monolith == null) {
            return
        }

        stopBluetooth()

        this.unregisterReceiver(bleReceiver)

        if (notificationManager != null) {
            notificationManager!!.cancel(545)
        }

        monolith?.stop()
        monolith = null

        super.onDestroy()
    }

    private val gattServerCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, l2capPSM);
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
        }
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.i("BLE: Discovering services via GATT ${gatt.toString()}")
                gatt?.discoverServices()
            } else {
                connecting.remove(gatt?.device?.address?.toString())
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(gatt?.device?.address?.toString())
                return
            }
            val services = gatt?.services ?: return
            if (services.size == 0) {
                connecting.remove(gatt.device?.address?.toString())
                return
            }
            Timber.i("BLE: Found services via GATT ${gatt.toString()}")
            services.forEach { service ->
                if (service.uuid == serviceUUID.uuid) {
                    service.characteristics.forEach { characteristic ->
                        if (characteristic.uuid == psmUUID) {
                            Timber.i("BLE: Requesting PSM characteristic")
                            gatt.readCharacteristic(characteristic)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (gatt == null || characteristic == null) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(gatt.device?.address?.toString())
                return
            }
            val device = gatt.device
            val psmBytes = characteristic.value
            val psm = bytesToInt(psmBytes)

            var connection = connections[device.address.toString()]
            if (connection != null) {
                if (connection.isConnected) {
                    connection.close()
                } else {
                    connections.remove(device.address.toString())
                    conduits.remove(device.address.toString())
                }
            }

            Timber.i("BLE: Connecting outbound $device PSM $psm")

            val socket = device.createInsecureL2capChannel(psm.toInt())
            try {
                socket.connect()
            } catch (e: Exception) {
                timber.log.Timber.i("BLE: Failed to connect to $device PSM $psm: ${e.toString()}")
                connecting.remove(device.address)
                return
            }
            if (!socket.isConnected) {
                Timber.i("BLE: Expected to be connected but not")
                connecting.remove(device.address)
                return
            }

            Timber.i("BLE: Connected outbound $device PSM $psm")

            Timber.i("Creating DendriteBLEPeering")
            val conduit = monolith!!.conduit("ble", Gobind.PeerTypeBluetooth)
            conduits[device.address] = conduit
            connections[device.address] = DendriteBLEPeering(conduit, socket)
            Timber.i("Starting DendriteBLEPeering")
            connections[device.address]!!.start()
            connecting.remove(device.address)
        }
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                Toast.makeText(applicationContext, "BLE legacy advertise failed: $errorCode", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Toast.makeText(applicationContext, "BLE legacy advertise started", Toast.LENGTH_SHORT).show()
        }
    }

    private val advertiseSetCallback: AdvertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            super.onAdvertisingSetStarted(advertisingSet, txPower, status)
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                Toast.makeText(applicationContext, "BLE advertise set started", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            super.onAdvertisingSetStopped(advertisingSet)
            Toast.makeText(applicationContext, "BLE advertise set stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                Timber.i("BLE: error %s", errorCode)
                Toast.makeText(applicationContext, "BLE: Error $errorCode scanning for devices", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (!result.isConnectable || result.scanRecord?.serviceUuids?.contains(serviceUUID) != true) {
                return
            }

            val key = result.device.address.toString()
            Timber.i("BLE: Scan result found $key")

            if (connections.containsKey(key)) {
                val connection = connections[key]
                if (connection?.isConnected == true) {
                    Timber.i("BLE: Ignoring device $key that we are already connected to")
                    return
                }
            }

            if (connecting.containsKey(key)) {
                Timber.i("BLE: Ignoring device $key that we are already connecting to")
                return
            }

            Timber.i("BLE: Connecting to $key")
            connecting[key] = true
            result.device.connectGatt(applicationContext, false, gattCallback)
        }
    }

    private fun intToBytes(x: Short): ByteArray {
        val buffer: ByteBuffer = ByteBuffer.allocate(java.lang.Short.BYTES)
        buffer.putShort(x)
        return buffer.array()
    }

    fun bytesToInt(bytes: ByteArray): Short {
        val buffer: ByteBuffer = ByteBuffer.allocate(java.lang.Short.BYTES)
        buffer.put(bytes.sliceArray(0 until java.lang.Short.BYTES))
        buffer.flip()
        return buffer.short
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val m = monolith ?: return
        when (key){
            VectorPreferences.SETTINGS_P2P_ENABLE_MULTICAST -> {
                val enabled = vectorPreferences.p2pEnableMulticast()
                m.setMulticastEnabled(enabled)
            }

            VectorPreferences.SETTINGS_P2P_ENABLE_BLUETOOTH -> {
                val enabled = vectorPreferences.p2pEnableBluetooth()
                if (enabled) {
                    startBluetooth()
                } else {
                    stopBluetooth()
                    m.disconnectType(Gobind.PeerTypeBluetooth)
                }
            }

            VectorPreferences.SETTINGS_P2P_ENABLE_STATIC -> {
                val enabled = vectorPreferences.p2pEnableStatic()
                if (enabled) {
                    val uri = vectorPreferences.p2pStaticURI()
                    m.setStaticPeer(uri)
                } else {
                    m.setStaticPeer("")
                }
            }

            VectorPreferences.SETTINGS_P2P_STATIC_URI -> {
                if (vectorPreferences.p2pEnableStatic()) {
                    val uri = vectorPreferences.p2pStaticURI()
                    m.setStaticPeer(uri)
                }
            }

            VectorPreferences.SETTINGS_P2P_BLE_CODED_PHY -> {
                val enabled = vectorPreferences.p2pEnableBluetooth()
                if (enabled) {
                    stopBluetooth()
                    m.disconnectType(Gobind.PeerTypeBluetooth)
                    startBluetooth()
                }
            }
        }
    }
}
