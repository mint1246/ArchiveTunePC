/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

enum class BackupArchiveCategory {
    LIBRARY,
    ACCOUNT,
    SETTINGS,
    DOWNLOADS,
}

enum class BackupArchiveStep {
    EXPORT_SETTINGS,
    CHECKPOINT_DATABASE,
    COPY_DATABASE_FILE,
}

data class BackupArchiveProgress(
    val step: BackupArchiveStep,
    val fileName: String? = null,
    val percent: Int,
    val indeterminate: Boolean,
)

enum class RestoreArchiveStep {
    VERIFYING,
    STOPPING_PLAYBACK,
    RESTORING_SETTINGS,
    RESTORING_FILE,
    RESTARTING,
}

data class RestoreArchiveProgress(
    val step: RestoreArchiveStep,
    val fileName: String? = null,
    val percent: Int,
    val indeterminate: Boolean,
)

enum class BackupValidationError {
    SOURCE_NOT_FOUND,
    EMPTY_ARCHIVE,
    MISSING_CONTENT,
    CORRUPTED,
}

data class BackupValidationResult(
    val isValid: Boolean,
    val availableCategories: Set<BackupArchiveCategory>,
    val error: BackupValidationError? = null,
)

sealed interface BackupPreferenceValue {
    data class BooleanValue(val value: Boolean) : BackupPreferenceValue
    data class IntValue(val value: Int) : BackupPreferenceValue
    data class LongValue(val value: Long) : BackupPreferenceValue
    data class FloatValue(val value: Float) : BackupPreferenceValue
    data class StringValue(val value: String) : BackupPreferenceValue
    data class StringSetValue(val value: Set<String>) : BackupPreferenceValue
}

data class BackupSettingsSnapshot(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
) {
    fun isEmpty(): Boolean =
        booleans.isEmpty() &&
            ints.isEmpty() &&
            longs.isEmpty() &&
            floats.isEmpty() &&
            strings.isEmpty() &&
            stringSets.isEmpty()
}

object BackupArchiveConstants {
    const val SETTINGS_XML_FILENAME = "settings.xml"
    const val SETTINGS_PB_FILENAME = "settings.preferences_pb"
    const val BUFFER_SIZE = 64 * 1024

    val ACCOUNT_PREFERENCE_KEYS: Set<String> =
        setOf(
            "innerTubeCookie",
            "visitorData",
            "dataSyncId",
            "poToken",
            "poTokenGvs",
            "poTokenPlayer",
            "poTokenSourceUrl",
            "webClientPoTokenEnabled",
            "accountName",
            "accountEmail",
            "accountChannelHandle",
            "useLoginForBrowse",
            "lastfmSession",
            "lastfmUsername",
            "lastfmProvider",
            "lastfmCustomEndpoint",
            "lastfmApiKeyOverride",
            "lastfmSecretOverride",
            "paxsenixApiKey",
            "listenbrainz_token",
            "discordToken",
            "discordUsername",
            "discordName",
            "proxyUsername",
            "proxyPassword",
            "spotify_sp_dc",
            "spotify_sp_key",
            "spotify_access_token",
            "spotify_access_token_expires_at",
            "spotify_account_name",
            "spotify_account_avatar_url",
        )

    val NON_PORTABLE_PREFERENCE_KEYS: Set<String> =
        setOf(
            "scheduledBackupEnabled",
            "scheduledBackupDirectoryUri",
            "scheduledBackupDirectoryName",
        )

    fun databaseSidecarNames(dbName: String): List<String> =
        listOf(
            "$dbName-wal",
            "$dbName-shm",
            "$dbName-journal",
        )

    fun databaseEntryNames(dbName: String): Set<String> =
        linkedSetOf(
            dbName,
            "$dbName-wal",
            "$dbName-shm",
            "$dbName-journal",
        )
}
