/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils.oem

import android.content.Context
import android.media.MediaRouter2
import android.os.Build
import androidx.annotation.RequiresApi

object SystemMediaControlResolver {
    fun canOpenMediaOutputSwitcher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun openMediaOutputSwitcher(context: Context): Boolean =
        if (canOpenMediaOutputSwitcher()) {
            runCatching { startNativeMediaDialogForAndroid14(context) }.getOrDefault(false)
        } else {
            false
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startNativeMediaDialogForAndroid14(context: Context): Boolean {
        val mediaRouter2 = MediaRouter2.getInstance(context)
        return mediaRouter2.showSystemOutputSwitcher()
    }
}
