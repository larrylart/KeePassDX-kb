package com.kunzisoft.keepass.receivers

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import com.kunzisoft.keepass.settings.PreferencesUtil
import android.os.SystemClock
import kotlin.math.min

import android.util.Log
private const val TAG = "BleHub"

object BleHub {
    private lateinit var appCtx: Context
    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    // (NUS) Nordic UART defaults — same ones you already use
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val CHAR_UUID:    UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
	val RX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private val mgrLock = Any()
    private var mgr: BluetoothDeviceManager? = null
    private fun ensureMgr(): BluetoothDeviceManager =
        synchronized(mgrLock) { mgr ?: BluetoothDeviceManager(appCtx).also { mgr = it } }

    private var currentAddress: String? = null

    fun init(context: Context) {
        if (!::appCtx.isInitialized) appCtx = context.applicationContext
    }
    fun setTarget(address: String?) { currentAddress = address }

    fun autoConnectIfEnabled(
        useExternal: Boolean,
        address: String?,
        onReady: ((Boolean, String?) -> Unit)? = null
    ) 
	{       
		setTarget(address)
        if (!useExternal || address.isNullOrBlank()) {
            onReady?.invoke(false, "Output device not enabled or not selected")
            return
        }
		
        //ensureMgr().connect(address) { ok, err ->
        //    _connected.postValue(ok)
        //    onReady?.invoke(ok, err)
		
		connectAndFetchLayoutSimple(timeoutMs = 2500L, onDone = onReady)
    }

	// get data from prefs
	fun autoConnectFromPrefs(onReady: ((Boolean, String?) -> Unit)? = null) {
		val useExt = PreferencesUtil.useExternalKeyboardDevice(appCtx)
		val addr   = PreferencesUtil.getOutputDeviceId(appCtx)
		autoConnectIfEnabled(useExt, addr, onReady)
	}

	// (stub) future tokenizer: returns input unchanged for now
	private fun tokenizeForSend(raw: String): String {
		// TODO: plug real tokenization here later
		return raw
	}

	// MD5 -> lowercase hex
	private fun md5Hex(bytes: ByteArray): String {
		val md = java.security.MessageDigest.getInstance("MD5")
		val digest = md.digest(bytes)
		val sb = StringBuilder(digest.size * 2)
		for (b in digest) sb.append(String.format("%02x", b))
		return sb.toString()
	}

	// Read and concatenate notifications for up to totalTimeoutMs.
	// Stops early if a newline is seen or the regex appears complete. 
	////////////////////////////
	// Read and concatenate notifications up to totalTimeoutMs.
	// Keeps waiting across small time gaps until full token is seen or the total deadline passes.
	// Continuously collect notifications up to totalTimeoutMs.
	// Stop early on newline or when the token is fully matched.
	private fun readHandshakeLine(
		totalTimeoutMs: Long,
		onDone: (String?) -> Unit
	) {
		val deadline = android.os.SystemClock.uptimeMillis() + totalTimeoutMs
		val full = Regex("""CONNECTED=LAYOUT_[A-Z]+(?:_[A-Z]+)*_(?:WINLIN|MAC)""")
		val sb = StringBuilder()
		var finished = false
		val handler = android.os.Handler(android.os.Looper.getMainLooper())

		fun finish(result: String?) {
			if (finished) return
			finished = true
			ensureMgr().stopNotificationStream()
			onDone(result)
		}

		// stop at the deadline if nothing more arrives
		val timeout = Runnable {
			val text = if (sb.isNotEmpty()) sb.toString() else null
			finish(text?.trim())
		}
		handler.postDelayed(timeout, totalTimeoutMs)

		// start streaming; every chunk is appended
		ensureMgr().startNotificationStream { bytes ->
			if (finished) return@startNotificationStream
			val part = bytes.toString(Charsets.UTF_8)
			Log.d(TAG, "RSP chunk: '$part'") 
			sb.append(part)
			val s = sb.toString()
			if (s.contains('\n') || full.containsMatchIn(s)) {
				handler.removeCallbacks(timeout)
				finish(s.trim())
			} else {
				// still within the window; keep listening
				if (android.os.SystemClock.uptimeMillis() >= deadline) {
					handler.removeCallbacks(timeout)
					finish(s.trim())
				}
			}
		}
	}

