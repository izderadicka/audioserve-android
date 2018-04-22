package eu.zderadicka.audioserve.fragments


import android.os.Bundle
import android.support.v4.app.Fragment
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.net.ApiClient


const val ARG_FOLDER_DETAILS = "folder_details"


class DetailsFragment : Fragment() {
    private lateinit var folderDetails: Bundle
    private lateinit var folderPath: String
    private lateinit var folderName: String

    lateinit var folderNameView: TextView
    lateinit var folderPathView: TextView
    lateinit var totalDurationView: TextView
    lateinit var numFilesView: TextView
    lateinit var numFoldersView: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            folderDetails = it.getBundle(ARG_FOLDER_DETAILS)
            folderPath = it.getString(ARG_FOLDER_PATH)
            folderName = it.getString(ARG_FOLDER_NAME)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_details, container, false)
        folderNameView =view.findViewById(R.id.folderName)
        folderPathView = view.findViewById(R.id.folderPath)
        folderNameView.text = folderName
        folderPathView.text = folderPath

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
        fun newInstance(folderPath:String, folderName:String, folderDetails: Bundle) =
                DetailsFragment().apply {
                    arguments = Bundle().apply {
                        putBundle(ARG_FOLDER_DETAILS, folderDetails)
                        putString(ARG_FOLDER_PATH, folderPath)
                        putString(ARG_FOLDER_NAME, folderName)
                    }
                }
    }
}
