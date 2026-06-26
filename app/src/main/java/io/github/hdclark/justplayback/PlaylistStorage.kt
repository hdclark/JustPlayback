package io.github.hdclark.justplayback

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

object PlaylistStorage {
    private const val PLAYLIST_EXTENSION = ".m3u"
    private const val PLAYLIST_MIME_TYPE = "audio/x-mpegurl"

    fun scanLibrary(context: Context): List<MusicFile> {
        val files = mutableListOf<MusicFile>()
        files += queryAudioFiles(context)
        files += queryPlaylistFiles(context)
        return files.distinctBy { it.uri }
    }

    fun loadPlaylistEntries(
        context: Context,
        playlist: MusicFile,
        library: List<MusicFile>
    ): List<MusicFile> {
        val audioFiles = library.filterNot { it.isPlaylist }
        val byRelativePath = audioFiles
            .mapNotNull { file -> file.relativePath?.let { normalizePath(it) to file } }
            .toMap()
        val byName = audioFiles.associateBy { it.name.lowercase(Locale.ROOT) }
        val seen = linkedSetOf<String>()

        return readPlaylistLines(context, playlist).mapNotNull { line ->
            val normalized = normalizePath(line)
            if (normalized.isEmpty()) {
                null
            } else {
                val file = byRelativePath[normalized]
                    ?: byName[normalized.substringAfterLast('/').lowercase(Locale.ROOT)]
                file?.takeIf { seen.add(it.uri) }
            }
        }
    }

    fun savePlaylist(context: Context, playlistName: String, files: List<MusicFile>): MusicFile? {
        val normalizedName = ensurePlaylistName(playlistName)
        val playlistBody = buildString {
            appendLine("#EXTM3U")
            files.filterNot { it.isPlaylist }.forEach { file ->
                appendLine(file.relativePath ?: file.name)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePlaylistScoped(context, normalizedName, playlistBody)
        } else {
            savePlaylistLegacy(normalizedName, playlistBody)
        }
    }

    private fun queryAudioFiles(context: Context): List<MusicFile> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = ? AND ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("1", "audio/%")
        val files = mutableListOf<MusicFile>()

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                if (name.isEmpty()) {
                    continue
                }
                val id = cursor.getLong(idCol)
                files += MusicFile(
                    id = id,
                    name = name,
                    uri = ContentUris.withAppendedId(uri, id).toString(),
                    size = cursor.getLong(sizeCol),
                    lastModified = cursor.getLong(modCol),
                    relativePath = buildRelativePath(
                        cursor.getStringOrNull(relativePathCol),
                        cursor.getStringOrNull(dataCol),
                        name,
                    )
                )
            }
        }

        return files
    }

    private fun queryPlaylistFiles(context: Context): List<MusicFile> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryScopedPlaylistFiles(context)
        } else {
            queryLegacyPlaylistFiles()
        }
    }

    private fun queryScopedPlaylistFiles(context: Context): List<MusicFile> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_MUSIC}/%")
        val files = mutableListOf<MusicFile>()

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)?.trim().orEmpty()
                val mimeType = cursor.getStringOrNull(mimeCol).orEmpty()
                if (!name.lowercase(Locale.ROOT).endsWith(PLAYLIST_EXTENSION) && mimeType != PLAYLIST_MIME_TYPE) {
                    continue
                }
                val id = cursor.getLong(idCol)
                val relativeDir = cursor.getString(relativePathCol).orEmpty()
                files += MusicFile(
                    id = id,
                    name = name,
                    uri = ContentUris.withAppendedId(uri, id).toString(),
                    size = cursor.getLong(sizeCol),
                    lastModified = cursor.getLong(modCol),
                    relativePath = buildRelativePath(relativeDir, null, name),
                    isPlaylist = true,
                )
            }
        }

        return files
    }

    private fun queryLegacyPlaylistFiles(): List<MusicFile> {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (!musicDir.exists()) {
            return emptyList()
        }
        return musicDir.walkTopDown()
            .filter { it.isFile && it.name.lowercase(Locale.ROOT).endsWith(PLAYLIST_EXTENSION) }
            .map { file ->
                MusicFile(
                    id = file.absolutePath.hashCode().toLong(),
                    name = file.name,
                    uri = Uri.fromFile(file).toString(),
                    size = file.length(),
                    lastModified = file.lastModified() / 1000,
                    relativePath = buildLegacyRelativePath(file),
                    isPlaylist = true,
                )
            }
            .toList()
    }

    private fun savePlaylistScoped(context: Context, name: String, body: String): MusicFile? {
        val existing = scanLibrary(context).firstOrNull { it.isPlaylist && it.name.equals(name, ignoreCase = true) }
        val playlistUri = existing?.let(Uri::parse) ?: context.contentResolver.insert(
            MediaStore.Files.getContentUri("external"),
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
                put(MediaStore.Files.FileColumns.MIME_TYPE, PLAYLIST_MIME_TYPE)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/")
            }
        ) ?: return null

        context.contentResolver.openOutputStream(playlistUri, "wt")?.bufferedWriter()?.use {
            it.write(body)
        } ?: return null

        return scanLibrary(context).firstOrNull { it.isPlaylist && it.uri == playlistUri.toString() }
    }

    private fun savePlaylistLegacy(name: String, body: String): MusicFile? {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (!musicDir.exists() && !musicDir.mkdirs()) {
            return null
        }
        val file = File(musicDir, name)
        file.writeText(body)
        return MusicFile(
            id = file.absolutePath.hashCode().toLong(),
            name = file.name,
            uri = Uri.fromFile(file).toString(),
            size = file.length(),
            lastModified = file.lastModified() / 1000,
            relativePath = buildLegacyRelativePath(file),
            isPlaylist = true,
        )
    }

    private fun readPlaylistLines(context: Context, playlist: MusicFile): List<String> {
        val uri = Uri.parse(playlist.uri)
        val text = when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() }?.readText()
            else -> null
        } ?: return emptyList()

        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    private fun ensurePlaylistName(name: String): String {
        val trimmed = name.trim().ifEmpty { "Playlist" }
        return if (trimmed.lowercase(Locale.ROOT).endsWith(PLAYLIST_EXTENSION)) trimmed else "$trimmed$PLAYLIST_EXTENSION"
    }

    private fun buildRelativePath(relativeDir: String?, dataPath: String?, name: String): String? {
        val scopedPath = relativeDir
            ?.substringAfter("${Environment.DIRECTORY_MUSIC}/", missingDelimiterValue = relativeDir)
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$it/$name" }
        if (scopedPath != null) {
            return normalizePath(scopedPath)
        }

        val legacyPath = dataPath?.let(::File) ?: return name
        return buildLegacyRelativePath(legacyPath) ?: name
    }

    private fun buildLegacyRelativePath(file: File): String? {
        val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val rootPath = musicRoot.absolutePath.trimEnd('/', '\\') + File.separator
        val absolutePath = file.absolutePath
        return if (absolutePath.startsWith(rootPath)) {
            normalizePath(absolutePath.removePrefix(rootPath))
        } else {
            file.name
        }
    }

    private fun normalizePath(path: String): String {
        val withoutMusicRoot = path
            .replace('\\', '/')
            .substringAfter("/Music/", path.replace('\\', '/'))
            .removePrefix("./")
            .trim('/')
        return withoutMusicRoot.lowercase(Locale.ROOT)
    }

    private fun android.database.Cursor.getStringOrNull(columnIndex: Int): String? {
        if (columnIndex < 0 || isNull(columnIndex)) {
            return null
        }
        return getString(columnIndex)
    }
}
