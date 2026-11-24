package kia.app.adas;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AdasLaneWarningTest {
    @Test
    public void mapsGenericLdwToTheSideReportedByTeyes() {
        assertEquals("Выезд влево", TeyesAdasFrontEvent.warningText(0x8, 1));
        assertEquals("Выезд вправо", TeyesAdasFrontEvent.warningText(0x8, 2));
        assertEquals("Выезд из полосы", TeyesAdasFrontEvent.warningText(0x8, null));
    }

    @Test
    public void ignoresLaneStateWithoutWarningAndHonorsExplicitSide() {
        assertEquals("Предупреждений о выезде нет", TeyesAdasFrontEvent.warningText(0x1, 1));
        assertEquals("Выезд вправо", TeyesAdasFrontEvent.warningText(0x4008, 1));
        assertEquals("Наезд на левую и правую линии", TeyesAdasFrontEvent.warningText(0x6000, null));
    }
}
