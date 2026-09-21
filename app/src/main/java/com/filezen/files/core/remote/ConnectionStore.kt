package com.filezen.files.core.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialize remote connections (SFTP/SMB/WebDAV/S3) to a JSON blob stored in
 * DataStore — avoids a Room migration for a simple list.
 */
object ConnectionStore {

    fun encode(list: List<RemoteConnection>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("type", c.type.name); put("label", c.label)
                put("host", c.host); put("port", c.port); put("user", c.user)
                put("pass", c.pass); put("root", c.root); put("extra", c.extra)
            })
        }
        return arr.toString()
    }

    fun decode(raw: String): List<RemoteConnection> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteConnection(
                id = o.optLong("id", i.toLong()),
                type = runCatching { RemoteType.valueOf(o.getString("type")) }
                    .getOrDefault(RemoteType.SFTP),
                label = o.optString("label"),
                host = o.optString("host"),
                port = o.optInt("port"),
                user = o.optString("user"),
                pass = o.optString("pass"),
                root = o.optString("root"),
                extra = o.optString("extra"),
            )
        }
    }.getOrDefault(emptyList())
}
