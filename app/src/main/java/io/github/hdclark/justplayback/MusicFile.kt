package io.github.hdclark.justplayback

import java.io.Serializable

data class MusicFile(
    val id: Long,
    val name: String,
    val uri: String,
    val size: Long,
    val lastModified: Long,
    val path: String? = null
) : Serializable {
    val isPlaylist: Boolean
        get() = name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true)
}
