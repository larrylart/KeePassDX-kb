package com.kunzisoft.keepass.settings

import android.os.Bundle
import androidx.fragment.app.Fragment

class OutputDeviceSettingsActivity : SettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mTimeoutEnable = false
        setTitle(NestedSettingsFragment.Screen.OUTPUT_DEVICE)
    }
    override fun retrieveMainFragment(): Fragment {
        return NestedSettingsFragment.newInstance(NestedSettingsFragment.Screen.OUTPUT_DEVICE)
    }
}
