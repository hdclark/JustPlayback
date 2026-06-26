package io.github.hdclark.justplayback

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        /** Bottom padding for the file list, in dp, to give breathing room at the end of the list. */
        private const val LIST_BOTTOM_PADDING_DP = 8
        private const val ACTION_PLAY_RANDOM = "io.github.hdclark.justplayback.PLAY_RANDOM"
        private const val MENU_REMOVE_FROM_LIST = 10_001
        private const val MENU_ADD_TO_PLAYLIST = 10_002
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: MusicAdapter

    private var musicService: MusicService? = null
    private var bound = false
    private var pendingFile: MusicFile? = null
    private var pendingAllFiles: List<MusicFile> = emptyList()
    private var defaultFiles: List<MusicFile> = emptyList()
    private var activePlaylist: MusicFile? = null
    private var activePlaylistFiles: List<MusicFile> = emptyList()
    private var searchQuery = ""
    private var shouldPlayRandomOnConnect = false
    private var pendingPlaylistWrite: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            bound = true
            // Play any file that was tapped before the service was ready
            pendingFile?.let { file ->
                musicService?.play(file, pendingAllFiles)
                pendingFile = null
                pendingAllFiles = emptyList()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            bound = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Result is informational; service will handle gracefully without it */ }

    private val playlistWritePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingPlaylistWrite
        pendingPlaylistWrite = null
        if (result.resultCode == RESULT_OK && action != null) {
            writePlaylistWithPermissionRetry(action)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val writePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val storageGranted = permissions[storagePermission] ?: (
            ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED
        )
        val writeGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || (
            permissions[writePermission] ?: (
                ContextCompat.checkSelfPermission(this, writePermission) == PackageManager.PERMISSION_GRANTED
            )
        )
        if (storageGranted && writeGranted) {
            refreshFromMediaStore()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable edge-to-edge so content draws behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setSupportActionBar(findViewById(R.id.toolbar))

        recyclerView = findViewById(R.id.recycler_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        // Apply status-bar inset to the AppBarLayout
        val appBarLayout = findViewById<AppBarLayout>(R.id.app_bar_layout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        // Apply navigation-bar inset to the RecyclerView bottom padding
        val initialBottomPad = (LIST_BOTTOM_PADDING_DP * resources.displayMetrics.density + 0.5f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight,
                initialBottomPad + navBars.bottom)
            insets
        }

        adapter = MusicAdapter(
            emptyList(),
            onClick = { file -> onFileClicked(file) },
            onLongClick = { view, file -> onFileLongPressed(view, file) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            checkPermissionsAndRefresh()
        }

        // Load from prefs on startup (no MediaStore query)
        loadAndDisplayFromPrefs()
        maybeHandleLaunchIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activePlaylist != null) {
                    activePlaylist = null
                    activePlaylistFiles = emptyList()
                    supportActionBar?.title = getString(R.string.app_name)
                    applyDisplayState()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Ask for notification permission early so the foreground service can show controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleLaunchIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                applyDisplayState()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val sortOrder = when (item.itemId) {
            R.id.sort_by_time -> Prefs.SORT_TIME
            R.id.sort_by_name -> Prefs.SORT_ALPHA
            R.id.sort_by_size -> Prefs.SORT_SIZE
            else -> return super.onOptionsItemSelected(item)
        }
        Prefs.saveSortOrder(this, sortOrder)
        applyDisplayState()
        return true
    }

    private fun loadAndDisplayFromPrefs() {
        defaultFiles = Prefs.loadFiles(this)
        activePlaylist = null
        activePlaylistFiles = emptyList()
        supportActionBar?.title = getString(R.string.app_name)
        applyDisplayState()
    }

    private fun sortFiles(files: List<MusicFile>, order: Int): List<MusicFile> {
        val comparator = when (order) {
            Prefs.SORT_ALPHA -> compareBy<MusicFile> { it.name.lowercase(Locale.ROOT) }
            Prefs.SORT_SIZE -> compareByDescending<MusicFile> { it.size }
            else -> compareByDescending<MusicFile> { it.lastModified }
        }
        return files.sortedWith(compareByDescending<MusicFile> { it.isPlaylist }.then(comparator))
    }

    private fun checkPermissionsAndRefresh() {
        val permissions = buildStoragePermissions()
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            refreshFromMediaStore()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun refreshFromMediaStore() {
        val files = PlaylistStorage.scanLibrary(this)
        Prefs.saveFiles(this, files)
        defaultFiles = files
        val currentPlaylist = activePlaylist
        if (currentPlaylist != null) {
            activePlaylist = defaultFiles.firstOrNull { it.uri == currentPlaylist.uri }
            activePlaylistFiles = activePlaylist?.let { PlaylistStorage.loadPlaylistEntries(this, it, defaultFiles) }.orEmpty()
            supportActionBar?.title = activePlaylist?.name ?: getString(R.string.app_name)
        } else {
            supportActionBar?.title = getString(R.string.app_name)
        }
        applyDisplayState()
        swipeRefresh.isRefreshing = false
        if (shouldPlayRandomOnConnect) {
            playRandomTrack()
        }
    }

    private fun ensureServiceStarted() {
        val intent = Intent(this, MusicService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun onFileClicked(file: MusicFile) {
        if (file.isPlaylist) {
            Toast.makeText(this, R.string.playlist_long_press_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val allFiles = currentPlayableFiles()
        if (allFiles.none { it.uri == file.uri }) {
            return
        }
        ensureServiceStarted()
        if (bound && musicService != null) {
            musicService?.play(file, allFiles)
        } else {
            pendingFile = file
            pendingAllFiles = allFiles
        }
    }

    private fun onFileLongPressed(anchor: View, file: MusicFile) {
        if (file.isPlaylist) {
            openPlaylist(file)
            return
        }

        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add(0, MENU_REMOVE_FROM_LIST, 0, R.string.remove_from_list)
        popup.menu.add(0, MENU_ADD_TO_PLAYLIST, 1, R.string.add_to_playlist)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REMOVE_FROM_LIST -> {
                    removeFileFromCurrentList(file)
                    true
                }
                MENU_ADD_TO_PLAYLIST -> {
                    showAddToPlaylistDialog(file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun removeFileFromCurrentList(file: MusicFile) {
        val playlist = activePlaylist
        if (playlist != null) {
            val updated = activePlaylistFiles.filterNot { it.uri == file.uri }
            if (PlaylistStorage.savePlaylist(this, playlist.name, updated) != null) {
                refreshFromMediaStore()
            }
        } else {
            defaultFiles = defaultFiles.filterNot { it.uri == file.uri }
            Prefs.saveFiles(this, defaultFiles)
            applyDisplayState()
        }
    }

    private fun showAddToPlaylistDialog(file: MusicFile) {
        val playlistFiles = defaultFiles.filter { it.isPlaylist }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val labels = buildList {
            add(getString(R.string.create_playlist))
            addAll(playlistFiles.map { it.name })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog(file)
                } else {
                    addFileToPlaylist(file, playlistFiles[which - 1])
                }
            }
            .show()
    }

    private fun showCreatePlaylistDialog(file: MusicFile) {
        val input = EditText(this).apply {
            hint = getString(R.string.new_playlist_name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.create_playlist)
            .setView(input)
            .setPositiveButton(R.string.save_playlist) { _, _ ->
                val playlistName = input.text.toString()
                val existing = defaultFiles.firstOrNull {
                    it.isPlaylist && it.name.equals(ensurePlaylistName(playlistName), ignoreCase = true)
                }
                if (existing != null) {
                    addFileToPlaylist(file, existing)
                } else {
                    writePlaylistWithPermissionRetry {
                        val created = PlaylistStorage.savePlaylist(this, playlistName, listOf(file))
                        if (created != null) {
                            refreshFromMediaStore()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addFileToPlaylist(file: MusicFile, playlist: MusicFile) {
        writePlaylistWithPermissionRetry {
            if (PlaylistStorage.addFileToPlaylist(this, playlist, file) != null) {
                refreshFromMediaStore()
            }
        }
    }

    private fun writePlaylistWithPermissionRetry(action: () -> Unit) {
        try {
            action()
        } catch (exception: PlaylistStorage.PlaylistWritePermissionException) {
            pendingPlaylistWrite = action
            playlistWritePermissionLauncher.launch(
                IntentSenderRequest.Builder(exception.intentSender).build()
            )
        } catch (exception: SecurityException) {
            Toast.makeText(this, R.string.playlist_write_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensurePlaylistName(name: String): String {
        val trimmed = name.trim().ifEmpty { "Playlist" }
        return if (trimmed.lowercase(Locale.ROOT).endsWith(".m3u")) trimmed else "$trimmed.m3u"
    }

    private fun openPlaylist(playlist: MusicFile) {
        activePlaylist = playlist
        activePlaylistFiles = PlaylistStorage.loadPlaylistEntries(this, playlist, defaultFiles)
        supportActionBar?.title = playlist.name
        applyDisplayState()
    }

    private fun currentBaseFiles(): List<MusicFile> = activePlaylistFiles.takeIf { activePlaylist != null } ?: defaultFiles

    private fun currentPlayableFiles(): List<MusicFile> = currentBaseFiles().filterNot { it.isPlaylist }

    private fun applyDisplayState() {
        val filtered = filterFiles(currentBaseFiles(), searchQuery)
        adapter.updateFiles(sortFiles(filtered, Prefs.loadSortOrder(this)))
    }

    private fun filterFiles(files: List<MusicFile>, query: String): List<MusicFile> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) {
            return files
        }
        return files.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
    }

    private fun maybeHandleLaunchIntent(intent: Intent?) {
        if (intent?.action != ACTION_PLAY_RANDOM) {
            return
        }
        if (defaultFiles.isEmpty()) {
            shouldPlayRandomOnConnect = true
            checkPermissionsAndRefresh()
            return
        }
        playRandomTrack()
    }

    private fun playRandomTrack() {
        val playableFiles = defaultFiles.filterNot { it.isPlaylist }
        if (playableFiles.isEmpty()) {
            shouldPlayRandomOnConnect = false
            return
        }
        shouldPlayRandomOnConnect = false
        onFileClicked(playableFiles[Random.nextInt(playableFiles.size)])
    }

    private fun buildStoragePermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return permissions
    }
}
