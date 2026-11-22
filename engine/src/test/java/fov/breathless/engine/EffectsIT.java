package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.EffectEntry;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.texture.TextureBank;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portes, ascenseurs et reveil des ennemis dans les niveaux d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class EffectsIT {

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

    /** Premiere liste d'effets contenant le code demande, ou -1. */
    private static int findList(Level level, int effectCode) {
        for (int i = 0; i < level.effectLists().size(); i++) {
            for (EffectEntry entry : level.effectLists().get(i)) {
                if (entry.effect() == effectCode && entry.param1() > 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static EffectEntry entryOf(Level level, int list, int effectCode) {
        for (EffectEntry entry : level.effectLists().get(list - 1)) {
            if (entry.effect() == effectCode) {
                return entry;
            }
        }
        throw new AssertionError("entree introuvable");
    }

    /** Blocs portant ce numero de trigger, par l'un ou l'autre de leurs deux champs. */
    private static List<Integer> blocksOf(Level level, int trigger) {
        List<Integer> blocks = new ArrayList<>();
        for (int i = 0; i < level.blocks().size(); i++) {
            Block block = level.blocks().get(i);
            if (block.trigger() == trigger || block.trigger2() == trigger) {
                blocks.add(i);
            }
        }
        return blocks;
    }

    @Test
    @DisplayName("Une porte s'ouvre de param1 unites, marque une pause, puis se referme")
    void doorOpensPausesAndCloses() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            int list = findList(level, 5);
            if (list < 0) {
                continue;
            }
            EffectEntry door = entryOf(level, list, 5);
            if (door.param2() <= 0) {
                continue;   // une pause nulle laisse la porte ouverte : cas teste ailleurs
            }
            List<Integer> blocks = blocksOf(level, door.trigger());
            if (blocks.isEmpty()) {
                continue;
            }

            World world = world(fixture, level);
            Effects effects = new Effects(world);
            int sample = blocks.get(0);
            int closed = world.ceilHeight(sample);

            // Declencher une liste lance toutes ses entrees, pas seulement la porte.
            effects.trigger(list);
            assertTrue(effects.activeCount() > 0, level.name() + " : la porte n'a pas demarre");

            // Ouverture : param1 unites, une par cinquantieme.
            for (int tick = 0; tick < door.param1(); tick++) {
                effects.update(1, -1, world.objects());
            }
            assertEquals(closed + door.param1(), world.ceilHeight(sample),
                    level.name() + " : la porte ne s'est pas ouverte entierement");

            // Pause, puis fermeture.
            for (int tick = 0; tick < door.param2() + door.param1() + 2; tick++) {
                effects.update(1, -1, world.objects());
            }
            assertEquals(closed, world.ceilHeight(sample),
                    level.name() + " : la porte ne s'est pas refermee");
            return;
        }
        throw new AssertionError("aucune porte exploitable dans les vingt niveaux");
    }

    /**
     * Chaque effet d'ouverture bouge le plan que l'original bouge, et lui seul.
     *
     * <p>Les trois portes du catalogue ne se ressemblent pas : {@code DoorUp} leve le
     * plafond, {@code Door2} — la porte « a due ante » — leve le plafond <em>et</em>
     * abaisse le sol, {@code DoorDown} abaisse le sol seul. Confondre les deux dernieres
     * avec la premiere donne une porte qui s'etire sans jamais s'ouvrir.
     */
    /**
     * L'inverse d'une porte : un ascenseur ne cede pas au joueur.
     *
     * <p>{@code FloorUpDown} et {@code FloorDownUp} n'ont aucun test du joueur — ils
     * bougent leur plancher quoi qu'il arrive. Leur appliquer la prudence d'une porte les
     * bloque des qu'on monte dessus, ce qui est precisement ce qu'un ascenseur ne doit pas
     * faire.
     */
    @Test
    @DisplayName("Un ascenseur boucle son cycle meme avec le joueur dessus")
    void aLiftMovesEvenUnderThePlayer() throws IOException {
        Fixture fixture = fixture();

        for (int effect : new int[]{7, 8}) {
            boolean seen = false;
            for (Level level : fixture.levels()) {
                int list = findList(level, effect);
                if (list < 0) {
                    continue;
                }
                EffectEntry lift = entryOf(level, list, effect);
                List<Integer> blocks = blocksOf(level, lift.trigger());
                if (blocks.isEmpty() || lift.param1() <= 0 || lift.param2() <= 0) {
                    continue;
                }

                World world = world(fixture, level);
                Effects effects = new Effects(world);
                int sample = blocks.get(0);
                int start = world.floorHeight(sample);
                int direction = (effect == 7) ? +1 : -1;

                effects.trigger(list);
                // Le joueur reste sur l'ascenseur pendant tout le cycle.
                for (int tick = 0; tick < lift.param1(); tick++) {
                    effects.update(1, sample, world.objects());
                }
                assertEquals(start + direction * lift.param1(), world.floorHeight(sample),
                        level.name() + " effet " + effect + " : l'aller ne s'est pas fait");

                int rest = lift.param2() + lift.param1() + 50;
                for (int tick = 0; tick < rest; tick++) {
                    effects.update(1, sample, world.objects());
                }
                assertEquals(start, world.floorHeight(sample),
                        level.name() + " effet " + effect
                                + " : l'ascenseur est reste bloque sous le joueur");
                seen = true;
                break;
            }
            assertTrue(seen, "aucun ascenseur exploitable pour l'effet " + effect);
        }
    }

    @Test
    @DisplayName("Chaque sorte de porte bouge le plan que l'original lui donne")
    void eachKindOfDoorMovesItsOwnSurface() throws IOException {
        Fixture fixture = fixture();

        // effet -> deplacement attendu du plafond et du sol, par unite d'ouverture.
        int[][] expected = {
            {5, +1, 0},     // DoorUp : le plafond se leve
            {6, +1, -1},    // Door2 : les deux battants s'ecartent
            {12, 0, -1},    // DoorDown : le sol s'abaisse
            {7, 0, +1},     // FloorUpDown : le sol se leve
            {8, 0, -1},     // FloorDownUp : le sol s'abaisse
        };

        int checked = 0;
        for (int[] expect : expected) {
            int effect = expect[0];
            boolean seen = false;

            for (Level level : fixture.levels()) {
                int list = findList(level, effect);
                if (list < 0) {
                    continue;
                }
                EffectEntry entry = entryOf(level, list, effect);
                List<Integer> blocks = blocksOf(level, entry.trigger());
                if (blocks.isEmpty() || entry.param1() <= 0) {
                    continue;
                }

                World world = world(fixture, level);
                Effects effects = new Effects(world);
                int sample = blocks.get(0);
                int ceilBefore = world.ceilHeight(sample);
                int floorBefore = world.floorHeight(sample);

                effects.trigger(list);
                for (int tick = 0; tick < entry.param1(); tick++) {
                    effects.update(1, -1, world.objects());
                }

                String id = level.name() + " effet " + effect;
                assertEquals(ceilBefore + expect[1] * entry.param1(),
                        world.ceilHeight(sample), id + " : plafond");
                assertEquals(floorBefore + expect[2] * entry.param1(),
                        world.floorHeight(sample), id + " : sol");

                // Une ouverture laisse toujours le plafond au-dessus du sol : l'inverse
                // est un bloc retourne, que rien ne sait dessiner.
                assertTrue(world.ceilHeight(sample) > world.floorHeight(sample),
                        id + " : le plafond est passe sous le sol");
                seen = true;
                checked++;
                break;
            }
            assertTrue(seen, "aucun niveau n'emploie l'effet " + effect);
        }
        assertEquals(expected.length, checked, "toutes les sortes doivent etre verifiees");
    }

    @Test
    @DisplayName("Une porte refuse de se refermer sur le joueur")
    void doorWillNotCloseOnThePlayer() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            int list = findList(level, 5);
            if (list < 0) {
                continue;
            }
            EffectEntry door = entryOf(level, list, 5);
            if (door.param2() <= 0) {
                continue;
            }
            List<Integer> blocks = blocksOf(level, door.trigger());
            if (blocks.isEmpty()) {
                continue;
            }

            World world = world(fixture, level);
            Effects effects = new Effects(world);
            int sample = blocks.get(0);
            int open = world.ceilHeight(sample) + door.param1();

            effects.trigger(list);
            // Le joueur se tient sous la porte pendant tout le cycle.
            int ticks = door.param1() + door.param2() + door.param1() + 50;
            for (int tick = 0; tick < ticks; tick++) {
                effects.update(1, sample, world.objects());
            }

            assertEquals(open, world.ceilHeight(sample),
                    level.name() + " : la porte s'est refermee sur le joueur");
            assertTrue(effects.activeCount() > 0,
                    level.name() + " : l'effet aurait du rester en attente");
            return;
        }
        throw new AssertionError("aucune porte exploitable dans les vingt niveaux");
    }

    @Test
    @DisplayName("L'effet le plus utilise du jeu reveille bien les ennemis en attente")
    void sleepingEnemiesWakeUp() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            int list = findList(level, 17);
            if (list < 0) {
                continue;
            }
            EffectEntry wake = entryOf(level, list, 17);
            World world = world(fixture, level);

            List<SceneObject> waiting = new ArrayList<>();
            for (SceneObject object : world.objects()) {
                if (object.dormantTrigger() == wake.trigger()) {
                    waiting.add(object);
                }
            }
            if (waiting.isEmpty()) {
                continue;
            }
            for (SceneObject object : waiting) {
                assertTrue(object.isDormant(), level.name() + " : objet deja eveille");
            }

            Effects effects = new Effects(world);
            effects.trigger(list);
            for (int tick = 0; tick < wake.param1() + 2; tick++) {
                effects.update(1, -1, world.objects());
            }

            for (SceneObject object : waiting) {
                assertTrue(!object.isDormant(),
                        level.name() + " : l'ennemi de trigger " + wake.trigger()
                                + " dort toujours");
            }
            return;
        }
        throw new AssertionError("aucun reveil d'ennemi exploitable dans les vingt niveaux");
    }

    @Test
    @DisplayName("Une lumiere clignotante alterne entre deux valeurs et revient a l'origine")
    void blinkingLightAlternates() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            int list = findList(level, 16);
            if (list < 0) {
                continue;
            }
            EffectEntry blink = entryOf(level, list, 16);
            List<Integer> blocks = blocksOf(level, blink.trigger());
            if (blocks.isEmpty()) {
                continue;
            }

            World world = world(fixture, level);
            Effects effects = new Effects(world);
            int sample = blocks.get(0);
            int origin = world.lightOffset(sample);

            effects.trigger(list);
            // Cinquante pas avant le premier basculement.
            for (int tick = 0; tick < 50; tick++) {
                effects.update(1, -1, world.objects());
            }
            assertEquals(blink.param1(), world.lightOffset(sample),
                    level.name() + " : la lumiere n'a pas bascule");

            // Dix pas plus tard elle revient a sa valeur d'origine.
            for (int tick = 0; tick < 10; tick++) {
                effects.update(1, -1, world.objects());
            }
            assertEquals(origin, world.lightOffset(sample),
                    level.name() + " : la lumiere n'est pas revenue a l'origine");
            return;
        }
        throw new AssertionError("aucune lumiere clignotante dans les vingt niveaux");
    }

    @Test
    @DisplayName("Actionner un interrupteur face a soi declenche ses effets")
    void switchesOpenWhenFaced() throws IOException {
        Fixture fixture = fixture();

        // Bit d'attribut, pas vers la case portant l'interrupteur, et cap a prendre.
        int[][] cotes = {
            {0x10, +1, 0, 1024},   // interrupteur sur la face est : on l'aborde par l'est
            {0x80, 0, -1, 512},
            {0x20, 0, +1, 1536},
            {0x40, -1, 0, 0},
        };

        int testes = 0;
        int bouges = 0;
        for (Level level : fixture.levels()) {
            for (int index = 1; index < level.blocks().size(); index++) {
                Block block = level.blocks().get(index);
                if (block.effectList() == 0 || (block.attributes() & 0xF0) == 0) {
                    continue;
                }
                for (int[] cote : cotes) {
                    if ((block.attributes() & cote[0]) == 0) {
                        continue;
                    }
                    // Case occupee par ce bloc, et case d'ou l'aborder.
                    int[] cell = findCell(level, index);
                    if (cell == null) {
                        continue;
                    }
                    int fromX = cell[0] + cote[1];
                    int fromZ = cell[1] + cote[2];
                    if (fromX < 0 || fromZ < 0 || fromX >= Level.MAP_SIZE || fromZ >= Level.MAP_SIZE) {
                        continue;
                    }
                    if (level.isVoid(fromX, fromZ) || level.isWall(fromX, fromZ)) {
                        continue;
                    }

                    World world = world(fixture, level);
                    Block stand = level.blockAt(fromX, fromZ);
                    Camera camera = new Camera(
                            Fixed.of(fromX * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                            Fixed.of(fromZ * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                            stand.floorHeight() + Camera.EYES_HEIGHT, cote[3]);
                    Effects effects = new Effects(world);

                    assertEquals(0, effects.activeCount());
                    assertTrue(effects.useSwitch(camera),
                            level.name() + " : l'interrupteur du bloc " + index
                                    + " n'a pas repondu depuis " + fromX + "," + fromZ);
                    assertTrue(effects.activeCount() > 0,
                            level.name() + " : l'interrupteur n'a declenche aucun effet");

                    // Et le decor doit bouger : c'est la porte qui s'ouvre.
                    short[] before = heights(world, level);
                    for (int tick = 0; tick < 300 && effects.activeCount() > 0; tick++) {
                        effects.update(1, level.blockIndex(fromX, fromZ), world.objects());
                    }
                    bouges += Arrays.equals(before, heights(world, level)) ? 0 : 1;

                    testes++;
                    break;
                }
                if (testes > 0) {
                    break;
                }
            }
        }
        assertTrue(testes >= 5, "trop peu d'interrupteurs verifies : " + testes);
        assertTrue(bouges >= 3,
                "les interrupteurs repondent mais le decor ne bouge pas : " + bouges + "/" + testes);
    }

    /** Hauteurs de sol et de plafond de tous les blocs, pour detecter un mouvement. */
    private static short[] heights(World world, Level level) {
        short[] snapshot = new short[level.blocks().size() * 2];
        for (int i = 0; i < level.blocks().size(); i++) {
            snapshot[i * 2] = (short) world.floorHeight(i);
            snapshot[i * 2 + 1] = (short) world.ceilHeight(i);
        }
        return snapshot;
    }

    @Test
    @DisplayName("Un interrupteur ne repond pas quand on lui tourne le dos")
    void switchesIgnoreTheWrongDirection() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            for (int index = 1; index < level.blocks().size(); index++) {
                Block block = level.blocks().get(index);
                if (block.effectList() == 0 || (block.attributes() & 0x10) == 0) {
                    continue;
                }
                int[] cell = findCell(level, index);
                if (cell == null || cell[0] + 1 >= Level.MAP_SIZE) {
                    continue;
                }
                int fromX = cell[0] + 1;
                int fromZ = cell[1];
                if (level.isVoid(fromX, fromZ) || level.isWall(fromX, fromZ)) {
                    continue;
                }

                World world = world(fixture, level);
                Block stand = level.blockAt(fromX, fromZ);
                Camera camera = new Camera(
                        Fixed.of(fromX * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                        Fixed.of(fromZ * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                        stand.floorHeight() + Camera.EYES_HEIGHT, 0);   // dos tourne
                Effects effects = new Effects(world);

                assertFalse(effects.useSwitch(camera),
                        level.name() + " : l'interrupteur a repondu alors qu'on lui tourne le dos");
                assertEquals(0, effects.activeCount());
                return;
            }
        }
        throw new AssertionError("aucun interrupteur sur face est dans les vingt niveaux");
    }

    /** Premiere case de la carte occupee par ce bloc. */
    private static int[] findCell(Level level, int blockIndex) {
        for (int z = 0; z < Level.MAP_SIZE; z++) {
            for (int x = 0; x < Level.MAP_SIZE; x++) {
                if (level.blockIndex(x, z) == blockIndex) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("Marcher sur une dalle declenche ses effets, et y rester ne les relance pas")
    void steppingOnATriggerFiresItOnce() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            for (int index = 0; index < level.blocks().size(); index++) {
                Block block = level.blocks().get(index);
                if (block.effectList() == 0 || (block.attributes() & 0xF0) != 0) {
                    continue;
                }
                World world = world(fixture, level);
                Effects effects = new Effects(world);

                effects.enterBlock(index);
                int started = effects.activeCount();
                if (started == 0) {
                    continue;   // liste vide ou effets non portes
                }

                // Re-entrer sur la meme dalle ne doit pas empiler un second exemplaire.
                effects.enterBlock(index);
                assertEquals(started, effects.activeCount(),
                        level.name() + " : l'effet a ete relance alors qu'il tourne deja");
                return;
            }
        }
        throw new AssertionError("aucune dalle a effet exploitable dans les vingt niveaux");
    }

    @Test
    @DisplayName("Toutes les listes d'effets des vingt niveaux se jouent sans trou")
    void everyEffectListRunsToCompletion() throws IOException {
        Fixture fixture = fixture();
        int lists = 0;

        for (Level level : fixture.levels()) {
            World world = world(fixture, level);
            Simulation simulation = new Simulation(world, Camera.atPlayerStart(level),
                    fixture.objects());
            Effects effects = simulation.effects();

            // On lance chaque liste et on la laisse se derouler.
            for (int list = 1; list <= level.effectLists().size(); list++) {
                effects.trigger(list);
                lists++;
            }
            PlayerInput input = new PlayerInput();
            for (int tick = 0; tick < 400; tick++) {
                simulation.tick(input);
            }

            assertEquals(0, effects.unimplementedCount(),
                    level.name() + " : un effet non porte s'est presente");
        }
        assertTrue(lists > 100, "trop peu de listes jouees : " + lists);
    }

    @Test
    @DisplayName("Un teleport depose le joueur sur la case visee")
    void teleportsMoveThePlayer() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            for (int list = 0; list < level.effectLists().size(); list++) {
                for (EffectEntry entry : level.effectLists().get(list)) {
                    if (entry.effect() != 15) {
                        continue;   // teleport
                    }
                    World world = world(fixture, level);
                    Camera camera = Camera.atPlayerStart(level);
                    if (entry.param1() == camera.cellX() && entry.param2() == camera.cellZ()) {
                        continue;   // il ramene la ou l'on est : ne prouverait rien
                    }
                    Simulation simulation = new Simulation(world, camera, fixture.objects());
                    simulation.effects().trigger(list + 1);

                    // Le depart est differe : rien ne bouge avant la trente-deuxieme image.
                    PlayerInput input = new PlayerInput();
                    for (int tick = 0; tick < 30; tick++) {
                        simulation.tick(input);
                    }
                    assertTrue(entry.param1() != camera.cellX()
                                    || entry.param2() != camera.cellZ(),
                            level.name() + " : le teleport est parti sans attendre");

                    for (int tick = 0; tick < 10; tick++) {
                        simulation.tick(input);
                    }
                    assertEquals(entry.param1(), camera.cellX(),
                            level.name() + " : le teleport n'a pas depose le joueur en x");
                    assertEquals(entry.param2(), camera.cellZ(),
                            level.name() + " : le teleport n'a pas depose le joueur en z");
                    return;
                }
            }
        }
        throw new AssertionError("aucun teleport dans les vingt niveaux");
    }

    @Test
    @DisplayName("La fin de niveau se signale une fois, puis se tait")
    void endOfLevelIsReportedOnce() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            for (int list = 0; list < level.effectLists().size(); list++) {
                boolean ends = level.effectLists().get(list).stream()
                        .anyMatch(entry -> entry.effect() == 14);
                if (!ends) {
                    continue;
                }
                World world = world(fixture, level);
                Simulation simulation = new Simulation(world, Camera.atPlayerStart(level),
                        fixture.objects());
                assertFalse(simulation.takeLevelFinished(), "le niveau demarre acheve");

                simulation.effects().trigger(list + 1);
                // EndLevel leve Escape ; le niveau ne se quitte qu'au bout du fondu de
                // sortie, une trentaine de pas plus tard.
                boolean finished = false;
                for (int tick = 0; tick < 100 && !finished; tick++) {
                    simulation.tick(new PlayerInput());
                    finished = simulation.takeLevelFinished();
                }

                assertTrue(finished, "la fin de niveau n'est pas remontee");
                assertFalse(simulation.takeLevelFinished(), "la fin de niveau se repete");
                return;
            }
        }
        throw new AssertionError("aucune fin de niveau dans les vingt niveaux");
    }

    @Test
    @DisplayName("Un plafond qui descend ne redescend pas : StopEffect2 ne rend pas la main")
    void oneWayEffectsRunOnce() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            int list = findList(level, 3);      // CeilDown
            if (list < 0) {
                continue;
            }
            EffectEntry entry = entryOf(level, list, 3);
            List<Integer> blocks = blocksOf(level, entry.trigger());
            if (blocks.isEmpty()) {
                continue;
            }
            int block = blocks.get(0);
            World world = world(fixture, level);
            Effects effects = new Effects(world);
            int start = world.ceilHeight(block);

            for (int round = 0; round < 3; round++) {
                effects.trigger(list);
                for (int tick = 0; tick < entry.param1() + 5; tick++) {
                    effects.update(1, -1, List.of());
                }
            }
            assertEquals(start - entry.param1(), world.ceilHeight(block),
                    "le plafond est redescendu a chaque declenchement");
            return;
        }
        throw new AssertionError("aucun CeilDown dans les vingt niveaux");
    }

    @Test
    @DisplayName("Une porte a cle ne s'ouvre qu'avec la cle, et la consomme")
    void lockedDoorNeedsItsKey() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            for (int list = 1; list <= level.effectLists().size(); list++) {
                EffectEntry locked = null;
                for (EffectEntry entry : level.effectLists().get(list - 1)) {
                    if (entry.effect() == 5 && entry.key() >= 1 && entry.key() <= 4
                            && entry.param1() > 0) {
                        locked = entry;
                    }
                }
                if (locked == null || level.effectLists().get(list - 1).get(0) != locked) {
                    continue;   // une entree sans cle avant elle partirait quand meme
                }
                List<Integer> blocks = blocksOf(level, locked.trigger());
                if (blocks.isEmpty()) {
                    continue;
                }
                int block = blocks.get(0);
                int key = PlayerState.PICKUP_GREEN_KEY + locked.key() - 1;

                World world = world(fixture, level);
                Effects effects = new Effects(world);
                PlayerState state = new PlayerState();
                effects.setPlayerState(state);
                int start = world.ceilHeight(block);

                effects.triggerFromSwitch(list);
                effects.update(1, -1, List.of());
                assertEquals(start, world.ceilHeight(block), "la porte s'ouvre sans la cle");
                assertEquals(Effects.MESSAGE_KEY_BASE + locked.key(), effects.takeMessageRequest(),
                        "le message de cle manquante n'est pas demande");

                state.collect(key, 0);
                effects.triggerFromSwitch(list);
                effects.update(1, -1, List.of());
                assertTrue(world.ceilHeight(block) > start, "la porte ne s'ouvre pas avec la cle");
                assertFalse(state.hasKey(key), "la cle n'a pas ete consommee");
                return;
            }
        }
        throw new AssertionError("aucune porte a cle dans les vingt niveaux");
    }
}
