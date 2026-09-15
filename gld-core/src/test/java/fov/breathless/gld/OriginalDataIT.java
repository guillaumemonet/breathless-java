package fov.breathless.gld;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de non-regression sur les donnees d'origine du jeu.
 *
 * <p>Les fichiers .GLD ne sont pas versionnes avec le code : le test est ignore
 * s'ils sont absents. Indiquer leur emplacement avec
 * {@code -Dbreathless.data=<chemin>} ; a defaut, {@code ../Breathless} est essaye.
 */
class OriginalDataIT {

    private static Path dataDir() {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null)
                ? Path.of(configured)
                : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");
        return dir;
    }

    private static List<Path> gldFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("Tous les .GLD d'origine s'ouvrent et portent une ID connue")
    void everyOriginalFileOpens() throws IOException {
        Path dir = dataDir();
        List<Path> files = gldFiles(dir);
        assertEquals(25, files.size(), "le jeu complet compte 25 fichiers .GLD");

        int levels = 0;
        for (Path file : files) {
            GldContainer gld = GldContainer.read(file);
            String id = gld.payloadId();
            assertTrue(List.of("MGLD", "LGLD", "TGLD", "OGLD", "GGLD", "SGLD").contains(id),
                    file.getFileName() + " : ID inattendue " + id);
            if ("LGLD".equals(id)) {
                levels++;
                assertTrue(gld.isCompressed(), file.getFileName() + " : un niveau devrait etre compresse");
                // La carte seule occupe 128 * 128 * 2 octets ; un niveau est forcement plus gros.
                assertTrue(gld.size() > 128 * 128 * 2,
                        file.getFileName() + " : niveau anormalement court (" + gld.size() + " octets)");
            }
        }
        assertEquals(20, levels, "le jeu compte 20 niveaux");
    }

    @Test
    @DisplayName("Les fichiers de ressources partages ne sont pas compresses")
    void sharedResourcesAreStoredPlain() throws IOException {
        Path dir = dataDir();
        for (String name : List.of("BLES0001.GLD", "BLES0002.GLD", "BLES0003.GLD", "BLES0004.GLD")) {
            Path file = dir.resolve(name);
            Assumptions.assumeTrue(Files.exists(file), name + " absent");
            GldContainer gld = GldContainer.read(file);
            assertEquals(Files.size(file), gld.size(), name + " : taille incoherente");
        }
    }
}
