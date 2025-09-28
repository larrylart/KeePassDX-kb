package com.kunzisoft.keepass.receivers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import android.bluetooth.BluetoothStatusCodes
import com.kunzisoft.keepass.settings.PreferencesUtil

data class BtDevice(val name: String, val address: String, val bonded: Boolean)

class BluetoothDeviceManager(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btMgr.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    /** Combined list (bonded ∪ scanned) */
    val devices = MutableLiveData<List<BtDevice>>(emptyList())

    private val devicesMap = LinkedHashMap<String, BtDevice>()
    private var scanning = false

    fun isBluetoothReady(): Boolean = adapter?.isEnabled == true

    /** Pull bonded devices and merge into map. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    fun refreshBonded() {
        val bonded = try { adapter?.bondedDevices.orEmpty() } catch (_: SecurityException) { emptySet() }
        var changed = false
        for (d in bonded) {
            val name = d.name ?: "Unknown"
            val updated = BtDevice(name, d.address, true)
            val prev = devicesMap[d.address]
            if (prev == null || prev != updated) {
                devicesMap[d.address] = updated
                changed = true
            }
        }
        if (changed) postList()
    }

    private fun postList() {
        main.post {
            devices.value = devicesMap.values.sortedWith(
                compareByDescending<BtDevice> { it.bonded }.thenBy { it.name.lowercase() }
            )
        }
    }

    /** Start BLE scan and merge finds with bonded list. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun start() {
        if (scanning || adapter == null) return
        scanning = true
        refreshBonded()
        try { scanner?.startScan(scanCb) } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCb) } catch (_: SecurityException) {}
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val address = dev.address ?: return
            val name = dev.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val bonded = try { dev.bondState == BluetoothDevice.BOND_BONDED } catch (_: SecurityException) { false }

            val existing = devicesMap[address]
            val updated = BtDevice(name, address, bonded)
            if (existing == null || existing != updated) {
                devicesMap[address] = updated
                postList()
            }
        }
    }

    /** Pair/bond with device – triggers system pairing UI if needed. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun pair(address: String): Boolean {
        val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null } ?: return false
        return try { dev.createBond() } catch (_: SecurityException) { false }
    }

    /** Unpair – uses hidden API via reflection. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun unpair(address: String): Boolean {
        val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null } ?: return false
        return try {
            val m = dev.javaClass.getMethod("removeBond")
            (m.invoke(dev) as? Boolean) == true
        } catch (_: Throwable) { false }
    }
	
	// --- Send (connect + write) --------------------------------------------------
	@Volatile private var gatt: android.bluetooth.BluetoothGatt? = null
	@Volatile private var resultCb: ((Boolean, String?) -> Unit)? = null

	// Persistent-connection support:
	@Volatile private var connectedAddress: String? = null
	@Volatile private var discovered = false
	@Volatile private var lastCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null
	@Volatile private var closeAfterOp = true   // legacy single-shot = true; persistent = false

	private val writeTimeoutMs = 10_000L
	private val timeoutRunnable = Runnable {
		resultCb?.invoke(false, "Timeout while writing characteristic")
		if (closeAfterOp) cleanupGatt()
	}

	// success/fail now only close if single-shot
	fun succeed() {
		resultCb?.invoke(true, null)
		if (closeAfterOp) cleanupGatt()
	}
	fun fail(msg: String) {
		resultCb?.invoke(false, msg)
		if (closeAfterOp) cleanupGatt()
	}

	private fun cleanupGatt() {
		try { main.removeCallbacks(timeoutRunnable) } catch (_: Throwable) {}
		try { gatt?.disconnect() } catch (_: Throwable) {}
		try { gatt?.close() } catch (_: Throwable) {}
		gatt = null
		resultCb = null
		discovered = false
		connectedAddress = null
		lastCharacteristic = null
		closeAfterOp = true
	}

	// ===== Persistent connection mode (BleHub uses these) ========================

	@android.annotation.SuppressLint("MissingPermission")
	@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
	fun connect(address: String, onResult: (Boolean, String?) -> Unit) {
		stop() // pause scanning
		val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
		if (dev == null) { onResult(false, "Invalid device address"); return }
		resultCb = onResult
		closeAfterOp = false
		connectedAddress = address
		discovered = false

		main.post {
			try {
				// Do NOT teardown after this; we keep a live GATT for the app session
				try { gatt?.disconnect() } catch (_: Throwable) {}
				try { gatt?.close() } catch (_: Throwable) {}
				gatt = dev.connectGatt(context, /*autoConnect*/ false, persistentGattCb)
			} catch (t: Throwable) {
				fail("Exception during connect: ${t.message}")
			}
		}

		// We return via onServicesDiscovered → succeed()/fail()
	}

	private val persistentGattCb = object : android.bluetooth.BluetoothGattCallback() {
		override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
			if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS &&
				newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
				g.discoverServices()
			} else {
				fail("Connection state=$newState status=$status")
			}
		}

		override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt, status: Int) {
			if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
				discovered = true
				succeed() // signals connect() completion (do not close)
			} else {
				fail("Service discovery failed status=$status")
			}
		}

		override fun onCharacteristicWrite(
			g: android.bluetooth.BluetoothGatt,
			characteristic: android.bluetooth.BluetoothGattCharacteristic,
			status: Int
		) {
			main.removeCallbacks(timeoutRunnable)
			if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) succeed()
			else fail("Write failed status=$status")
		}
	}

	// new function?
	@android.annotation.SuppressLint("MissingPermission")
	@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
	fun writeOrConnect(
		address: String,
		serviceUuid: java.util.UUID,
		characteristicUuid: java.util.UUID,
		payload: ByteArray,
		writeType: Int = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
		onResult: (Boolean, String?) -> Unit
	) {
		//resultCb = onResult
		closeAfterOp = false // do not close after write — persistent mode

		// Append newline if the global toggle is ON
		val actualPayload = if (PreferencesUtil.sendNewLineAfterPassword(context)) {
			payload + "\n".toByteArray(Charsets.UTF_8)
		} else {
			payload
		}

		var wroteOnce = false
		val doWrite = fun() 
		{
			// guard: avoid duplicate writes if callback fires twice
			if (wroteOnce) return  
			wroteOnce = true
		
			val g = gatt
			if (g == null) {
				fail("Not connected")
				return
			}

			val service = g.getService(serviceUuid)
			val ch = service?.getCharacteristic(characteristicUuid)
			if (ch == null) {
				fail("Characteristic not found")
				return
			}
			lastCharacteristic = ch
			
			resultCb = onResult

			var resolvedWriteType = writeType
			val props = ch.properties
			val hasWriteResp = (props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
			val hasWriteNoResp = (props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
			if (resolvedWriteType == android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT &&
				!hasWriteResp && hasWriteNoResp) {
				resolvedWriteType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
			}
			val isNoResponse =
				(resolvedWriteType == android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

			if (android.os.Build.VERSION.SDK_INT >= 33) {
				val rc = g.writeCharacteristic(ch, actualPayload, resolvedWriteType)
				if (rc != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
					fail("writeCharacteristic rc=$rc")
					return
				}
				if (isNoResponse) {
					succeed()
					return
				}
			} else {
				ch.writeType = resolvedWriteType
				ch.value = actualPayload
				if (!g.writeCharacteristic(ch)) {
					fail("writeCharacteristic returned false")
					return
				}
				if (isNoResponse) {
					succeed()
					return
				}
			}

			// Wait for onCharacteristicWrite with a timeout
			main.removeCallbacks(timeoutRunnable)
			main.postDelayed(timeoutRunnable, writeTimeoutMs)
		}

		if (gatt != null && connectedAddress == address && discovered) {
			doWrite.invoke()
		} else {
			connect(address) { ok, err ->
				if (!ok) onResult(false, err) else doWrite.invoke()
			}
		}
	}

	fun disconnect() {
		cleanupGatt()
	}


/* - old function ?
	////////////////////////////////////////////////
	// Connects to [address], discovers [serviceUuid], then writes [payload] to [characteristicUuid].
	// You MUST have BLUETOOTH_CONNECT permission granted when calling this.
	@android.annotation.SuppressLint("MissingPermission")
	@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
	fun connectAndWrite(
		address: String,
		payload: ByteArray,
		serviceUuid: java.util.UUID,
		characteristicUuid: java.util.UUID,
		writeType: Int = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
		onResult: (success: Boolean, error: String?) -> Unit
	) {
		// Stop scanning so the controller isn't busy
		stop()

		val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
		if (dev == null) {
			onResult(false, "Invalid device address")
			return
		}
		resultCb = onResult

		// Append newline if the global toggle is ON
		val actualPayload = if (PreferencesUtil.sendNewLineAfterPassword(context)) {
			payload + "\n".toByteArray(Charsets.UTF_8)
		} else {
			payload
		}

		// Make sure we run connect on the main thread
		main.post {
			try {
				cleanupGatt()
				gatt = dev.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
					override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
						if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS || newState != android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
							fail("Failed to connect (status=$status, state=$newState)")
							return
						}
						g.discoverServices()
					}

					override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt, status: Int) {
						if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
							fail("Service discovery failed (status=$status)")
							return
						}
						val service = g.getService(java.util.UUID.fromString(serviceUuid.toString()))
							?: g.getService(serviceUuid) // double-try if some stacks stringify
						val ch = service?.getCharacteristic(characteristicUuid)
						if (ch == null) {
							fail("Characteristic not found")
							return
						}

						// ---- Decide the actual write type to use ----
						var resolvedWriteType = writeType
						val props = ch.properties
						val hasWriteResp = (props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
						val hasWriteNoResp = (props and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

						// If DEFAULT was requested but the characteristic doesn't support response, fall back to NO_RESPONSE
						if (resolvedWriteType == android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT &&
							!hasWriteResp && hasWriteNoResp) {
							resolvedWriteType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
						}
						val isNoResponse = (resolvedWriteType == android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

						// ---- Perform the write ----
						if (android.os.Build.VERSION.SDK_INT >= 33) {
							val rc = g.writeCharacteristic(ch, actualPayload, resolvedWriteType)
							if (rc != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
								fail("writeCharacteristic() rc=$rc")
								return
							}
							if (isNoResponse) {
								// No callback will arrive: treat as success immediately
								succeed()
								return
							}
						} else {
							ch.writeType = resolvedWriteType
							ch.value = actualPayload
							if (!g.writeCharacteristic(ch)) {
								fail("writeCharacteristic() returned false")
								return
							}
							if (isNoResponse) {
								// Legacy API: no callback for NO_RESPONSE either
								succeed()
								return
							}
						}

						// We wrote with response → wait for onCharacteristicWrite with a timeout
						main.removeCallbacks(timeoutRunnable)
						main.postDelayed(timeoutRunnable, writeTimeoutMs)
					}


					override fun onCharacteristicWrite(
						g: android.bluetooth.BluetoothGatt,
						characteristic: android.bluetooth.BluetoothGattCharacteristic,
						status: Int
					) {
						main.removeCallbacks(timeoutRunnable)
						if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
							succeed()
						} else {
							fail("Write failed (status=$status)")
						}
					}
				})
			} catch (t: Throwable) {
				fail("Exception during connect: ${t.message}")
			}
		}
	}	
	*/
}
