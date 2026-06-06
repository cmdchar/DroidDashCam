package com.helge.droiddashcam.ui

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.navigation.fragment.findNavController
import com.helge.droiddashcam.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        findPreference<Preference>("remote_viewer")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settings_to_viewer)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(resources.getColor(com.helge.droiddashcam.R.color.black, null))
    }
}
