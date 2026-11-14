package com.ccui.workbench;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ReplacementScaleTest {
    @Test public void usesOnlyRequiredScale() {
        assertEquals(1, Workspace.scaleFactor(1024, 1024, 100, 100, 50, 50));
        assertEquals(2, Workspace.scaleFactor(1024, 1024, 100, 100, 200, 150));
        assertEquals(3, Workspace.scaleFactor(1024, 1024, 100, 100, 201, 150));
    }

    @Test public void capsScaleByDimensionsAndArea() {
        assertEquals(2, Workspace.scaleFactor(3000, 1000, 10, 10, 1000, 1000));
        assertEquals(1, Workspace.scaleFactor(4096, 4096, 10, 10, 1000, 1000));
        assertEquals(1, Workspace.scaleFactor(8192, 8192, 10, 10, 1000, 1000));
    }
}
