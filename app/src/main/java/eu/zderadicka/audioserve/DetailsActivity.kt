package eu.zderadicka.audioserve

import android.content.Intent
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.Menu
import android.view.MenuItem
import eu.zderadicka.audioserve.data.pathFromFolderId
import eu.zderadicka.audioserve.fragments.ARG_FOLDER_DETAILS
import eu.zderadicka.audioserve.fragments.ARG_FOLDER_NAME
import eu.zderadicka.audioserve.fragments.ARG_FOLDER_PATH
import eu.zderadicka.audioserve.fragments.DetailsFragment

private val searchUri = Uri.parse("https://www.google.com/search")
private val toReplace = Regex("""[_\-/,]""")
private val bracketed = Regex("""[(\[{].*[)\]}]""")



class DetailsActivity : AppCompatActivity() {

    lateinit var folderName: String
    lateinit var folderPath:String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        folderName = intent.getStringExtra(ARG_FOLDER_NAME)?:""
        val folderId = intent.getStringExtra(ARG_FOLDER_PATH)!!
        folderPath = pathFromFolderId(folderId)
        val details = intent.getBundleExtra(ARG_FOLDER_DETAILS)
        val t = supportFragmentManager.beginTransaction()
        t.add(R.id.detailsFragment, DetailsFragment.newInstance(folderPath,folderName,details))
        t.commit()


    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.info_menu, menu)
        return true
    }

    private val searchString:String
        get() {
            val searchPrefix = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("pref_web_search_prefix", "")
            var query = searchPrefix+ " "+folderName + " " + folderPath
            query = toReplace.replace(query, " ")
            query = bracketed.replace(query, "")
            query = Regex("""\s{2,}""").replace(query, " ")
            query = query.trim()
            return query
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_web_search -> {
                val uri = searchUri.buildUpon().appendQueryParameter("q", searchString).build()
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = uri
                startActivity(intent)
            }
        }

        return super.onOptionsItemSelected(item)
    }
}
