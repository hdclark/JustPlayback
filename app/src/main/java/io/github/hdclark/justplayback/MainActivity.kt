package io.github.hdclark.justplayback

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            refreshFromMediaStore()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        recyclerView = findViewById(R.id.recycler_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        adapter = MusicAdapter(emptyList()) { file ->
            val allFiles = Prefs.loadFiles(this)
            if (bound && musicService != null) {
                musicService?.play(file, allFiles)
            } else {
                // Service not yet bound; start it as foreground and store pending request.
                // The main serviceConnection (onStart) will call play once connected.
                pendingFile = file
                pendingAllFiles = allFiles
                ensureServiceStarted()
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            checkPermissionsAndRefresh()
        }

        // Load from prefs on startup (no MediaStore query)
        loadAndDisplayFromPrefs()
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
        val files = Prefs.loadFiles(this)
        val sortOrder = Prefs.loadSortOrder(this)
        val sorted = sortFiles(files, sortOrder)
        adapter.updateFiles(sorted)
    }

    private fun sortFiles(files: List<MusicFile>, order: Int): List<MusicFile> = when (order) {
        Prefs.SORT_ALPHA -> files.sortedBy { it.name.lowercase() }
        Prefs.SORT_SIZE -> files.sortedByDescending { it.size }
        else -> files.sortedByDescending { it.lastModified }
    }

    private fun checkPermissionsAndRefresh() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            refreshFromMediaStore()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun refreshFromMediaStore() {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val files = mutableListOf<MusicFile>()

        contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val fileUri = android.content.ContentUris.withAppendedId(uri, id).toString()
                files.add(
                    MusicFile(
                        id = id,
                        name = cursor.getString(nameCol) ?: "",
                        uri = fileUri,
                        size = cursor.getLong(sizeCol),
                        lastModified = cursor.getLong(modCol)
                    )
                )
            }
        }

        Prefs.saveFiles(this, files)
        val sortOrder = Prefs.loadSortOrder(this)
        val sorted = sortFiles(files, sortOrder)
        adapter.updateFiles(sorted)
        swipeRefresh.isRefreshing = false
    }

    private fun ensureServiceStarted() {
        val intent = Intent(this, MusicService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
}
