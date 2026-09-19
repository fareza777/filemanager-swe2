package com.filezen.files.core.fileops

import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.SortRule
import java.io.File
import java.util.Locale

data class SortPreviewItem(val file: FileEntry, val rule: SortRule, val target: String)

object SortRuleEngine {

    fun matches(rule: SortRule, e: FileEntry): Boolean {
        if (e.isDirectory) return false
        return when (rule.matchType) {
            "EXTENSION" -> e.extension.equals(rule.pattern.trim().removePrefix("."), ignoreCase = true)
            "CONTAINS" -> e.name.lowercase(Locale.ROOT).contains(rule.pattern.lowercase(Locale.ROOT))
            "REGEX" -> runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(e.name) }
                .getOrDefault(false)
            else -> false
        }
    }

    /** Which files in [root] (recursive) would move, under which rule, where to. */
    suspend fun preview(root: File, rules: List<SortRule>, maxDepth: Int = 6): List<SortPreviewItem> {
        val out = mutableListOf<SortPreviewItem>()
        fun walk(d: File, depth: Int) {
            if (depth > maxDepth) return
            d.listFiles()?.forEach { f ->
                if (f.isDirectory) walk(f, depth + 1)
                else {
                    val e = FileEntry.from(f)
                    rules.firstOrNull { matches(it, e) }?.let {
                        // don't propose moving it into itself/same dir
                        if (File(it.targetPath).absolutePath != f.parentFile?.absolutePath)
                            out += SortPreviewItem(e, it, it.targetPath)
                    }
                }
            }
        }
        walk(root, 0)
        return out
    }

    fun describe(rule: SortRule): String = when (rule.matchType) {
        "EXTENSION" -> "*.${rule.pattern.trim().removePrefix(".")}"
        "CONTAINS" -> "name contains “${rule.pattern}”"
        "REGEX" -> "name matches /${rule.pattern}/"
        else -> rule.pattern
    }
}
