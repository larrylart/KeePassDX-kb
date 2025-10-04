////////////////////////////////////////////////////////////////////
// Larry note: some of the functionality in here, specific to blue_keyboard might 
// need to move to separate class
////////////////////////////////////////////////////////////////////

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
import com.kunzisoft.keepass.settings.preference.SimpleDropdownPreference

import android.os.Handler
import android.os.Looper

class OutputDevicePreferenceFragment : NestedSettingsFragment() {

    private lateinit var manager: BluetoothDeviceManager

    private lateinit var rowPref: OutputDeviceRowPreference
    //private lateinit var actionPref: Preference
	private lateinit var actionPref: ActionButtonStartPreference

    private val KEY_ACTION = "pref_output_dongle_connect"

    private lateinit var deviceTypePref: SimpleDropdownPreference
    private lateinit var layoutPref: SimpleDropdownPreference


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

	private fun postUI(block: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) block()
		else Handler(Looper.getMainLooper()).post(block)
	}

    override fun onCreateScreenPreference(
        screen: Screen,
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        setPreferencesFromResource(R.xml.preferences_output_device, rootKey)
        manager = BluetoothDeviceManager(requireContext())

        // --- Device Type (stub) ---
        deviceTypePref = requireNotNull(findPreference(getString(R.string.pref_device_type_key)))
        val deviceTypeEntries = listOf(getString(R.string.device_type_blue_kb))
        val deviceTypeValues  = listOf("BLUE_KB")
        val savedDeviceType   = PreferencesUtil.getDeviceType(requireContext()) ?: "BLUE_KB"
        deviceTypePref.setData(deviceTypeEntries, deviceTypeValues, savedDeviceType)
        deviceTypePref.onSelected = { value, _ ->
            // Stub: just store for now
            PreferencesUtil.setDeviceType(requireContext(), value)
        }

        rowPref = requireNotNull(findPreference("pref_output_dongle_row"))
        //actionPref = requireNotNull(findPreference(KEY_ACTION))
		actionPref = requireNotNull(findPreference<ActionButtonStartPreference>(KEY_ACTION))

        // Initial enable state from global setting
        //val enabled = PreferencesUtil.useExternalKeyboardDevice(requireContext())
        //rowPref.setSwitchChecked(enabled)
        //actionPref.isEnabled = enabled


		//////////////////////////
        // --- Keyboard Layout ---
        layoutPref = requireNotNull(findPreference(getString(R.string.pref_keyboard_layout_key)))

		// set my value for keyboard layout 
		val (layoutEntries, layoutValues) = keyboardLayoutOptions()

        // Preselect last saved (or none)
        val savedLayout = PreferencesUtil.getKeyboardLayout(requireContext())
        layoutPref.setData(layoutEntries, layoutValues, savedLayout)

		// Initial enable state from global setting (AFTER layoutPref exists)
		val enabled = PreferencesUtil.useExternalKeyboardDevice(requireContext())
		rowPref.setSwitchChecked(enabled)      // if this fires callback, layoutPref is ready now
		actionPref.isEnabled = enabled
		deviceTypePref.isEnabled = enabled
		layoutPref.isEnabled = enabled
		rowPref.setDropdownEnabled(enabled)


        // Enable/disable dropdowns along with the main toggle
        //val enabled_kbl = PreferencesUtil.useExternalKeyboardDevice(requireContext())
        //deviceTypePref.isEnabled = enabled_kbl
        //layoutPref.isEnabled = enabled_kbl


        // Toggle persists via PreferencesUtil and gates the row/action
        /*rowPref.onToggleChanged = { isChecked ->
            PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), isChecked)
            rowPref.setDropdownEnabled(isChecked)
            actionPref.isEnabled = isChecked
			
			// if user disables output device, drop the BLE link immediately
			if (!isChecked) {
				BleHub.disconnect()
			}			
        }*/
		
		// NEW onToggleChanged to handle connect not just disconnect
		rowPref.onToggleChanged = { isChecked ->
			PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), isChecked)

			// keep UI in sync with master toggle
			rowPref.setDropdownEnabled(isChecked)
			actionPref.isEnabled = isChecked
			deviceTypePref.isEnabled = isChecked
			layoutPref.isEnabled = isChecked

			if (!isChecked) {
				// turned OFF - drop any live BLE link
				BleHub.disconnect()
			} else {
				// turned ON - try to connect+handshake to the currently selected device
				val addr = PreferencesUtil.getOutputDeviceId(requireContext())
				if (addr.isNullOrBlank()) {
					Toast.makeText(requireContext(), R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
				} else 
				{
					BleHub.connectAndFetchLayoutSimple(timeoutMs = 2500L) { ok, _ ->
						postUI {
							if (!ok) {
								Toast.makeText(requireContext(),
									R.string.msg_failed_connect_device,
									Toast.LENGTH_SHORT
								).show()
							} else 
							{
								// Ensure UI thread for preference UI updates
								val newLayout = PreferencesUtil.getKeyboardLayout(requireContext())
								if (!newLayout.isNullOrBlank()) {
									layoutPref.setSelectedValue(newLayout)
								}								
							}
						}
					}
				}
			}
		}

		// end of new change

        // Selection persists via PreferencesUtil and updates the action row
        /*rowPref.onDeviceSelected = { address, _label ->
            val name = manager.devices.value.orEmpty()
                .firstOrNull { it.address == address }?.name.orEmpty()
            PreferencesUtil.setOutputDeviceId(requireContext(), address)
            PreferencesUtil.setOutputDeviceName(requireContext(), name)
            updateActionRow(address)
        }*/
		
		// NEW onDeviceSelected - to connect/disconnect to selected devices
		rowPref.onDeviceSelected = { address, _label ->
			val name = manager.devices.value.orEmpty()
				.firstOrNull { it.address == address }?.name.orEmpty()

			// Persist selection
			PreferencesUtil.setOutputDeviceId(requireContext(), address)
			PreferencesUtil.setOutputDeviceName(requireContext(), name)
			updateActionRow(address)

			// Only auto-connect if the feature is enabled
			if (PreferencesUtil.useExternalKeyboardDevice(requireContext())) {
				// 1) Close any existing link
				BleHub.disconnect()

				// 2) Connect + read layout via the same handshake used elsewhere
				BleHub.connectAndFetchLayoutSimple(timeoutMs = 2500L) { ok, _ ->
					postUI {
						if (!ok) {
							Toast.makeText(requireContext(), R.string.msg_failed_connect_device, Toast.LENGTH_SHORT).show()
						} else {
							// Ensure UI thread for preference UI updates							
							val newLayout = PreferencesUtil.getKeyboardLayout(requireContext())
							if (!newLayout.isNullOrBlank()) {
								layoutPref.setSelectedValue(newLayout)
							}							
						}
					}
				}
			}
		}
        
		/* duplicate ? why... test and cleanup
		rowPref.onToggleChanged = { isChecked ->
            PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), isChecked)
            rowPref.setDropdownEnabled(isChecked)
            actionPref.isEnabled = isChecked
            deviceTypePref.isEnabled = isChecked
            layoutPref.isEnabled = isChecked
            if (!isChecked) {
                com.kunzisoft.keepass.receivers.BleHub.disconnect()
            }
        }

        // When device changes, keep the dropdowns enabled/disabled in sync
        rowPref.onDeviceSelected = { address, _label ->
            val name = manager.devices.value.orEmpty()
                .firstOrNull { it.address == address }?.name.orEmpty()
            PreferencesUtil.setOutputDeviceId(requireContext(), address)
            PreferencesUtil.setOutputDeviceName(requireContext(), name)
            updateActionRow(address)
        }
		*/

		///////////////////
        // On Keyboard Layout select: send "C:SET:LAYOUT=VALUE\n" and await "R:OK"
		layoutPref.onSelected = { value, _ ->
			val ctx = requireContext()
			val prev = PreferencesUtil.getKeyboardLayout(ctx)
			val address = PreferencesUtil.getOutputDeviceId(ctx)

			if (address.isNullOrBlank()) {
				Toast.makeText(ctx, R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
				// revert UI immediately
				layoutPref.setSelectedValue(prev)
			} else {
				// Build command with newline
				val cmd = "C:SET:LAYOUT=$value\n"

				// Fire command then wait for a single notification "R:OK"
				BleHub.sendCommandAwaitOk(cmd) { ok, err ->
					if (ok) {
						postUI {
							PreferencesUtil.setKeyboardLayout(ctx, value)
							Toast.makeText(ctx, R.string.msg_layout_set_ok, Toast.LENGTH_SHORT).show()
						}
					} else {
						postUI {
							// Revert UI to previous value
							layoutPref.setSelectedValue(prev)
							val msg = if (err.isNullOrBlank()) getString(R.string.msg_layout_set_failed)
									  else getString(R.string.msg_layout_set_failed) + ": " + err
							Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
						}
					}
				}
			}
		}


		/////////////////
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
			
		// NEW: refresh layout dropdown from prefs on (re)entry
		val current = PreferencesUtil.getKeyboardLayout(requireContext())
		if (!current.isNullOrBlank()) {
			postUI { layoutPref.setSelectedValue(current) }
		}
	
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
	
	// compact list of value labels for keyboard layout
	private fun keyboardLayoutOptions(): Pair<List<String>, List<String>> 
	{
		// value - label 
		val pairs = listOf(
			// existing
			"UK_WINLIN" to "Layout UK Windows/Linux",
			"UK_MAC"    to "Layout UK Mac",
			"IE_WINLIN" to "Layout IE Windows/Linux",
			"IE_MAC"    to "Layout IE Mac",
			"US_WINLIN" to "Layout US Windows/Linux",
			"US_MAC"    to "Layout US Mac",

			// new: DE / FR / ES / IT
			"DE_WINLIN" to "Layout DE Windows/Linux",
			"DE_MAC"    to "Layout DE Mac",
			"FR_WINLIN" to "Layout FR Windows/Linux",
			"FR_MAC"    to "Layout FR Mac",
			"ES_WINLIN" to "Layout ES Windows/Linux",
			"ES_MAC"    to "Layout ES Mac",
			"IT_WINLIN" to "Layout IT Windows/Linux",
			"IT_MAC"    to "Layout IT Mac",

			// new: PT-PT / PT-BR
			"PT_PT_WINLIN" to "Layout PT-PT Windows/Linux",
			"PT_PT_MAC"    to "Layout PT-PT Mac",
			"PT_BR_WINLIN" to "Layout PT-BR Windows/Linux",
			"PT_BR_MAC"    to "Layout PT-BR Mac",

			// new: Nordics (WINLIN only as requested)
			"SE_WINLIN" to "Layout SE Windows/Linux",
			"NO_WINLIN" to "Layout NO Windows/Linux",
			"DK_WINLIN" to "Layout DK Windows/Linux",
			"FI_WINLIN" to "Layout FI Windows/Linux",

			// new: Switzerland variants (WINLIN)
			"CH_DE_WINLIN" to "Layout CH-DE Windows/Linux",
			"CH_FR_WINLIN" to "Layout CH-FR Windows/Linux",

			// new: Turkey
			"TR_WINLIN" to "Layout TR Windows/Linux",
			"TR_MAC"    to "Layout TR Mac"
		)

		val values  = pairs.map { it.first }
		val entries = pairs.map { it.second }
		return entries to values
	}

// end of class	
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

