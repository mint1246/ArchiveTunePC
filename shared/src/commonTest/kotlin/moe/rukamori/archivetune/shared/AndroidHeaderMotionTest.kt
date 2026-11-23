package moe.rukamori.archivetune.shared

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalMaterial3Api::class)
class AndroidHeaderMotionTest {
    @Test fun floatingHeaderFollowsConsumedScrollWithoutConsumingTheListOrHidingAtItsBottomEdge() {
        val state = TopAppBarState(-126f, 0f, 0f)
        val behavior = AndroidAppBarScrollBehavior(state, null, null) { true }
        val connection = behavior.nestedScrollConnection
        assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, -50f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(-50f, state.heightOffset)
        connection.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput)
        assertEquals(-50f, state.heightOffset)
        connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput)
        assertEquals(0f, state.heightOffset)
        assertEquals(0f, state.contentOffset)
    }
}
