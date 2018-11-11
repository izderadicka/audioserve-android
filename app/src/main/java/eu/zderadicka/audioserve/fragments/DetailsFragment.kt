package eu.zderadicka.audioserve.fragments


import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import eu.zderadicka.audioserve.ACTION_NAVIGATE_TO_FOLDER
import eu.zderadicka.audioserve.MainActivity

import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.net.ApiClient
import java.io.File


const val ARG_FOLDER_DETAILS = "folder_details"
const val ARG_FOLDER_FULL_ID = "folder_full_id"
private const val LOG_TAG = "DetailsFragment"


class DetailsFragment : androidx.fragment.app.Fragment() {
    private lateinit var folderDetails: Bundle
    private lateinit var folderPath: String
    private lateinit var folderName: String
    private lateinit var folderId: String

    lateinit var folderNameView: TextView
    lateinit var folderPathView: TextView
    lateinit var totalDurationView: TextView
    lateinit var numFilesView: TextView
    lateinit var numFoldersView: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            folderDetails = it.getBundle(ARG_FOLDER_DETAILS)!!
            folderPath = it.getString(ARG_FOLDER_PATH)!!
            folderName = it.getString(ARG_FOLDER_NAME)!!
            folderId = it.getString(ARG_FOLDER_FULL_ID)!!
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_details, container, false)
        folderNameView =view.findViewById(R.id.folderName)
        folderPathView = view.findViewById(R.id.folderPath)
        folderNameView.text = folderName
        folderPathView.text = folderPath

        folderPathView.setOnClickListener{
            if ( it is TextView && ! it.text.isBlank()) {
                // navigate to folder path
                val parentFolder = File(folderId).parent
                if (!parentFolder.isNullOrBlank()) {
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.action = ACTION_NAVIGATE_TO_FOLDER

                    intent.putExtra(METADATA_KEY_MEDIA_ID, parentFolder)
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    Log.d(LOG_TAG, "Folder id is ${parentFolder}")
                    activity?.finish()
                    startActivity(intent)
                }
            }
        }

        totalDurationView = view.findViewById(R.id.totalTime)
        totalDurationView.text = DateUtils.formatElapsedTime(folderDetails
                .getLong(METADATA_KEY_TOTAL_DURATION,0L))
        numFilesView = view.findViewById(R.id.totalFiles)
        numFilesView.text = folderDetails.getInt(METADATA_KEY_FILES_COUNT).toString()
        numFoldersView = view.findViewById(R.id.totalFolders)
        numFoldersView.text = folderDetails.getInt(METADATA_KEY_FOLDERS_COUNT).toString()


        val client = ApiClient.getInstance(context!!)
        val imagePath =  folderDetails.getString(METADATA_KEY_FOLDER_PICTURE_PATH)
        imagePath?.let {client.loadPicture(it){bitmap, err ->
            val img = view.findViewById<ImageView>(R.id.folderImage)
            img.setImageBitmap(bitmap)
            img.visibility = View.VISIBLE
        }}
        val textPath =  folderDetails.getString(METADATA_KEY_FOLDER_TEXT_URL)
        textPath?.let {client.loadText(it){text, err ->
            val txt = view.findViewById<TextView>(R.id.folderText)
            txt.text = text
        }}

        return view
    }


    companion object {

        @JvmStatic
        fun newInstance(folderId: String, folderPath:String, folderName:String, folderDetails: Bundle) =
                DetailsFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_FOLDER_FULL_ID, folderId)
                        putBundle(ARG_FOLDER_DETAILS, folderDetails)
                        putString(ARG_FOLDER_PATH, folderPath)
                        putString(ARG_FOLDER_NAME, folderName)
                    }
                }
    }
}
