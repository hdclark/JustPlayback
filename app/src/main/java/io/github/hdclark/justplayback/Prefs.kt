package io.github.hdclark.justplayback

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val PREF_NAME = "justplayback"
    private const val KEY_FILES = "files"
    private const val KEY_SORT = "sort_order"
    private const val KEY_EXCLUDED = "excluded_uris"

    const val SORT_TIME = 0
    const val SORT_ALPHA = 1
    const val SORT_SIZE = 2

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveFiles(context: Context, files: List<MusicFile>) {
        val array = JSONArray()
        files.forEach { f ->
            val obj = JSONObject()
            obj.put("id", f.id)
            obj.put("name", f.name)
            obj.put("uri", f.uri)
            obj.put("size", f.size)
            obj.put("lastModified", f.lastModified)
            obj.put("isM3u", f.isM3u)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_FILES, array.toString()).apply()
    }

    fun loadFiles(context: Context): List<MusicFile> {
        val str = prefs(context).getString(KEY_FILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(str)
            val list = mutableListOf<MusicFile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    MusicFile(
                        id = obj.getLong("id"),
                        name = obj.getString("name"),
                        uri = obj.getString("uri"),
                        size = obj.getLong("size"),
                        lastModified = obj.getLong("lastModified"),
                        isM3u = obj.optBoolean("isM3u", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSortOrder(context: Context, order: Int) {
        prefs(context).edit().putInt(KEY_SORT, order).apply()
    }

    fun loadSortOrder(context: Context): Int =
        prefs(context).getInt(KEY_SORT, SORT_TIME)

    fun saveExcluded(context: Context, excluded: Set<String>) {
        val array = JSONArray()
        excluded.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_EXCLUDED, array.toString()).apply()
    }

    fun loadExcluded(context: Context): Set<String> {
        val str = prefs(context).getString(KEY_EXCLUDED, null) ?: return emptySet()
        return try {
            val array = JSONArray(str)
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) set.add(array.getString(i))
            set
        } catch (_: Exception) {
            emptySet()
        }
    }
}
