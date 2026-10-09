package dev.applewatchandroid.companion.apple_watch_companion

import android.os.Bundle

/** Bundle projection retains native presence and observation timestamps. */
internal object BridgeStateProjection {
    fun from(data: Bundle): Map<String, Any> = buildMap {
        put("connected", data.getBoolean("connected", false))
        put("bridgeAvailable", true)
        put("features", mapOf(
            "nativeFaces" to true, "watchSettings" to true, "telemetry" to true,
            "phoneFind" to true, "notificationRelay" to true,
            "health" to false, "appManagement" to false))
        put("pairingAvailable", true)
        put("discoveryAvailable", false)
        put("wifiAvailable", true)
        data.getString("deviceName")?.let { put("deviceName", it) }
        for (key in listOf("pairId", "pairState", "productType", "buildVersion", "connectionStatus",
                "setupPhase", "discoveredProductType", "discoveredWatchOs", "activationTitle",
                "activationMessage", "bridgeJournal", "identityError")) {
            data.getString(key)?.let { put(key, it) }
        }
        for (key in listOf("identityKnown", "hasPair", "operationalEligible", "activationConfirmed",
                "setupRunning", "pinRequired", "bluetoothPermission")) put(key, data.getBoolean(key, false))
        if (data.containsKey("activationChallengeId")) put("activationChallengeId", data.getInt("activationChallengeId"))
        data.getStringArray("activationFields")?.let { put("activationFields", it.toList()) }
        val candidates = data.getParcelableArrayList<Bundle>("discoveredWatches") ?: arrayListOf()
        put("discoveredWatches", candidates.map { row -> mapOf(
            "token" to row.getString("token"), "productType" to row.getString("productType"),
            "watchOs" to row.getString("watchOs"), "rssi" to row.getInt("rssi")) })
        put("observedAt", data.getLong("observedAt"))
        if (data.containsKey("batteryLevel")) {
            put("batteryLevel", data.getInt("batteryLevel"))
        }
        if (data.containsKey("isCharging")) put("isCharging", data.getBoolean("isCharging"))
        put("chargingObserved", data.getBoolean("chargingObserved", false))
        for (key in listOf("phoneFindAvailable", "phoneFlashPermission", "phoneFindKnown",
                "phoneFindActive", "phoneFindLocalProbe", "phoneFindDidPlay")) {
            if (data.containsKey(key)) put(key, data.getBoolean(key))
        }
        if (data.containsKey("phoneFindBehavior")) put("phoneFindBehavior", data.getInt("phoneFindBehavior"))
        if (data.containsKey("phoneFindObservedAt")) put("phoneFindObservedAt", data.getLong("phoneFindObservedAt"))
        data.getString("phoneFindStopReason")?.let { put("phoneFindStopReason", it) }
        for (setting in listOf("RIGHT_WRIST", "INVERT_SCREEN", "TIME_24_HOUR")) {
            val key = "watchSetting_$setting"
            if (data.containsKey(key) && data.containsKey("${key}_observedAt")) {
                put(key, data.getBoolean(key))
                put("${key}_observedAt", data.getLong("${key}_observedAt"))
            }
        }
        for (key in listOf("batteryObservedAt", "aboutObservedAt", "availableStorageBytes",
                "numberOfApps", "numberOfSongs", "numberOfPhotos", "purgeableSpaceBytes", "userDeletableSpaceBytes")) {
            if (data.containsKey(key)) put(key, data.getLong(key))
        }
        data.getString("activeFaceId")?.let { put("activeFaceId", it) }
        putAll(NativeFaceIpcProjection.from(data))
        for (key in listOf("monogramPreferencePair", "monogramPreferenceEpoch", "monogramPreferenceText")) {
            data.getString(key)?.let { put(key, it) }
        }
        if (data.containsKey("monogramPreferenceSourceTimestamp")) {
            put("monogramPreferenceSourceTimestamp", data.getDouble("monogramPreferenceSourceTimestamp"))
        }
        if (data.containsKey("monogramPreferenceObservedAt")) {
            put("monogramPreferenceObservedAt", data.getLong("monogramPreferenceObservedAt"))
        }
        for (key in listOf("pigmentMirrorPair", "pigmentMirrorEpoch", "pigmentMirrorOrigin", "pigmentMirrorRequestId")) {
            data.getString(key)?.let { put(key, it) }
        }
        if (data.containsKey("monogramMirrorVersion")) put("monogramMirrorVersion", data.getInt("monogramMirrorVersion"))
        if (data.containsKey("monogramMirrorKnown")) put("monogramMirrorKnown", data.getBoolean("monogramMirrorKnown"))
        for (key in listOf("monogramMirrorPair", "monogramMirrorEpoch", "monogramMirrorOrigin", "monogramMirrorRequestId", "monogramMirrorRevision", "monogramMirrorText")) {
            data.getString(key)?.let { put(key, it) }
        }
        if (data.containsKey("monogramMirrorSourceTimestamp")) put("monogramMirrorSourceTimestamp", data.getDouble("monogramMirrorSourceTimestamp"))
        if (data.containsKey("monogramMirrorUpdatedAt")) put("monogramMirrorUpdatedAt", data.getLong("monogramMirrorUpdatedAt"))
        if (data.containsKey("pigmentMirrorSourceTimestamp")) put("pigmentMirrorSourceTimestamp", data.getDouble("pigmentMirrorSourceTimestamp"))
        if (data.containsKey("pigmentMirrorUpdatedAt")) put("pigmentMirrorUpdatedAt", data.getLong("pigmentMirrorUpdatedAt"))
        data.getStringArrayList("pigmentMirrorNames")?.let {
            if (it.size <= 1023) put("pigmentMirrorNames", it.toList())
        }
        if (data.containsKey("pigmentMirrorVersion")) put("pigmentMirrorVersion", data.getInt("pigmentMirrorVersion"))
        for (key in listOf("pigmentMirrorAutomaticOrigin", "pigmentMirrorAutomaticRequestId")) {
            data.getString(key)?.let { put(key, it) }
        }
        if (data.containsKey("pigmentMirrorAutomaticTimestamp")) put("pigmentMirrorAutomaticTimestamp", data.getDouble("pigmentMirrorAutomaticTimestamp"))
        if (data.containsKey("pigmentMirrorAutomaticUpdatedAt")) put("pigmentMirrorAutomaticUpdatedAt", data.getLong("pigmentMirrorAutomaticUpdatedAt"))
        data.getStringArrayList("pigmentMirrorAutomaticNames")?.let {
            if (it.size <= 1023) put("pigmentMirrorAutomaticNames", it.toList())
        }
        for (key in listOf("pigmentPreferencePair", "pigmentPreferenceEpoch")) {
            data.getString(key)?.let { put(key, it) }
        }
        if (data.containsKey("pigmentPreferenceSourceTimestamp")) {
            put("pigmentPreferenceSourceTimestamp", data.getDouble("pigmentPreferenceSourceTimestamp"))
        }
        if (data.containsKey("pigmentPreferenceObservedAt")) {
            put("pigmentPreferenceObservedAt", data.getLong("pigmentPreferenceObservedAt"))
        }
        data.getStringArrayList("pigmentPreferenceNames")?.let {
            if (it.size <= 1023) put("pigmentPreferenceNames", it.toList())
        }
    }

}
