/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.equalizer

import moe.rukamori.archivetune.playback.EqCapabilities
import moe.rukamori.archivetune.playback.EqProfile
import moe.rukamori.archivetune.playback.EqProfilesPayload
import moe.rukamori.archivetune.playback.EqSettings
import moe.rukamori.archivetune.playback.EqualizerJson
import java.util.UUID

/**
 * Serialized preference names and profile-selection sentinels.
 *
 * These are the exact names already written by the Android DataStore keys in
 * `PreferenceKeys.kt`; that file builds its keys from this object so the on-disk format has one
 * source of truth. Never rename a value here without an explicit migration of existing installs.
 */
object EqualizerPreferenceKeys {
    const val ENABLED = "equalizerEnabled"
    const val CONTROL_MODE = "equalizerControlMode"
    const val BAND_LEVELS_MB = "equalizerBandLevelsMb"
    const val AUTO_HEADROOM_ENABLED = "equalizerAutoHeadroomEnabled"
    const val OUTPUT_GAIN_ENABLED = "equalizerOutputGainEnabled"
    const val OUTPUT_GAIN_MB = "equalizerOutputGainMb"
    const val BASS_BOOST_ENABLED = "equalizerBassBoostEnabled"
    const val BASS_BOOST_STRENGTH = "equalizerBassBoostStrength"
    const val VIRTUALIZER_ENABLED = "equalizerVirtualizerEnabled"
    const val VIRTUALIZER_STRENGTH = "equalizerVirtualizerStrength"
    const val SELECTED_PROFILE_ID = "equalizerSelectedProfileId"
    const val CUSTOM_PROFILES_JSON = "equalizerCustomProfilesJson"
}

object EqualizerProfileIds {
    const val FLAT = "flat"
    const val MANUAL = "manual"
    const val PREFIX = "profile:"

    fun stored(profileId: String): String = "$PREFIX$profileId"
}

enum class EqualizerControlMode(
    val storageValue: String,
) {
    BASIC("basic"),
    ADVANCED("advanced"),
    ;

    companion object {
        fun fromStorage(value: String?): EqualizerControlMode =
            entries.firstOrNull { it.storageValue == value } ?: BASIC
    }
}

enum class EqualizerTone {
    BASS,
    MIDRANGE,
    TREBLE,
}

data class EqualizerConfiguration(
    val controlMode: EqualizerControlMode,
    val selectedProfileId: String,
    val settings: EqSettings,
    val capabilities: EqCapabilities?,
    val profiles: List<EqProfile>,
)

/** Minimal read side of the preferences store, supplied by the platform adapter. */
interface EqualizerPreferenceReader {
    fun string(key: String): String?
    fun boolean(key: String): Boolean?
    fun int(key: String): Int?
}

/** Write side of the preferences store; reads are still available so edits can be transactional. */
interface EqualizerPreferenceWriter : EqualizerPreferenceReader {
    fun setString(key: String, value: String)
    fun setBoolean(key: String, value: Boolean)
    fun setInt(key: String, value: Int)
}

/**
 * All equalizer values after applying persisted defaults and coercion. This is the complete
 * persistence contract for the feature and is independent of Android DataStore.
 */
data class EqualizerStoredPreferences(
    val controlMode: EqualizerControlMode,
    val selectedProfileId: String,
    val settings: EqSettings,
    val profiles: List<EqProfile>,
)

/**
 * Owner of the equalizer settings persistence semantics: which keys exist, their defaults and
 * bounds, the JSON profile format, selection/import rules and the derived effective settings.
 * The platform adapter only supplies key/value storage; it never decides what is written.
 */
