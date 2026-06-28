package io.github.hdclark.justplayback

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
        private const val LEGACY_PLAYLIST_DIR = "playlists"
        private const val ACTION_PLAY_SHUFFLE_ALL = "io.github.hdclark.justplayback.action.PLAY_SHUFFLE_ALL"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: MusicAdapter
    private lateinit var playlistBackCallback: OnBackPressedCallback

    private var musicService: MusicService? = null
    private var bound = false
    private var pendingFile: MusicFile? = null
    private var pendingAllFiles: List<MusicFile> = emptyList()
    private var libraryAudioFiles: List<MusicFile> = emptyList()
    private var libraryFiles: List<MusicFile> = emptyList()
    private var playlistViewFiles: List<MusicFile>? = null
    private var playlistViewTitle: String? = null
    private var displayedFiles: List<MusicFile> = emptyList()
    private var searchQuery = ""
    private var shouldPlayShuffleAll = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            bound = true
            pendingFile?.let { file ->
                musicService?.play(file, pendingAllFiles)
                pendingFile = null
                pendingAllFiles = emptyList()
            }
            maybePlayShuffleAll()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            bound = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storagePermission = storagePermission()
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
        shouldPlayShuffleAll = intent?.action == ACTION_PLAY_SHUFFLE_ALL

        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(findViewById(R.id.toolbar))

        recyclerView = findViewById(R.id.recycler_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        val appBarLayout = findViewById<AppBarLayout>(R.id.app_bar_layout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        val initialBottomPad = (LIST_BOTTOM_PADDING_DP * resources.displayMetrics.density + 0.5f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialBottomPad + navBars.bottom
            )
            insets
        }

        adapter = MusicAdapter(
            files = emptyList(),
            onClick = { file -> if (file.isM3u) playM3u(file) else playFile(file) },
            onLongClick = { file -> showLongPressMenu(file) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener { checkPermissionsAndRefresh() }

        playlistBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closePlaylistView()
            }
        }
        onBackPressedDispatcher.addCallback(this, playlistBackCallback)

        migratePrivatePlaylists()
        loadAndDisplayFromPrefs()

        if (shouldPlayShuffleAll && libraryAudioFiles.isEmpty() && hasStoragePermission()) {
            swipeRefresh.isRefreshing = true
            refreshFromMediaStore()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_PLAY_SHUFFLE_ALL) {
            shouldPlayShuffleAll = true
            maybePlayShuffleAll()
            if (libraryAudioFiles.isEmpty() && hasStoragePermission()) {
                swipeRefresh.isRefreshing = true
                refreshFromMediaStore()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MusicService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
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
        searchView.queryHint = getString(R.string.action_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                renderCurrentList()
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
        renderCurrentList()
        return true
    }

    private fun loadAndDisplayFromPrefs() {
        val excluded = Prefs.loadExcluded(this)
        libraryAudioFiles = Prefs.loadFiles(this).filter { it.uri !in excluded }
        libraryFiles = libraryAudioFiles + loadPublicPlaylists()
        if (playlistViewFiles == null) {
            renderCurrentList()
        }
    }

    private fun loadPublicPlaylists(): List<MusicFile> {
        val musicDir = getPublicPlaylistDirectory() ?: return emptyList()
        return musicDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("m3u", "m3u8") }
            .map { playlistFile(it) }
            .toList()
    }

    private fun sortFiles(files: List<MusicFile>, order: Int): List<MusicFile> {
        return files.sortedWith { left, right ->
            when {
                left.isM3u != right.isM3u -> right.isM3u.compareTo(left.isM3u)
                order == Prefs.SORT_ALPHA -> left.name.lowercase(Locale.ROOT)
                    .compareTo(right.name.lowercase(Locale.ROOT))
                order == Prefs.SORT_SIZE -> right.size.compareTo(left.size)
                else -> right.lastModified.compareTo(left.lastModified)
            }
        }
    }

    private fun renderCurrentList() {
        val source = playlistViewFiles ?: sortFiles(libraryFiles, Prefs.loadSortOrder(this))
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = if (query.isEmpty()) {
            source
        } else {
            source.filter { it.name.lowercase(Locale.ROOT).contains(query) }
        }
        displayedFiles = filtered
        adapter.updateFiles(filtered)
        supportActionBar?.title = playlistViewTitle ?: getString(R.string.app_name)
        playlistBackCallback.isEnabled = playlistViewFiles != null
    }

    private fun checkPermissionsAndRefresh() {
        val storagePermission = storagePermission()
        if (ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED) {
            refreshFromMediaStore()
        } else {
            permissionLauncher.launch(arrayOf(storagePermission))
        }
    }

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
        @Suppress("DEPRECATION")
        val dataColumn = MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            dataColumn,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = ?"
        val selectionArgs = arrayOf("1")
        val files = mutableListOf<MusicFile>()

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val pathCol = cursor.getColumnIndexOrThrow(dataColumn)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                if (name.isEmpty()) continue
                files.add(
                    MusicFile(
                        id = id,
                        name = name,
                        uri = android.content.ContentUris.withAppendedId(uri, id).toString(),
                        path = cursor.getString(pathCol),
                        size = cursor.getLong(sizeCol),
                        lastModified = cursor.getLong(modCol)
                    )
                )
            }
        }

        val excluded = Prefs.loadExcluded(this)
        libraryAudioFiles = files.filter { it.uri !in excluded }
        Prefs.saveFiles(this, libraryAudioFiles)
        libraryFiles = libraryAudioFiles + loadPublicPlaylists()
        if (playlistViewFiles == null) {
            renderCurrentList()
        }
        swipeRefresh.isRefreshing = false
        maybePlayShuffleAll()
    }

    private fun playFile(file: MusicFile) {
        val allFiles = if (playlistViewFiles != null) displayedFiles else libraryAudioFiles
        if (allFiles.isEmpty()) return
        ensureServiceStarted()
        if (bound && musicService != null) {
            musicService?.play(file, allFiles)
        } else {
            pendingFile = file
            pendingAllFiles = allFiles
        }
    }

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

    private fun resolveM3u(file: MusicFile): List<MusicFile> {
        val result = mutableListOf<MusicFile>()
        try {
            val playlistPath = file.path ?: file.uri.removePrefix("file://")
            val baseDir = File(playlistPath).parentFile
            val libraryByPath = libraryAudioFiles.mapNotNull { item ->
                item.path?.let { path -> path to item }
            }.toMap()
            val libraryByUri = libraryAudioFiles.associateBy { it.uri }

            var pendingTitle: String? = null
            var index = 0
            BufferedReader(InputStreamReader(File(playlistPath).inputStream())).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("#EXTINF:") -> {
                            pendingTitle = line.substringAfter(",", "").takeIf { it.isNotEmpty() }
                        }
                        line.startsWith("#") || line.isEmpty() -> Unit
                        else -> {
                            resolvePlaylistEntry(line, baseDir, libraryByPath, libraryByUri, index, pendingTitle)
                                ?.let(result::add)
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

    private fun showLongPressMenu(file: MusicFile) {
        if (file.isM3u) {
            openPlaylistView(file)
            return
        }

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

    private fun removeFromList(file: MusicFile) {
        val excluded = Prefs.loadExcluded(this).toMutableSet()
        excluded.add(file.uri)
        Prefs.saveExcluded(this, excluded)
        loadAndDisplayFromPrefs()
        Toast.makeText(this, R.string.removed_from_list, Toast.LENGTH_SHORT).show()
    }

    private fun addToPlaylist(file: MusicFile) {
        val playlistDir = getPublicPlaylistDirectory() ?: return
        playlistDir.mkdirs()
        val existing = playlistDir.listFiles { candidate ->
            candidate.extension.lowercase(Locale.ROOT) in setOf("m3u", "m3u8")
        }?.sortedBy { it.name } ?: emptyList()

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
            val entryPath = file.path
            val playlistLine = if (entryPath != null) {
                runCatching {
                    playlist.parentFile?.toPath()?.relativize(File(entryPath).toPath())?.toString()
                }.getOrNull() ?: entryPath
            } else {
                file.uri
            }
            val entry = "#EXTINF:-1,${file.name}\n$playlistLine\n"
            if (!playlist.exists()) {
                playlist.writeText("#EXTM3U\n$entry")
            } else {
                playlist.appendText(entry)
            }
            MediaScannerConnection.scanFile(this, arrayOf(playlist.absolutePath), null, null)
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

    private fun ensureServiceStarted() {
        val intent = Intent(this, MusicService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun openPlaylistView(file: MusicFile) {
        val resolved = resolveM3u(file)
        if (resolved.isEmpty()) {
            Toast.makeText(this, R.string.m3u_empty, Toast.LENGTH_SHORT).show()
            return
        }
        playlistViewFiles = resolved
        playlistViewTitle = getString(R.string.playlist_view_title, file.name)
        renderCurrentList()
    }

    private fun closePlaylistView() {
        playlistViewFiles = null
        playlistViewTitle = null
        renderCurrentList()
    }

    private fun maybePlayShuffleAll() {
        if (!shouldPlayShuffleAll || !bound) return
        val allFiles = libraryAudioFiles.shuffled(Random())
        if (allFiles.isEmpty()) return
        shouldPlayShuffleAll = false
        musicService?.play(allFiles.first(), allFiles)
    }

    private fun migratePrivatePlaylists() {
        val privateDir = getExternalFilesDir(LEGACY_PLAYLIST_DIR) ?: return
        val publicDir = getPublicPlaylistDirectory() ?: return
        val privateFiles = privateDir.listFiles { file ->
            file.extension.lowercase(Locale.ROOT) in setOf("m3u", "m3u8")
        } ?: return
        publicDir.mkdirs()

        val migratedPaths = mutableListOf<String>()
        privateFiles.forEach { source ->
            val destination = File(publicDir, source.name)
            if (!destination.exists()) {
                runCatching { source.copyTo(destination) }
                    .onSuccess { migratedPaths.add(destination.absolutePath) }
            }
        }

        if (migratedPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, migratedPaths.toTypedArray(), null, null)
        }
    }

    private fun getPublicPlaylistDirectory(): File? {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return if (musicDir.exists() || musicDir.mkdirs()) musicDir else null
    }

    private fun playlistFile(file: File): MusicFile = MusicFile(
        id = file.absolutePath.hashCode().toLong(),
        name = file.name,
        uri = Uri.fromFile(file).toString(),
        path = file.absolutePath,
        size = file.length(),
        lastModified = file.lastModified() / 1000L,
        isM3u = true
    )

    private fun resolvePlaylistEntry(
        rawLine: String,
        baseDir: File?,
        libraryByPath: Map<String, MusicFile>,
        libraryByUri: Map<String, MusicFile>,
        index: Int,
        pendingTitle: String?
    ): MusicFile? {
        libraryByUri[rawLine]?.let {
            return it.copy(id = index.toLong(), name = pendingTitle ?: it.name)
        }

        val resolvedPath = when {
            rawLine.startsWith("content://") -> null
            rawLine.startsWith("file://") -> Uri.parse(rawLine).path
            File(rawLine).isAbsolute -> rawLine
            baseDir != null -> File(baseDir, rawLine).path
            else -> rawLine
        }

        val normalizedPath = resolvedPath?.let { path ->
            runCatching { File(path).canonicalPath }.getOrDefault(path)
        }
        if (normalizedPath != null) {
            libraryByPath[normalizedPath]?.let {
                return it.copy(id = index.toLong(), name = pendingTitle ?: it.name)
            }

            val resolvedFile = File(normalizedPath)
            if (resolvedFile.exists()) {
                return MusicFile(
                    id = index.toLong(),
                    name = pendingTitle ?: resolvedFile.name,
                    uri = Uri.fromFile(resolvedFile).toString(),
                    path = normalizedPath,
                    size = resolvedFile.length(),
                    lastModified = resolvedFile.lastModified() / 1000L
                )
            }
        }

        return null
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, storagePermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun storagePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
}
