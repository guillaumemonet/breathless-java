package fov.breathless.engine;

import fov.breathless.gld.level.Level;
import fov.breathless.gld.object.ObjectDefinition;

/**
 * Un pas de jeu : commandes, deplacement, declenchements, effets.
 *
 * <p>Rassemble ce que la boucle principale du moteur d'origine enchaine a chaque
 * cinquantieme de seconde, pour que la fenetre du jeu et les tests fassent avancer le
 * monde exactement de la meme facon.
 */
public final class Simulation {

    private final World world;
    private final Player player;
    private final Effects effects;
    private final Combat combat;
    private final EnemyAi enemies;
    private final Camera camera;
    private final AutoMap autoMap;
    private final Terminal terminal;
    private boolean levelFinished;
    private SoundPlayer sound = SoundPlayer.SILENT;

    /** Branche le son : la simulation le passe aux effets et aux tirs. */
    public void setSoundPlayer(SoundPlayer player) {
        this.sound = (player == null) ? SoundPlayer.SILENT : player;
        effects.setSoundPlayer(this.sound);
        if (combat != null) {
            combat.setSoundPlayer(this.sound);
        }
        enemies.setSoundPlayer(this.sound);
    }
    private final PlayerState state = new PlayerState();
    private int currentBlock;

    /**
     * Seuil de ramassage, repris tel quel : le carre du rayon de l'objet augmente de deux
     * fois le carre de la demi-largeur du joueur. Ce n'est pas la somme des rayons au
     * carre, mais c'est la formule du moteur d'origine.
     */
    private static final int PLAYER_REACH = 2 * Player.WIDTH * Player.WIDTH;

    public Simulation(World world, Camera camera, fov.breathless.gld.object.ObjectBank bank) {
        this.world = world;
        this.camera = camera;
        this.player = new Player(world);
        this.effects = new Effects(world);
        this.combat = (bank == null) ? null : new Combat(world, new Weapons(bank), state, bank);
        this.enemies = new EnemyAi(world);
        this.autoMap = new AutoMap(world);
        this.terminal = (combat == null) ? null : new Terminal(state, combat.weapons());
        this.currentBlock = blockUnderCamera();
    }

    public Simulation(World world, Camera camera) {
        this(world, camera, null);
    }

    /**
     * Le terminal, ou {@code null} si aucune banque d'objets n'a ete fournie.
     *
     * <p>Tant qu'il est ouvert, le jeu ne doit plus avancer : c'est a l'appelant de
     * cesser d'appeler {@link #tick}.
     */
    public Terminal terminal() {
        return terminal;
    }

    /**
     * Le niveau vient-il d'etre acheve ?
     *
     * <p>L'appel efface la reponse : c'est a l'appelant de charger la suite.
     */
    public boolean takeLevelFinished() {
        boolean finished = levelFinished;
        levelFinished = false;
        return finished;
    }

    /**
     * Pose le joueur au centre d'une case et le recale sur son sol.
     *
     * <p>{@code InitPlayerPos2} : la teleportation ne conserve ni la vitesse ni la
     * hauteur de vue precedentes.
     */
    private void teleport(int cellX, int cellZ) {
        camera.setPosition(
                Fixed.of(cellX * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                Fixed.of(cellZ * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2));
        player.stop();
        currentBlock = blockUnderCamera();
        camera.setY(world.floorHeight(currentBlock) + Camera.EYES_HEIGHT);
    }

    /**
     * Remet la partie dans l'etat que porte un code de niveau.
     *
     * <p>Le niveau lui-meme reste a la charge de l'appelant : le code dit lequel, la
     * simulation ne sait pas charger.
     */
    public void restore(LevelCode.State saved) {
        state.restore(saved.health(), saved.shields(), saved.energy(), saved.credits());
        if (combat == null) {
            return;
        }
        Weapons weapons = combat.weapons();
        int[] power = saved.weaponPower();
        for (int weapon = 0; weapon < power.length; weapon++) {
            weapons.setPower(weapon, power[weapon]);
        }
        weapons.select(saved.activeWeapon());
    }

    /** Carte automatique : ce que le joueur a releve en avancant. */
    public AutoMap autoMap() {
        return autoMap;
    }

    /** Gestion des tirs, ou {@code null} si aucune banque d'objets n'a ete fournie. */
    public Combat combat() {
        return combat;
    }

    public Camera camera() {
        return camera;
    }

    public Player player() {
        return player;
    }

    public Effects effects() {
        return effects;
    }

    public PlayerState state() {
        return state;
    }

    public World world() {
        return world;
    }

    /** Avance le monde d'un cinquantieme de seconde. */
    public void tick(PlayerInput input) {
        player.update(camera, input);

        int block = blockUnderCamera();
        if (block != currentBlock) {
            // On ne declenche qu'au franchissement : rester sur la dalle ne relance rien.
            currentBlock = block;
            effects.enterBlock(block);
        }

        if (input.action) {
            input.action = false;
            effects.useSwitch(camera);
        }

        effects.update(1, currentBlock, world.objects());
        int request = effects.takeTerminalRequest();
        if (request >= 0 && terminal != null) {
            terminal.open(request);
        }
        int[] destination = effects.takeTeleportRequest();
        if (destination != null) {
            teleport(destination[0], destination[1]);
        }
        levelFinished |= effects.takeLevelFinished();
        world.animate(1);
        followFloors();
        collectPickups();

        int before = state.health() + state.shields();
        state.standOn(world.level().blocks().get(currentBlock).floorDamage(), 1);
        if (state.health() + state.shields() < before) {
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SCREAM);
        }

        if (combat != null) {
            combat.update(camera, input, currentBlock);
        }
        enemies.update(camera, state, combat);
        autoMap.survey(camera);
        state.advance(1);
    }

    /**
     * Recale les objets sur le sol de leur case.
     *
     * <p>Indispensable des qu'un ascenseur bouge : sans cela un ennemi reste suspendu a
     * l'altitude qu'avait le sol au chargement. Le moteur d'origine fait de meme, a
     * chaque image, pour tout ce qui n'est ni projectile ni explosion.
     */
    private void followFloors() {
        for (SceneObject object : world.objects()) {
            int cellX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
            int cellZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);
            object.setY(world.floorHeight(Math.abs(world.cell(cellX, cellZ))));
        }
    }

    /**
     * Ramasse ce qui est a portee.
     *
     * <p>Un objet dont le joueur a deja son content — de la sante a cent pour cent, une
     * cle deja possedee — reste en place : il pourra servir plus tard.
     */
    private void collectPickups() {
        int playerX = camera.x() >> 16;
        int playerZ = camera.z() >> 16;

        for (SceneObject object : world.objects()) {
            if (object.isCollected() || object.isDormant()
                    || object.definition().objectType() != ObjectDefinition.TYPE_PICKUP) {
                continue;
            }
            int radius = object.definition().radius();
            if (radius == 0) {
                continue;
            }
            int dx = object.x() - playerX;
            int dz = object.z() - playerZ;
            if (dx * dx + dz * dz >= radius * radius + PLAYER_REACH) {
                continue;
            }
            if (state.collect(object.pickupSubtype(), object.pickupValue())) {
                object.collect();
            }
        }
    }

    private int blockUnderCamera() {
        return Math.abs(world.cell(camera.cellX(), camera.cellZ()));
    }
}
