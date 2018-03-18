package eu.zderadicka.audioserve

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
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


private const val LOG_TAG = "Main"

class MainActivity : AppCompatActivity(),
        NavigationView.OnNavigationItemSelectedListener,
        MediaActivity {

    private val folderFragment: FolderFragment?
    get() = supportFragmentManager.findFragmentById(R.id.folderContainer) as FolderFragment

    private lateinit var mBrowser: MediaBrowserCompat
    private lateinit var controllerFragment: ControllerFragment

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

    override fun onItemClicked(item: MediaBrowserCompat.MediaItem) {

        if (item.isBrowsable) {

            val newFragment = FolderFragment.newInstance(item.mediaId!!, item.description.title?.toString()?:"unknown")
            supportFragmentManager.beginTransaction().replace(R.id.folderContainer, newFragment)
                    .addToBackStack(item.mediaId)
                    .commit()

        } else if (item.isPlayable) {
            Log.d(LOG_TAG, "Requesting play of ${item.mediaId}")
            val ctl = MediaControllerCompat.getMediaController(this).transportControls
            ctl.playFromMediaId(item.mediaId, null)


        }


    }

    override val mediaBrowser: MediaBrowserCompat
    get() {
        return this.mBrowser
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }

        val toggle = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()
        nav_view.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {

            val newFragment = FolderFragment.newInstance(AudioService.MEDIA_ROOT_TAG, getString(R.string.collections_title))

            supportFragmentManager.beginTransaction().add(R.id.folderContainer, newFragment).commit()
        }

        controllerFragment = supportFragmentManager.findFragmentById(R.id.playerControls) as ControllerFragment

        mBrowser = MediaBrowserCompat(this, ComponentName(this, AudioService::class.java),
                mediaServiceConnectionCallback,null)
        mBrowser.connect()


    }


    private fun onMediaServiceConnected() {
        controllerFragment.onMediaServiceConnected()
        folderFragment?.onMediaServiceConnected()

    }

    override fun onDestroy() {
        super.onDestroy()
        mBrowser.disconnect()

    }


    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
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
        when (item.itemId) {
            R.id.action_reload -> {
                folderFragment?.reload()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_exit -> {
                //TODO -  better exit - stop playing and also stop background service
                finish()
            }
            R.id.nav_slideshow -> {

            }
            R.id.nav_manage -> {

            }
            R.id.nav_share -> {

            }
            R.id.nav_send -> {

            }
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }


}
