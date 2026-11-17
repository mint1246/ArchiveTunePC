package example

import moe.rukamori.archivetune.equalizer.EqualizerPreferenceWriter
import moe.rukamori.archivetune.equalizer.EqualizerPreferences
import moe.rukamori.archivetune.equalizer.EqualizerProfileIds

private class MemoryPreferences : EqualizerPreferenceWriter {
    private val values = mutableMapOf<String, Any>()
    override fun string(key: String): String? = values[key] as? String
    override fun boolean(key: String): Boolean? = values[key] as? Boolean
    override fun int(key: String): Int? = values[key] as? Int
    override fun setString(key: String, value: String) { values[key] = value }
    override fun setBoolean(key: String, value: Boolean) { values[key] = value }
    override fun setInt(key: String, value: Int) { values[key] = value }
}

fun main() {
    val preferences = MemoryPreferences()
    EqualizerPreferences.updateBandLevels(preferences, listOf(-100, 0, 100))
    val stored = EqualizerPreferences.read(preferences)
    check(stored.selectedProfileId == EqualizerProfileIds.MANUAL)
    check(stored.settings.bandLevelsMb == listOf(-100, 0, 100))
    println("Read equalizer settings without Android UI")
}
