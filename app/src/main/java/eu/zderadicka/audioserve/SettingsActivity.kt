package eu.zderadicka.audioserve

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.preference.*
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.CacheManager
import eu.zderadicka.audioserve.net.MEDIA_CACHE_DIR
import java.io.File
import java.util.*

private const val LOG_TAG = "Settings"

class SettingsFragment: PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {


    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.settings)

        val transcodingPref: ListPreference = findPreference("pref_transcoding") as ListPreference
        transcodingPref.entries = arrayOf(
                activity.getString(R.string.transcoding_low) + " ${ApiClient.transcodingBitrates.low} kbps",
                activity.getString(R.string.transcoding_medium) + " ${ApiClient.transcodingBitrates.medium} kbps",
                activity.getString(R.string.transcoding_high) + " ${ApiClient.transcodingBitrates.high} kbps",
                activity.getString(R.string.transcoding_no)
                )

        val cacheLocationPref: ListPreference = findPreference("pref_cache_location") as ListPreference
        val prefs =  PreferenceManager.getDefaultSharedPreferences(activity)
        val cacheLocation =prefs.getString("pref_cache_location",null)
        val entriesNames = storagesList.map{it.first}.toTypedArray()
        val entriesValues = storagesList.map{it.second}.toTypedArray()
        if (cacheLocation == null) {
            prefs.edit().putString("pref_cache_location", entriesValues.get(0)).commit()
        }
        cacheLocationPref.entries = entriesNames
        cacheLocationPref.entryValues = entriesValues
        cacheLocationPref.setDefaultValue(activity.cacheDir.absolutePath)

        for (i in 0 until preferenceScreen.preferenceCount) {
            val pref = preferenceScreen.getPreference(i)
            if (pref is PreferenceCategory) {
                for (j in 0 until pref.preferenceCount) {
                    updateSummary(pref.getPreference(j))
                }
            } else {
                updateSummary(pref)
            }
        }


        // validate url
        findPreference("pref_server_url").setOnPreferenceChangeListener { _, newValue ->

            val ok = try {
                val uri = Uri.parse(newValue as String)
                uri != null && (uri.scheme == "http" || uri.scheme == "https")
                && uri.host != null
                && uri.path?.endsWith("/") == true
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
                            getString(R.string.connection_error, err.name),
                            Toast.LENGTH_LONG).show()
                }
            }
            true
        }

        findPreference("pref_cache_size").setOnPreferenceChangeListener{ _, newValue ->
            try {
                val n = (newValue as String).toInt()
                n>= 0 && n<=50000
                true
            } catch (e: Exception) {
                false
            }
        }

        findPreference("pref_delayed_fg_stop").setOnPreferenceChangeListener{_, newValue ->
            try {
                val n = (newValue as String).toInt();
                n>=0 && n< 365 * 24
            } catch (e: Exception) {
                false
            }
        }

        findPreference("pref_volume_boost_db").setOnPreferenceChangeListener{_, newValue ->
            try {
                val n = (newValue as String).toInt();
                n>=1 && n <= 30
            } catch (e: Exception) {
                false
            }
        }

        findPreference("pref_clear_cache").setOnPreferenceClickListener {

            Thread {
                ApiClient.clearCache(activity)
                CacheManager.clearCache()
                activity.runOnUiThread({
                    Toast.makeText(activity, "Cache cleared", Toast.LENGTH_LONG).show()
                })
            }.run()
            true
        }

        findPreference("pref_night_theme").setOnPreferenceChangeListener{_, newValue ->
            val mode = (newValue as String).toInt()
            AppCompatDelegate.setDefaultNightMode(mode)
            Toast.makeText(activity, "May need to exit and start application to apply", Toast.LENGTH_LONG).show()
            true
        }

        cacheLocationPref.setOnPreferenceChangeListener { preference, newValue ->

            val oldValue = preference.sharedPreferences.getString(preference.key, null)
            val oldCacheDir = File(oldValue, MEDIA_CACHE_DIR)
            if (oldCacheDir.isDirectory) {
                val res = oldCacheDir.deleteRecursively()
                if (! res) {
                    Log.e(LOG_TAG, "Cannot delete old cache")
                }
            }

            val newDir = File(newValue as String)
            newDir.isDirectory()
        }
    }

    private val storagesList: List<Pair<String,String>>
    get (){

        fun freeSize(s:Long): String {
            val size =  android.text.format.Formatter.formatShortFileSize(activity, s)
            return getString(R.string.free_capacity, size)
        }

        val l = ArrayList<Pair<String,String>>()
        val cname = getString(R.string.storage_internal_cache)
        val cfile = activity.cacheDir
        l.add(Pair(cname + " (${freeSize(cfile.freeSpace)})",cfile.absolutePath))
        val sname = R.string.storage_internal
        val sfile = activity.filesDir
        l.add(Pair(getString(sname) + " (${freeSize(sfile.freeSpace)})",sfile.absolutePath))

       activity.externalMediaDirs.forEachIndexed { index, file ->

           if (file != null && Environment.getExternalStorageState(file) == Environment.MEDIA_MOUNTED) {
               val name = getString(R.string.storage_external, index.toString())
               l.add(Pair(name + " (${freeSize(file.freeSpace)})", file.absolutePath))
           }

       }
        return l
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
        findPreference(key)?.let { updateSummary(it) }
    }

    private fun updateSummary(pref: Preference) {
        val sps = preferenceScreen.sharedPreferences
        when (pref.key) {
            "pref_server_url" -> pref.summary = sps.getString(pref.key, "")
            "pref_cache_size" -> {
                val v  = sps.getString(pref.key, "0")
                pref.summary = "$v MB"
            }
            "pref_shared_secret" -> {
                val secret = sps.getString("pref_shared_secret", null)
                if (secret != null && secret.length>0) {
                    pref.summary = getString(R.string.pref_shared_secret_summary_set)
                } else {
                    pref.summary = getString(R.string.pref_shared_secret_summary_empty)
                }
            }
            "pref_transcoding" -> {
                if ( pref !is  ListPreference) return
                val t = sps.getString("pref_transcoding", null)?: return
                if (t=="0") {
                    pref.summary=getString(R.string.no_transcoding)
                } else {
                    val idx = pref.findIndexOfValue(t)
                    pref.summary = getString(R.string.pref_transcoding_summary, pref.entries.get(idx))
                }
            }
            "pref_preload" -> {
                if ( pref !is  ListPreference) return
                val p = sps.getString("pref_preload", null)?: return
                if (p == "0") {
                    pref.summary = "Will not preload any files ahead of current"
                } else {
                    pref.summary = getString(R.string.pref_preload_summary, p)
                }
            }
            "pref_cache_location" -> {
                if ( pref !is  ListPreference) return
                val value = sps.getString("pref_cache_location", null)?: return
                val idx = pref.findIndexOfValue(value)
                val name = if (idx>=0) pref.entries.get(idx) else ""
                pref.summary = "$name : $value"
            }
            "pref_downloads" -> {
                if (pref !is ListPreference) return
                val p = sps.getString("pref_downloads", null) ?: return
                pref.summary = getString(R.string.pref_downloads_summary, p)

            }
            "pref_autorewind" -> {
                if (pref !is CheckBoxPreference) return
                val checked = sps.getBoolean("pref_autorewind", true)
                if (checked) {
                    pref.summary=getString(R.string.pref_autorewind_selected_title)

                } else {
                    pref.summary=getString(R.string.pref_autorewind_unselected_title)
                }

            }

            "pref_skip_silence" -> {
                if (pref !is CheckBoxPreference) return
                val checked = sps.getBoolean("pref_skip_silence", false)
                if (checked) {
                    pref.summary=getString(R.string.pref_skip_silence_selected_summary)

                } else {
                    pref.summary=getString(R.string.pref_skip_silence_unselected_summary)
                }

            }

            "pref_web_search_prefix" -> {
                if (pref !is EditTextPreference) return
                val prefix = sps.getString("pref_web_search_prefix", null)
                if (prefix.isNullOrBlank()) {
                    pref.summary = getString(R.string.pref_web_search_prefix_summary_empty)
                } else {
                    pref.summary = getString(R.string.pref_web_search_prefix_summary, prefix)
                }
            }

            "pref_delayed_fg_stop" -> {
                if (pref !is EditTextPreference) return
                val delay = sps.getString("pref_delayed_fg_stop", null)
                val isOff = delay.isNullOrBlank() || delay.toInt() == 0
                if (isOff) {
                    pref.summary = getString(R.string.pref_delayed_fg_stop_summary_off)
                } else {
                    pref.summary = getString(R.string.pref_delayed_fg_stop_summary_on, delay)
                }
            }

            "pref_playback_speed", "pref_playback_pitch" -> {
            }

            "pref_volume_boost" -> {
                if (pref !is CheckBoxPreference) return
                val checked = sps.getBoolean( "pref_volume_boost", false)
                if (checked) {
                    pref.summary=getString(R.string.pref_volume_boost_on_summary)

                } else {
                    pref.summary=getString(R.string.pref_volume_boost_off_summary)
                }
            }

            "pref_volume_boost_db" -> {
                val v  = sps.getString(pref.key, "1")
                pref.summary = getString(R.string.pref_volume_boost_db_summary, v)
            }

            "pref_group" -> {
                val v  = sps.getString(pref.key, null)

                if (v.isNullOrBlank()) {
                    pref.summary = getString(R.string.pref_group_summary_null)
                } else {
                    pref.summary = getString(R.string.pref_group_summary_filled,v)
                }
            }

            "pref_night_theme" -> {
                val v =sps.getString(pref.key, null)?: return
                when (v) {
                    "-1" -> {pref.summary = "Using system wise setting for Day/Night theme"}
                    "1" -> {pref.summary = "Always Day theme"}
                    "2" -> {pref.summary = "Always Night theme"}
                }
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
