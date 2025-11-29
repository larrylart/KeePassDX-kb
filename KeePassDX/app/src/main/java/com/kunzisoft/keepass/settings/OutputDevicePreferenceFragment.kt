////////////////////////////////////////////////////////////////////
// OutputDevicePreferenceFragment
//
// Settings screen responsible for:
//
//  - Listing nearby BLE devices (via BluetoothDeviceManager)
//  - Showing current selection + pairing state
//  - Letting the user toggle "Use external keyboard device" on/off
//  - Handling pairing/unpairing
//  - Initiating BleHub "connect + handshake" from the Settings screen
//  - Providing a password prompt UI for APPKEY provisioning
//  - Updating the keyboard layout on the dongle (binary MTLS C0/C1/C2 ops)
//
// This fragment is the **UI layer**; all BLE logic happens in:
//     BluetoothDeviceManager  – GATT + scanning
//     BleHub                  – provisioning + MTLS + command API
//
// NOTE: A large portion of the logic in this file is tightly coupled
//       to BlueKeyboard firmware. Some of it may eventually belong
//       in a dedicated UI-helper class.
//
// IMPORTANT: Settings-based connects **allowPrompt = true**, while
//            app-start auto-connects do *not* show password dialogs.
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

import android.util.Log

class OutputDevicePreferenceFragment : NestedSettingsFragment() 
{

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

