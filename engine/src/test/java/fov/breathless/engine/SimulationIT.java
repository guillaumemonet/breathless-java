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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le pas de jeu complet, sur les niveaux d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class SimulationIT {

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

    /** Place la camera sur un objet a ramasser du sous-type demande, dans un niveau. */
    private static Simulation standingOn(Fixture fixture, int subtype) {
        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            for (SceneObject object : world.objects()) {
                if (object.definition().objectType() != ObjectDefinition.TYPE_PICKUP
                        || object.isDormant()
                        || object.pickupSubtype() != subtype) {
                    continue;
                }
                int cellX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
                int cellZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);
                Block block = level.blockAt(cellX, cellZ);
                if (block == null) {
                    continue;
                }
                Camera camera = new Camera(Fixed.of(object.x()), Fixed.of(object.z()),
                        block.floorHeight() + Camera.EYES_HEIGHT, 0);
                return new Simulation(world, camera);
            }
        }
        return null;
    }

    @Test
    @DisplayName("Marcher sur des credits les ramasse et l'objet disparait")
    void walkingOverCreditsCollectsThem() throws IOException {
        Fixture fixture = fixture();
        Simulation simulation = standingOn(fixture, PlayerState.PICKUP_CREDITS);
        Assumptions.assumeTrue(simulation != null, "aucun credit eveille dans les niveaux");

        assertTrue(simulation.state().credits() == 0, "le joueur demarre sans credits");

        PlayerInput input = new PlayerInput();
        simulation.tick(input);

        assertTrue(simulation.state().credits() > 0, "les credits n'ont pas ete ramasses");
    }

    @Test
    @DisplayName("Une cle ramassee est acquise, et son objet retire de la scene")
    void keysArePickedUpOnce() throws IOException {
        Fixture fixture = fixture();

        for (int subtype = PlayerState.PICKUP_GREEN_KEY; subtype <= PlayerState.PICKUP_BLUE_KEY; subtype++) {
            Simulation simulation = standingOn(fixture, subtype);
            if (simulation == null) {
                continue;
            }
            final int keySubtype = subtype;
            assertFalse(simulation.state().hasKey(subtype));

            simulation.tick(new PlayerInput());

            assertTrue(simulation.state().hasKey(subtype), "la cle n'a pas ete prise");

            // L'objet doit avoir quitte la scene : il n'est plus ni teste ni dessine.
            long stillThere = simulation.world().objects().stream()
                    .filter(o -> !o.isCollected())
                    .filter(o -> o.definition().objectType() == ObjectDefinition.TYPE_PICKUP)
                    .filter(o -> o.pickupSubtype() == keySubtype)
                    .count();
            assertEquals(0, stillThere, "la cle est restee dans le niveau");
            return;
        }
        throw new AssertionError("aucune cle eveillee dans les vingt niveaux");
    }

    @Test
    @DisplayName("Un objet de sante reste en place quand le joueur est au maximum")
    void fullHealthLeavesTheMedkit() throws IOException {
        Fixture fixture = fixture();
        Simulation simulation = standingOn(fixture, PlayerState.PICKUP_HEALTH);
        Assumptions.assumeTrue(simulation != null, "aucun soin eveille dans les niveaux");

        simulation.tick(new PlayerInput());

        // La sante est pleine au depart : le soin doit rester disponible.
        for (SceneObject object : simulation.world().objects()) {
            if (object.definition().objectType() == ObjectDefinition.TYPE_PICKUP
                    && object.pickupSubtype() == PlayerState.PICKUP_HEALTH) {
                assertFalse(object.isCollected(),
                        "le soin a ete gaspille alors que la sante etait pleine");
            }
        }
    }

    @Test
    @DisplayName("Trois mille pas de jeu sur chaque niveau ne cassent rien")
    void longRunIsStable() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            Camera camera = Camera.atPlayerStart(level);
            Simulation simulation = new Simulation(world, camera);
            PlayerInput input = new PlayerInput();
            java.util.Random random = new java.util.Random(level.name().hashCode());

            for (int tick = 0; tick < 3000; tick++) {
                if (tick % 25 == 0) {
                    input.forward = random.nextBoolean();
                    input.backward = random.nextBoolean();
                    input.left = random.nextBoolean();
                    input.right = random.nextBoolean();
                    input.strafe = random.nextBoolean();
                    input.run = random.nextBoolean();
                }
                simulation.tick(input);
            }

            assertTrue(simulation.state().health() >= 0, level.name() + " : sante negative");
            assertTrue(simulation.state().shields() >= 0, level.name() + " : boucliers negatifs");
            assertTrue(simulation.effects().activeCount() <= Effects.MAX_ACTIVE,
                    level.name() + " : trop d'effets actifs");
        }
    }
}
