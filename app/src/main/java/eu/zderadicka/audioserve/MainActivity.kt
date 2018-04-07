package eu.zderadicka.audioserve

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.Snackbar
import android.support.design.widget.NavigationView
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.app_bar_main.*
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import eu.zderadicka.audioserve.data.METADATA_KEY_IS_BOOKMARK
import eu.zderadicka.audioserve.data.METADATA_KEY_LAST_POSITION
import eu.zderadicka.audioserve.data.METADATA_KEY_MEDIA_ID
import eu.zderadicka.audioserve.data.folderIdFromFileId
import eu.zderadicka.audioserve.fragments.*
import eu.zderadicka.audioserve.utils.ifStoppedOrDead
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.android.synthetic.main.nav_header_main.*
import java.io.File


private const val LOG_TAG = "Main"
const val ACTION_NAVIGATE_TO_ITEM = "eu.zderadicka.audioserve.navigate_to_item"

class MainActivity : AppCompatActivity(),
        NavigationView.OnNavigationItemSelectedListener,
        MediaActivity,
        TopActivity,
        ControllerHolder {

    private val folderFragment: FolderFragment?
        get() = supportFragmentManager.findFragmentById(R.id.folderContainer) as FolderFragment?

    private lateinit var mBrowser: MediaBrowserCompat
    private lateinit var controllerFragment: ControllerFragment
    private var pendingMediaItem: MediaBrowserCompat.MediaItem? = null

    private val mediaServiceConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            super.onConnected()
            val token = mBrowser.getSessionToken()
            val mediaController = MediaControllerCompat(this@MainActivity, // Context
                    token)
            MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
            this@MainActivity.onMediaServiceConnected()
        }

        override fun onConnectionFailed() {
            super.onConnectionFailed()
            Log.e(LOG_TAG, "Failed to connect to media service")
        }

        override fun onConnectionSuspended() {
            super.onConnectionSuspended()
            Log.w(LOG_TAG, "Connection to media service suspended")
        }

    }

    private val mCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            if (state == null) return
            super.onPlaybackStateChanged(state)
            ifStoppedOrDead(state,
                    {
                        playerControlsContainer.visibility = View.GONE
                    },
                    {
                        playerControlsContainer.visibility = View.VISIBLE
                    })

        }
    }

    override fun onItemClicked(item: MediaBrowserCompat.MediaItem) {

        fun newFolderFragment(id:String, name: String)  {
            val newFragment = FolderFragment.newInstance(id, name)
            supportFragmentManager.beginTransaction().replace(R.id.folderContainer, newFragment)
                    .addToBackStack(item.mediaId)
                    .commit()
        }

        if (item.isBrowsable) {
            stopPlayback()
            newFolderFragment(item.mediaId!!, item.description.title?.toString()
                    ?: "unknown")

        } else if (item.isPlayable) {
            if (item.description.extras?.getBoolean(METADATA_KEY_IS_BOOKMARK) == true) {
                Log.d(LOG_TAG, "Processing bookmark ${item.mediaId}")
                val folderId = folderIdFromFileId(item.mediaId.toString())
                val folderName = File(folderId).name
                newFolderFragment(folderId, folderName)
                pendingMediaItem = item



            } else {
                Log.d(LOG_TAG, "Requesting play of ${item.mediaId}")
                val ctl = MediaControllerCompat.getMediaController(this).transportControls
                ctl.playFromMediaId(item.mediaId, null)
            }

        }


    }

    override fun onFolderLoaded(folderId: String, error: Boolean) {
        val item = pendingMediaItem
        pendingMediaItem = null

        if (!error && item!= null) {
            val ctl = MediaControllerCompat.getMediaController(this).transportControls

            val extras = Bundle()
            val startAt: Long = item.description.extras?.getLong(METADATA_KEY_LAST_POSITION)?:0
            if (startAt>0) {
                ctl.seekTo(startAt)
                extras.putLong(METADATA_KEY_LAST_POSITION, startAt)
            }
            ctl.prepareFromMediaId(item.mediaId, extras)
        }
    }

    private fun stopPlayback() {
        mediaController?.transportControls?.stop()

    }

    override val mediaBrowser: MediaBrowserCompat
        get() {
            return this.mBrowser
        }

    private lateinit var drawerToggle:ActionBarDrawerToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }


        drawerToggle  = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(drawerToggle)
        drawerToggle.setHomeAsUpIndicator(R.drawable.ic_arrow_back_white)
        drawerToggle.syncState()
        drawerToggle.setToolbarNavigationClickListener {
            onBackPressed()
        }


        nav_view.setNavigationItemSelectedListener(this)

        // All this crap just to enable clicking on link in navigation header
        val navHeader = nav_view.inflateHeaderView(R.layout.nav_header_main)
        navHeader.findViewById<TextView>(R.id.homeLinkView).movementMethod = LinkMovementMethod()


        if (savedInstanceState == null) {
            if (intent != null && intent.action == ACTION_NAVIGATE_TO_ITEM) {
                val itemId = intent.getStringExtra(METADATA_KEY_MEDIA_ID)
                val folderId = folderIdFromFileId(itemId)
                val name = File(folderId).name
                openInitalFolder(folderId,name)

            } else {
                openInitalFolder(AudioService.MEDIA_ROOT_TAG, getString(R.string.collections_title))
            }
        }

        controllerFragment = supportFragmentManager.findFragmentById(R.id.playerControls) as ControllerFragment

        mBrowser = MediaBrowserCompat(this, ComponentName(this, AudioService::class.java),
                mediaServiceConnectionCallback, null)
        mBrowser.connect()

    }


    private fun showUpNavigation() {
        drawerToggle.isDrawerIndicatorEnabled=supportFragmentManager.getBackStackEntryCount() < 1


    }


    private fun onMediaServiceConnected() {
        controllerFragment.onMediaServiceConnected()
        folderFragment?.onMediaServiceConnected()
        registerMediaCallback()

    }


    private val mediaController: MediaControllerCompat?
        get() {
            return MediaControllerCompat.getMediaController(this)
        }

    private fun registerMediaCallback() {
        mediaController?.registerCallback(mCallback)
        //update with current state
        mCallback.onPlaybackStateChanged(mediaController?.playbackState)
    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(mCallback)
    }

    override fun onStart() {
        super.onStart()
        registerMediaCallback()

    }

    override fun onDestroy() {
        super.onDestroy()
        mBrowser.disconnect()

    }

    private var backDoublePressed = false
    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else if (supportFragmentManager.backStackEntryCount == 0) {
            if (backDoublePressed) {
                super.onBackPressed()
            } else {
                backDoublePressed = true
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                Handler().postDelayed({backDoublePressed = false}, 2000)
            }

        } else {
            stopPlayback()
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        Log.d(LOG_TAG, "Clicked menu item id ${item.itemId} which is resource ${getResources().getResourceName(item.itemId)}")
        when (item.itemId) {
            R.id.action_reload -> {
                folderFragment?.reload()
                return true
            }


            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun openInitalFolder(folderId: String, folderName: String) {
        //
        for (i in 0..supportFragmentManager.backStackEntryCount) {
            supportFragmentManager.popBackStack()
        }

        val transaction = supportFragmentManager.beginTransaction()

        if (folderFragment == null) {
                    transaction.add(R.id.folderContainer, FolderFragment.newInstance(folderId, folderName), folderId)

        } else {
           transaction.replace(R.id.folderContainer, FolderFragment.newInstance(folderId, folderName), folderId)
        }

        transaction.commit()

    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_exit -> {
                mediaController?.transportControls?.stop()
                finish()
            }
            R.id.nav_browse -> {
                stopPlayback()
                openInitalFolder(AudioService.MEDIA_ROOT_TAG, getString(R.string.collections_title))

            }
            R.id.nav_recent -> {
                stopPlayback()
                openInitalFolder(AudioService.RECENTLY_LISTENED_TAG, getString(R.string.recently_listened))
            }
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun setFolderTitle(title: String) {
        this.title = title
        showUpNavigation()
    }


    override fun onControllerClick() {
        folderFragment?.scrollToNowPlaying()
    }
}
