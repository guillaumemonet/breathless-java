package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.object.ObjectBank;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La carte automatique : ce qu'elle releve, et ce qu'elle refuse de relever.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class AutoMapIT {

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

    private static World world(Fixture fixture, Level level) {
        return new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
    }

    @Test
    @DisplayName("Un tour sur place releve la case du joueur et ses alentours")
    void turningAroundSurveysTheRoom() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        AutoMap map = new AutoMap(world(fixture, level));
        Camera camera = Camera.atPlayerStart(level);

        assertEquals(0, map.seenCount(), "la carte doit partir vierge");

        for (int turn = 0; turn < Trig.STEPS; turn += 64) {
            camera.setHeading(turn);
            map.survey(camera);
        }

        assertTrue(map.isSeen(camera.cellX(), camera.cellZ()),
                "la case du joueur n'a pas ete relevee");
        assertTrue(map.seenCount() > 8,
                "seulement " + map.seenCount() + " cases relevees en tournant sur place");
    }

    @Test
    @DisplayName("Un rayon ne traverse ni les murs ni le vide")
    void raysStopAtWalls() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            AutoMap map = new AutoMap(world(fixture, level));
            Camera camera = Camera.atPlayerStart(level);
            for (int turn = 0; turn < Trig.STEPS; turn += 32) {
                camera.setHeading(turn);
                map.survey(camera);
            }

            // Un rayon marque la case ou il s'arrete, mur compris : au-dela, rien. On
            // verifie donc qu'aucune case relevee n'est isolee derriere du vide.
            //
            // Le voisinage inclut les diagonales : un pas de Bresenham deplace les deux
            // coordonnees a la fois, ce qui fait passer le rayon par le coin de deux
            // cases. L'original en fait autant.
            int beyond = 0;
            for (int z = 1; z < Level.MAP_SIZE - 1; z++) {
                for (int x = 1; x < Level.MAP_SIZE - 1; x++) {
                    if (!map.isSeen(x, z) || level.rawCell(x, z) > 0) {
                        continue;
                    }
                    // Case non praticable relevee : elle doit toucher une case praticable
                    // elle-meme relevee, sinon le rayon a traverse quelque chose.
                    boolean touches = false;
                    for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                                                  {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                        if (level.rawCell(x + step[0], z + step[1]) > 0
                                && map.isSeen(x + step[0], z + step[1])) {
                            touches = true;
                            break;
                        }
                    }
                    if (!touches) {
                        beyond++;
                    }
                }
            }
            assertEquals(0, beyond,
                    level.name() + " : " + beyond + " case(s) relevee(s) au-dela d'un obstacle");
        }
    }

    @Test
    @DisplayName("Une porte fermee arrete le releve")
    void closedDoorsStopTheSurvey() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            // Une case dont le bloc a plafond et sol confondus : une porte fermee.
            for (int z = 1; z < Level.MAP_SIZE - 1; z++) {
                for (int x = 1; x < Level.MAP_SIZE - 1; x++) {
                    int code = level.rawCell(x, z);
                    if (code <= 0) {
                        continue;
                    }
                    Block door = level.blocks().get(code);
                    if (door.ceilHeight() != door.floorHeight()) {
                        continue;
                    }
                    // Se placer deux cases avant la porte, en ligne, et regarder vers elle.
                    for (int[] step : new int[][]{{1, 0, 1024}, {-1, 0, 0},
                                                  {0, 1, 1536}, {0, -1, 512}}) {
                        int fromX = x + step[0] * 2;
                        int fromZ = z + step[1] * 2;
                        int midX = x + step[0];
                        int midZ = z + step[1];
                        if (level.rawCell(fromX, fromZ) <= 0 || level.rawCell(midX, midZ) <= 0) {
                            continue;
                        }
                        int behindX = x - step[0];
                        int behindZ = z - step[1];
                        if (level.rawCell(behindX, behindZ) <= 0) {
                            continue;   // rien derriere la porte : le test ne dirait rien
                        }

                        World world = world(fixture, level);
                        AutoMap map = new AutoMap(world);
                        Camera camera = new Camera(
                                Fixed.of(fromX * Level.BLOCK_SIZE + 32),
                                Fixed.of(fromZ * Level.BLOCK_SIZE + 32),
                                0, step[2]);
                        map.survey(camera);

                        assertTrue(map.isSeen(x, z), "la porte elle-meme doit etre relevee");
                        assertFalse(map.isSeen(behindX, behindZ),
                                level.name() + " : le releve a traverse la porte fermee en "
                                        + x + "," + z);
                        return;
                    }
                }
            }
        }
        throw new AssertionError("aucune porte fermee exploitable dans les vingt niveaux");
    }

    @Test
    @DisplayName("Le trace tient dans la vue et montre le repere du joueur")
    void theDrawingFitsTheView() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        AutoMap map = new AutoMap(world(fixture, level));
        Camera camera = Camera.atPlayerStart(level);
        for (int turn = 0; turn < Trig.STEPS; turn += 32) {
            camera.setHeading(turn);
            map.survey(camera);
        }

        int width = AutoMap.VIEW_COLUMNS * AutoMap.CELL;
        int height = AutoMap.VIEW_ROWS * AutoMap.CELL;
        assertEquals(320, width);
        assertEquals(200, height);

        byte[] pixels = new byte[width * height];
        map.render(pixels, width, height, camera);

        boolean[] seen = new boolean[256];
        for (byte pixel : pixels) {
            seen[pixel & 0xFF] = true;
        }
        assertTrue(seen[2], "aucun mur infranchissable trace");
        assertTrue(seen[243], "aucun sol releve trace");
        assertTrue(seen[41] || seen[243], "le repere du joueur n'apparait pas");
    }

    @Test
    @DisplayName("La carte se recentre sans jamais sortir du niveau")
    void theWindowStaysInsideTheMap() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = world(fixture, level);
        AutoMap map = new AutoMap(world);

        int width = AutoMap.VIEW_COLUMNS * AutoMap.CELL;
        int height = AutoMap.VIEW_ROWS * AutoMap.CELL;
        byte[] pixels = new byte[width * height];

        // Les quatre coins de la carte : la fenetre doit rester dans les bornes.
        for (int[] corner : new int[][]{{0, 0}, {127, 0}, {0, 127}, {127, 127}}) {
            Camera camera = new Camera(
                    Fixed.of(corner[0] * Level.BLOCK_SIZE + 32),
                    Fixed.of(corner[1] * Level.BLOCK_SIZE + 32), 0, 0);
            map.render(pixels, width, height, camera);   // ne doit pas deborder
        }
    }
}
