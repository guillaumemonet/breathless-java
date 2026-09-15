package fov.breathless.gld.level;

import fov.breathless.gld.GldContainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analyse des vingt niveaux d'origine.
 *
 * <p>Le parseur recoupe deja lui-meme la longueur annoncee dans l'en-tete avec la
 * position atteinte apres la carte : le seul fait qu'il ne leve pas d'exception sur
 * les vingt fichiers est une validation a l'octet pres. Les assertions ci-dessous
 * verifient en plus que le contenu a du sens pour un jeu jouable.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class LevelReaderIT {

    private static List<Level> levels() throws IOException {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");

        List<Level> levels = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                GldContainer gld = GldContainer.read(file);
                if (LevelReader.LGLD.equals(gld.payloadId())) {
                    levels.add(LevelReader.read(gld));
                }
            }
        }
        return levels;
    }

    @Test
    @DisplayName("Les vingt niveaux s'analysent sans incoherence structurelle")
    void allLevelsParse() throws IOException {
        assertEquals(20, levels().size());
    }

    @Test
    @DisplayName("Chaque niveau est jouable : depart, decor, textures, objets")
    void everyLevelIsPlayable() throws IOException {
        for (Level level : levels()) {
            String id = level.name();

            assertNotNull(level.playerStart(), id + " : pas de position de depart");
            assertFalse(level.blocks().isEmpty(), id + " : aucun bloc");
            assertFalse(level.edges().isEmpty(), id + " : aucune arete");
            assertFalse(level.textureNames().isEmpty(), id + " : aucune texture");
            assertFalse(level.objects().isEmpty(), id + " : aucun objet place");
            assertFalse(level.loadingPicture().isEmpty(), id + " : pas d'image de chargement");

            // Les limites du moteur d'origine, definies dans Sorgenti/TMap.i.
            assertTrue(level.blocks().size() <= 500, id + " : trop de blocs");
            assertTrue(level.edges().size() <= 400, id + " : trop d'aretes");
            assertTrue(level.textureNames().size() <= 60, id + " : trop de textures");
            assertTrue(level.objects().size() < 256, id + " : trop d'objets");
        }
    }

    @Test
    @DisplayName("Le joueur demarre au centre d'une case praticable")
    void playerStartsOnWalkableGround() throws IOException {
        for (Level level : levels()) {
            PlayerStart start = level.playerStart();
            int x = Math.floorDiv(start.x(), Level.BLOCK_SIZE);
            int z = Math.floorDiv(start.z(), Level.BLOCK_SIZE);

            assertFalse(level.isVoid(x, z), level.name() + " : depart dans le vide");
            assertFalse(level.isWall(x, z), level.name() + " : depart dans un mur");

            // Les departs sont poses au centre des cases de 64 unites.
            assertEquals(Level.BLOCK_SIZE / 2, Math.floorMod(start.x(), Level.BLOCK_SIZE),
                    level.name() + " : depart non centre en x");
            assertEquals(Level.BLOCK_SIZE / 2, Math.floorMod(start.z(), Level.BLOCK_SIZE),
                    level.name() + " : depart non centre en z");
        }
    }

    @Test
    @DisplayName("Les caps sont exprimes dans le repere 2048 pas par tour, sur huit directions")
    void headingsUseEngineScale() throws IOException {
        for (Level level : levels()) {
            int heading = level.playerStart().heading();
            assertTrue(heading >= 0 && heading < 2048,
                    level.name() + " : cap hors bornes (" + heading + ")");
            // L'editeur stocke un cap 0..7 decale de huit bits.
            assertEquals(0, heading % 256,
                    level.name() + " : cap " + heading + " hors des huit directions");
        }
    }

    @Test
    @DisplayName("Le monde jouable est clos : aucune case praticable en bordure de carte")
    void playableAreaIsEnclosed() throws IOException {
        for (Level level : levels()) {
            for (int i = 0; i < Level.MAP_SIZE; i++) {
                assertBorderIsSealed(level, i, 0);
                assertBorderIsSealed(level, i, Level.MAP_SIZE - 1);
                assertBorderIsSealed(level, 0, i);
                assertBorderIsSealed(level, Level.MAP_SIZE - 1, i);
            }
        }
    }

    /**
     * Le raycaster ne teste aucune borne : il compte sur le fait qu'un mur arrete
     * toujours le rayon avant la bordure. Une case praticable sur le bord signifierait
     * un debordement possible.
     */
    private static void assertBorderIsSealed(Level level, int x, int y) {
        assertTrue(level.isVoid(x, y) || level.isWall(x, y),
                level.name() + " : case praticable en bordure (" + x + "," + y + ")");
    }
}
