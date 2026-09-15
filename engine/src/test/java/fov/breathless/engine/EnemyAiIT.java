package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.object.ObjectDefinition;
import fov.breathless.gld.texture.TextureBank;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comportement des ennemis dans les niveaux d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class EnemyAiIT {

    /** Hauteur libre minimale pour qu'un poste d'observation soit valable. */
    private static final int HEADROOM = 64;

    private record Fixture(List<Level> levels, TextureBank textures, GfxBank gfx, ObjectBank objects) {
    }

    private static Fixture fixture() throws IOException {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");

        List<Level> levels = new ArrayList<>();
        TextureBank textures = null;
        GfxBank gfx = null;
        ObjectBank objects = null;
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                switch (gld.payloadId()) {
                    case LevelReader.LGLD -> levels.add(LevelReader.read(gld));
                    case TextureBank.TGLD -> textures = TextureBank.read(gld);
                    case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                    case ObjectBank.OGLD -> objects = ObjectBank.read(gld);
                    default -> {
                        // Sans objet ici.
                    }
                }
            }
        }
        return new Fixture(levels, textures, gfx, objects);
    }

    /** Un face-a-face : la camera a quelques blocs d'un ennemi eveille, avec du plafond. */
    private record Standoff(World world, Simulation simulation, SceneObject enemy) {
    }

    private static Standoff standoff(Fixture fixture) {
        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            for (SceneObject enemy : world.objects()) {
                if (enemy.definition().objectType() != ObjectDefinition.TYPE_ENEMY
                        || enemy.isDormant()) {
                    continue;
                }
                int cellX = Math.floorDiv(enemy.x(), Level.BLOCK_SIZE);
                int cellZ = Math.floorDiv(enemy.z(), Level.BLOCK_SIZE);

                for (int[] offset : new int[][]{{0, 3}, {0, -3}, {3, 0}, {-3, 0}}) {
                    int x = cellX + offset[0];
                    int z = cellZ + offset[1];
                    if (x < 0 || z < 0 || x >= Level.MAP_SIZE || z >= Level.MAP_SIZE) {
                        continue;
                    }
                    if (level.isVoid(x, z) || level.isWall(x, z)) {
                        continue;
                    }
                    Block block = level.blockAt(x, z);
                    // Une case sans hauteur libre est une porte fermee : on ne peut pas
                    // s'y tenir, et un tir y mourrait contre le plafond.
                    if (block.ceilHeight() - block.floorHeight() < HEADROOM) {
                        continue;
                    }
                    Camera camera = new Camera(
                            Fixed.of(x * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                            Fixed.of(z * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                            block.floorHeight() + Camera.EYES_HEIGHT, 0);
                    return new Standoff(world, new Simulation(world, camera, fixture.objects()), enemy);
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("Un ennemi eveille se rapproche du joueur")
    void enemiesCloseIn() throws IOException {
        Standoff standoff = standoff(fixture());
        Assumptions.assumeTrue(standoff != null, "aucun face-a-face exploitable");

        Camera camera = standoff.simulation().camera();
        int before = distance(standoff.enemy(), camera);

        PlayerInput input = new PlayerInput();
        for (int tick = 0; tick < 100; tick++) {
            standoff.simulation().tick(input);
        }

        int after = distance(standoff.enemy(), camera);
        assertTrue(after < before,
                "l'ennemi devrait s'etre rapproche (" + before + " puis " + after + ")");
    }

    @Test
    @DisplayName("Un ennemi finit par blesser le joueur")
    void enemiesEventuallyHurt() throws IOException {
        Standoff standoff = standoff(fixture());
        Assumptions.assumeTrue(standoff != null, "aucun face-a-face exploitable");

        PlayerInput input = new PlayerInput();
        for (int tick = 0; tick < 600; tick++) {
            standoff.simulation().tick(input);
        }

        PlayerState state = standoff.simulation().state();
        assertTrue(state.shields() < PlayerState.MAX_SHIELDS || state.health() < PlayerState.MAX_HEALTH,
                "le joueur est ressorti indemne d'un face-a-face de douze secondes");
    }

    @Test
    @DisplayName("Un ennemi anime sa marche et ne quitte jamais le monde jouable")
    void enemiesWalkWithinTheWorld() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            Camera camera = Camera.atPlayerStart(level);
            Simulation simulation = new Simulation(world, camera, fixture.objects());

            // Reveille tout le monde pour solliciter l'IA au maximum.
            for (SceneObject object : world.objects()) {
                object.awaken();
            }

            PlayerInput input = new PlayerInput();
            for (int tick = 0; tick < 500; tick++) {
                simulation.tick(input);

                for (SceneObject object : world.objects()) {
                    if (object.definition().objectType() != ObjectDefinition.TYPE_ENEMY
                            || object.isCollected()) {
                        continue;
                    }
                    int cellX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
                    int cellZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);
                    String where = level.name() + " : " + object.definition().name()
                            + " en " + cellX + "," + cellZ + " au pas " + tick;

                    assertTrue(cellX >= 0 && cellX < Level.MAP_SIZE, where + " hors carte");
                    assertTrue(cellZ >= 0 && cellZ < Level.MAP_SIZE, where + " hors carte");
                    assertTrue(!level.isWall(cellX, cellZ), where + " dans un mur");
                    assertTrue(!level.isVoid(cellX, cellZ), where + " dans le vide");
                    assertTrue(object.animCounter() >= 0 && object.animCounter() < 16,
                            where + " : pose hors du cycle de marche");
                }
            }
        }
    }

    @Test
    @DisplayName("Les seize poses du cycle de marche defilent")
    void walkCycleAdvances() throws IOException {
        Standoff standoff = standoff(fixture());
        Assumptions.assumeTrue(standoff != null, "aucun face-a-face exploitable");

        boolean[] seen = new boolean[16];
        PlayerInput input = new PlayerInput();
        for (int tick = 0; tick < 600; tick++) {
            standoff.simulation().tick(input);
            seen[standoff.enemy().animCounter()] = true;
        }

        int count = 0;
        for (boolean pose : seen) {
            if (pose) {
                count++;
            }
        }
        assertEquals(16, count, "les seize poses devraient toutes avoir ete jouees");
    }

    private static int distance(SceneObject enemy, Camera camera) {
        int dx = enemy.x() - (camera.x() >> 16);
        int dz = enemy.z() - (camera.z() >> 16);
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }
}
