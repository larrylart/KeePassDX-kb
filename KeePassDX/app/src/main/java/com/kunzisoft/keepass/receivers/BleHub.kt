package com.kunzisoft.keepass.receivers

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import com.kunzisoft.keepass.settings.PreferencesUtil

object BleHub {
    private lateinit var appCtx: Context
    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    // (NUS) Nordic UART defaults — same ones you already use
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val CHAR_UUID:    UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

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
    ) {
        setTarget(address)
        if (!useExternal || address.isNullOrBlank()) {
            onReady?.invoke(false, "Output device not enabled or not selected")
            return
        }
        ensureMgr().connect(address) { ok, err ->
            _connected.postValue(ok)
            onReady?.invoke(ok, err)
        }
    }

	// add this function inside object BleHub
	fun autoConnectFromPrefs(onReady: ((Boolean, String?) -> Unit)? = null) {
		val useExt = PreferencesUtil.useExternalKeyboardDevice(appCtx)
		val addr   = PreferencesUtil.getOutputDeviceId(appCtx)
		autoConnectIfEnabled(useExt, addr, onReady)
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
