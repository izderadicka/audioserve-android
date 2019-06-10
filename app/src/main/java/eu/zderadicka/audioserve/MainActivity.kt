package eu.zderadicka.audioserve

import android.annotation.SuppressLint
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
import eu.zderadicka.audioserve.fragments.*
import kotlinx.android.synthetic.main.content_main.*
import java.io.File
import android.app.SearchManager
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.preference.PreferenceManager
import android.support.v7.widget.SearchView
import android.support.v7.widget.SwitchCompat
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.DOWNLOAD_ACTION
import eu.zderadicka.audioserve.net.DownloadService
import eu.zderadicka.audioserve.ui.ExpandableFrameLayout
import eu.zderadicka.audioserve.utils.*
import kotlinx.android.synthetic.main.sleep_overlay.*


private const val LOG_TAG = "Main"
const val ACTION_NAVIGATE_TO_ITEM = "eu.zderadicka.audioserve.navigate_to_item"
const val ACTION_NAVIGATE_TO_FOLDER = "eu.zderadicka.audioserve.navigate_to_folder"
private const val ROOT_RECENT = 0
private const val ROOT_BROWSE = 1
private const val ROOT_BOOKMARKS = 2


class MainActivity : AppCompatActivity(),
        NavigationView.OnNavigationItemSelectedListener,
        MediaActivity,
        TopActivity,
        ControllerHolder {

    private val folderFragment: BaseFolderFragment?
        get() = supportFragmentManager.findFragmentById(R.id.folderContainer) as BaseFolderFragment?

    private lateinit var mBrowser: MediaBrowserCompat
    private lateinit var controllerFragment: ControllerFragment
    private var pendingMediaItem: MediaBrowserCompat.MediaItem? = null
    private var searchPrefix: String? = null
    private var collection: Int? = null
    private var folderDetails: Bundle? = null
    private var folderId: String? = null

    private val mediaServiceConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            super.onConnected()
            val token = mBrowser.sessionToken
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


    private var playerControlsVisible = false
    set(value) {
        if (field!= value) {
            field = value
            invalidateOptionsMenu()
        }
    }
    private val mCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            super.onPlaybackStateChanged(state)
            ifStoppedOrDead(state,
                    {
                        playerControlsContainer.visibility = View.GONE
                        playerControlsVisible = false
                    },
                    {
                        playerControlsContainer.visibility = View.VISIBLE
                        playerControlsVisible = true
                        //playerControlsContainer.layoutParams.height = resources.getDimension(R.dimen.control_view_height_max).toInt();
                    })

        }
    }

    private fun newFolderFragment(id: String, name: String, preparePlay: Boolean = false) {
        val newFragment = FolderFragment.newInstance(id, name, preparePlay)
        supportFragmentManager.beginTransaction().replace(R.id.folderContainer, newFragment)
                .addToBackStack(id)
                .commit()
    }

    override fun onItemClicked(item: MediaBrowserCompat.MediaItem, action: ItemAction, currentlyPlaying:Boolean) {
        val ctl = MediaControllerCompat.getMediaController(this).transportControls
        when (action) {
            ItemAction.Download -> {
                if (isOffline) {
                    Toast.makeText(this,getString(R.string.no_downloads_offline), Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(this,getString(R.string.download_confirmation), Toast.LENGTH_LONG).show()
                val bundle = Bundle()
                bundle.putString(METADATA_KEY_MEDIA_ID, item.mediaId)
                bundle.putBoolean(METADATA_KEY_IS_FOLDER, item.isBrowsable)
                val downloadIntent = Intent(this,DownloadService::class.java)
                downloadIntent.action = DOWNLOAD_ACTION
                downloadIntent.putExtra(METADATA_KEY_MEDIA_ID, item.mediaId)
                downloadIntent.putExtra(METADATA_KEY_IS_FOLDER, item.isBrowsable)
                startService(downloadIntent)

            }
            ItemAction.Open -> {

                if (item.isBrowsable) {
                    stopPlayback()
                    newFolderFragment(item.mediaId!!, item.description.title?.toString()
                            ?: "unknown")

                } else if (item.isPlayable) {
                    if (item.description.extras?.getBoolean(METADATA_KEY_IS_BOOKMARK) == true) {
                        Log.d(LOG_TAG, "Processing bookmark ${item.mediaId}")
                        val folderId = folderIdFromFileId(item.mediaId.toString())
                        val folderName = File(folderId).name
                        newFolderFragment(folderId, folderName, preparePlay=true)
                        pendingMediaItem = item


                    } else {
                        Log.d(LOG_TAG, "Requesting play of ${item.mediaId}")
                        ctl.playFromMediaId(item.mediaId, null)
                    }
                }
            }

            ItemAction.Bookmark -> {
                val task = BookMarkInsertTask(this)
                if (item.description.extras?.getBoolean(METADATA_KEY_IS_BOOKMARK, false) ==false) {
                    if (item.isPlayable && currentlyPlaying) {
                        item.description.extras?.putLong(METADATA_KEY_LAST_POSITION, controllerFragment.currentPlayTime)
                    } else {
                        item.description.extras?.remove(METADATA_KEY_LAST_POSITION)
                    }
                }
                task.execute(item)
            }
        }
    }

    override fun onFolderLoaded(folderId: String, folderDetails: Bundle?, error: Boolean, empty: Boolean) {
        val item = pendingMediaItem
        pendingMediaItem = null

        if (!error && item != null) {
            val extras = Bundle()
            val startAt: Long = item.description.extras?.getLong(METADATA_KEY_LAST_POSITION) ?: 0
            if (startAt > 0) {
                //ctl.seekTo(startAt)
                extras.putLong(METADATA_KEY_LAST_POSITION, startAt)
            }
            extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP,
                    item.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP)?: 0)
            if (item.description.extras?.getBoolean(METADATA_PLAY_AFTER_SEEK) == true) {
                extras.putBoolean(METADATA_PLAY_AFTER_SEEK, true)
            }
            mediaController?.transportControls?.prepareFromMediaId(item.mediaId, extras)
        }

        collection = collectionFromFolderId(folderId)?: collectionFromSearchId(folderId)?: collectionFromModifiedId(folderId)
        searchPrefix = if (collection == null || isOffline) null else "${AudioService.SEARCH_PREFIX}${collection}_"
        Log.d(LOG_TAG, "Loaded folder $folderId in collection $collection")
        this.folderId = folderId
        this.folderDetails = folderDetails
        invalidateOptionsMenu()
        updateDrawerMenu()
    }

    private fun updateDrawerMenu() {
        val menu = nav_view.menu
        menu.findItem(R.id.nav_modified).isVisible = collection != null
    }

    private fun stopPlayback() {
        mediaController?.transportControls?.stop()

    }

    override val mediaBrowser: MediaBrowserCompat
        get() {
            return this.mBrowser
        }

    private lateinit var drawerToggle: ActionBarDrawerToggle

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        Log.d(LOG_TAG, "Created main activity with action ${intent.action}")

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }


        drawerToggle = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(drawerToggle)
        drawerToggle.setHomeAsUpIndicator(R.drawable.ic_arrow_back_white)
        drawerToggle.syncState()
        drawerToggle.setToolbarNavigationClickListener {
            when (rootFolder) {
                ROOT_RECENT -> openRecentFolder()
                ROOT_BROWSE -> openRootFolder()
                ROOT_BOOKMARKS -> openBookmarksFolder()
                else -> openRecentFolder()
            }
        }


        nav_view.setNavigationItemSelectedListener(this)

        // All this crap just to enable clicking on link in navigation header
        val navHeader = nav_view.inflateHeaderView(R.layout.nav_header_main)
        navHeader.findViewById<TextView>(R.id.homeLinkView).movementMethod = LinkMovementMethod()

        try {
            val pInfo = this.packageManager.getPackageInfo(packageName, 0)
            val version = pInfo?.versionName
            val nameView = navHeader.findViewById<TextView>(R.id.appNameView)
            nameView.text =  "audioserve v. $version"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(LOG_TAG, "Cannot get version name")
        }


        if (savedInstanceState == null) {
            if (intent?.action == ACTION_NAVIGATE_TO_ITEM) {
                val itemId = intent.getStringExtra(METADATA_KEY_MEDIA_ID)
                val folderId = folderIdFromFileId(itemId)
                val name = File(folderId).name
                openInitialFolder(folderId, name)
                rootFolder = ROOT_RECENT

            } else {
                openRecentFolder()
            }
        }

        controllerFragment = supportFragmentManager.findFragmentById(R.id.playerControls) as ControllerFragment

        mBrowser = MediaBrowserCompat(this, ComponentName(this, AudioService::class.java),
                mediaServiceConnectionCallback, null)
        mBrowser.connect()

        createOfflineSwitch()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val haveServer = !prefs.getString("pref_server_url", null).isNullOrBlank()
        val haveSecret = !prefs.getString(("pref_shared_secret"), null).isNullOrBlank()

        //mask clicks on the sleep overlay
        sleepOverlayView.setOnClickListener {
        }

        cancelSleepBtn.setOnClickListener {
            cancelSleepTimer(this)
        }

        extendSleepBtn.setOnClickListener{
            extendSleepTimer(this)
        }



        if (!haveSecret || !haveServer) startActivity(Intent(this, SettingsActivity::class.java))

    }

    private val isOffline: Boolean
    get() = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_offline", false)

    private fun createOfflineSwitch() {
        // test switch
        val actionView = nav_view.menu.findItem(R.id.offline_switch).actionView
        val switcher =  actionView.findViewById(R.id.switcher) as SwitchCompat
        switcher.isChecked = isOffline
        switcher.setOnClickListener{
            val makeOffline = switcher.isChecked
            val editor = PreferenceManager.getDefaultSharedPreferences(this@MainActivity).edit()
            editor.putBoolean("pref_offline", makeOffline)
            editor.apply()
            Toast.makeText(this@MainActivity, if (makeOffline) "Going Offline" else "Going Online",
                    Toast.LENGTH_SHORT).show()

            openRootFolder()

        }

    }

    private var rootFolder: Int = ROOT_RECENT

    private fun openRootFolder() {
        stopPlayback()
        openInitialFolder(AudioService.MEDIA_ROOT_TAG,
                if (isOffline) getString(R.string.collection_offline)
                    else getString(R.string.collections_title))
        rootFolder = ROOT_BROWSE
    }

    private fun openRecentFolder() {
        stopPlayback()
        openInitialFolder(AudioService.RECENTLY_LISTENED_TAG, getString(R.string.recently_listened))
        rootFolder = ROOT_RECENT
    }

    private fun openBookmarksFolder() {
        stopPlayback()
        for (i in 0..supportFragmentManager.backStackEntryCount) {
            supportFragmentManager.popBackStack()
        }

        val transaction = supportFragmentManager.beginTransaction()
        val fragment = BookmarksFragment()
        if (folderFragment != null) {
            transaction.replace(R.id.folderContainer, fragment, fragment.folderId)
        } else {
            transaction.add(R.id.folderContainer, fragment, fragment.folderId)
        }

        transaction.commit()
        rootFolder = ROOT_BOOKMARKS
    }


    private fun showUpNavigation() {
        drawerToggle.isDrawerIndicatorEnabled = supportFragmentManager.backStackEntryCount < 1


    }


    private fun onMediaServiceConnected() {
        controllerFragment.onMediaServiceConnected()
        folderFragment?.onMediaServiceConnected()
        registerMediaCallback()

        // if we are connected when playing or paused move to the right folder
        val state = mediaController?.playbackState?.state
        val mediaId = mediaController?.metadata?.description?.mediaId
        if ((state == PlaybackStateCompat.STATE_PAUSED || state == PlaybackStateCompat.STATE_PLAYING)
                && mediaId != null) {
            Log.d(LOG_TAG, "Play has already item $mediaId move to its folder")
            val folderId = folderIdFromFileId(mediaId)
            if (folderId != folderFragment?.folderId) {
                val name = File(folderId).name
                openInitialFolder(folderId, name)
            }
        }
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

    private var timerOn = false
    set(value) {
        if (field!= value) {
            invalidateOptionsMenu()
            field = value
        }
    }
    private fun onTimerChange(isRunning:Boolean, remainsMins: Int) {

        timerOn = isRunning
        val menu = nav_view.menu
        val startTimer = menu.findItem(R.id.nav_sleep)
        val stopTimer = menu.findItem(R.id.nav_cancel_sleep)
        startTimer.isVisible = !isRunning
        stopTimer.isVisible = isRunning
        sleepOverlayView.visibility = if (isRunning) View.VISIBLE else View.GONE
        setTimerRemaingTime(remainsMins)


    }

    @SuppressLint("SetTextI18n")
    private fun setTimerRemaingTime(remainsMins: Int) {
        val hours = (remainsMins / 60).toString().padStart(2,'0')
        val mins = (remainsMins % 60).toString().padStart(2,'0')
        sleepCounterView.text = "$hours:$mins"
    }

    private val timerServiceConnection = object: ServiceConnection {
        private var svc: SleepService? = null
        override fun onServiceDisconnected(name: ComponentName?) {
            svc?.statusListener = null
            svc = null
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val svc = (service as SleepService.LocalBinder).service
            svc.statusListener = this@MainActivity::onTimerChange
            this.svc = svc
            setTimerRemaingTime(svc.remainsMins)
        }

    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(mCallback)
    }

    override fun onStart() {
        super.onStart()
        registerMediaCallback()
        bindService(Intent(this, SleepService::class.java),
                timerServiceConnection,
                Context.BIND_AUTO_CREATE)

    }

    override fun onDestroy() {
        super.onDestroy()
        mBrowser.disconnect()
        unbindService(timerServiceConnection)

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
                Toast.makeText(this, getString(R.string.press_back_toast), Toast.LENGTH_SHORT).show()
                Handler().postDelayed({ backDoublePressed = false }, 2000)
            }

        } else {
            stopPlayback()
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.

        menuInflater.inflate(R.menu.main, menu)
        val timerItem = menu.findItem(R.id.action_sleep)
        timerItem.isVisible = ! timerOn && playerControlsVisible
        val searchItem = menu.findItem(R.id.action_search)
        searchItem.isVisible = searchPrefix != null
        val infoItem = menu.findItem(R.id.action_info)
        infoItem.isVisible = folderDetails != null
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.setSearchableInfo(
                searchManager.getSearchableInfo(componentName))
        val orderingMenu = menu.findItem(R.id.submenu_ordering)
        if (searchPrefix != null) {
            orderingMenu.isVisible = true

            val ordering = PreferenceManager
                    .getDefaultSharedPreferences(this)
                    .getString("pref_ordering", FoldersOrdering.Alphabetical.letter)
            when (ordering) {
                FoldersOrdering.Alphabetical.letter -> {
                    menu.findItem(R.id.action_ordering_alphabetical).isChecked = true
                }
                FoldersOrdering.RecentFirst.letter -> {
                    menu.findItem(R.id.action_ordering_recent).isChecked = true
                }
            }
        } else {
            orderingMenu.isVisible = false
        }

        val checkRecentItem = menu.findItem(R.id.action_check_remote_positions)
        checkRecentItem.isVisible = ApiClient.getInstance(this).isPositionEnabled

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        Log.d(LOG_TAG, "Clicked menu item id ${item.itemId} which is resource ${resources.getResourceName(item.itemId)}")
        return when (item.itemId) {
            R.id.action_reload -> {
                folderFragment?.reload()
                true
            }

            R.id.action_info -> {
                val intent = Intent(this,  DetailsActivity::class.java)
                intent.putExtra(ARG_FOLDER_PATH, folderFragment?.folderId)
                intent.putExtra(ARG_FOLDER_NAME, folderFragment?.folderName)
                intent.putExtra(ARG_FOLDER_DETAILS, folderDetails)
                startActivity(intent)
                true
            }

            R.id.action_sleep -> {
                startSleepTimer(this)
                true
            }

            R.id.action_ordering_alphabetical -> {
                item.isChecked = true
                changeOrdering(FoldersOrdering.Alphabetical)
                true
            }
            R.id.action_ordering_recent -> {
                changeOrdering(FoldersOrdering.RecentFirst)
                item.isChecked = true
                true
            }
            R.id.action_check_remote_positions -> {
                ApiClient.getInstance(this).queryPositionForFolderOrMediaId(
                        folderId?.let{if (isTrueFolder(it)) it else null},
                        if (playerControlsVisible) mediaController?.metadata?.description?.mediaId else null,
                        if (playerControlsVisible) controllerFragment.currentPlayTime else null
                ){
                    items, err ->
                    if (err!= null) {
                        Log.e(LOG_TAG, "Error querying position $err")
                        runOnUiThread {
                            Toast.makeText(this, "Cann't get remote position: $err", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        checkRemotePositions(items, false,null)
                    }
                }


                true
            }


            else -> return super.onOptionsItemSelected(item)
        }
    }

    fun checkRemotePositions(items: ArrayList<MediaBrowserCompat.MediaItem>?,
                             playAfter: Boolean,
                             onContinueWithCurrent: (() -> Unit)?) {

        val d = RemotePositionsDialogFragment()
        d.setListener(object: RemotePositionsDialogFragment.Listener {
            override fun onItemChosen(item: MediaBrowserCompat.MediaItem) {
                if (playAfter) {
                    item.description.extras?.putBoolean(METADATA_PLAY_AFTER_SEEK, true)
                }
                onItemClicked(item, ItemAction.Open, false)
            }

            override fun onContinueWithCurrent() {
                onContinueWithCurrent?.invoke()
            }

        })
        val args = Bundle()
        args.putParcelableArrayList(REMOTE_POSITIONS_LIST, items)
        d.arguments = args
        d.show(supportFragmentManager, "Positions dialog")

    }

    private fun changeOrdering(ord: FoldersOrdering) {

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getString("pref_ordering", FoldersOrdering.Alphabetical.letter)
        if (ord.letter != current) {
            Log.d(LOG_TAG, "Changed ordering to ${ord.letter}")
            prefs.edit().putString("pref_ordering", ord.letter).apply()
            folderFragment?.reload()
        }

    }

    private fun openInitialFolder(folderId: String, folderName: String) {
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
                stopPlayback() //need to stop as some setting cannot be changed with player sitting on the file
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.nav_exit -> {
                mediaController?.transportControls?.stop()
                finish()
                Handler().postDelayed({ System.exit(0)}, 1000)
                // or android.os.Process.killProcess(android.os.Process.myPid());

            }
            R.id.nav_browse -> {
                openRootFolder()

            }
            R.id.nav_recent -> {
                openRecentFolder()
            }
            R.id.nav_bookmarks -> {
                openBookmarksFolder()
            }
            R.id.nav_modified -> {
                collection?.let {
                    val folderId = AudioService.RECENTLY_MODIFIED_PREFIX +it
                    newFolderFragment(folderId, getString(R.string.recently_modified))
                }


            }
            R.id.nav_sleep -> {
                val d = SleepDialogFragment()
                d.show(supportFragmentManager,"Sleep dialog")
            }

            R.id.nav_cancel_sleep -> {
                cancelSleepTimer(this)
            }

            R.id.offline_switch -> return false

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(LOG_TAG, "New intent arrived with action ${intent.action}")

        if (Intent.ACTION_SEARCH == intent.action && searchPrefix != null) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            if (query != null && query.length >= 3) {
                val searchId = searchPrefix + query
                Log.d(LOG_TAG, "Searching for $query")
                newFolderFragment(searchId, query, true)
            } else {
                Toast.makeText(this, getString(R.string.search_warning), Toast.LENGTH_LONG).show()
            }
        } else if (intent.action == ACTION_NAVIGATE_TO_FOLDER) {
            stopPlayback()
            val folderId = intent.getStringExtra(METADATA_KEY_MEDIA_ID)
            if (!folderId.isNullOrBlank()) {
                val name = File(folderId).name?:"uknown"
                newFolderFragment(folderId, name)
            }
        }

    }

    override fun onPause() {
        super.onPause()
        if (playerControlsContainer is ExpandableFrameLayout) playerControlsContainer.onPause()
    }
}
