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
 * Un objet que le decor cache ne doit modifier aucun pixel.
 *
 * <p>Ce test verrouille deux corrections d'un defaut bien visible en jeu — des ennemis
 * apparaissant a travers les murs :
 *
 * <ul>
 *   <li>les bornes de decoupe ne valent que pour les transitions que la passe des murs a
 *       reellement traitees ; au-dela, la colonne est bouchee ;</li>
 *   <li>un objet n'est dessine que si un rayon a traverse sa case. La profondeur seule ne
 *       suffit pas : derriere un angle, un ennemi peut se trouver <em>devant</em> le mur
 *       que voit sa colonne.</li>
 * </ul>
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class OcclusionIT {

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

    @Test
    @DisplayName("Un objet dont aucun rayon n'a atteint la case ne dessine rien")
    void unreachedObjectsDrawNothing() throws IOException {
        Fixture fixture = fixture();
        Viewport viewport = new Viewport(320, 200);
        int checked = 0;

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            Renderer renderer = new Renderer(world, viewport);
            Camera camera = Camera.atPlayerStart(level);
            for (SceneObject object : world.objects()) {
                object.awaken();
            }

            for (int turn = 0; turn < Trig.STEPS; turn += 512) {
                camera.setHeading(turn);
                byte[] complete = renderer.render(camera).pixels().clone();
                RayTable table = renderer.rayTable();

                for (SceneObject object : new ArrayList<>(world.objects())) {
                    if (object.definition().objectType() != ObjectDefinition.TYPE_ENEMY) {
                        continue;
                    }
                    int cellX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
                    int cellZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);
                    if (table.reached(cellX, cellZ)) {
                        continue;
                    }
                    checked++;

                    int at = world.objects().indexOf(object);
                    world.objects().remove(at);
                    byte[] without = renderer.render(camera).pixels().clone();
                    world.objects().add(at, object);

                    assertEquals(0, differences(complete, without),
                            level.name() + " cap " + turn + " : "
                                    + object.definition().name() + " dessine alors qu'aucun rayon"
                                    + " n'atteint sa case " + cellX + "," + cellZ);

                    // Une seule verification par vue suffit : le rendu est deterministe.
                    break;
                }
            }
        }
        assertTrue(checked > 0, "aucun objet hors de portee des rayons n'a ete rencontre");
    }

    @Test
    @DisplayName("Les transitions au-dela de la fermeture d'une colonne sont ecartees")
    void closedColumnsExposeNothingBeyond() throws IOException {
        Fixture fixture = fixture();
        Viewport viewport = new Viewport(320, 200);
        int closedColumns = 0;

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            Renderer renderer = new Renderer(world, viewport);
            Camera camera = Camera.atPlayerStart(level);

            for (int turn = 0; turn < Trig.STEPS; turn += 512) {
                camera.setHeading(turn);
                renderer.render(camera);
                RayTable table = renderer.rayTable();

                for (int column = 0; column < viewport.width(); column++) {
                    int usable = table.visibleCount(column);
                    assertTrue(usable <= table.count(column),
                            level.name() + " colonne " + column
                                    + " : plus de transitions exploitables que de transitions");
                    if (usable < table.count(column)) {
                        closedColumns++;
                    }
                }
            }
        }
        assertTrue(closedColumns > 0,
                "aucune colonne fermee avant sa derniere transition : le test ne prouve rien");
    }

    @Test
    @DisplayName("Un objet qui attend son declencheur se voit quand meme")
    void waitingObjectsAreStillDrawn() throws IOException {
        Fixture fixture = fixture();
        Viewport viewport = new Viewport(320, 200);

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            Renderer renderer = new Renderer(world, viewport);

            for (SceneObject object : new ArrayList<>(world.objects())) {
                if (!object.isDormant() || object.definition().frames().isEmpty()) {
                    continue;
                }
                int cellX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
                int cellZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);
                // Se placer une case a l'ouest, degagee, et regarder vers l'est.
                int fromX = cellX - 1;
                if (fromX < 0 || level.isVoid(fromX, cellZ) || level.isWall(fromX, cellZ)
                        || level.isVoid(cellX, cellZ) || level.isWall(cellX, cellZ)) {
                    continue;
                }
                Block stand = level.blockAt(fromX, cellZ);
                if (stand.ceilHeight() - stand.floorHeight() < 64) {
                    continue;   // pas de quoi tenir debout
                }
                Camera camera = new Camera(
                        Fixed.of(fromX * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                        Fixed.of(cellZ * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                        stand.floorHeight() + Camera.EYES_HEIGHT, 0);

                byte[] with = renderer.render(camera).pixels().clone();
                int at = world.objects().indexOf(object);
                world.objects().remove(at);
                byte[] without = renderer.render(camera).pixels().clone();
                world.objects().add(at, object);

                if (differences(with, without) == 0) {
                    continue;   // hors champ ou cache par le decor : ce cas ne prouve rien
                }
                return;   // il se dessine : c'est ce qu'il fallait montrer
            }
        }
        throw new AssertionError(
                "aucun objet en attente n'a pu etre observe : le test ne prouve rien");
    }

    private static int differences(byte[] a, byte[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                count++;
            }
        }
        return count;
    }
}
