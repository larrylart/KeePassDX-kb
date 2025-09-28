package com.kunzisoft.keepass.settings

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.receivers.BluetoothDeviceManager
import com.kunzisoft.keepass.receivers.BtDevice
import android.bluetooth.BluetoothDevice
import com.kunzisoft.keepass.settings.preference.OutputDeviceRowPreference
import com.google.android.material.button.MaterialButton

import androidx.preference.PreferenceViewHolder
import android.util.AttributeSet
import com.kunzisoft.keepass.receivers.BleHub

class OutputDevicePreferenceFragment : NestedSettingsFragment() {

    private lateinit var manager: BluetoothDeviceManager

    private lateinit var rowPref: OutputDeviceRowPreference
    //private lateinit var actionPref: Preference
	private lateinit var actionPref: ActionButtonStartPreference

    private val KEY_ACTION = "pref_output_dongle_connect"

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshStart() }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent.action) {
                try { manager.refreshBonded() } catch (_: SecurityException) {}
                updateActionRow(PreferencesUtil.getOutputDeviceId(requireContext()))
            }
        }
    }

    override fun onCreateScreenPreference(
        screen: Screen,
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        setPreferencesFromResource(R.xml.preferences_output_device, rootKey)
        manager = BluetoothDeviceManager(requireContext())

        rowPref = requireNotNull(findPreference("pref_output_dongle_row"))
        //actionPref = requireNotNull(findPreference(KEY_ACTION))
		actionPref = requireNotNull(findPreference<ActionButtonStartPreference>(KEY_ACTION))

        // Initial enable state from global setting
        val enabled = PreferencesUtil.useExternalKeyboardDevice(requireContext())
        rowPref.setSwitchChecked(enabled)
        actionPref.isEnabled = enabled

        // Toggle persists via PreferencesUtil and gates the row/action
        rowPref.onToggleChanged = { isChecked ->
            PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), isChecked)
            rowPref.setDropdownEnabled(isChecked)
            actionPref.isEnabled = isChecked
			
			// if user disables output device, drop the BLE link immediately
			if (!isChecked) {
				BleHub.disconnect()
			}			
        }

        // Selection persists via PreferencesUtil and updates the action row
        rowPref.onDeviceSelected = { address, _label ->
            val name = manager.devices.value.orEmpty()
                .firstOrNull { it.address == address }?.name.orEmpty()
            PreferencesUtil.setOutputDeviceId(requireContext(), address)
            PreferencesUtil.setOutputDeviceName(requireContext(), name)
            updateActionRow(address)
        }

        // Preload saved selection into the row/action visibility
        val saved = PreferencesUtil.getOutputDeviceId(requireContext())
        updateActionRow(saved)

		// Pair/unpair click (row tap)
		actionPref.setOnPreferenceClickListener {
			handlePairUnpairClick()
			true
		}

		// Pair/unpair click (button on the left)
		actionPref.onButtonClick = {
			handlePairUnpairClick()
		}

    }

	private fun handlePairUnpairClick() {
		val addr = PreferencesUtil.getOutputDeviceId(requireContext()).orEmpty()
		if (addr.isBlank()) {
			Toast.makeText(requireContext(), R.string.msg_select_first, Toast.LENGTH_SHORT).show()
			return
		}

		val bonded = currentIsBonded(addr)
		val ok = if (bonded) manager.unpair(addr) else manager.pair(addr)

		if (!ok) {
			Toast.makeText(requireContext(),
				if (bonded) R.string.msg_unpair_failed else R.string.msg_pair_failed,
				Toast.LENGTH_SHORT).show()
		} else {
			Toast.makeText(requireContext(),
				if (bonded) R.string.msg_unpairing else R.string.msg_pairing,
				Toast.LENGTH_SHORT).show()
		}
	}


    override fun onStart() {
        super.onStart()
        val perms = neededPermissions()
        if (perms.isEmpty()) refreshStart() else reqPerms.launch(perms)
        requireContext().registerReceiver(bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        manager.stop()
    }

    private fun refreshStart() {
        if (!manager.isBluetoothReady()) {
            Toast.makeText(requireContext(), R.string.msg_bluetooth_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        manager.devices.observe(viewLifecycleOwner) { list -> populateRow(list) }
        manager.start()
    }

    private fun populateRow(list: List<BtDevice>) {
        val entries = list.map { labelFor(it) }
        val values  = list.map { it.address }
        val saved   = PreferencesUtil.getOutputDeviceId(requireContext())
        rowPref.setData(entries, values, saved)
        // show/hide action based on saved
        updateActionRow(saved)
    }

    private fun labelFor(d: BtDevice): String {
        val name = d.name.ifBlank { getString(R.string.label_unknown_device) }
        val star = if (d.bonded) " •paired" else ""
        return "$name (${d.address})$star"
    }

    private fun updateActionRow(address: String?) {
        val hasSel = !address.isNullOrBlank()
        actionPref.isVisible = hasSel
        if (!hasSel) return

        val bonded = currentIsBonded(address!!)
        actionPref.title = getString(if (bonded) R.string.btn_unpair else R.string.btn_connect)
        actionPref.summary = getString(
            if (bonded) R.string.btn_unpair_summary else R.string.btn_connect_summary
        )
		
		// this updates the button label
		actionPref.paired = bonded		
    }

    private fun currentIsBonded(address: String): Boolean {
        return manager.devices.value.orEmpty()
            .any { it.address == address && it.bonded }
    }

    private fun neededPermissions(): Array<String> {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

class ActionButtonStartPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : Preference(context, attrs) {

    var paired: Boolean = false
        set(value) {
            field = value
            notifyChanged() // tells Android to rebind the view
        }

    var onButtonClick: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val btn = holder.findViewById(R.id.actionButton) as? MaterialButton
        btn?.apply {
            text = if (paired) context.getString(R.string.btn_unpair)
                   else context.getString(R.string.btn_pair)
            setOnClickListener { onButtonClick?.invoke() }
        }
    }
}

