package eu.zderadicka.audioserve

import android.content.SharedPreferences
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.widget.Toast
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.CacheManager
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

            val ok = try {
                val uri = Uri.parse(newValue as String)
                uri != null && (uri.scheme == "http" || uri.scheme == "https")
                && uri.host != null
                && uri.path.endsWith("/")
            } catch (e: Exception) {
                false
            }
            if (! ok) Toast.makeText(activity,getString(R.string.pref_server_url_error_msg), Toast.LENGTH_LONG).show()
            ok
        }

        findPreference("pref_test_connection").setOnPreferenceClickListener {
            ApiClient.getInstance(activity).loadPreferences() { err ->
                if (err == null) {

                        Toast.makeText(activity, getString(R.string.connection_working), Toast.LENGTH_LONG).show()

                } else {
                    Toast.makeText(activity,
                            getString(R.string.connection_error, err?.name?:getString(R.string.unknown_error)),
                            Toast.LENGTH_LONG).show()
                }
            }
            true
        }

        findPreference("pref_cache_size").setOnPreferenceChangeListener{ pref, newValue ->
            try {
                val n = (newValue as String).toInt()
                n>= 0 && n<=50000
                true
            } catch (e: Exception) {
                false
            }
        }

        findPreference("pref_clear_cache").setOnPreferenceClickListener {
            //FIXME - this is provisional and usafe
            Thread({
                ApiClient.clearCache(activity)
                CacheManager.clearCache(activity)
                activity.runOnUiThread({
                    Toast.makeText(activity, "Cache cleared", Toast.LENGTH_LONG).show()
                })
            }).run()
            true
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
        } else if (pref.key == "pref_cache_size") {
            val v  = preferenceScreen.sharedPreferences.getString(pref.key, "0")
            pref.summary = "$v MB"
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