package com.filezen.files.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.zenPrefs by preferencesDataStore("filezen_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ThemeAccent { DYNAMIC, TEAL, SUNSET, VIOLET, OCEAN, ROSE }
enum class ViewMode { LIST, GRID }
enum class SortField { NAME, TYPE, SIZE, DATE }

data class ScrollState(val index: Int = 0, val offset: Int = 0)

class SettingsStore(private val ctx: Context) {

    private object K {
        val THEME = stringPreferencesKey("theme")
        val ACCENT = stringPreferencesKey("accent")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASC = booleanPreferencesKey("sort_asc")
        val LAST_BROWSE = stringPreferencesKey("last_browse_path")
        val INBOX_ROOTS = stringSetPreferencesKey("inbox_roots")
        val SAF_ROOTS = stringSetPreferencesKey("saf_roots")
        val AD_FREE = booleanPreferencesKey("ad_free")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val AUTO_SORT = booleanPreferencesKey("auto_sort")
        val AMOLED = booleanPreferencesKey("amoled")
        val FOLDER_SIZES = booleanPreferencesKey("folder_sizes")
        val RECENT_QUERY = stringPreferencesKey("recent_queries")
        val SCROLL_PREFIX = "scroll_" // + sanitized path -> "index,offset"
    }

    val theme: Flow<ThemeMode> = ctx.zenPrefs.data.map {
        runCatching { ThemeMode.valueOf(it[K.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    val accent: Flow<ThemeAccent> = ctx.zenPrefs.data.map {
        runCatching { ThemeAccent.valueOf(it[K.ACCENT] ?: "TEAL") }.getOrDefault(ThemeAccent.TEAL)
    }
    val viewMode: Flow<ViewMode> = ctx.zenPrefs.data.map {
        runCatching { ViewMode.valueOf(it[K.VIEW_MODE] ?: "LIST") }.getOrDefault(ViewMode.LIST)
    }
    val sortField: Flow<SortField> = ctx.zenPrefs.data.map {
        runCatching { SortField.valueOf(it[K.SORT_FIELD] ?: "NAME") }.getOrDefault(SortField.NAME)
    }
    val sortAsc: Flow<Boolean> = ctx.zenPrefs.data.map { it[K.SORT_ASC] ?: true }
    val lastBrowsePath: Flow<String?> = ctx.zenPrefs.data.map { it[K.LAST_BROWSE] }
    val inboxRoots: Flow<Set<String>> = ctx.zenPrefs.data.map { it[K.INBOX_ROOTS] ?: emptySet() }
    val safRoots: Flow<Set<String>> = ctx.zenPrefs.data.map { it[K.SAF_ROOTS] ?: emptySet() }
    val adFree: Flow<Boolean> = ctx.zenPrefs.data.map { it[K.AD_FREE] ?: false }
    val showHidden: Flow<Boolean> = ctx.zenPrefs.data.map { it[K.SHOW_HIDDEN] ?: false }
    val amoled: Flow<Boolean> = ctx.zenPrefs.data.map { it[K.AMOLED] ?: false }
    val folderSizes: Flow<Boolean> = ctx.zenPrefs.data.map { it[K.FOLDER_SIZES] ?: false }
    val autoSort: Flow<Boolean> = ctx.zenPrefs.data.map { it[K.AUTO_SORT] ?: false }
    val recentQueries: Flow<List<String>> = ctx.zenPrefs.data.map {
        (it[K.RECENT_QUERY] ?: "").split("\n").filter { s -> s.isNotBlank() }.take(10)
    }

    suspend fun setTheme(v: ThemeMode) = ctx.zenPrefs.edit { it[K.THEME] = v.name }
    suspend fun setAccent(v: ThemeAccent) = ctx.zenPrefs.edit { it[K.ACCENT] = v.name }
    suspend fun setViewMode(v: ViewMode) = ctx.zenPrefs.edit { it[K.VIEW_MODE] = v.name }
    suspend fun setSortField(v: SortField) = ctx.zenPrefs.edit { it[K.SORT_FIELD] = v.name }
    suspend fun setSortAsc(v: Boolean) = ctx.zenPrefs.edit { it[K.SORT_ASC] = v }
    suspend fun setLastBrowsePath(v: String?) = ctx.zenPrefs.edit {
        if (v == null) it.remove(K.LAST_BROWSE) else it[K.LAST_BROWSE] = v
    }
    suspend fun setInboxRoots(v: Set<String>) = ctx.zenPrefs.edit { it[K.INBOX_ROOTS] = v }
    suspend fun setSafRoots(v: Set<String>) = ctx.zenPrefs.edit { it[K.SAF_ROOTS] = v }
    suspend fun setAdFree(v: Boolean) = ctx.zenPrefs.edit { it[K.AD_FREE] = v }
    suspend fun setAmoled(v: Boolean) = ctx.zenPrefs.edit { it[K.AMOLED] = v }
    suspend fun setFolderSizes(v: Boolean) = ctx.zenPrefs.edit { it[K.FOLDER_SIZES] = v }
    suspend fun setShowHidden(v: Boolean) = ctx.zenPrefs.edit { it[K.SHOW_HIDDEN] = v }
    suspend fun setAutoSort(v: Boolean) = ctx.zenPrefs.edit { it[K.AUTO_SORT] = v }

    suspend fun addRecentQuery(q: String) {
        ctx.zenPrefs.edit {
            val cur = (it[K.RECENT_QUERY] ?: "").split("\n").filter { s -> s.isNotBlank() }
            it[K.RECENT_QUERY] = (listOf(q.trim()) + cur.filter { s -> s != q.trim() }).take(10).joinToString("\n")
        }
    }

    fun scrollFor(path: String): Flow<ScrollState> = ctx.zenPrefs.data.map {
        val raw = it[stringPreferencesKey(K.SCROLL_PREFIX + path.hashCode())] ?: return@map ScrollState()
        val p = raw.split(",").mapNotNull { s -> s.toIntOrNull() }
        if (p.size == 2) ScrollState(p[0], p[1]) else ScrollState()
    }

    suspend fun saveScroll(path: String, index: Int, offset: Int) {
        ctx.zenPrefs.edit { it[stringPreferencesKey(K.SCROLL_PREFIX + path.hashCode())] = "$index,$offset" }
    }

    suspend fun snapshotInboxRoots(): Set<String> = inboxRoots.first()
}
