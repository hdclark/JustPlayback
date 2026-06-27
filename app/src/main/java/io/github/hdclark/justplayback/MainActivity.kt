package io.github.hdclark.justplayback

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        /** Bottom padding for the file list, in dp, to give breathing room at the end of the list. */
        private const val LIST_BOTTOM_PADDING_DP = 8
        private const val PLAYLIST_DIR = "playlists"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: MusicAdapter

    private var musicService: MusicService? = null
    private var bound = false
    private var pendingFile: MusicFile? = null
    private var pendingAllFiles: List<MusicFile> = emptyList()

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val storageGranted = permissions[storagePermission] ?: (
            ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED
        )
        if (storageGranted) {
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
            files = emptyList(),
            onClick = { file ->
                if (file.isM3u) playM3u(file) else playFile(file)
            },
            onLongClick = { file -> showLongPressMenu(file) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            checkPermissionsAndRefresh()
        }

        // Load from prefs on startup (no MediaStore query)
        loadAndDisplayFromPrefs()

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

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
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
        loadAndDisplayFromPrefs()
        return true
    }

    private fun loadAndDisplayFromPrefs() {
        val excluded = Prefs.loadExcluded(this)
        val audioFiles = Prefs.loadFiles(this).filter { it.uri !in excluded }
        val allFiles = audioFiles + loadLocalPlaylists()
        val sortOrder = Prefs.loadSortOrder(this)
        val sorted = sortFiles(allFiles, sortOrder)
        adapter.updateFiles(sorted)
    }

    private fun loadLocalPlaylists(): List<MusicFile> {
        val playlistDir = getExternalFilesDir(PLAYLIST_DIR) ?: return emptyList()
        if (!playlistDir.exists()) return emptyList()
        return playlistDir.listFiles { f -> f.extension.equals("m3u", ignoreCase = true) }
            ?.map { f ->
                MusicFile(
                    id = f.absolutePath.hashCode().toLong(),
                    name = f.name,
                    uri = "file://${f.absolutePath}",
                    size = f.length(),
                    lastModified = f.lastModified() / 1000L,
                    isM3u = true
                )
            } ?: emptyList()
    }

    private fun sortFiles(files: List<MusicFile>, order: Int): List<MusicFile> = when (order) {
        Prefs.SORT_ALPHA -> files.sortedBy { it.name.lowercase(Locale.ROOT) }
        Prefs.SORT_SIZE -> files.sortedByDescending { it.size }
        else -> files.sortedByDescending { it.lastModified }
    }

    private fun checkPermissionsAndRefresh() {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED) {
            refreshFromMediaStore()
        } else {
            permissionLauncher.launch(arrayOf(storagePermission))
        }
    }

    /**
     * Bug 1 fix: Trigger a media scan on common music directories before querying
     * MediaStore, so files added during the current day are immediately visible.
     * The MediaStore query runs in the scan-completion callback.
     */
    private fun refreshFromMediaStore() {
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS),
        ).map { it.absolutePath }.toTypedArray()

        if (dirs.isEmpty()) {
            queryMediaStoreAndUpdateList()
            return
        }

        val pending = AtomicInteger(dirs.size)
        MediaScannerConnection.scanFile(this, dirs, null) { _, _ ->
            if (pending.decrementAndGet() == 0) {
                runOnUiThread { queryMediaStoreAndUpdateList() }
            }
        }
    }

    private fun queryMediaStoreAndUpdateList() {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = ? AND ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("1", "audio/%")
        val files = mutableListOf<MusicFile>()

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val fileUri = android.content.ContentUris.withAppendedId(uri, id).toString()
                files.add(
                    MusicFile(
                        id = id,
                        name = name,
                        uri = fileUri,
                        size = cursor.getLong(sizeCol),
                        lastModified = cursor.getLong(modCol)
                    )
                )
            }
        }

        val excluded = Prefs.loadExcluded(this)
        val filteredAudio = files.filter { it.uri !in excluded }
        Prefs.saveFiles(this, filteredAudio)

        val allFiles = filteredAudio + loadLocalPlaylists()
        val sortOrder = Prefs.loadSortOrder(this)
        val sorted = sortFiles(allFiles, sortOrder)
        adapter.updateFiles(sorted)
        swipeRefresh.isRefreshing = false
    }

    // -------------------------------------------------------------------------
    // Playback helpers
    // -------------------------------------------------------------------------

    private fun playFile(file: MusicFile) {
        val allFiles = Prefs.loadFiles(this)
        ensureServiceStarted()
        if (bound && musicService != null) {
            musicService?.play(file, allFiles)
        } else {
            pendingFile = file
            pendingAllFiles = allFiles
        }
    }

    /**
     * Feature 4: parse an M3U playlist file and start shuffled playback of its contents.
     */
    private fun playM3u(file: MusicFile) {
        val resolved = resolveM3u(file)
        if (resolved.isEmpty()) {
            Toast.makeText(this, R.string.m3u_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val shuffled = resolved.shuffled(Random())
        ensureServiceStarted()
        if (bound && musicService != null) {
            musicService?.play(shuffled.first(), shuffled)
        } else {
            pendingFile = shuffled.first()
            pendingAllFiles = shuffled
        }
    }

    /**
     * Parse an M3U file stored in the app's playlist directory and return a list of MusicFiles.
     * Lines starting with '#' are directives/comments; other non-empty lines are content URIs.
     */
    private fun resolveM3u(file: MusicFile): List<MusicFile> {
        val result = mutableListOf<MusicFile>()
        try {
            val path = file.uri.removePrefix("file://")
            var pendingTitle: String? = null
            var index = 0
            BufferedReader(InputStreamReader(File(path).inputStream())).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("#EXTINF:") -> {
                            pendingTitle = line.substringAfter(",", "").takeIf { it.isNotEmpty() }
                        }
                        line.startsWith("#") || line.isEmpty() -> Unit
                        else -> {
                            val name = pendingTitle ?: line.substringAfterLast("/")
                            result.add(
                                MusicFile(
                                    id = index.toLong(),
                                    name = name,
                                    uri = line,
                                    size = 0L,
                                    lastModified = 0L
                                )
                            )
                            pendingTitle = null
                            index++
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_playlist, Toast.LENGTH_SHORT).show()
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Long-press menu — Feature 5
    // -------------------------------------------------------------------------

    private fun showLongPressMenu(file: MusicFile) {
        if (file.isM3u) return
        val items = arrayOf(
            getString(R.string.action_remove_from_list),
            getString(R.string.action_add_to_playlist)
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> removeFromList(file)
                    1 -> addToPlaylist(file)
                }
            }
            .show()
    }

    /**
     * Adds the file's URI to a persistent exclusion set so it is filtered out on every
     * subsequent load or refresh, effectively hiding it from the list permanently.
     */
    private fun removeFromList(file: MusicFile) {
        val excluded = Prefs.loadExcluded(this).toMutableSet()
        excluded.add(file.uri)
        Prefs.saveExcluded(this, excluded)
        loadAndDisplayFromPrefs()
        Toast.makeText(this, R.string.removed_from_list, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows a dialog listing existing playlists plus a "New playlist…" option.
     * The chosen or newly created .m3u file gets an entry appended for this audio file.
     */
    private fun addToPlaylist(file: MusicFile) {
        val playlistDir = getExternalFilesDir(PLAYLIST_DIR) ?: return
        playlistDir.mkdirs()
        val existing = playlistDir.listFiles { f -> f.extension.equals("m3u", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()

        val options = mutableListOf(getString(R.string.new_playlist))
        existing.forEach { options.add(it.nameWithoutExtension) }

        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_to_playlist)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    promptNewPlaylistName(file, playlistDir)
                } else {
                    appendToPlaylist(file, existing[which - 1])
                }
            }
            .show()
    }

    private fun promptNewPlaylistName(file: MusicFile, dir: File) {
        val input = EditText(this).apply { hint = getString(R.string.playlist_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_playlist)
            .setView(input)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    appendToPlaylist(file, File(dir, "$name.m3u"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appendToPlaylist(file: MusicFile, playlist: File) {
        try {
            val entry = "#EXTINF:-1,${file.name}\n${file.uri}\n"
            if (!playlist.exists()) {
                playlist.writeText("#EXTM3U\n$entry")
            } else {
                playlist.appendText(entry)
            }
            loadAndDisplayFromPrefs()
            Toast.makeText(
                this,
                getString(R.string.added_to_playlist, playlist.nameWithoutExtension),
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_playlist, Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------

    private fun ensureServiceStarted() {
        val intent = Intent(this, MusicService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
}
