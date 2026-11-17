/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.equalizer

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.playback.EqProfile
import javax.inject.Inject

class ObserveEqualizerUseCase
    @Inject
    constructor(
        private val repository: EqualizerRepository,
    ) {
        operator fun invoke(): Flow<EqualizerConfiguration> =
            repository.observe().map { snapshot ->
                val capabilities = snapshot.capabilities
                val normalizedLevels =
                    resampleLevels(
                        levelsMb = snapshot.settings.bandLevelsMb,
                        targetCount = capabilities?.bandCount ?: snapshot.settings.bandLevelsMb.size,
                    )
                EqualizerConfiguration(
                    controlMode = snapshot.controlMode,
                    selectedProfileId = snapshot.selectedProfileId,
                    settings = snapshot.settings.copy(bandLevelsMb = normalizedLevels),
                    capabilities = capabilities,
                    profiles = snapshot.profiles,
                )
            }
    }

class UpdateEqualizerUseCase
    @Inject
    constructor(
        private val repository: EqualizerRepository,
    ) {
        suspend fun setEnabled(enabled: Boolean) = repository.setEnabled(enabled)

        suspend fun setControlMode(mode: EqualizerControlMode) = repository.setControlMode(mode)

        fun adjustToneBands(
            tone: EqualizerTone,
            targetLevelMb: Int,
            configuration: EqualizerConfiguration,
        ): List<Int> = moe.rukamori.archivetune.equalizer.adjustToneBands(tone, targetLevelMb, configuration)

        suspend fun updateTone(
            tone: EqualizerTone,
            targetLevelMb: Int,
            configuration: EqualizerConfiguration,
        ) {
            repository.updateBandLevels(adjustToneBands(tone, targetLevelMb, configuration))
        }

        suspend fun updateBand(
            index: Int,
            levelMb: Int,
            configuration: EqualizerConfiguration,
        ) {
            val capabilities = configuration.capabilities ?: return
            if (index !in 0 until capabilities.bandCount) return
            val levels = resampleLevels(configuration.settings.bandLevelsMb, capabilities.bandCount).toMutableList()
            levels[index] = levelMb.coerceIn(capabilities.minBandLevelMb, capabilities.maxBandLevelMb)
            repository.updateBandLevels(levels)
        }

        suspend fun resetBands(configuration: EqualizerConfiguration) {
            val count = configuration.capabilities?.bandCount ?: return
            repository.updateBandLevels(List(count) { 0 })
        }

        suspend fun updateBandLevels(levelsMb: List<Int>) = repository.updateBandLevels(levelsMb)

        suspend fun setOutputGainEnabled(enabled: Boolean) = repository.setOutputGainEnabled(enabled)

        suspend fun setOutputGainMb(value: Int) = repository.setOutputGainMb(value)

        suspend fun setBassBoostEnabled(enabled: Boolean) = repository.setBassBoostEnabled(enabled)

        suspend fun setBassBoostStrength(value: Int) = repository.setBassBoostStrength(value)

        suspend fun setVirtualizerEnabled(enabled: Boolean) = repository.setVirtualizerEnabled(enabled)

        suspend fun setVirtualizerStrength(value: Int) = repository.setVirtualizerStrength(value)

        suspend fun setAutoHeadroomEnabled(enabled: Boolean) = repository.setAutoHeadroomEnabled(enabled)
    }

class ManageEqualizerProfilesUseCase
    @Inject
    constructor(
        private val repository: EqualizerRepository,
    ) {
        suspend fun save(
            name: String,
            configuration: EqualizerConfiguration,
        ): EqProfile =
            repository.saveProfile(
                name = name.trim(),
                centerFrequenciesHz = configuration.capabilities?.centerFreqHz.orEmpty(),
                settings = configuration.settings,
            )

        suspend fun apply(profile: EqProfile) = repository.applyProfile(profile)

        suspend fun delete(profileId: String) = repository.deleteProfile(profileId)

        suspend fun import(uri: Uri): Int = repository.importProfiles(uri)

        suspend fun export(
            uri: Uri,
            profile: EqProfile,
        ) = repository.exportProfile(uri, profile)
    }

class ApplyEqualizerPresetUseCase
    @Inject
    constructor(
        private val repository: EqualizerRepository,
    ) {
        operator fun invoke(presetId: String): Boolean =
            when {
                presetId == "flat" -> {
                    repository.applyFlatPreset()
                }

                presetId.startsWith("system:") -> {
                    presetId.removePrefix("system:").toIntOrNull()?.let(repository::applySystemPreset)
                        ?: false
                }

                else -> {
                    false
                }
            }
    }
