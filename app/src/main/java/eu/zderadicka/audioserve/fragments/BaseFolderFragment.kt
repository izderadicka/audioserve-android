package eu.zderadicka.audioserve.fragments

interface BaseFolderFragment {
    fun onMediaServiceConnected()
    val folderId:String
    val folderName:String
    fun scrollToNowPlaying()
    fun reload()
}