object EqualizerPreferences {
    fun read(reader: EqualizerPreferenceReader): EqualizerStoredPreferences =
        EqualizerStoredPreferences(
            controlMode = EqualizerControlMode.fromStorage(reader.string(EqualizerPreferenceKeys.CONTROL_MODE)),
            selectedProfileId =
                reader.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID) ?: EqualizerProfileIds.FLAT,
            settings =
                EqSettings(
                    enabled = reader.boolean(EqualizerPreferenceKeys.ENABLED) ?: false,
                    bandLevelsMb = decodeLevels(reader.string(EqualizerPreferenceKeys.BAND_LEVELS_MB)),
                    outputGainEnabled = reader.boolean(EqualizerPreferenceKeys.OUTPUT_GAIN_ENABLED) ?: false,
                    outputGainMb = reader.int(EqualizerPreferenceKeys.OUTPUT_GAIN_MB) ?: 0,
                    bassBoostEnabled = reader.boolean(EqualizerPreferenceKeys.BASS_BOOST_ENABLED) ?: false,
                    bassBoostStrength =
                        (reader.int(EqualizerPreferenceKeys.BASS_BOOST_STRENGTH) ?: 0).coerceIn(0, 1_000),
                    virtualizerEnabled = reader.boolean(EqualizerPreferenceKeys.VIRTUALIZER_ENABLED) ?: false,
                    virtualizerStrength =
                        (reader.int(EqualizerPreferenceKeys.VIRTUALIZER_STRENGTH) ?: 0).coerceIn(0, 1_000),
                    autoHeadroomEnabled = reader.boolean(EqualizerPreferenceKeys.AUTO_HEADROOM_ENABLED) ?: false,
                ),
            profiles = decodeProfiles(reader.string(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON)),
        )

    fun setEnabled(writer: EqualizerPreferenceWriter, enabled: Boolean) =
        writer.setBoolean(EqualizerPreferenceKeys.ENABLED, enabled)

    fun setControlMode(writer: EqualizerPreferenceWriter, mode: EqualizerControlMode) =
        writer.setString(EqualizerPreferenceKeys.CONTROL_MODE, mode.storageValue)

    fun updateBandLevels(
        writer: EqualizerPreferenceWriter,
        levelsMb: List<Int>,
    ) {
        writer.setString(EqualizerPreferenceKeys.BAND_LEVELS_MB, encodeLevels(levelsMb))
        writer.setString(EqualizerPreferenceKeys.SELECTED_PROFILE_ID, EqualizerProfileIds.MANUAL)
    }

    /** Any manual tweak detaches the current selection from a named profile. */
    private fun markManual(writer: EqualizerPreferenceWriter) =
        writer.setString(EqualizerPreferenceKeys.SELECTED_PROFILE_ID, EqualizerProfileIds.MANUAL)

    fun setOutputGainEnabled(
        writer: EqualizerPreferenceWriter,
        enabled: Boolean,
    ) {
        writer.setBoolean(EqualizerPreferenceKeys.OUTPUT_GAIN_ENABLED, enabled)
        markManual(writer)
    }

    fun setOutputGainMb(
        writer: EqualizerPreferenceWriter,
        gainMb: Int,
    ) {
        writer.setInt(EqualizerPreferenceKeys.OUTPUT_GAIN_MB, gainMb.coerceIn(-1_500, 1_500))
        markManual(writer)
    }

    fun setBassBoostEnabled(
        writer: EqualizerPreferenceWriter,
        enabled: Boolean,
    ) {
        writer.setBoolean(EqualizerPreferenceKeys.BASS_BOOST_ENABLED, enabled)
        markManual(writer)
    }

    fun setBassBoostStrength(
        writer: EqualizerPreferenceWriter,
        strength: Int,
    ) {
        writer.setInt(EqualizerPreferenceKeys.BASS_BOOST_STRENGTH, strength.coerceIn(0, 1_000))
        markManual(writer)
    }

    fun setVirtualizerEnabled(
        writer: EqualizerPreferenceWriter,
        enabled: Boolean,
    ) {
        writer.setBoolean(EqualizerPreferenceKeys.VIRTUALIZER_ENABLED, enabled)
        markManual(writer)
    }

    fun setVirtualizerStrength(
        writer: EqualizerPreferenceWriter,
        strength: Int,
    ) {
        writer.setInt(EqualizerPreferenceKeys.VIRTUALIZER_STRENGTH, strength.coerceIn(0, 1_000))
        markManual(writer)
    }

    fun setAutoHeadroomEnabled(
        writer: EqualizerPreferenceWriter,
        enabled: Boolean,
    ) {
        writer.setBoolean(EqualizerPreferenceKeys.AUTO_HEADROOM_ENABLED, enabled)
        markManual(writer)
    }

    fun applyProfile(
        writer: EqualizerPreferenceWriter,
        profile: EqProfile,
    ) {
        writeProfileSettings(writer, profile)
        writer.setString(EqualizerPreferenceKeys.SELECTED_PROFILE_ID, EqualizerProfileIds.stored(profile.id))
    }

    fun saveProfile(
        writer: EqualizerPreferenceWriter,
        name: String,
        centerFrequenciesHz: List<Int>,
        settings: EqSettings,
    ): EqProfile {
        val profile =
            EqProfile(
                id = uniqueProfileId(writer.string(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON)),
                name = name,
                bandCenterFreqHz = centerFrequenciesHz,
                bandLevelsMb = settings.bandLevelsMb,
                outputGainMb = settings.outputGainMb,
                outputGainEnabled = settings.outputGainEnabled,
                bassBoostStrength = settings.bassBoostStrength,
                bassBoostEnabled = settings.bassBoostEnabled,
                virtualizerStrength = settings.virtualizerStrength,
                virtualizerEnabled = settings.virtualizerEnabled,
                autoHeadroomEnabled = settings.autoHeadroomEnabled,
            )
        writer.setString(
            EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON,
            encodeProfiles(decodeProfiles(writer.string(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON)) + profile),
        )
        writer.setString(EqualizerPreferenceKeys.SELECTED_PROFILE_ID, EqualizerProfileIds.stored(profile.id))
        return profile
    }

    fun deleteProfile(
        writer: EqualizerPreferenceWriter,
        profileId: String,
    ) {
        val remaining =
            decodeProfiles(writer.string(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON))
                .filterNot { it.id == profileId }
        writer.setString(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON, encodeProfiles(remaining))
        if (writer.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID) == EqualizerProfileIds.stored(profileId)) {
            writer.setString(EqualizerPreferenceKeys.SELECTED_PROFILE_ID, EqualizerProfileIds.MANUAL)
        }
    }

    /** Returns the number of imported profiles. Never partially applies a failed import. */
    fun importProfiles(
        writer: EqualizerPreferenceWriter,
        raw: String,
    ): Int {
        val imported = decodeImport(raw)
        require(imported.isNotEmpty())
        val existing = decodeProfiles(writer.string(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON))
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        val normalized =
            imported.map { profile ->
                val id = profile.id.takeIf { it.isNotBlank() && existingIds.add(it) } ?: newUniqueId(existingIds)
                profile.copy(id = id, name = profile.name.trim())
            }
        writer.setString(
            EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON,
            encodeProfiles(existing + normalized),
        )
        writeProfileSettings(writer, normalized.first())
        writer.setString(EqualizerPreferenceKeys.SELECTED_PROFILE_ID, EqualizerProfileIds.stored(normalized.first().id))
        return imported.size
    }

    fun encodeProfileForExport(profile: EqProfile): String =
        EqualizerJson.json.encodeToString(EqProfilesPayload(listOf(profile)))

    fun encodeLevels(levelsMb: List<Int>): String = EqualizerJson.json.encodeToString(levelsMb)

    fun decodeLevels(raw: String?): List<Int> =
        raw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { EqualizerJson.json.decodeFromString<List<Int>>(it) }.getOrNull() }
            .orEmpty()

    fun encodeProfiles(profiles: List<EqProfile>): String =
        EqualizerJson.json.encodeToString(
            EqProfilesPayload(profiles.distinctBy(EqProfile::id).sortedBy { it.name.lowercase() }),
        )

    fun decodeProfiles(raw: String?): List<EqProfile> =
        raw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { EqualizerJson.json.decodeFromString<EqProfilesPayload>(it).profiles }.getOrNull() }
            .orEmpty()

    fun decodeImport(raw: String): List<EqProfile> =
        runCatching { EqualizerJson.json.decodeFromString<EqProfilesPayload>(raw).profiles }.getOrNull()
            ?: runCatching { EqualizerJson.json.decodeFromString<List<EqProfile>>(raw) }.getOrDefault(emptyList())

    private fun writeProfileSettings(
        writer: EqualizerPreferenceWriter,
        profile: EqProfile,
    ) {
        writer.setBoolean(EqualizerPreferenceKeys.ENABLED, true)
        writer.setString(EqualizerPreferenceKeys.BAND_LEVELS_MB, encodeLevels(profile.bandLevelsMb))
        writer.setInt(EqualizerPreferenceKeys.OUTPUT_GAIN_MB, profile.outputGainMb.coerceIn(-1_500, 1_500))
        writer.setBoolean(
            EqualizerPreferenceKeys.OUTPUT_GAIN_ENABLED,
            profile.outputGainEnabled ?: (profile.outputGainMb != 0),
        )
        writer.setInt(EqualizerPreferenceKeys.BASS_BOOST_STRENGTH, profile.bassBoostStrength.coerceIn(0, 1_000))
        writer.setBoolean(
            EqualizerPreferenceKeys.BASS_BOOST_ENABLED,
            profile.bassBoostEnabled ?: (profile.bassBoostStrength != 0),
        )
        writer.setInt(EqualizerPreferenceKeys.VIRTUALIZER_STRENGTH, profile.virtualizerStrength.coerceIn(0, 1_000))
        writer.setBoolean(
            EqualizerPreferenceKeys.VIRTUALIZER_ENABLED,
            profile.virtualizerEnabled ?: (profile.virtualizerStrength != 0),
        )
        writer.setBoolean(EqualizerPreferenceKeys.AUTO_HEADROOM_ENABLED, profile.autoHeadroomEnabled)
    }

    private fun uniqueProfileId(rawSavedProfiles: String?): String =
        newUniqueId(decodeProfiles(rawSavedProfiles).mapTo(mutableSetOf()) { it.id })

    private fun newUniqueId(existingIds: MutableSet<String>): String =
        generateSequence { UUID.randomUUID().toString() }.first(existingIds::add)
}
