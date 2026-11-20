package com.vinabeef.field

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import java.util.UUID

class RfidScannerManager(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onTagScanned: (code: String, method: String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // Standard Client Characteristic Configuration Descriptor for BLE Notify
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun startBleReaderDiscovery() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || bluetoothAdapter?.isEnabled != true) {
            onStatusChanged("Bluetooth is OFF or unavailable on this device.")
            return
        }
        if (isScanning) return

        isScanning = true
        onStatusChanged("Scanning for Handheld BLE RFID Reader...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        // Stop scan after 10 seconds to save battery
        handler.postDelayed({
            if (isScanning) {
                stopBleScan()
                if (bluetoothGatt == null) {
                    onStatusChanged("No BLE RFID wand found. Ready for NFC or Manual/Demo Scan.")
                }
            }
        }, 10_000L)
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: return
            // Match common handheld RFID readers (Chainway, Zebra, TSL, UHF, RFID, Safetrust)
            val upper = name.uppercase()
            if (upper.contains("RFID") || upper.contains("UHF") || upper.contains("ZEBRA") ||
                upper.contains("CHAINWAY") || upper.contains("TSL") || upper.contains("READER")) {
                stopBleScan()
                onStatusChanged("Connecting to RFID Wand: $name...")
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatusChanged("Connected to ${gatt.device.name ?: "RFID Reader"}. Discovering GATT...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatusChanged("BLE RFID Reader disconnected.")
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            // Subscribe to the first NOTIFY characteristic exposed by the RFID reader's UART/EPC service
            for (service in gatt.services) {
                for (ch in service.characteristics) {
                    val props = ch.properties
                    if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        gatt.setCharacteristicNotification(ch, true)
                        val desc = ch.getDescriptor(cccdUuid)
                        if (desc != null) {
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                        }
                        onStatusChanged("RFID Wand Ready (${gatt.device.name}) — Pull trigger to scan tag")
                        return
                    }
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val rawBytes = characteristic.value ?: return
            val ascii = String(rawBytes, Charsets.UTF_8).trim().replace(Regex("[^A-Za-z0-9-]"), "")
            val epc = if (ascii.length >= 6) ascii else rawBytes.joinToString("") { "%02X".format(it) }
            if (epc.isNotEmpty()) {
                handler.post { onTagScanned(epc.uppercase(), "BLE_RFID") }
            }
        }
    }

    fun handleNfcTag(tag: Tag) {
        val hexUid = tag.id.joinToString("") { "%02X".format(it) }
        if (hexUid.isNotEmpty()) {
            onTagScanned(hexUid, "NFC")
        }
    }

    @SuppressLint("MissingPermission")
    fun release() {
        stopBleScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}