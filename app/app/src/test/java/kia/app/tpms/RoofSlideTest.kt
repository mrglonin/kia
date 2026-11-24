package kia.app.tpms

import org.junit.Assert.*
import org.junit.Test

class RoofSlideTest {
    @Test fun firstPositionDuplicatesAndDisconnectDoNotInventTravel() {
        val slide = RoofSlide(null)
        slide.update(true, true, 1000)
        assertEquals(1f, slide.position(1000), 0f)
        assertFalse(slide.moving(1000))
        slide.update(false, true, 2000)
        slide.update(false, true, 2600)
        assertEquals(.5f, slide.position(2600), .001f)
        slide.update(null, true, 2800)
        assertFalse(slide.moving(2801))
        slide.update(true, true, 3000)
        assertEquals(1f, slide.position(3000), 0f)
    }

    @Test fun aReversalStartsFromTheDisplayedPanelAndRespectsDisabledAnimations() {
        val slide = RoofSlide(false)
        slide.update(true, true, 1000)
        val midpoint = slide.position(1600)
        slide.update(false, true, 1600)
        assertEquals(midpoint, slide.position(1600), .001f)
        assertEquals(0f, slide.position(2050), 0f)
        slide.update(true, false, 3000)
        assertFalse(slide.moving(3001))
        assertEquals(1f, slide.position(3001), 0f)
    }
}
