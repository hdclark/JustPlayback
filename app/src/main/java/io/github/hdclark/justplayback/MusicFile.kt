package io.github.hdclark.justplayback

import java.io.Serializable

data class MusicFile(
    val id: Long,
    val name: String,
    val uri: String,
    val size: Long,
    val lastModified: Long,
    val isM3u: Boolean = false
) : Serializable
