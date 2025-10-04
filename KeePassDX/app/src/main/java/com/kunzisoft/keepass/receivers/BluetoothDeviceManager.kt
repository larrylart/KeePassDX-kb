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

	@Volatile private var currentMtu: Int = 23

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


	// new
    @Volatile private var notifyCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null
    @Volatile private var notificationsEnabled = false
	
	// --- notification one-shot + stream support ---
	@Volatile private var notifListener: ((ByteArray?) -> Unit)? = null
	private val notifTimeouts = Handler(Looper.getMainLooper())

	// queue to hold packets that arrive when no waiter is set
	private val notifBuffer: ArrayDeque<ByteArray> = ArrayDeque()

	// streaming listener: active while we want to collect multiple notifications
	@Volatile private var streamListener: ((ByteArray) -> Unit)? = null

	// end new

	fun startNotificationStream(onChunk: (ByteArray) -> Unit) {
		streamListener = onChunk
		// Immediately deliver anything that arrived before the stream was started
		synchronized(notifBuffer) {
			while (notifBuffer.isNotEmpty()) {
				onChunk(notifBuffer.removeFirst())
			}
		}
	}

	fun stopNotificationStream() {
		streamListener = null
	}

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
		// notify
        notifyCharacteristic = null
        notificationsEnabled = false
		
		synchronized(notifBuffer) { notifBuffer.clear() }
		try { notifTimeouts.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
		notifListener = null
		
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
				// Do NOT teardown after this - we keep a live GATT for the app session
				try { gatt?.disconnect() } catch (_: Throwable) {}
				try { gatt?.close() } catch (_: Throwable) {}
				gatt = dev.connectGatt(context, /*autoConnect*/ false, persistentGattCb)
			} catch (t: Throwable) {
				fail("Exception during connect: ${t.message}")
			}
		}

		// We return via onServicesDiscovered → succeed()/fail()
	}

	///////////////////////////////
	// new modified
	private val persistentGattCb = object : android.bluetooth.BluetoothGattCallback() 
	{
		override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
			if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS &&
				newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {

				// Prefer faster link for the initial handshake
				if (android.os.Build.VERSION.SDK_INT >= 21) {
					try {
						g.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH)
					} catch (_: Throwable) {}
				}

				// Request a larger MTU so the dongle can send the whole line in one notify
				if (android.os.Build.VERSION.SDK_INT >= 21) {
					val ok = try { g.requestMtu(247) } catch (_: Throwable) { false }
					if (!ok) {
						// If request failed, proceed anyway
						g.discoverServices()
					}
				} else {
					g.discoverServices()
				}
			} else {
				fail("Connection state=$newState status=$status")
			}
		}

        override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt, status: Int) {
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                // Find NUS service + chars
                val svc = g.getService(BleHub.SERVICE_UUID)
                lastCharacteristic = svc?.getCharacteristic(BleHub.CHAR_UUID) // TX (write)
                notifyCharacteristic = svc?.getCharacteristic(BleHub.RX_UUID) // RX (notify)

                if (notifyCharacteristic != null) {
                    // Enable notifications on RX
                    g.setCharacteristicNotification(notifyCharacteristic, true)
                    val cccd = notifyCharacteristic!!.getDescriptor(
                        java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    if (cccd != null) {
                        cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        // Write descriptor; completion handled in onDescriptorWrite
                        if (!g.writeDescriptor(cccd)) {
                            // Could not write CCCD; still try to continue
                            notificationsEnabled = false
                            succeed()
                        }
                    } else {
                        // No CCCD available; continue anyway
                        notificationsEnabled = false
                        succeed()
                    }
                } else {
                    // No RX, but we can still write-only
                    notificationsEnabled = false
                    succeed()
                }
            } else {
                fail("Service discovery failed status=$status")
            }
        }

        override fun onDescriptorWrite(
            g: android.bluetooth.BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS &&
                descriptor.characteristic == notifyCharacteristic) {
                notificationsEnabled = true
            }
            // Signal connect() completion (do not close)
            succeed()
        }

		override fun onCharacteristicChanged(
			g: android.bluetooth.BluetoothGatt,
			characteristic: android.bluetooth.BluetoothGattCharacteristic
		) {
			if (characteristic == notifyCharacteristic) {
				val data = characteristic.value

				// 1) if a stream is active, deliver there (do NOT consume one-shot)
				streamListener?.let { streamCb ->
					streamCb(data)
					return
				}

				// 2) otherwise deliver to one-shot waiter if present
				val cb = notifListener
				if (cb != null) {
					notifListener = null
					try { notifTimeouts.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
					cb(data)
				} else {
					// 3) no one is listening -> buffer for the next waiter
					synchronized(notifBuffer) { notifBuffer.addLast(data.copyOf()) }
				}
			}
		}

		override fun onMtuChanged(
			g: android.bluetooth.BluetoothGatt,
			mtu: Int,
			status: Int
		) {
			currentMtu = mtu
			// Continue to service discovery after MTU negotiation
			try { g.discoverServices() } catch (_: Throwable) {}
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

	// new - wait for notify message
	fun awaitNextNotification(timeoutMs: Long, onResult: (ByteArray?) -> Unit) 
	{
		// if something is already buffered, return it immediately
		synchronized(notifBuffer) {
			if (notifBuffer.isNotEmpty()) {
				onResult(notifBuffer.removeFirst())
				return
			}
		}
		// otherwise arm a one-shot listener + timeout
		notifListener = onResult
		notifTimeouts.postDelayed({
			val cb = notifListener
			notifListener = null
			cb?.invoke(null) // timeout
		}, timeoutMs)
	}



}
