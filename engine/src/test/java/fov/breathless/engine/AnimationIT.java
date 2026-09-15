package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.object.ObjectDefinition;
import fov.breathless.gld.texture.Texture;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cadences d'animation des textures et des objets.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class AnimationIT {

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

    /** Premier niveau comportant une texture animee, avec l'index de celle-ci. */
    private record Animated(World world, int textureIndex, int frameCount) {
    }

    private static Animated findAnimatedTexture(Fixture fixture) {
        for (Level level : fixture.levels()) {
            for (int i = 0; i < level.textureNames().size(); i++) {
                Texture texture = fixture.textures().get(level.textureNames().get(i));
                if (texture != null && texture.isAnimated()) {
                    World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
                    return new Animated(world, i + 1, texture.frameCount());
                }
            }
        }
        throw new AssertionError("aucune texture animee reclamee par un niveau");
    }

    @Test
    @DisplayName("Une texture animee change d'image toutes les huit, et boucle")
    void texturesAdvanceEveryEighthTick() throws IOException {
        Animated animated = findAnimatedTexture(fixture());
        World world = animated.world();
        int index = animated.textureIndex();

        assertEquals(0, world.textureFrame(index), "l'animation doit demarrer a la premiere image");

        // Sept pas ne suffisent pas.
        for (int tick = 0; tick < 7; tick++) {
            world.animate(1);
        }
        assertEquals(0, world.textureFrame(index), "l'image a change trop tot");

        world.animate(1);
        assertEquals(1, world.textureFrame(index), "l'image aurait du changer au huitieme pas");

        // Un cycle complet ramene a la premiere image.
        for (int tick = 0; tick < 8 * (animated.frameCount() - 1); tick++) {
            world.animate(1);
        }
        assertEquals(0, world.textureFrame(index), "l'animation n'a pas boucle");
    }

    @Test
    @DisplayName("Plusieurs pas d'un coup avancent d'autant, sans perdre le reste")
    void animationKeepsItsRemainder() throws IOException {
        Animated animated = findAnimatedTexture(fixture());
        World world = animated.world();
        int index = animated.textureIndex();

        world.animate(5);
        assertEquals(0, world.textureFrame(index));
        world.animate(5);   // dix pas au total : une image, et deux de reste
        assertEquals(1, world.textureFrame(index));
        world.animate(6);   // seize au total : deux images
        assertEquals(2 % animated.frameCount(), world.textureFrame(index));
    }

    @Test
    @DisplayName("Les objets a animation simple tournent, et tous ensemble")
    void simpleObjectsAnimateInStep() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());

            // Deux instances d'un meme type a animation simple.
            List<SceneObject> sameType = new ArrayList<>();
            for (SceneObject object : world.objects()) {
                ObjectDefinition definition = object.definition();
                if (definition.animType() > ObjectDefinition.ANIM_NONE
                        && definition.objectType() < ObjectDefinition.TYPE_SHOT
                        && definition.frames().size() >= 2) {
                    if (sameType.isEmpty() || sameType.get(0).definition() == definition) {
                        sameType.add(object);
                    }
                }
            }
            if (sameType.size() < 2) {
                continue;
            }

            assertEquals(0, sameType.get(0).animCounter());

            for (int tick = 0; tick < 3; tick++) {
                world.animate(1);
            }
            assertEquals(0, sameType.get(0).animCounter(), "l'objet a change trop tot");

            world.animate(1);
            assertNotEquals(0, sameType.get(0).animCounter(),
                    "l'objet aurait du changer d'image au quatrieme pas");

            // L'animation appartient au type : toutes les instances sont synchrones.
            int frame = sameType.get(0).animCounter();
            for (SceneObject object : sameType) {
                assertEquals(frame, object.animCounter(),
                        level.name() + " : instances desynchronisees");
            }
            return;
        }
        throw new AssertionError("aucun type a animation simple pose en double");
    }

    @Test
    @DisplayName("Une texture fixe et un ennemi ne bougent pas tout seuls")
    void fixedThingsStayPut() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());

        // Repere une texture non animee du niveau.
        int fixedIndex = -1;
        for (int i = 0; i < level.textureNames().size(); i++) {
            Texture texture = fixture.textures().get(level.textureNames().get(i));
            if (texture != null && !texture.isAnimated()) {
                fixedIndex = i + 1;
                break;
            }
        }
        assertTrue(fixedIndex > 0, "aucune texture fixe dans le premier niveau");

        for (int tick = 0; tick < 200; tick++) {
            world.animate(1);
        }
        assertEquals(0, world.textureFrame(fixedIndex), "une texture fixe a change d'image");

        for (SceneObject object : world.objects()) {
            if (object.definition().isDirectional()) {
                assertEquals(0, object.animCounter(),
                        "la pose d'un ennemi depend de son comportement, pas de l'horloge");
            }
        }
    }
}