	////////////////////////////////////////////////////////////////////
	// Receiver for system bond-state changes.
	// - Refreshes the device list when bonding transitions
	// - Updates UI for selected device
	// - If the *selected* device becomes BONDED, automatically triggers
	//   a Settings-grade connect (BleHub.connectFromSettings),
	//   which may show password prompts if provisioning is needed.
	////////////////////////////////////////////////////////////////////
	private val bondReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) return

			val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
			val was = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
			val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

			// keep your UI refresh
			try { manager.refreshBonded() } catch (_: SecurityException) {}
			updateActionRow(PreferencesUtil.getOutputDeviceId(requireContext()))

			// NEW: when selected device just became BONDED, run the Settings connect (allowPrompt = true)
			val selected = PreferencesUtil.getOutputDeviceId(requireContext())
			if (dev.address == selected && was != BluetoothDevice.BOND_BONDED && now == BluetoothDevice.BOND_BONDED) {
				BleHub.connectFromSettings { ok, err ->
					// optional: feedback
					if (!ok) {
						Toast.makeText(requireContext(), err ?: "Failed to connect", Toast.LENGTH_SHORT).show()
					}
				}
			}
		}
	}

	private fun postUI(block: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) block()
		else Handler(Looper.getMainLooper()).post(block)
	}

	////////////////////////////////////////////////////////////////////
	// onCreateScreenPreference
	//
	// Main initialization of the settings screen:
	//
	//   1. Inject password dialog provider into BleHub (MUST happen early)
	//   2. Load preferences (device type, selected device, layout, toggle)
	//   3. Initialize BluetoothDeviceManager
	//   4. Set up all UI callbacks:
	//        - toggle changed
	//        - device selected from dropdown
	//        - layout changed
	//        - pair/unpair button
	//
	// This is the main glue between Settings UI <-> BLE control/hub logic.
	////////////////////////////////////////////////////////////////////
    override fun onCreateScreenPreference(
        screen: Screen,
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
		// ensure BleHub holds app context + has a password prompt EARLY
		BleHub.init(requireContext())

		// Register a UI callback for BleHub that can show a password dialog.
		// BleHub will call this when APPKEY provisioning requires user input.
		// MUST be registered before any connectFromSettings() triggers, or
		// you'll get crashes / "prompt is null" paths.
		BleHub.setPasswordPrompt { _, reply ->
			postUI {
				//Log.d("BleHub", "DEBUG: passwordPrompt UI callback invoked, showing dialog")

				val activity = requireActivity()
				val edit = android.widget.EditText(activity).apply {
					inputType = android.text.InputType.TYPE_CLASS_TEXT or
								android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
					hint = "App password"
				}
				androidx.appcompat.app.AlertDialog.Builder(activity)
					.setTitle("Secure the dongle")
					.setMessage("Enter the dongle password")
					.setView(edit)
					.setPositiveButton("OK") { _, _ -> reply(edit.text.toString().toCharArray()) }
					.setNegativeButton("Cancel") { _, _ -> reply(null) }
					.show()
			}
		}		
		
        setPreferencesFromResource(R.xml.preferences_output_device, rootKey)
        manager = BluetoothDeviceManager(requireContext())

		// Device type selector (currently only "Blue KB"). Stored in prefs for future extensibility (multiple device types).
        deviceTypePref = requireNotNull(findPreference(getString(R.string.pref_device_type_key)))
        val deviceTypeEntries = listOf(getString(R.string.device_type_blue_kb))
        val deviceTypeValues  = listOf("BLUE_KB")
        val savedDeviceType   = PreferencesUtil.getDeviceType(requireContext()) ?: "BLUE_KB"
        deviceTypePref.setData(deviceTypeEntries, deviceTypeValues, savedDeviceType)
        deviceTypePref.onSelected = { value, _ ->
            // Stub: just store for now
            PreferencesUtil.setDeviceType(requireContext(), value)
        }

		// rowPref: dropdown of discovered devices + on/off switch
		// actionPref: left-side "Pair / Unpair / Connect" button
		// layoutPref: keyboard layout selector (sent via binary C0 -> ACK)
        rowPref = requireNotNull(findPreference("pref_output_dongle_row"))
		actionPref = requireNotNull(findPreference<ActionButtonStartPreference>(KEY_ACTION))

		//////////////////////////
        // Keyboard Layout 
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
		
		/////////
		// :: Master toggle: "Use External Keyboard Device"
		//
		// OFF:
		//   - Persist OFF in prefs immediately
		//   - Clear disabled-by-error flag
		//   - Drop any active BLE link (BleHub.disconnect())
		//   - Disable all related UI widgets
		//
		// ON:
		//   - Requires a selected device; otherwise reverts toggle
		//   - Runs BleHub.connectFromSettings() (full MTLS handshake)
		//   - On failure: revert toggle/state
		//   - On success:
		//       - persist ON in prefs
		//       - clear disabled-by-error
		//       - query layout from dongle (C1-C2)
		//       - update UI accordingly
		////////////////////////////////////////////
		rowPref.onToggleChanged = { isChecked ->		

			// keep UI in sync with master toggle
			rowPref.setDropdownEnabled(isChecked)
			actionPref.isEnabled = isChecked
			deviceTypePref.isEnabled = isChecked
			layoutPref.isEnabled = isChecked

			if (!isChecked) 
			{
				// Persist OFF immediately
				PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), false)
                PreferencesUtil.setOutputDeviceDisabledByError(requireContext(), false)
				
				// turned OFF - drop any live BLE link
				BleHub.disconnect()
				
			} else 
			{
				// turned ON - try to connect+handshake to the currently selected device
				val addr = PreferencesUtil.getOutputDeviceId(requireContext())
				if (addr.isNullOrBlank()) 
				{
					Toast.makeText(requireContext(), R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
					// Revert UI since we can’t connect without a device
					rowPref.setSwitchChecked(false)
					rowPref.setDropdownEnabled(false)
					actionPref.isEnabled = false
					deviceTypePref.isEnabled = false
					layoutPref.isEnabled = false
					
				} else 
				{				
					//BleHub.connectAndEstablishSecure { ok, _ ->					
					BleHub.connectFromSettings { ok, _ ->
						postUI {
							if (!ok) 
							{
								Toast.makeText(requireContext(), R.string.msg_failed_connect_device, Toast.LENGTH_SHORT).show()
                                // keep prefs + UI consistent on failure
                                //??PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), false)
								
                                rowPref.setSwitchChecked(false)
                                actionPref.isEnabled = false
                                deviceTypePref.isEnabled = false
                                layoutPref.isEnabled = false
								
							} else 
							{
								// Persist ON after success (prevents silent auto-connect race)
								PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), true)
								PreferencesUtil.setOutputDeviceDisabledByError(requireContext(), false)
								
								// ask device which layout it uses now (binary C1-C2)
								BleHub.getLayout { okL, layoutId, _ ->
									postUI {
										if (okL && layoutId != null) {
											// store & reflect in UI (map your numeric id to saved string if needed)
											// If you're already storing a string code, keep the existing prefs flow:
											val saved = PreferencesUtil.getKeyboardLayout(requireContext())
											if (!saved.isNullOrBlank()) layoutPref.setSelectedValue(saved)
										}
									}
								}
							}
						}
					}
					
				}
			}
		}
		
		////////////
		// :: onDeviceSelected
		//
		// When user picks a device from the dropdown:
		//
		//   1. Persist selection (address + name)
		//   2. Update "pair/unpair/connect" action button
		//   3. If feature is enabled:
		//         - Drop any existing session
		//         - Connect + MTLS-handshake the newly selected device
		//         - Sync layout from device (C1-C2)
		//         - Persist enablement flags
		//
		// THIS is the path used when manually selecting or switching devices.
		//////////////////////////////////////////////////////////
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
				BleHub.connectFromSettings { ok, _ ->
					postUI {
						if (!ok) 
						{
							Toast.makeText(requireContext(), R.string.msg_failed_connect_device, Toast.LENGTH_SHORT).show()
							
                            rowPref.setSwitchChecked(false)
                            actionPref.isEnabled = false
                            deviceTypePref.isEnabled = false
                            layoutPref.isEnabled = false
							
						} else 
						{
							PreferencesUtil.setUseExternalKeyboardDevice(requireContext(), true)
							PreferencesUtil.setOutputDeviceDisabledByError(requireContext(), false)
							
							BleHub.getLayout { okL, layoutId, _ ->
								postUI {
									if (okL && layoutId != null) {
										val saved = PreferencesUtil.getKeyboardLayout(requireContext())
										if (!saved.isNullOrBlank()) layoutPref.setSelectedValue(saved)
									}
								}
							}
						}
					}
				}
								
			}
		}        

		/////////////
		// :: Keyboard layout selector (string code)
		//
		// User chooses a layout (e.g. "UK_WINLIN").
		//
		// Sends the layout string via secure MTLS command:
		//      C0(payload=layoutString) - expects ACK(0x00)
		//
		// On success: store & reflect in UI
		// On failure: revert UI, show error
		//////////////////////////////////////////////////////////////
		layoutPref.onSelected = { value, _ ->
			val ctx = requireContext()
			val prev = PreferencesUtil.getKeyboardLayout(ctx)
			val address = PreferencesUtil.getOutputDeviceId(ctx)

			if (address.isNullOrBlank()) {
				Toast.makeText(ctx, R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
				// revert UI immediately
				layoutPref.setSelectedValue(prev)
			} else {

				// value is your string code (e.g. "UK_WINLIN")
				// Send the string to the dongle and wait for ACK(0x00)
				BleHub.setLayoutString(value) { ok, err ->
					postUI {
						if (ok) {
							PreferencesUtil.setKeyboardLayout(ctx, value)
							Toast.makeText(ctx, R.string.msg_layout_set_ok, Toast.LENGTH_SHORT).show()
						} else {
							// revert UI to previous selection
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

	////////////////////////////////////////////////////////////////////
	// Handles pair/unpair from both:
	//   - actionPref row tap
	//   - action button click
	//
	// manager.pair/unpair() uses platform APIs / reflection.
	// Shows a toast for success/failure.
	////////////////////////////////////////////////////////////////////
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

	////////////////////////////////////////////////////////////////////
	// onStart()
	//
	//  - Request missing Bluetooth permissions
	//  - Start scanning once permissions are granted
	//  - Register bond receiver
	//  - Sync UI components (layout selector, main toggle)
	//
	// NOTE: BleHub passwordPrompt *must* already be set earlier.
	////////////////////////////////////////////////////////////////////
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
		
		// NEW: refresh switch state from prefs in case it was flipped by startup policy
		val enabled = PreferencesUtil.useExternalKeyboardDevice(requireContext())
		rowPref.setSwitchChecked(enabled)
		actionPref.isEnabled = enabled
		deviceTypePref.isEnabled = enabled
		layoutPref.isEnabled = enabled	
    }

	////////////////////////////////////////////////////////////////////
	// onStop()
	//  - Unregister bond receiver
	//  - Stop BLE scan
	//  - Remove BleHub password prompt (avoids leaking UI context)
	//
	// IMPORTANT: If we leave this screen while a provisioning flow is
	//           active, BleHub will no longer be allowed to show dialogs.
	////////////////////////////////////////////////////////////////////
    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        manager.stop()
		
		// IMPORTANT: remove the provider when we leave this screen
		BleHub.clearPasswordPrompt()
	
    }

    private fun refreshStart() {
        if (!manager.isBluetoothReady()) {
            Toast.makeText(requireContext(), R.string.msg_bluetooth_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        manager.devices.observe(viewLifecycleOwner) { list -> populateRow(list) }
        manager.start()
    }

	////////////////////////////////////////////////////////////////////
	// Populate dropdown entries from manager.devices LiveData.
	// Entries contain device name + address + paired marker.
	// Also ensures the action button visibility matches selection.
	////////////////////////////////////////////////////////////////////
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
        val star = if (d.bonded) " -paired" else ""
        return "$name (${d.address})$star"
    }

	////////////////////////////////////////////////////////////////////
	// Updates the left-side button (pair/unpair/connect) based on:
	//   - whether a device is selected
	//   - whether that device is bonded
	//
	// actionPref.paired toggles the button label + icon dynamically.
	////////////////////////////////////////////////////////////////////
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

	////////////////////////////////////////////////////////////////////	
	// Returns (labels, values) for all supported keyboard layouts.
	// values  = short codes sent to firmware
	// labels  = human-readable strings shown in UI
	//
	// The list includes:
	//   - UK, IE, US, DE, FR, ES, IT
	//   - PT-PT, PT-BR
	//   - Nordics (SE, NO, DK, FI)
	//   - Swiss variants
	//   - Turkey
	//
	// Order matters for dropdown readability.
	// TODO: maybe create a command that will fetch the available layout from dongle
	////////////////////////////////////////////////////////////////////
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

////////////////////////////////////////////////////////////////////
// ActionButtonStartPreference
//
// Custom Preference that shows a MaterialButton inside a settings row.
//
// Used for: Pair / Unpair / Connect button in Output Device settings.
//
// Properties:
//   paired: toggles button label between "Unpair" / "Pair"
//   onButtonClick: callback invoked when button pressed
//
// onBindViewHolder updates the button label + listener dynamically.
////////////////////////////////////////////////////////////////////
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

