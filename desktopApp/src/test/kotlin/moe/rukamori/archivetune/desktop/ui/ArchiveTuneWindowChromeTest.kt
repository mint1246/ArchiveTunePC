package moe.rukamori.archivetune.desktop.ui

import androidx.compose.ui.geometry.Offset
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveTuneWindowChromeTest {
    @Test
    fun `west resize keeps opposite edge fixed when minimum width is reached`() {
        val start = Rectangle(100, 80, 900, 700)

        val resized = resizedWindowBounds(
            start = start,
            delta = Point(700, 0),
            minimum = Dimension(640, 480),
            direction = ResizeDirection.WEST,
        )

        assertEquals(Rectangle(360, 80, 640, 700), resized)
        assertEquals(start.x + start.width, resized.x + resized.width)
    }

    @Test
    fun `north west resize clamps both dimensions around the opposite corner`() {
        val start = Rectangle(-1200, -300, 900, 700)

        val resized = resizedWindowBounds(
            start = start,
            delta = Point(800, 600),
            minimum = Dimension(640, 480),
            direction = ResizeDirection.NORTH_WEST,
        )

        assertEquals(Rectangle(-940, -80, 640, 480), resized)
        assertEquals(start.x + start.width, resized.x + resized.width)
        assertEquals(start.y + start.height, resized.y + resized.height)
    }

    @Test
    fun `south east resize follows pointer delta`() {
        val resized = resizedWindowBounds(
            start = Rectangle(40, 30, 800, 600),
            delta = Point(125, 75),
            minimum = Dimension(640, 480),
            direction = ResizeDirection.SOUTH_EAST,
        )

        assertEquals(Rectangle(40, 30, 925, 675), resized)
    }

    @Test
    fun `maximized drag anchor works on a monitor left of the primary display`() {
        val maximized = Rectangle(-1920, 0, 1920, 1080)

        val anchor = windowDragRestoreAnchor(maximized, Point(-960, 24))
        val restoredLocation = restoredDragLocation(
            pointer = Point(-900, 80),
            restoredSize = Dimension(1000, 700),
            horizontalFraction = anchor.horizontalFraction,
            pointerOffsetY = anchor.pointerOffsetY,
        )

        assertEquals(0.5, anchor.horizontalFraction)
        assertEquals(24, anchor.pointerOffsetY)
        assertEquals(Point(-1400, 56), restoredLocation)
    }

    @Test
    fun `restore anchor clamps cursor races outside the maximized bounds`() {
        val maximized = Rectangle(0, 0, 1920, 1080)

        val left = windowDragRestoreAnchor(maximized, Point(-30, -5))
        val right = windowDragRestoreAnchor(maximized, Point(2000, 20))

        assertEquals(0.0, left.horizontalFraction)
        assertEquals(0, left.pointerOffsetY)
        assertEquals(1.0, right.horizontalFraction)
    }

    @Test
    fun `maximized startup fallback restores to a visibly floating size`() {
        assertEquals(
            Dimension(1024, 576),
            fallbackRestoredSize(Rectangle(-1536, 278, 1280, 720), Dimension(640, 480)),
        )
    }

    @Test
    fun `fallback restore respects the minimum without exceeding the monitor`() {
        assertEquals(
            Dimension(900, 700),
            fallbackRestoredSize(Rectangle(0, 0, 1000, 800), Dimension(900, 700)),
        )
        assertEquals(
            Dimension(600, 400),
            fallbackRestoredSize(Rectangle(0, 0, 600, 400), Dimension(640, 480)),
        )
    }

    @Test
    fun `startup restore fallback is centered on negative-origin monitor`() {
        assertEquals(
            Rectangle(-1408, 350, 1024, 576),
            fallbackRestoredBounds(Rectangle(-1536, 278, 1280, 720), Dimension(640, 480)),
        )
    }

    @Test
    fun `fallback pointer coordinates include drag region offset and negative window origin`() {
        val draggedAreaOrigin = Offset(240f, 36f)
        val dragStart = Offset(80f, 12f)
        assertEquals(
            Point(-1280, 56),
            windowPointFromPixels(
                start = Point(-1440, 24),
                pixels = draggedAreaOrigin + dragStart,
                scaleX = 2.0,
                scaleY = 1.5,
            ),
        )
    }

    @Test
    fun `fractional scale accumulates tiny drag movements without truncating each event`() {
        val cumulativePixels = (0 until 9).fold(Offset.Zero) { total, _ ->
            total + Offset(0.5f, 1f)
        }
        assertEquals(
            Point(203, 106),
            windowPointFromPixels(Point(200, 100), cumulativePixels, scaleX = 1.5, scaleY = 1.5),
        )
    }

    @Test
    fun `fixed physical window size converts to destination monitor dp`() {
        assertEquals(
            800f,
            physicalPixelsToDpSize(1200, 900, scaleX = 1.5, scaleY = 1.5).width.value,
        )
        assertEquals(
            600f,
            physicalPixelsToDpSize(1200, 900, scaleX = 1.5, scaleY = 1.5).height.value,
        )
    }

}
