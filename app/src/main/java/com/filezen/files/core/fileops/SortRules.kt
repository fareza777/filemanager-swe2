package com.filezen.files.core.fileops

import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.SortRule
import java.io.File
import java.util.Locale

data class SortPreviewItem(val file: FileEntry, val rule: SortRule, val target: String)

object SortRuleEngine {

    fun matches(rule: SortRule, e: FileEntry): Boolean {
        if (e.isDirectory) return false
        // Optional source scope: the rule only applies to files inside it
        // (e.g. "*.pdf in WhatsApp Documents → Documents/WhatsApp").
        rule.sourcePath?.let { src ->
            val base = File(src).absolutePath
            if (!e.path.startsWith(base + "/")) return false
        }
        return when (rule.matchType) {
            "EXTENSION" -> {
                val wanted = rule.pattern.split(",").map { it.trim().removePrefix(".").lowercase() }
                e.extension.lowercase() in wanted
            }
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

    fun describe(rule: SortRule): String {
        val base = when (rule.matchType) {
            "EXTENSION" -> rule.pattern.split(",")
                .joinToString(" ") { "*.${it.trim().removePrefix(".")}" }
            "CONTAINS" -> "name contains “${rule.pattern}”"
            "REGEX" -> "name matches /${rule.pattern}/"
            else -> rule.pattern
        }
        return rule.sourcePath?.let { "$base in ${File(it).name}" } ?: base
    }
}
