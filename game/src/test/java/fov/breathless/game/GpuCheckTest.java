package fov.breathless.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuCheckTest {

    @Test
    void reconnaitLesPucesIntegrees() {
        assertTrue(GpuCheck.looksIntegrated("AMD Radeon(TM) Graphics"));
        assertTrue(GpuCheck.looksIntegrated("Intel(R) UHD Graphics 620"));
        assertTrue(GpuCheck.looksIntegrated("Intel(R) Iris(R) Xe Graphics"));
        assertTrue(GpuCheck.looksIntegrated("Microsoft Basic Render Driver"));
    }

    @Test
    void laisseLesCartesDediees() {
        assertFalse(GpuCheck.looksIntegrated("NVIDIA GeForce RTX 3070 Laptop GPU/PCIe/SSE2"));
        assertFalse(GpuCheck.looksIntegrated("AMD Radeon RX 6800 XT"));
        assertFalse(GpuCheck.looksIntegrated(null));
    }

    @Test
    void avertitSurLaCarteIntegreeQuandUneAutreExiste() {
        List<String> cards = List.of("AMD Radeon(TM) Graphics", "NVIDIA GeForce RTX 3070 Laptop GPU");
        assertTrue(GpuCheck.hasOtherCard(cards));
        String warn = GpuCheck.report("AMD Radeon(TM) Graphics", cards);
        assertNotNull(warn);
        assertTrue(warn.contains("une autre"));
        assertNull(GpuCheck.report("NVIDIA GeForce RTX 3070 Laptop GPU/PCIe/SSE2", cards));
    }
}
