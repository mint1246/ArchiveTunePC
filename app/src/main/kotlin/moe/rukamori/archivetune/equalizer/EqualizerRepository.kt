/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.equalizer

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.EqCapabilities
import moe.rukamori.archivetune.playback.EqProfile
import moe.rukamori.archivetune.playback.EqSettings
import moe.rukamori.archivetune.playback.EqualizerPlaybackController
import moe.rukamori.archivetune.utils.dataStore
import javax.inject.Inject
import javax.inject.Singleton

data class EqualizerRepositorySnapshot(
    val controlMode: EqualizerControlMode,
    val selectedProfileId: String,
    val settings: EqSettings,
    val capabilities: EqCapabilities?,
    val profiles: List<EqProfile>,
)

/**
 * Android DataStore adapter for the headless equalizer preferences contract.
 *
 * All defaults, bounds, serialized names, profile JSON and selection rules live in
 * [EqualizerPreferences]; this class only maps string keys to typed DataStore keys and owns the
 * platform file and playback-controller seams.
 */
@Singleton
class EqualizerRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playbackController: EqualizerPlaybackController,
    ) {
        fun observe(): Flow<EqualizerRepositorySnapshot> =
            combine(
                context.dataStore.data.map { preferences ->
                    EqualizerPreferences.read(DataStoreReader(preferences))
                },
                playbackController.capabilities,
            ) { stored, capabilities ->
                EqualizerRepositorySnapshot(
                    controlMode = stored.controlMode,
                    selectedProfileId = stored.selectedProfileId,
                    settings = stored.settings,
                    capabilities = capabilities,
                    profiles = stored.profiles,
                )
            }.flowOn(Dispatchers.IO)

        suspend fun setEnabled(enabled: Boolean) = edit { EqualizerPreferences.setEnabled(it, enabled) }

        suspend fun setControlMode(mode: EqualizerControlMode) = edit { EqualizerPreferences.setControlMode(it, mode) }

        suspend fun updateBandLevels(levelsMb: List<Int>) = edit { EqualizerPreferences.updateBandLevels(it, levelsMb) }

        suspend fun setOutputGainEnabled(enabled: Boolean) =
            edit { EqualizerPreferences.setOutputGainEnabled(it, enabled) }

        suspend fun setOutputGainMb(gainMb: Int) = edit { EqualizerPreferences.setOutputGainMb(it, gainMb) }

        suspend fun setBassBoostEnabled(enabled: Boolean) =
            edit { EqualizerPreferences.setBassBoostEnabled(it, enabled) }

        suspend fun setBassBoostStrength(strength: Int) =
            edit { EqualizerPreferences.setBassBoostStrength(it, strength) }

        suspend fun setVirtualizerEnabled(enabled: Boolean) =
            edit { EqualizerPreferences.setVirtualizerEnabled(it, enabled) }

        suspend fun setVirtualizerStrength(strength: Int) =
            edit { EqualizerPreferences.setVirtualizerStrength(it, strength) }

        suspend fun setAutoHeadroomEnabled(enabled: Boolean) =
            edit { EqualizerPreferences.setAutoHeadroomEnabled(it, enabled) }

        suspend fun applyProfile(profile: EqProfile) = edit { EqualizerPreferences.applyProfile(it, profile) }

        suspend fun saveProfile(
            name: String,
            centerFrequenciesHz: List<Int>,
            settings: EqSettings,
        ): EqProfile {
            var saved: EqProfile? = null
            edit { saved = EqualizerPreferences.saveProfile(it, name, centerFrequenciesHz, settings) }
            return requireNotNull(saved)
        }

        suspend fun deleteProfile(profileId: String) = edit { EqualizerPreferences.deleteProfile(it, profileId) }

        suspend fun importProfiles(uri: Uri): Int =
            withContext(Dispatchers.IO) {
                val raw =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Unable to open equalizer profile")
                var imported = 0
                edit { imported = EqualizerPreferences.importProfiles(it, raw) }
                imported
            }

        suspend fun exportProfile(
            uri: Uri,
            profile: EqProfile,
        ) = withContext(Dispatchers.IO) {
            val raw = EqualizerPreferences.encodeProfileForExport(profile)
            val output = context.contentResolver.openOutputStream(uri) ?: error("Unable to open equalizer profile")
            output.bufferedWriter().use { it.write(raw) }
        }

        fun applyFlatPreset(): Boolean = playbackController.applyFlatPreset()

        fun applySystemPreset(index: Int): Boolean = playbackController.applySystemPreset(index)

        private suspend fun edit(block: (EqualizerPreferenceWriter) -> Unit) =
            withContext(Dispatchers.IO) {
                context.dataStore.edit { preferences -> block(DataStoreWriter(preferences)) }
            }
    }

/** Maps the SDK's stable string keys onto Android DataStore typed keys. */
private class DataStoreReader(
    private val preferences: Preferences,
) : EqualizerPreferenceReader {
    override fun string(key: String): String? = preferences[stringPreferencesKey(key)]

    override fun boolean(key: String): Boolean? = preferences[booleanPreferencesKey(key)]

    override fun int(key: String): Int? = preferences[intPreferencesKey(key)]
}

private class DataStoreWriter(
    private val preferences: MutablePreferences,
) : EqualizerPreferenceWriter {
    override fun string(key: String): String? = preferences[stringPreferencesKey(key)]

    override fun boolean(key: String): Boolean? = preferences[booleanPreferencesKey(key)]

    override fun int(key: String): Int? = preferences[intPreferencesKey(key)]

    override fun setString(key: String, value: String) {
        preferences[stringPreferencesKey(key)] = value
    }

    override fun setBoolean(key: String, value: Boolean) {
        preferences[booleanPreferencesKey(key)] = value
    }

    override fun setInt(key: String, value: Int) {
        preferences[intPreferencesKey(key)] = value
    }
}
