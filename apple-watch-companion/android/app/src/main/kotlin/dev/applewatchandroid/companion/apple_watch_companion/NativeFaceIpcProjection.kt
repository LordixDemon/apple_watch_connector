package dev.applewatchandroid.companion.apple_watch_companion

import android.os.Bundle

/** Shared bounded Binder-to-Flutter projection; contains no native face resources. */
internal object NativeFaceIpcProjection {
    fun from(data: Bundle): Map<String, Any> = buildMap {
        put("faceCollectionKnown", data.getBoolean("faceCollectionKnown", false))
        for (key in listOf("faceCollectionPair", "faceCollectionEpoch")) {
            data.getString(key)?.let { put(key, it) }
        }
        if (data.getBoolean("faceCollectionKnown", false)) {
            if (data.getBoolean("faceCollectionSelectionKnown", false)) {
                data.getString("activeFaceId")?.let { put("activeFaceId", it) }
            }
            put("faceCollectionObservedAt", data.getLong("faceCollectionObservedAt"))
            data.getByteArray("faceComplicationCatalog")?.let { put("faceComplicationCatalog", it) }
            put("faceComplicationCatalogComplete", data.getBoolean("faceComplicationCatalogComplete", false))
            for (key in listOf("faceCollectionComplete", "faceCollectionOrderKnown", "faceCollectionSelectionKnown")) {
                put(key, data.getBoolean(key))
            }
            put("faceCollectionOrder", data.getStringArrayList("faceCollectionOrder") ?: arrayListOf<String>())
            @Suppress("DEPRECATION")
            val rows = data.getParcelableArrayList<Bundle>("faceCollectionFaces") ?: arrayListOf()
            put("faceCollectionFaces", rows.map { row -> mapOf(
                "id" to (row.getString("id") ?: ""), "bundle" to (row.getString("bundle") ?: ""),
                "configurationBytes" to row.getInt("configurationBytes"),
                "archiveAvailable" to row.getBoolean("archiveAvailable", false),
                "configuration" to row.getByteArray("configuration")) })
        }
    }
}
