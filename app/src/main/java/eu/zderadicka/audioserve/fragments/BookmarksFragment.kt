package eu.zderadicka.audioserve.fragments

import android.support.v4.app.Fragment
import eu.zderadicka.audioserve.R

class BookmarksFragment: Fragment(), BaseFolderFragment {
    override fun onMediaServiceConnected() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override val folderId: String = "__BOOKMARKS"
    override val folderName: String = getString(R.string.folder_bookmarks)

    override fun scrollToNowPlaying() {
        //this is no action for bookmarks - at least now
    }

    override fun reload() {
        //TODO
    }
}