package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tirs, portee et impacts, sur les donnees d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class CombatIT {

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

    private static Simulation simulation(Fixture fixture) {
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
        return new Simulation(world, Camera.atPlayerStart(level), fixture.objects());
    }

    @Test
    @DisplayName("Les six armes du jeu sont retrouvees dans les donnees")
    void allSixWeaponsAreDescribed() throws IOException {
        Fixture fixture = fixture();
        Weapons weapons = new Weapons(fixture.objects());

        assertEquals(Weapons.COUNT, weapons.describedCount(),
                "les six armes de la boutique devraient etre decrites");

        // Les degats croissent avec le numero d'arme, sauf le lance-flammes qui compense
        // par sa cadence et se paie par une portee minuscule.
        assertNotNull(weapons.projectileOf(0));
        assertTrue(weapons.projectileOf(5).param(1) > weapons.projectileOf(0).param(1),
                "la derniere arme devrait frapper plus fort que la premiere");

        ObjectDefinition flame = weapons.projectileOf(3);
        assertTrue(flame.param(4) < 256,
                "le lance-flammes devrait avoir une portee tres courte, pas " + flame.param(4));
    }

    @Test
    @DisplayName("Le joueur ne demarre qu'avec l'arme de base")
    void startsWithTheBasicWeaponOnly() throws IOException {
        Weapons weapons = new Weapons(fixture().objects());

        assertTrue(weapons.owns(0));
        for (int weapon = 1; weapon < Weapons.COUNT; weapon++) {
            assertFalse(weapons.owns(weapon), "arme " + weapon + " ne devrait pas etre acquise");
        }
        assertFalse(weapons.select(4), "on ne peut pas selectionner une arme qu'on n'a pas");
        assertEquals(0, weapons.active());

        weapons.give(4);
        assertTrue(weapons.select(4));
        assertEquals(4, weapons.active());
    }

    @Test
    @DisplayName("Tirer coute de l'energie et cree un projectile")
    void firingSpendsEnergyAndSpawnsAShot() throws IOException {
        Fixture fixture = fixture();
        Simulation simulation = simulation(fixture);

        int energy = simulation.state().energy();
        int cost = simulation.combat().weapons().energyCost();
        assertTrue(cost > 0, "l'arme de base devrait couter de l'energie");

        PlayerInput input = new PlayerInput();
        input.fire = true;
        input.firePresses = 1;
        simulation.tick(input);

        assertEquals(energy - cost, simulation.state().energy(), "l'energie n'a pas ete debitee");
        long mine = simulation.world().projectiles().stream()
                .filter(Projectile::isFromPlayer).count();
        assertEquals(1, mine, "aucun tir du joueur n'est parti");
    }

    @Test
    @DisplayName("Une arme ordinaire tire au coup par coup : tenir la gachette ne suffit pas")
    void ordinaryWeaponsFireOncePerPress() throws IOException {
        Simulation simulation = simulation(fixture());
        PlayerInput input = new PlayerInput();

        // Un appui : un tir.
        input.fire = true;
        input.firePresses = 1;
        input.firePresses = 1;
        simulation.tick(input);
        int afterOne = simulation.state().energy();
        assertTrue(afterOne < PlayerState.MAX_ENERGY, "le premier appui n'a pas tire");

        // La gachette reste tenue, mais sans nouvel appui : plus rien ne part.
        for (int tick = 0; tick < 20; tick++) {
            simulation.tick(input);
        }
        assertEquals(afterOne, simulation.state().energy(),
                "tenir la gachette a continue de tirer");

        // Un second appui, un second tir.
        input.firePresses = 1;
        simulation.tick(input);
        assertTrue(simulation.state().energy() < afterOne, "le second appui n'a pas tire");
    }

    @Test
    @DisplayName("Sans energie, l'arme reste muette")
    void noEnergyNoShot() throws IOException {
        Simulation simulation = simulation(fixture());
        simulation.state().spendEnergy(PlayerState.MAX_ENERGY);

        PlayerInput input = new PlayerInput();
        input.fire = true;
        input.firePresses = 1;
        simulation.tick(input);

        assertTrue(simulation.world().projectiles().isEmpty(), "un tir est parti sans energie");
    }

    @Test
    @DisplayName("Un tir finit toujours par disparaitre, mur ou portee")
    void everyShotEventuallyStops() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            Camera camera = Camera.atPlayerStart(level);
            Simulation simulation = new Simulation(world, camera, fixture.objects());

            for (int turn = 0; turn < Trig.STEPS; turn += 256) {
                camera.setHeading(turn);
                PlayerInput input = new PlayerInput();
                input.fire = true;
                input.firePresses = 1;
        input.firePresses = 1;
                simulation.tick(input);
                input.fire = false;

                int ticks = 0;
                while (!world.projectiles().isEmpty() && ticks < 600) {
                    simulation.tick(input);
                    ticks++;
                }
                assertTrue(world.projectiles().isEmpty(),
                        level.name() + " cap " + turn + " : un tir ne s'arrete jamais");
            }
        }
    }

    @Test
    @DisplayName("De deux ennemis cote a cote, c'est le plus proche qui encaisse")
    void theNearerOfTwoTakesTheHit() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
        Camera camera = Camera.atPlayerStart(level);

        // Un cardinal degage devant le point de depart, sur deux cases.
        int[][] cardinals = {{1, 0, 0}, {0, 1, 512}, {-1, 0, 1024}, {0, -1, 1536}};
        int stepX = 0;
        int stepZ = 0;
        boolean found = false;
        for (int[] cardinal : cardinals) {
            boolean clear = true;
            for (int step = 1; step <= 2 && clear; step++) {
                int x = camera.cellX() + cardinal[0] * step;
                int z = camera.cellZ() + cardinal[1] * step;
                clear = x >= 0 && z >= 0 && x < Level.MAP_SIZE && z < Level.MAP_SIZE
                        && !level.isVoid(x, z) && !level.isWall(x, z);
            }
            if (clear) {
                stepX = cardinal[0];
                stepZ = cardinal[1];
                camera.setHeading(cardinal[2]);
                found = true;
                break;
            }
        }
        Assumptions.assumeTrue(found, "pas de couloir degage devant le point de depart");

        // Choix stable d'un type : l'ordre d'une table de hachage varie d'une execution
        // a l'autre, et le test doit dire la meme chose a chaque fois.
        ObjectDefinition kind = fixture.objects().objects().values().stream()
                .filter(d -> d.objectType() == ObjectDefinition.TYPE_ENEMY)
                .filter(d -> d.radius() >= 25 && d.param(3) > 1)
                .min(Comparator.comparing(ObjectDefinition::name))
                .orElse(null);
        assertNotNull(kind, "aucun type d'ennemi exploitable");

        int originX = camera.x() >> 16;
        int originZ = camera.z() >> 16;
        int floor = world.floorHeight(level.blockIndex(camera.cellX(), camera.cellZ()));

        Weapons weapons = new Weapons(fixture.objects());

        // Les deux a la meme distance le long du tir ; l'un pile dans l'axe, l'autre
        // decale de cote. Le decale est donc plus loin a tout instant du vol, et l'ecart
        // reste petit devant la portee, pour que les deux y entrent ensemble quel que soit
        // le pas du projectile.
        int offset = (weapons.projectile().radius() + kind.radius()) / 4;
        SceneObject near = enemy(kind,
                originX + stepX * 90, originZ + stepZ * 90, floor);
        SceneObject far = enemy(kind,
                originX + stepX * 90 - stepZ * offset,
                originZ + stepZ * 90 + stepX * offset, floor);

        // Le lointain en tete de liste : la selection fautive prenait le premier venu.
        world.objects().clear();
        world.objects().add(far);
        world.objects().add(near);

        // On pilote le combat seul, sans l'IA : des ennemis qui chargent changeraient de
        // place pendant le vol et le test ne dirait plus rien.
        Combat combat = new Combat(world, weapons, new PlayerState(), fixture.objects());
        int playerBlock = level.blockIndex(camera.cellX(), camera.cellZ());
        PlayerInput input = new PlayerInput();
        input.fire = true;
        input.firePresses = 1;
        combat.update(camera, input, playerBlock);
        input.fire = false;
        for (int tick = 0; tick < 200 && !world.projectiles().isEmpty(); tick++) {
            combat.update(camera, input, playerBlock);
        }

        assertTrue(near.strength() < kind.param(3), "le plus proche n'a pas ete touche");
        assertEquals(kind.param(3), far.strength(),
                "le plus eloigne a encaisse a la place du plus proche");
    }

    private static SceneObject enemy(ObjectDefinition kind, int x, int z, int floor) {
        SceneObject object = new SceneObject(kind, x, z, floor, 0, 0);
        object.setStrength(kind.param(3));
        return object;
    }

    @Test
    @DisplayName("Une arme amelioree part en double, et le second tir est gratuit")
    void boostedWeaponsFireTwiceForOneCharge() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
        Camera camera = Camera.atPlayerStart(level);

        Weapons weapons = new Weapons(fixture.objects());
        assertFalse(weapons.isBoosted(0), "l'arme de base ne demarre pas amelioree");

        PlayerState plain = new PlayerState();
        Combat simple = new Combat(world, weapons, plain, fixture.objects());
        PlayerInput input = new PlayerInput();
        input.fire = true;
        input.firePresses = 1;
        simple.update(camera, input, level.blockIndex(camera.cellX(), camera.cellZ()));
        int shots = world.projectiles().size();
        int spent = PlayerState.MAX_ENERGY - plain.energy();
        world.projectiles().clear();

        weapons.boost(0);
        assertTrue(weapons.isBoosted(0), "l'amelioration n'a pas pris");

        PlayerState boosted = new PlayerState();
        Combat doubled = new Combat(world, weapons, boosted, fixture.objects());
        input.firePresses = 1;   // la premiere pression a deja ete consommee
        doubled.update(camera, input, level.blockIndex(camera.cellX(), camera.cellZ()));

        assertEquals(shots * 2, world.projectiles().size(),
                "une arme amelioree doit partir deux fois");
        assertEquals(spent, PlayerState.MAX_ENERGY - boosted.energy(),
                "le second tir d'une arme amelioree ne doit rien couter");

        // Et les deux partent de part et d'autre de l'axe.
        int first = world.projectiles().get(0).heading();
        int second = world.projectiles().get(1).heading();
        assertNotEquals(first, second, "les deux tirs partent dans le meme axe");
    }

    @Test
    @DisplayName("Une arme ne s'ameliore que si on la possede deja")
    void boostNeedsTheWeaponFirst() throws IOException {
        Weapons weapons = new Weapons(fixture().objects());

        weapons.boost(1);
        assertFalse(weapons.isBoosted(1), "une arme non possedee ne s'ameliore pas");
        assertFalse(weapons.owns(1), "l'amelioration ne doit pas donner l'arme");

        weapons.give(1);
        weapons.boost(1);
        assertTrue(weapons.isBoosted(1));
        weapons.boost(1);
        assertTrue(weapons.isBoosted(1), "l'amelioration ne s'empile pas");
    }

    @Test
    @DisplayName("Abattre un ennemi rapporte les points que son type declare")
    void killingAnEnemyScoresItsValue() throws IOException {
        Fixture fixture = fixture();
        Level level = fixture.levels().get(0);
        World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
        Camera camera = Camera.atPlayerStart(level);

        // Un type peu resistant, pour qu'un seul tir suffise.
        ObjectDefinition kind = fixture.objects().objects().values().stream()
                .filter(d -> d.objectType() == ObjectDefinition.TYPE_ENEMY)
                .filter(d -> d.radius() > 0 && d.param(2) > 0)
                .min(Comparator.comparingInt(d -> d.param(3)))
                .orElse(null);
        assertNotNull(kind, "aucun type d'ennemi exploitable");

        int[][] cardinals = {{1, 0, 0}, {0, 1, 512}, {-1, 0, 1024}, {0, -1, 1536}};
        int stepX = 0;
        int stepZ = 0;
        boolean found = false;
        for (int[] cardinal : cardinals) {
            int x = camera.cellX() + cardinal[0];
            int z = camera.cellZ() + cardinal[1];
            if (x >= 0 && z >= 0 && x < Level.MAP_SIZE && z < Level.MAP_SIZE
                    && !level.isVoid(x, z) && !level.isWall(x, z)) {
                stepX = cardinal[0];
                stepZ = cardinal[1];
                camera.setHeading(cardinal[2]);
                found = true;
                break;
            }
        }
        Assumptions.assumeTrue(found, "pas de case degagee devant le point de depart");

        SceneObject target = new SceneObject(kind,
                (camera.x() >> 16) + stepX * 60, (camera.z() >> 16) + stepZ * 60,
                world.floorHeight(level.blockIndex(camera.cellX(), camera.cellZ())), 0, 0);
        target.setStrength(1);                      // un seul coup doit suffire
        world.objects().clear();
        world.objects().add(target);

        PlayerState state = new PlayerState();
        assertEquals(0, state.score(), "le score part de zero");

        Combat combat = new Combat(world, new Weapons(fixture.objects()), state,
                fixture.objects());
        int playerBlock = level.blockIndex(camera.cellX(), camera.cellZ());
        PlayerInput input = new PlayerInput();
        input.fire = true;
        input.firePresses = 1;
        combat.update(camera, input, playerBlock);
        input.fire = false;
        for (int tick = 0; tick < 200 && !world.projectiles().isEmpty(); tick++) {
            combat.update(camera, input, playerBlock);
        }

        assertTrue(target.strength() <= 0, "l'ennemi n'a pas ete abattu");
        assertEquals(kind.param(2), state.score(),
                "le score credite ne correspond pas au bareme du type " + kind.name());
    }

    @Test
    @DisplayName("Le bareme des ennemis suit leur resistance")
    void scoresFollowToughness() throws IOException {
        Fixture fixture = fixture();

        int weakest = Integer.MAX_VALUE;
        int toughest = 0;
        int weakestScore = 0;
        int toughestScore = 0;
        for (ObjectDefinition d : fixture.objects().objects().values()) {
            if (d.objectType() != ObjectDefinition.TYPE_ENEMY) {
                continue;
            }
            assertTrue(d.param(2) >= 1000 && d.param(2) <= 25000,
                    d.name() + " : bareme de " + d.param(2) + ", hors des valeurs du jeu");
            if (d.param(3) < weakest) {
                weakest = d.param(3);
                weakestScore = d.param(2);
            }
            if (d.param(3) > toughest) {
                toughest = d.param(3);
                toughestScore = d.param(2);
            }
        }
        // Si l'on avait lu le mauvais parametre, cette relation ne tiendrait pas.
        assertTrue(toughestScore > weakestScore,
                "le plus coriace (" + toughest + " PV, " + toughestScore + " points) ne"
                        + " rapporte pas plus que le plus faible (" + weakest + " PV, "
                        + weakestScore + " points)");
    }

    @Test
    @DisplayName("Un ennemi encaisse ses points de vie avant de tomber")
    void enemiesTakeTheirHitPoints() throws IOException {
        Fixture fixture = fixture();

        for (Level level : fixture.levels()) {
            World world = new World(level, fixture.textures(), fixture.gfx(), fixture.objects());
            for (SceneObject object : world.objects()) {
                if (object.definition().objectType() != ObjectDefinition.TYPE_ENEMY) {
                    continue;
                }
                int strength = object.strength();
                assertTrue(strength > 0, object.definition().name() + " : aucun point de vie");
                assertEquals(object.definition().param(3), strength,
                        "les points de vie viennent du troisieme parametre du type");

                // Un coup qui n'acheve pas ne retire pas l'objet de la scene.
                assertFalse(object.damage(strength - 1));
                assertFalse(object.isCollected());
                // Le coup suivant l'abat.
                assertTrue(object.damage(1));
                return;
            }
        }
        throw new AssertionError("aucun ennemi dans les vingt niveaux");
    }
}
