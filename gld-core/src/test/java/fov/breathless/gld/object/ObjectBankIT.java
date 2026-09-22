package fov.breathless.gld.object;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.level.MapObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lecture de la banque d'objets d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class ObjectBankIT {

    private static Path dataDir() {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");
        return dir;
    }

    private static List<Path> gldFiles() throws IOException {
        try (var stream = Files.list(dataDir())) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList();
        }
    }

    private static ObjectBank bank() throws IOException {
        for (Path file : gldFiles()) {
            GldContainer gld = GldContainer.read(file);
            if (ObjectBank.OGLD.equals(gld.payloadId())) {
                return ObjectBank.read(gld);
            }
        }
        throw new AssertionError("Aucune banque d'objets dans les donnees d'origine");
    }

    @Test
    @DisplayName("Tous les objets se lisent, images et segments compris")
    void everyObjectIsReadable() throws IOException {
        ObjectBank bank = bank();
        assertFalse(bank.objects().isEmpty());

        for (ObjectDefinition object : bank.objects().values()) {
            String id = object.name();
            assertFalse(object.frames().isEmpty(), id + " : aucune image");

            for (ObjectFrame frame : object.frames()) {
                assertTrue(frame.width() > 0 && frame.height() > 0,
                        id + " : dimensions nulles");
                assertTrue(frame.pixelHeight() >= frame.height(),
                        id + " : hauteur de pixels incoherente");

                for (int x = 0; x < frame.width(); x++) {
                    for (ObjectFrame.Segment segment : frame.column(x)) {
                        assertTrue(segment.top() >= 0, id + " : segment a ligne negative");
                        assertTrue(segment.length() > 0, id + " : segment vide");
                        assertTrue(segment.top() + segment.length() <= frame.pixelHeight(),
                                id + " : segment hors de l'image");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Un ennemi porte huit orientations de seize poses")
    void enemiesAreDirectional() throws IOException {
        ObjectBank bank = bank();

        List<ObjectDefinition> enemies = new ArrayList<>();
        for (ObjectDefinition object : bank.objects().values()) {
            if (object.objectType() == ObjectDefinition.TYPE_ENEMY) {
                enemies.add(object);
            }
        }
        assertFalse(enemies.isEmpty(), "aucun ennemi dans la banque");

        for (ObjectDefinition enemy : enemies) {
            assertTrue(enemy.isDirectional(),
                    enemy.name() + " : un ennemi devrait avoir une animation directionnelle");
            // Huit orientations de seize poses, plus deux images d'etat.
            assertTrue(enemy.frames().size() >= 8 * 16,
                    enemy.name() + " : seulement " + enemy.frames().size() + " images");
        }
    }

    @Test
    @DisplayName("Les objets nommes par les niveaux existent tous dans la banque")
    void levelsOnlyReferenceExistingObjects() throws IOException {
        ObjectBank bank = bank();

        int checked = 0;
        int levels = 0;
        for (Path file : gldFiles()) {
            GldContainer gld = GldContainer.read(file);
            if (!LevelReader.LGLD.equals(gld.payloadId())) {
                continue;
            }
            Level level = LevelReader.read(gld);
            levels++;
            for (String name : level.objectNames()) {
                assertNotNull(bank.get(name),
                        level.name() + " reclame l'objet absent " + name);
                checked++;
            }
            // Et chaque objet pose designe bien une entree de cette table.
            for (MapObject placed : level.objects()) {
                assertFalse(level.objectName(placed.objectIndex()).isEmpty(),
                        level.name() + " : objet pose sans nom");
            }
        }
        assertEquals(20, levels);
        assertTrue(checked > 0, "aucune reference d'objet verifiee");
    }

    @Test
    @DisplayName("Une image citee deux fois n'est lue qu'une fois, et l'instance est partagee")
    void repeatedFramesShareOneInstance() throws IOException {
        ObjectBank bank = bank();

        // Le moteur d'origine compare les *pointeurs* de la liste d'images pour savoir ou
        // s'arrete une animation. Lire chaque entree separement rendrait ces comparaisons
        // toujours fausses : il faut que deux entrees egales donnent la meme instance.
        boolean sawRepeat = false;
        for (ObjectDefinition object : bank.objects().values()) {
            List<ObjectFrame> frames = object.frames();
            IdentityHashMap<ObjectFrame, Integer> distinct = new IdentityHashMap<>();
            for (ObjectFrame frame : frames) {
                distinct.putIfAbsent(frame, distinct.size());
            }
            if (distinct.size() < frames.size()) {
                sawRepeat = true;
            }
        }
        assertTrue(sawRepeat,
                "aucune image repetee : les instances ne sont donc pas partagees");
    }

    @Test
    @DisplayName("Cinq des douze ennemis n'ont pas d'animation de chute et explosent")
    void someEnemiesHaveNoFallAnimation() throws IOException {
        ObjectBank bank = bank();

        // DestroyEnemy : la derniere image de tir egale la premiere de chute veut dire
        // qu'il n'y a pas de chute du tout, et que l'ennemi explose sur place.
        List<String> falling = new ArrayList<>();
        List<String> exploding = new ArrayList<>();
        for (ObjectDefinition enemy : bank.objects().values()) {
            if (enemy.objectType() != ObjectDefinition.TYPE_ENEMY) {
                continue;
            }
            List<ObjectFrame> frames = enemy.frames();
            assertTrue(frames.size() > 42, enemy.name() + " : trop peu d'images");
            if (frames.get(41) == frames.get(42)) {
                exploding.add(enemy.name());
            } else {
                falling.add(enemy.name());
            }
        }

        assertEquals(Set.of("WALK", "EYE1", "SKUL", "TERM", "SPID"), Set.copyOf(exploding),
                "ce sont ces cinq ennemis qui explosent plutot que de tomber");
        assertEquals(7, falling.size(), "les sept autres tombent");

        // Et la chute s'arrete des que l'image ne change plus : deux images pour les uns,
        // trois pour les autres — jamais un compte fixe.
        for (String name : falling) {
            List<ObjectFrame> frames = bank.get(name).frames();
            int last = 42;
            while (last < 44 && frames.get(last) != frames.get(last + 1)) {
                last++;
            }
            assertTrue(last == 43 || last == 44,
                    name + " : chute de longueur inattendue (42.." + last + ")");
        }
    }
}
