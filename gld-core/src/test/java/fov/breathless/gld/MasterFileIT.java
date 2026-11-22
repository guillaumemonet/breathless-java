package fov.breathless.gld;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Le fichier maitre BREATHLESS.GLD : noms des mondes et des niveaux. */
class MasterFileIT {

    @Test
    @DisplayName("Quatre mondes de cinq arenes, et la musique des titres")
    void readsWorldsAndLevels() throws IOException {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Path file = dir.resolve("BREATHLESS.GLD");
        Assumptions.assumeTrue(Files.isRegularFile(file), "fichier maitre absent : test ignore");

        MasterFile master = MasterFile.read(GldContainer.read(file));

        assertEquals("MUST", master.presentationMusic());
        assertEquals(4, master.worlds().size());
        for (MasterFile.World world : master.worlds()) {
            assertEquals(5, world.levels().size(), world.name());
        }
        assertEquals("FIRST WORLD - FIRST ARENA", master.title(1, 1));
        assertEquals("SECOND WORLD - THIRD ARENA", master.title(2, 3));
        assertEquals("LAST WORLD - LAST ARENA", master.title(4, 5));
    }
}
