package eu.zderadicka.audioserve

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import eu.zderadicka.audioserve.data.pathFromFolderId
import eu.zderadicka.audioserve.fragments.ARG_FOLDER_DETAILS
import eu.zderadicka.audioserve.fragments.ARG_FOLDER_NAME
import eu.zderadicka.audioserve.fragments.ARG_FOLDER_PATH
import eu.zderadicka.audioserve.fragments.DetailsFragment

class DetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val folderName = intent.getStringExtra(ARG_FOLDER_NAME)?:""
        val folderId = intent.getStringExtra(ARG_FOLDER_PATH)!!
        val folderPath = pathFromFolderId(folderId)
        val details = intent.getBundleExtra(ARG_FOLDER_DETAILS)
        val t = supportFragmentManager.beginTransaction()
        t.add(R.id.detailsFragment, DetailsFragment.newInstance(folderPath,folderName,details))
        t.commit()


    }
}
