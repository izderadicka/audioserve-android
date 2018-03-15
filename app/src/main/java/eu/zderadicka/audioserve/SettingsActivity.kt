package eu.zderadicka.audioserve

import android.content.SharedPreferences
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import java.net.URL


class SettingsFragment: PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.settings)

        for (i in 0 until preferenceScreen.preferenceCount) {
            updateSummary(preferenceScreen.getPreference(i))
        }

        // validate url
        findPreference("pref_server_url").setOnPreferenceChangeListener { preference, newValue ->
            try {
                val uri = Uri.parse(newValue as String)
                uri != null && (uri.scheme == "http" || uri.scheme == "https")
                && uri.host != null
            } catch (e: Exception) {
                false
            }


        }
    }


    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        updateSummary(findPreference(key))
    }

    private fun updateSummary(pref: Preference) {
        if (pref.key == "pref_server_url" ) {
            pref.summary = preferenceScreen.sharedPreferences.getString(pref.key, "")
        } else if (pref.key == "pref_shared_secret") {
            val secret = preferenceScreen.sharedPreferences.getString("pref_shared_secret", null)
            if (secret != null && secret.length>0) {
                pref.summary = getString(R.string.pref_shared_secret_summary_set)
            } else {
                pref.summary = getString(R.string.pref_shared_secret_summary_empty)
            }
        }
    }

}
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
}
