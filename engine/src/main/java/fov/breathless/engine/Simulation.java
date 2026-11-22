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
    /** Le fondu de l'image : entree, teleportation, sortie. */
    private final Fade fade = new Fade();

    /** Le fondu en cours, que la couche graphique applique a l'image. */
    public Fade fade() {
        return fade;
    }

    /** Le premier pas du niveau, qui fait entendre le fondu d'entree. */
    private boolean firstTick = true;

    /** {@code EndLevelFade} : la sortie est lancee, on attend le noir. */
    private boolean leaving;

    /** {@code rndseed} : un seul generateur pour tout le niveau. */
    private final Rnd rnd = new Rnd();

    /** Etat propre a cette simulation, quand aucune partie en cours n'est fournie. */
    private final PlayerState ownState = new PlayerState();

    /** La partie en cours, dont l'etat traverse les niveaux, ou {@code null}. */
    private final Career career;
    private int currentBlock;

    /**
     * Seuil de ramassage, repris tel quel : le carre du rayon de l'objet augmente de deux
     * fois le carre de la demi-largeur du joueur. Ce n'est pas la somme des rayons au
     * carre, mais c'est la formule du moteur d'origine.
     */
    private static final int PLAYER_REACH = 2 * Player.WIDTH * Player.WIDTH;

    public Simulation(World world, Camera camera, fov.breathless.gld.object.ObjectBank bank) {
        this(world, camera, bank, null);
    }

    /**
     * Simule un niveau en reprenant l'etat d'un joueur deja en route.
     *
     * <p>Le moteur d'origine garde l'etat du joueur dans ses globales : il traverse les
     * niveaux. Passer une {@link Career} evite de le recreer a chaque chargement, ce qui
     * ferait tout perdre au passage d'un niveau au suivant.
     *
     * @param career la partie en cours, ou {@code null} pour un etat neuf
     */
    public Simulation(World world, Camera camera, fov.breathless.gld.object.ObjectBank bank,
                      Career career) {
        this.world = world;
        this.camera = camera;
        this.career = career;
        this.player = new Player(world);
        this.effects = new Effects(world);
        this.effects.setPlayerState(state());
        this.effects.setRnd(rnd);
        this.effects.setFade(fade);
        // Fondu d'entree, et le son du teleport qui l'accompagne (TMapStart).
        this.fade.levelStart();
        Weapons arsenal = (career != null) ? career.weapons()
                : (bank == null) ? null : new Weapons(bank);
        this.combat = (bank == null) ? null : new Combat(world, arsenal, state(), bank);
        this.enemies = new EnemyAi(world, rnd);
        this.autoMap = new AutoMap(world);
        this.terminal = (combat == null) ? null : new Terminal(state(), combat.weapons());
        this.currentBlock = blockUnderCamera();
        this.player.place(camera);
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
        player.place(camera);
        currentBlock = blockUnderCamera();
    }

    /**
     * Remet la partie dans l'etat que porte un code de niveau.
     *
     * <p>Le niveau lui-meme reste a la charge de l'appelant : le code dit lequel, la
     * simulation ne sait pas charger.
     */
    public void restore(LevelCode.State saved) {
        state().restore(saved.health(), saved.shields(), saved.energy(), saved.credits());
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

    /** L'etat du joueur : celui de la partie en cours, s'il y en a une. */
    public PlayerState state() {
        return (career != null) ? career.state() : ownState;
    }

    public World world() {
        return world;
    }

    /** Message demande pendant le pas, indice de {@link Messages}, ou -1. */
    private int messageRequest = -1;

    /**
     * Le message a afficher, ou -1 : un ramassage, une cle qui manque. L'appel efface la
     * demande.
     */
    public int takeMessageRequest() {
        int request = messageRequest;
        messageRequest = -1;
        if (request < 0) {
            request = effects.takeMessageRequest();
        }
        return request;
    }

    /** {@code PlayerHit} : le coup, et le cri qui l'accompagne. */
    private void hurt(int damage) {
        int before = state().health() + state().shields();
        state().hit(damage);
        if (state().health() + state().shields() < before) {
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SCREAM);
        }
    }

    private boolean deathOver;

    /**
     * La sequence de mort est-elle finie ? Les yeux sont a terre, l'attente ecoulee, et
     * le fondu de sortie a atteint le noir.
     */
    public boolean isDeathFinished() {
        return deathOver;
    }

    /** Commandes vides, pour les pas ou le jeu est gele. */
    private static final PlayerInput FROZEN = new PlayerInput();

    /**
     * {@code st PlayerMoved} dans {@code InitPlayerPos} : la liste du bloc de depart part
     * des le premier pas.
     */
    private boolean moved = true;

    /** Avance le monde d'un cinquantieme de seconde. */
    public void tick(PlayerInput input) {
        // Teleport met ProgramState a -1 : le temps du fondu, les commandes sont ignorees
        // et les objets ne bougent plus.
        boolean frozen = fade.freezesGame();

        // SwitchManagement passe avant le deplacement, sur la case et le cap du pas
        // precedent.
        if (input.action) {
            input.action = false;
            if (!frozen) {
                effects.useSwitch(camera);
            }
        }

        sound.setListener(camera.x() >> 16, camera.z() >> 16);
        if (firstTick) {
            // TMapStart : le fondu d'entree part sur le son du teleport.
            firstTick = false;
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_TELEPORT);
        }
        if (state().isDead()) {
            player.die();
        }
        player.update(camera, frozen ? FROZEN : input);
        moved |= player.changedBlock();

        int fallDamage = player.takeFallDamage();
        if (fallDamage > 0) {
            hurt(fallDamage);
        }
        if (player.takeStepSound()) {
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_STEP);
        }

        currentBlock = blockUnderCamera();
        // PlayerMoved n'est leve qu'au changement de bloc — et par InitPlayerPos, d'ou le
        // declenchement du bloc de depart au premier pas. Rien ne part pendant une chute.
        if (moved && !player.isFalling()) {
            effects.enterBlock(currentBlock);
        }
        moved = false;

        effects.update(1, currentBlock, world.objects());
        int request = effects.takeTerminalRequest();
        if (request >= 0 && terminal != null) {
            terminal.open(request);
        }
        int[] destination = effects.takeTeleportRequest();
        if (destination != null) {
            teleport(destination[0], destination[1]);
        }
        // EndLevel leve Escape ; la boucle principale lance alors le fondu de sortie, et
        // le niveau ne se quitte qu'une fois le noir atteint. La fin de la sequence de
        // mort leve Escape de la meme facon.
        if ((effects.takeLevelFinished() || player.isDeathFinished()) && !leaving) {
            leaving = true;
            fade.levelEnd();
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_TELEPORT);
        }
        if (leaving && fade.reachedFull()) {
            levelFinished = !player.isDeathFinished();
            deathOver = player.isDeathFinished();
        }
        fade.advance(1);
        world.animate(1);
        followFloors();
        collectPickups();

        int before = state().health() + state().shields();
        // DMnohurt : un joueur qui tombe ne touche pas encore le sol, et le compteur repart.
        int damage = player.isFalling() ? 0 : world.level().blocks().get(currentBlock).floorDamage();
        state().standOn(damage, 1);
        if (state().health() + state().shields() < before) {
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SCREAM);
        }

        if (!frozen) {
            if (combat != null) {
                combat.setPlayerSpeed(player.speed());
                combat.update(camera, input, currentBlock);
            }
            enemies.update(camera, state(), combat);
        }
        autoMap.survey(camera);
        state().advance(1);
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
            // DrawObjects ne recale que les types autres que 4 et 5 : une explosion reste
            // centree sur ce qui a explose.
            int type = object.definition().objectType();
            if (type == ObjectDefinition.TYPE_SHOT || type == ObjectDefinition.TYPE_EXPLOSION) {
                continue;
            }
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
            // CtrlCollObj ne lit pas obj_inactive : un ramassable porteur d'un trigger se
            // prend des le depart, seul ActiveEnemy l'aurait attendu.
            if (object.isCollected()
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
            if (state().collect(object.pickupSubtype(), object.pickupValue())) {
                object.collect();
                // Le message du ramassage, puis le premier son de l'objet.
                messageRequest = Messages.forPickup(object.pickupSubtype());
                java.util.List<String> names = object.definition().sounds();
                if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                    sound.play(names.get(0));
                }
            }
        }
    }

    private int blockUnderCamera() {
        return Math.abs(world.cell(camera.cellX(), camera.cellZ()));
    }
}