	/////////////////////////////
	// Connect, then wait once (~1.5s) for "CONNECTED=LAYOUT_XXX".
	//  If seen, store in prefs and report success. otherwise retry connects a few times.
	/////////////////
	fun connectAndFetchLayoutSimple(
		timeoutMs: Long = 1500L,
		retries: Int = 2,
		onDone: ((Boolean, String?) -> Unit)? = null
	) {
		val addr = PreferencesUtil.getOutputDeviceId(appCtx)
		if (addr.isNullOrBlank()) {
			onDone?.invoke(false, "No device selected")
			return
		}

		fun parseLayout(line: String): String? {
			Log.d(TAG, "RSP: received $line")
			val m = Regex("""CONNECTED=LAYOUT_([A-Z]+(?:_[A-Z]+)*_(?:WINLIN|MAC))""").find(line) ?: return null
			return m.groupValues[1]
		}

		fun attempt(left: Int) 
		{
			// CONNECT first, then wait for the handshake line
			ensureMgr().connect(addr) { ok, err ->
				if (!ok) {
					if (left > 0) {
						attempt(left - 1)
					} else {
						onDone?.invoke(false, err)
					}
					return@connect
				}

				// Wait 1–2s for the CONNECTED=LAYOUT_... line from the dongle
				readHandshakeLine(timeoutMs) { concatenated ->
					val reply = concatenated?.trim().orEmpty()
					Log.d(TAG, "RSP: $reply")

					val layout = parseLayout(reply)
					if (layout != null) {
						PreferencesUtil.setKeyboardLayout(appCtx, layout)
						_connected.postValue(true)
						onDone?.invoke(true, null)
					} else {
						if (left > 0) {
							// clean reconnect and try again
							ensureMgr().disconnect()
							attempt(left - 1)
						} else {
							onDone?.invoke(false, "No handshake")
						}
					}
				}
			}
		}


		attempt(retries)
	}

	////////////////////////////
	// Send a string to the dongle as "S:<value>" and await a single notify "R:H=<md5hex>".
	// Compares the returned hash to MD5(value). Success only on exact match.
	///////////////////////////////////////////////////////
	fun sendStringAwaitHash(
		value: String,
		timeoutMs: Long = 6000L,
		onResult: (Boolean, String?) -> Unit
	) 
	{
		val addr = PreferencesUtil.getOutputDeviceId(appCtx)
		if (addr.isNullOrBlank()) {
			onResult(false, "No device selected")
			return
		}

		// 1) S: + tokenized payload (stub = original text)
		val body = tokenizeForSend(value)
		val payloadStr = "S:$body"
		val expectedHash = md5Hex(body.toByteArray(Charsets.UTF_8)) // compare against what we intended to send

		// 2) Write (BluetoothDeviceManager will append newline if that setting is enabled)
		ensureMgr().writeOrConnect(
			address = addr,
			serviceUuid = SERVICE_UUID,
			characteristicUuid = CHAR_UUID,
			payload = payloadStr.toByteArray(Charsets.UTF_8)
		) { ok, err ->
			if (!ok) {
				onResult(false, err)
				return@writeOrConnect
			}

			// 3) Await one notification and check "R:H=<32 hex>"
			ensureMgr().awaitNextNotification(timeoutMs) { bytes ->
				if (bytes == null) {
					onResult(false, "No reply")
					return@awaitNextNotification
				}
				val reply = bytes.toString(Charsets.UTF_8).trim()

				// e.g. R:H=<md5hash>
				val m = Regex("""R:H=([0-9a-fA-F]{32})""").find(reply)
				if (m == null) {
					onResult(false, "Unexpected reply: $reply")
					return@awaitNextNotification
				}

				val got = m.groupValues[1].lowercase()
				if (got == expectedHash) {
					onResult(true, null)
				} else {
					onResult(false, "Hash mismatch")
				}
			}
		}
	}

	/////////////////////////////
    // Send ASCII command and expect "R:OK" as the next notification.
    fun sendCommandAwaitOk(
        command: String,
        timeoutMs: Long = 5000,
        onResult: (Boolean, String?) -> Unit
    ) {
        val addr = PreferencesUtil.getOutputDeviceId(appCtx)
        if (addr.isNullOrBlank()) {
            onResult(false, "No device selected")
            return
        }
		
		// debug
		//Log.d(TAG, "CMD: ${command.trimEnd('\r', '\n')}")
		
        // Ensure connection + write; manager keeps a persistent GATT
        ensureMgr().writeOrConnect(
            address = addr,
            serviceUuid = SERVICE_UUID,
            characteristicUuid = CHAR_UUID,
            payload = command.toByteArray(Charsets.UTF_8)
        ) { ok, err ->
            if (!ok) 
			{
				// debug
				//Log.d(TAG, "RSP: <write failed: $err>")
				
                onResult(false, err)
                return@writeOrConnect
            }
            // Wait for the next notification
            ensureMgr().awaitNextNotification(timeoutMs) { bytes ->
                if (bytes == null) 
				{
					Log.d(TAG, "RSP: <timeout>")
                    onResult(false, "No reply")
                    return@awaitNextNotification
                }
                val reply = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrDefault("")
				//Log.d(TAG, "RSP: $reply")
				
                if (reply.contains("R:OK")) onResult(true, null)
                else onResult(false, "Reply: $reply")
            }
        }
    }


    fun writePassword(
        bytes: ByteArray,
        address: String? = currentAddress,
        service: UUID = SERVICE_UUID,
        characteristic: UUID = CHAR_UUID,
        onResult: (Boolean, String?) -> Unit
    ) {
        val target = address
        if (target.isNullOrBlank()) { onResult(false, "No device selected"); return }
        ensureMgr().writeOrConnect(
            address = target,
            serviceUuid = service,
            characteristicUuid = characteristic,
            payload = bytes,
            onResult = { ok, err ->
                if (ok) _connected.postValue(true)
                onResult(ok, err)
            }
        )
    }

    fun disconnect() {
        ensureMgr().disconnect()
        _connected.postValue(false)
    }
}
