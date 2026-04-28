package io.github.hdclark.justplayback

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MusicAdapter(
    private var files: List<MusicFile>,
    private val onClick: (MusicFile) -> Unit
) : RecyclerView.Adapter<MusicAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_name)
        val meta: TextView = view.findViewById(R.id.tv_meta)
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
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(file.lastModified * 1000L))
        holder.meta.text = "$sizeStr • $dateStr"
        holder.itemView.setOnClickListener { onClick(file) }
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
