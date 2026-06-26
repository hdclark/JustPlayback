package io.github.hdclark.justplayback

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Bottom padding for the file list, in dp, to give breathing room at the end of the list. */
        private const val LIST_BOTTOM_PADDING_DP = 8
        const val ACTION_AUTO_PLAY = "io.github.hdclark.justplayback.AUTO_PLAY"
        private const val PLAYLIST_NAME = "JustPlayback.m3u"
        private const val M3U_MIME_TYPE = "audio/x-mpegurl"
        private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: MusicAdapter
    private lateinit var searchInput: EditText

    private var musicService: MusicService? = null
    private var bound = false
    private var pendingFile: MusicFile? = null
    private var pendingAllFiles: List<MusicFile> = emptyList()
    private var searchQuery = ""
    private var displayedPlaylist: List<MusicFile>? = null
    private var pendingAutoPlay = false
    private var serviceBindRequested = false
    private var pendingAddToPlaylist: MusicFile? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            displayedPlaylist = null
            isEnabled = false
            loadAndDisplayFromPrefs()
        }
    }

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
        }

        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            bound = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Result is informational; service will handle gracefully without it */ }

    private val writeAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pendingFile = pendingAddToPlaylist
        if (result.resultCode == Activity.RESULT_OK && pendingFile != null) {
            pendingAddToPlaylist = null
            addToPublicPlaylist(pendingFile)
        } else {
            pendingAddToPlaylist = null
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = storagePermissions().all { permission ->
            permissions[permission] ?: (
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            )
        }
        if (storageGranted) {
            refreshFromMediaStore()
            pendingAddToPlaylist?.let { file ->
                pendingAddToPlaylist = null
                addToPublicPlaylist(file)
            }
        } else {
            pendingAddToPlaylist = null
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pendingAutoPlay = intent?.action == ACTION_AUTO_PLAY

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(findViewById(R.id.toolbar))

        recyclerView = findViewById(R.id.recycler_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        searchInput = findViewById(R.id.search_input)
        onBackPressedDispatcher.addCallback(this, backCallback)

        val appBarLayout = findViewById<AppBarLayout>(R.id.app_bar_layout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        val initialBottomPad = (LIST_BOTTOM_PADDING_DP * resources.displayMetrics.density + 0.5f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight,
                initialBottomPad + navBars.bottom)
            insets
        }

        adapter = MusicAdapter(
            emptyList(),
            onClick = { file -> playFile(file, currentPlaybackFiles()) },
            onLongClick = { file -> handleLongClick(file) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        searchInput.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty()
            updateDisplayedFiles(currentSourceFiles())
        }

        swipeRefresh.setOnRefreshListener { checkPermissionsAndRefresh() }

        loadAndDisplayFromPrefs()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        bindMusicService()
        if (pendingAutoPlay) {
            autoPlayRandomWhenReady()
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBindRequested) {
            unbindService(serviceConnection)
            serviceBindRequested = false
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
        updateDisplayedFiles(currentSourceFiles())
        return true
    }

    private fun loadAndDisplayFromPrefs() = updateDisplayedFiles(currentSourceFiles())

    private fun currentSourceFiles(): List<MusicFile> = displayedPlaylist ?: Prefs.loadFiles(this)

    private fun currentPlaybackFiles(): List<MusicFile> = displayedPlaylist ?: Prefs.loadFiles(this).filterNot { it.isPlaylist }

    private fun updateDisplayedFiles(files: List<MusicFile>) {
        val filtered = if (searchQuery.isBlank()) {
            files
        } else {
            files.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        adapter.updateFiles(sortFiles(filtered, Prefs.loadSortOrder(this)))
        backCallback.isEnabled = displayedPlaylist != null
    }

    private fun sortFiles(files: List<MusicFile>, order: Int): List<MusicFile> {
        val comparator = when (order) {
            Prefs.SORT_ALPHA -> compareBy<MusicFile> { it.name.lowercase(Locale.ROOT) }
            Prefs.SORT_SIZE -> compareByDescending { it.size }
            else -> compareByDescending { it.lastModified }
        }
        return files.sortedWith(compareByDescending<MusicFile> { it.isPlaylist }.then(comparator))
    }

    private fun storagePermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun storagePermissions(): Array<String> {
        val permissions = mutableListOf(storagePermission())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private fun checkPermissionsAndRefresh() {
        val missingPermissions = storagePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            refreshFromMediaStore()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun refreshFromMediaStore() {
        val files = queryAudioFiles() + queryMusicDirectoryPlaylists()
        Prefs.saveFiles(this, files.distinctBy { it.uri })
        displayedPlaylist = null
        updateDisplayedFiles(Prefs.loadFiles(this))
        swipeRefresh.isRefreshing = false
    }

    private fun queryAudioFiles(): List<MusicFile> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = ? AND ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("1", "audio/%")
        val files = mutableListOf<MusicFile>()
        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val id = cursor.getLong(idCol)
                files.add(
                    MusicFile(
                        id,
                        name,
                        android.content.ContentUris.withAppendedId(uri, id).toString(),
                        cursor.getLong(sizeCol),
                        cursor.getLong(modCol),
                        cursor.getString(pathCol)
                    )
                )
            }
        }
        return files
    }

    private fun queryMusicDirectoryPlaylists(): List<MusicFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                .walkTopDown()
                .filter { it.isFile && isPlaylistName(it.name) }
                .map { file ->
                    MusicFile(file.absolutePath.hashCode().toLong(), file.name, Uri.fromFile(file).toString(), file.length(), file.lastModified() / 1000, file.absolutePath)
                }
                .toList()
        }

        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? OR ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(Environment.DIRECTORY_MUSIC, "${Environment.DIRECTORY_MUSIC}/%")
        val files = mutableListOf<MusicFile>()
        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                if (name.isEmpty() || !isPlaylistName(name)) continue
                val id = cursor.getLong(idCol)
                files.add(
                    MusicFile(
                        id,
                        name,
                        android.content.ContentUris.withAppendedId(uri, id).toString(),
                        cursor.getLong(sizeCol),
                        cursor.getLong(modCol),
                        cursor.getString(pathCol).orEmpty() + name
                    )
                )
            }
        }
        return files
    }

    private fun handleLongClick(file: MusicFile) {
        if (file.isPlaylist) {
            val playlistFiles = loadPlaylistFiles(file)
            if (playlistFiles.isEmpty()) {
                Toast.makeText(this, R.string.playlist_empty, Toast.LENGTH_LONG).show()
            } else {
                displayedPlaylist = playlistFiles
                updateDisplayedFiles(playlistFiles)
            }
            return
        }

        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.remove_from_list), getString(R.string.add_to_playlist))) { _, which ->
                if (which == 0) removeFromList(file) else addToPublicPlaylist(file)
            }
            .show()
    }

    private fun removeFromList(file: MusicFile) {
        val updated = Prefs.loadFiles(this).filterNot { it.uri == file.uri }
        Prefs.saveFiles(this, updated)
        displayedPlaylist = displayedPlaylist?.filterNot { it.uri == file.uri }
        updateDisplayedFiles(displayedPlaylist ?: updated)
    }

    private fun addToPublicPlaylist(file: MusicFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAddToPlaylist = file
            permissionLauncher.launch(storagePermissions())
            return
        }
        val playlistUri = findOrCreatePublicPlaylist() ?: return
        if (!appendToPlaylist(playlistUri, file)) return
        Toast.makeText(this, R.string.added_to_playlist, Toast.LENGTH_SHORT).show()
        refreshFromMediaStore()
    }

    private fun appendToPlaylist(playlistUri: Uri, file: MusicFile): Boolean {
        return try {
            val line = "${playlistEntryFor(file, playlistUri)}\n"
            if (playlistUri.scheme == "file") {
                appendLineToFilePlaylist(File(requireNotNull(playlistUri.path)), line)
            } else {
                appendLineToContentPlaylist(playlistUri, line)
            }
            true
        } catch (exception: SecurityException) {
            requestPlaylistWriteAccess(playlistUri, file, exception)
            false
        } catch (_: IOException) {
            Toast.makeText(this, R.string.playlist_write_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun appendLineToContentPlaylist(playlistUri: Uri, line: String) {
        contentResolver.openFileDescriptor(playlistUri, "rw")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
                val playlistSize = channel.size()
                val separator = playlistSize > 0L && playlistNeedsSeparator(playlistUri)
                channel.position(playlistSize)
                channel.write(java.nio.ByteBuffer.wrap((if (separator) "\n$line" else line).toByteArray()))
            }
        } ?: throw IOException("Unable to open playlist for writing")
    }

    private fun playlistEntryFor(file: MusicFile, playlistUri: Uri): String {
        val path = file.path ?: return file.uri
        if (playlistUri.scheme == "file") {
            val playlistDirectory = playlistUri.path?.let { File(it).parentFile }
            return relativePathFromDirectory(path, playlistDirectory) ?: path
        }
        return relativePathFromMusicDirectory(path) ?: path
    }

    private fun relativePathFromDirectory(path: String, directory: File?): String? {
        if (directory == null) return null
        return try {
            directory.toPath().relativize(File(path).toPath()).toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun relativePathFromMusicDirectory(path: String): String? {
        val normalized = normalizePath(path)
        val musicPrefix = "${Environment.DIRECTORY_MUSIC}/"
        if (normalized.startsWith(musicPrefix)) {
            return normalized.removePrefix(musicPrefix)
        }
        val musicSegment = "/$musicPrefix"
        val musicIndex = normalized.indexOf(musicSegment)
        return if (musicIndex >= 0) {
            normalized.substring(musicIndex + musicSegment.length)
        } else {
            null
        }
    }

    private fun appendLineToFilePlaylist(playlistFile: File, line: String) {
        playlistFile.parentFile?.mkdirs()
        val separator = playlistFile.exists() && playlistFile.length() > 0L &&
            playlistFile.inputStream().use { it.lastByteOrNull() != '\n'.code }
        playlistFile.appendText(if (separator) "\n$line" else line)
    }

    private fun playlistNeedsSeparator(playlistUri: Uri): Boolean {
        return contentResolver.openInputStream(playlistUri)?.use { input ->
            input.lastByteOrNull()?.let { it != '\n'.code } ?: false
        } ?: false
    }

    private fun InputStream.lastByteOrNull(): Int? {
        var last = -1
        while (true) {
            val next = read()
            if (next == -1) break
            last = next
        }
        return last.takeIf { it != -1 }
    }

    private fun requestPlaylistWriteAccess(playlistUri: Uri, file: MusicFile, exception: SecurityException) {
        pendingAddToPlaylist = file
        val intentSender = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
            exception is RecoverableSecurityException
        ) {
            exception.userAction.actionIntent.intentSender
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createWriteRequest(contentResolver, listOf(playlistUri)).intentSender
        } else {
            null
        }
        if (intentSender != null) {
            writeAccessLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            pendingAddToPlaylist = null
        }
    }

    private fun findOrCreatePublicPlaylist(): Uri? {
        queryMusicDirectoryPlaylists().firstOrNull { isAppPlaylist(it) }?.let { return Uri.parse(it.uri) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val musicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            musicDirectory.mkdirs()
            return Uri.fromFile(File(musicDirectory, PLAYLIST_NAME))
        }

        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, PLAYLIST_NAME)
            put(MediaStore.Files.FileColumns.MIME_TYPE, M3U_MIME_TYPE)
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
        }
        return contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }

    private fun isPlaylistName(name: String): Boolean =
        PLAYLIST_EXTENSIONS.any { extension -> name.endsWith(".$extension", ignoreCase = true) }

    private fun isAppPlaylist(file: MusicFile): Boolean {
        if (file.name != PLAYLIST_NAME) return false
        val path = file.path ?: return false
        val expectedRelativePath = "${Environment.DIRECTORY_MUSIC}/$PLAYLIST_NAME"
        return path == expectedRelativePath || File(path).let { it.name == PLAYLIST_NAME && it.parentFile?.name == Environment.DIRECTORY_MUSIC }
    }

    private fun loadPlaylistFiles(playlist: MusicFile): List<MusicFile> {
        val knownFiles = Prefs.loadFiles(this).filterNot { it.isPlaylist }
        val byUri = knownFiles.associateBy { it.uri }
        val byPath = knownFiles.mapNotNull { file -> file.path?.let { normalizePath(it) to file } }.toMap()
        val normalizedPaths = byPath.keys.toList()
        val byUniqueName = knownFiles.groupBy { it.name.lowercase(Locale.ROOT) }
            .filterValues { it.size == 1 }
            .mapValues { it.value.first() }
        return try {
            contentResolver.openInputStream(Uri.parse(playlist.uri))?.bufferedReader()?.useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { entry ->
                        val normalizedEntry = normalizePlaylistEntry(entry, playlist.path)
                        byUri[entry]
                            ?: byPath[normalizedEntry]
                            ?: matchByPathSuffix(normalizedEntry, normalizedPaths, byPath)
                            ?: byUniqueName[File(entry).name.lowercase(Locale.ROOT)]
                    }
                    .toList()
            }.orEmpty()
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun playFile(file: MusicFile, allFiles: List<MusicFile>) {
        if (file.isPlaylist) {
            handleLongClick(file)
            return
        }
        ensureServiceStarted()
        if (bound && musicService != null) {
            musicService?.play(file, allFiles.filterNot { it.isPlaylist })
        } else {
            pendingFile = file
            pendingAllFiles = allFiles.filterNot { it.isPlaylist }
        }
    }

    private fun autoPlayRandomWhenReady() {
        val playable = Prefs.loadFiles(this).filterNot { it.isPlaylist }
        pendingAutoPlay = false
        val file = playable.randomOrNull() ?: return
        playFile(file, playable)
    }

    private fun ensureServiceStarted() {
        val intent = Intent(this, MusicService::class.java)
        startForegroundService(intent)
        bindMusicService()
    }

    private fun bindMusicService() {
        if (serviceBindRequested) return
        val intent = Intent(this, MusicService::class.java)
        serviceBindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun matchByPathSuffix(
        normalizedEntry: String,
        normalizedPaths: List<String>,
        byPath: Map<String, MusicFile>
    ): MusicFile? {
        val matches = normalizedPaths.filter { it.endsWith("/$normalizedEntry") }
        return matches.singleOrNull()?.let { byPath[it] }
    }

    private fun normalizePlaylistEntry(entry: String, playlistPath: String?): String {
        val entryFile = File(entry)
        val path = if (entryFile.isAbsolute) {
            entryFile.path
        } else {
            playlistPath?.let { File(it).parentFile?.resolve(entry)?.path } ?: entry
        }
        return normalizePath(path)
    }

    private fun normalizePath(path: String): String = File(path).normalize().path
}
