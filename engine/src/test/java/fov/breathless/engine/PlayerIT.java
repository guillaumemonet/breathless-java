package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deplacement du joueur dans les niveaux d'origine.
 *
 * <p>La propriete qui compte est negative : quoi qu'on fasse aux commandes, le joueur ne
 * doit jamais se retrouver dans un mur ni sortir de la carte. Une erreur de collision ne
 * se voit pas forcement a l'oeil, mais elle se voit ici.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class PlayerIT {

    private record Fixture(List<Level> levels, TextureBank textures, GfxBank gfx) {
    }

    private static Fixture fixture() throws IOException {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");

        List<Level> levels = new ArrayList<>();
        TextureBank textures = null;
        GfxBank gfx = null;
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
                    default -> {
                        // Sans objet ici.
                    }
                }
            }
        }
        return new Fixture(levels, textures, gfx);
    }

    @Test
    @DisplayName("Quelles que soient les commandes, le joueur reste dans le monde jouable")
    void playerNeverEntersAWall() throws IOException {
        Fixture fixture = fixture();
        assertEquals(20, fixture.levels().size());

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx());
            Player player = new Player(world);
            Camera camera = Camera.atPlayerStart(level);
            PlayerInput input = new PlayerInput();

            // Graine fixe : un echec doit etre reproductible.
            Random random = new Random(level.name().hashCode());

            for (int tick = 0; tick < 3000; tick++) {
                if (tick % 25 == 0) {
                    input.forward = random.nextBoolean();
                    input.backward = random.nextBoolean();
                    input.left = random.nextBoolean();
                    input.right = random.nextBoolean();
                    input.strafe = random.nextBoolean();
                    input.run = random.nextBoolean();
                }
                player.update(camera, input);

                int cellX = camera.cellX();
                int cellZ = camera.cellZ();
                String where = level.name() + " au pas " + tick
                        + " en " + cellX + "," + cellZ;

                assertTrue(cellX >= 0 && cellX < Level.MAP_SIZE, where + " : hors carte en x");
                assertTrue(cellZ >= 0 && cellZ < Level.MAP_SIZE, where + " : hors carte en z");
                assertTrue(!level.isWall(cellX, cellZ), where + " : dans un mur");
                assertTrue(!level.isVoid(cellX, cellZ), where + " : dans le vide");
            }
        }
    }

    @Test
    @DisplayName("Le joueur avance, et plus vite en courant")
    void runningIsFasterThanWalking() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx());

        int walked = travel(world, level, false);
        int ran = travel(world, level, true);

        assertTrue(walked > 0, "le joueur n'a pas avance");
        assertTrue(ran > walked, "courir devrait aller plus loin (" + walked + " puis " + ran + ")");
    }

    /** Distance parcourue en ligne droite depuis le depart, en unites monde. */
    private static int travel(World world, Level level, boolean run) {
        Player player = new Player(world);
        Camera camera = Camera.atPlayerStart(level);
        PlayerInput input = new PlayerInput();
        input.forward = true;
        input.run = run;

        int startX = camera.x();
        int startZ = camera.z();
        for (int tick = 0; tick < 20; tick++) {
            player.update(camera, input);
        }
        int dx = (camera.x() - startX) >> 16;
        int dz = (camera.z() - startZ) >> 16;
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    @Test
    @DisplayName("La vitesse s'etablit progressivement : le mouvement a de l'inertie")
    void speedRampsUp() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx());

        Player player = new Player(world);
        Camera camera = Camera.atPlayerStart(level);
        PlayerInput input = new PlayerInput();
        input.forward = true;

        assertEquals(0, player.speed(), "le joueur demarre a l'arret");
        player.update(camera, input);
        int afterOne = player.speed();
        assertTrue(afterOne > 0, "le joueur doit accelerer");

        for (int tick = 0; tick < 10; tick++) {
            player.update(camera, input);
        }
        assertTrue(player.speed() >= afterOne, "la vitesse ne doit pas retomber");

        // Relachement : la vitesse redescend a zero. Il faut lui en laisser le temps,
        // la deceleration valant quatre par image pour une vitesse de marche de 64.
        input.forward = false;
        for (int tick = 0; tick < 40; tick++) {
            player.update(camera, input);
        }
        assertEquals(0, player.speed(), "le joueur doit finir par s'arreter");
    }

    @Test
    @DisplayName("Le joueur ne franchit pas une marche trop haute")
    void tallStepsBlockTheWay() throws IOException {
        Fixture fixture = fixture();

        // Cherche deux cases voisines dont la difference de hauteur de sol depasse la
        // limite franchissable, et verifie que le moteur refuse bien le passage.
        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx());
            Player player = new Player(world);

            for (int z = 1; z < Level.MAP_SIZE - 1; z++) {
                for (int x = 1; x < Level.MAP_SIZE - 1; x++) {
                    if (level.isVoid(x, z) || level.isWall(x, z)
                            || level.isVoid(x + 1, z) || level.isWall(x + 1, z)) {
                        continue;
                    }
                    Block from = level.blockAt(x, z);
                    Block to = level.blockAt(x + 1, z);
                    if (to.floorHeight() - from.floorHeight() <= Player.MAX_RISE) {
                        continue;
                    }

                    // Place le joueur au centre de la case basse, face a la marche.
                    Camera camera = new Camera(
                            Fixed.of(x * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                            Fixed.of(z * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                            from.floorHeight() + Camera.EYES_HEIGHT,
                            0);
                    PlayerInput input = new PlayerInput();
                    input.forward = true;
                    for (int tick = 0; tick < 60; tick++) {
                        player.update(camera, input);
                    }

                    assertEquals(x, camera.cellX(),
                            level.name() + " : marche de "
                                    + (to.floorHeight() - from.floorHeight())
                                    + " unites franchie en " + x + "," + z);
                    return;
                }
            }
        }
    }
}
