package io.github.hdclark.justplayback

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MusicAdapter(
    private var files: List<MusicFile>,
    private val onClick: (MusicFile) -> Unit,
    private val onLongClick: (View, MusicFile) -> Unit
) : RecyclerView.Adapter<MusicAdapter.ViewHolder>() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_name)
        val meta: TextView = view.findViewById(R.id.tv_meta)

        init {
            name.isSelected = true
            meta.isSelected = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_music, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        val sizeStr = formatSize(file.size)
        val dateStr = Instant.ofEpochSecond(file.lastModified)
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
        holder.meta.text = if (file.isPlaylist) "Playlist • $dateStr" else "$sizeStr • $dateStr"
        holder.itemView.setOnClickListener { onClick(file) }
        holder.itemView.setOnLongClickListener {
            onLongClick(it, file)
            true
        }
    }

    override fun getItemCount(): Int = files.size

    fun updateFiles(newFiles: List<MusicFile>) {
        files = newFiles
        notifyDataSetChanged()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toLong()
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        var current = ci.first()
        while (value >= 1024 * 1024) {
            value /= 1024
            current = ci.next()
        }
        val result = value / 1024.0
        return "%.1f %ciB".format(result, current)
    }
}
