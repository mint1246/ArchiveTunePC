package moe.rukamori.archivetune.downloads

import androidx.media3.exoplayer.offline.Download
import moe.rukamori.archivetune.sdk.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStateMappingTest {
    @Test
    fun mapsMedia3StatesWithoutDependingOnTheirNumericValues() {
        val cases = mapOf(
            Download.STATE_QUEUED to DownloadState.QUEUED,
            Download.STATE_DOWNLOADING to DownloadState.DOWNLOADING,
            Download.STATE_RESTARTING to DownloadState.RESTARTING,
            Download.STATE_COMPLETED to DownloadState.COMPLETED,
            Download.STATE_FAILED to DownloadState.FAILED,
            Download.STATE_REMOVING to DownloadState.REMOVING,
            Download.STATE_STOPPED to DownloadState.STOPPED,
            -1 to DownloadState.UNKNOWN,
        )
        cases.forEach { (mediaState, expected) -> assertEquals(expected, downloadState(mediaState)) }
    }
}
