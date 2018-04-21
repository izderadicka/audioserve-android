package eu.zderadicka.audioserve

import android.content.SharedPreferences
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.preference.*
import android.util.Log
import android.widget.Toast
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.CacheManager
import eu.zderadicka.audioserve.net.MEDIA_CACHE_DIR
import java.io.File
import java.net.URL
import java.util.*

private const val LOG_TAG = "Settings"

class SettingsFragment: PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {


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

            Thread({
                ApiClient.clearCache(activity)
                CacheManager.clearCache(activity)
                activity.runOnUiThread({
                    Toast.makeText(activity, "Cache cleared", Toast.LENGTH_LONG).show()
                })
            }).run()
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

        fun fileSize(s:Long): String {
            return android.text.format.Formatter.formatShortFileSize(activity, s)
        }

        val l = ArrayList<Pair<String,String>>()
        val cname = getString(R.string.storage_internal_cache)
        val cfile = activity.cacheDir
        l.add(Pair(cname + " (${fileSize(cfile.freeSpace)})",cfile.absolutePath))
        val sname = R.string.storage_internal
        val sfile = activity.filesDir
        l.add(Pair(getString(sname) + " (${fileSize(sfile.freeSpace)})",sfile.absolutePath))

       activity.externalMediaDirs.forEachIndexed { index, file ->
            if (Environment.getExternalStorageState(file) == Environment.MEDIA_MOUNTED) {
                val name = getString(R.string.storage_external, index)
                l.add(Pair(name+ " (${fileSize(file.freeSpace)})",file.absolutePath))
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
        updateSummary(findPreference(key))
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
