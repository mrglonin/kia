package kia.app.media.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import kia.app.protocol.adapter.MediaSourceKind;

public final class RealMediaSourceStatusTest {
    @Test
    public void parsesNestedFmAndAmFrames() {
        RealMediaSourceStatus fm = RealMediaSourceStatus.parse(new byte[]{
                (byte) 0xBB, 0x41, (byte) 0xA1, 0x0D, 0x7A,
                (byte) 0xFD, 0x08, 0x09, 0x02, 0x00, 101, 20, 0x00
        });
        assertEquals(MediaSourceKind.FM_RADIO, fm.kind);
        assertEquals("101.2", fm.frequency);
        assertTrue(fm.radio());

        RealMediaSourceStatus am = RealMediaSourceStatus.parse(new byte[]{
                (byte) 0xBB, 0x41, (byte) 0xA1, 0x0D, 0x7A,
                (byte) 0xFD, 0x08, 0x09, 0x09, 0x04, 0x06, 0x30, 0x00
        });
        assertEquals(MediaSourceKind.AM_RADIO, am.kind);
        assertEquals("1584", am.frequency);
    }

    @Test
    public void parsesEveryKnownNonRadioSourceWithoutTreatingItAsOff() {
        int[] rawSources = {0x0B, 0x16, 0x07, 0x0E, 0x23, 0x24, 0x25, 0x11};
        for (int raw : rawSources) {
            RealMediaSourceStatus status = RealMediaSourceStatus.parse(new byte[]{
                    (byte) 0xBB, 0x41, (byte) 0xA1, 0x08, 0x7A,
                    (byte) raw, 0x00, 0x00
            });
            assertFalse(status.off);
            assertFalse(status.radio());
        }
    }

    @Test
    public void recognizesOffAndRejectsTruncatedNestedFrame() {
        RealMediaSourceStatus off = RealMediaSourceStatus.parse(new byte[]{
                (byte) 0xBB, 0x41, (byte) 0xA1, 0x08, 0x7A,
                (byte) 0x81, 0x00, 0x00
        });
        assertTrue(off.off);
        assertNull(RealMediaSourceStatus.parse(new byte[]{
                (byte) 0xBB, 0x41, (byte) 0xA1, 0x09, 0x7A,
                (byte) 0xFD, 0x08, 0x09, 0x02
        }));
        assertNull(RealMediaSourceStatus.parse(new byte[]{
                (byte) 0xBB, 0x41, (byte) 0xA1, 0x06, 0x7A, 0x02
        }));
    }
}
