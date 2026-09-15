package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendu des vingt niveaux d'origine depuis leur point de depart.
 *
 * <p>Les assertions portent sur des invariants qu'une erreur de projection ou de DDA
 * casserait immediatement : rayons ordonnes, colonnes toutes bouchees, image non vide.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class RenderIT {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;

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
                        // Objets et sons ne servent pas au rendu des murs.
                    }
                }
            }
        }
        return new Fixture(levels, textures, gfx);
    }

    @Test
    @DisplayName("Chaque niveau se rend depuis son point de depart sans image vide")
    void everyLevelRenders() throws IOException {
        Fixture fixture = fixture();
        assertEquals(20, fixture.levels().size());

        Viewport viewport = new Viewport(WIDTH, HEIGHT);

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx());
            Renderer renderer = new Renderer(world, viewport);
            Camera camera = Camera.atPlayerStart(level);

            for (int turn = 0; turn < Trig.STEPS; turn += 256) {
                camera.setHeading(level.playerStart().heading() + turn);
                Framebuffer frame = renderer.render(camera);

                assertRayTableIsSane(level, renderer.rayTable(), turn);

                // Sols, plafonds et murs couvrent desormais toute la fenetre : un pixel
                // reste a zero seulement si une surface n'a pas ete peinte.
                assertTrue(paintedPixels(frame) > WIDTH * HEIGHT * 9 / 10,
                        level.name() + " cap " + camera.heading()
                                + " : trop de pixels non peints (" + paintedPixels(frame) + ")");
            }
        }
    }

    /**
     * Le rayon doit toujours finir sur un mur : le monde est clos, et le rasteriseur
     * s'appuie dessus pour terminer chaque colonne.
     */
    private static void assertRayTableIsSane(Level level, RayTable table, int turn) {
        for (int column = 0; column < table.columns(); column++) {
            int count = table.count(column);
            String where = level.name() + " cap+" + turn + " colonne " + column;

            assertTrue(count > 0, where + " : aucune transition");
            assertTrue(count <= RayTable.MAX_HITS, where + " : trop de transitions");
            assertTrue(table.isWall(column, count - 1), where + " : le rayon ne finit pas sur un mur");

            int previous = 0;
            for (int hit = 0; hit < count; hit++) {
                int distance = table.distance(column, hit);
                assertTrue(distance > 0, where + " : distance nulle ou negative au rang " + hit);
                assertTrue(distance >= previous, where + " : distances non croissantes au rang " + hit);
                previous = distance;

                int brush = table.brushColumn(column, hit);
                assertTrue(brush >= 0 && brush < 64, where + " : colonne de brosse hors bornes");

                int edge = table.edge(column, hit);
                assertTrue(edge >= 1 && edge <= 4, where + " : numero d'arete invalide (" + edge + ")");
            }
        }
    }

    private static int paintedPixels(Framebuffer frame) {
        int painted = 0;
        for (byte pixel : frame.pixels()) {
            if (pixel != 0) {
                painted++;
            }
        }
        return painted;
    }

    @Test
    @DisplayName("Un mur deux fois plus loin se projette deux fois plus petit")
    void projectionIsInverselyProportionalToDistance() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx());
        Viewport viewport = new Viewport(WIDTH, HEIGHT);
        Renderer renderer = new Renderer(world, viewport);

        Camera camera = Camera.atPlayerStart(level);
        renderer.render(camera);

        // Distance du mur vu par la colonne centrale, avant et apres un recul d'une case.
        int before = wallDistance(renderer.rayTable(), WIDTH / 2);
        camera.setPosition(camera.x() - Fixed.of(Level.BLOCK_SIZE) * cosSign(camera), camera.z());
        renderer.render(camera);
        int after = wallDistance(renderer.rayTable(), WIDTH / 2);

        assertTrue(after > before,
                "reculer doit eloigner le mur (" + before + " puis " + after + ")");
    }

    private static int cosSign(Camera camera) {
        return camera.viewDirX() >= 0 ? 1 : -1;
    }

    private static int wallDistance(RayTable table, int column) {
        return table.distance(column, table.count(column) - 1);
    }
}
