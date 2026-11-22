package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.object.ObjectDefinition;

import java.util.List;

/**
 * Comportement des ennemis.
 *
 * <p>Portage de la boucle {@code MNloop} et de {@code ChooseEnemyDir}
 * (Sorgenti/Objects.asm). Chaque ennemi suit une petite machine a etats, dont les noms
 * viennent des commentaires d'origine :
 *
 * <ul>
 *   <li><strong>0 chercher</strong> — se tourne d'un huitieme de tour vers le joueur a
 *       chaque decision ;</li>
 *   <li><strong>1 errer</strong> — garde son cap trois fois sur quatre, sinon tourne d'un
 *       cote ou de l'autre ;</li>
 *   <li><strong>2 et 3</strong> — apres avoir bute, contournent l'obstacle dans un sens
 *       qu'ils gardent jusqu'a ce qu'un pas reussisse ;</li>
 *   <li><strong>4 viser</strong> — s'arrete et se tourne vers le joueur avant de tirer ;</li>
 *   <li><strong>5 encaisser</strong> — repart dans un comportement tire au sort ;</li>
 *   <li><strong>6 coller</strong> — apres avoir touche le joueur, reste sur lui ;</li>
 *   <li><strong>-1 tirer</strong>, <strong>-3 tomber</strong>.</li>
 * </ul>
 *
 * <p>Une decision n'est pas prise a chaque pas : {@code obj_cont1} la retarde, de trois
 * pas en visee a quarante-sept apres un obstacle. C'est ce qui donne aux ennemis leur
 * allure — des lignes droites, pas une poursuite au plus pres.
 *
 * <p>Deux economies du moteur d'origine sont conservees parce qu'elles changent le
 * comportement observable : les ennemis ne sont mis a jour qu'<strong>une image sur
 * deux</strong>, et seuls ceux qui ont ete dessines a la derniere image ou qui sont a
 * moins de vingt blocs bougent.
 */
public final class EnemyAi {

    /** {@code MAX_ENEMY_DIST} : vingt blocs, au carre. */
    private static final long MAX_DISTANCE_SQUARED = (long) (20 * Level.BLOCK_SIZE) * (20 * Level.BLOCK_SIZE);

    /** Denivele franchissable par un ennemi. */
    private static final int MAX_RISE = 24;

    /** Les ennemis tournent par huitiemes de tour. */
    private static final int TURN = 256;

    /** {@code PLAYER_WIDTH} et {@code PLAYER_EYES_HEIGHT} de TMap.i. */
    private static final int PLAYER_WIDTH = 16;

    /**
     * {@code ObjSinCos} : trente-deux directions en 8.8, recopiees telles quelles. Les
     * valeurs ne sont pas des arrondis exacts — 236 la ou le cosinus de 22,5 degres donne
     * 237 — et les ennemis avancent d'autant.
     */
    private static final int[] COS = new int[32];
    private static final int[] SIN = new int[32];

    static {
        int[] quadrant = {256, 251, 236, 213, 181, 142, 98, 50, 0};
        for (int i = 0; i < 32; i++) {
            int q = i & 7;
            int c = quadrant[q];
            int s = quadrant[8 - q];
            switch (i >> 3) {
                case 0 -> { COS[i] = c; SIN[i] = s; }
                case 1 -> { COS[i] = -s; SIN[i] = c; }
                case 2 -> { COS[i] = -c; SIN[i] = -s; }
                default -> { COS[i] = s; SIN[i] = -c; }
            }
        }
    }

    // Etats, tels que numerotes par la table de repartition d'origine.
    private static final int DEAD = -3;
    private static final int FIRING = -1;
    private static final int SEEK = 0;
    private static final int WANDER = 1;
    private static final int SEEK_AFTER_WALL = 2;
    private static final int WANDER_AFTER_WALL = 3;
    private static final int AIMING = 4;
    private static final int HURT = 5;
    private static final int CLINGING = 6;

    /** Premiere et derniere image de la chute. {@code CEDcaduta}. */
    private static final int FALL_FIRST_FRAME = 42;
    private static final int FALL_LAST_FRAME = 44;

    /** Duree d'une image de chute, en pas d'ennemi. */
    private static final int FALL_FRAME_TICKS = 8;

    /** Vitesse a laquelle le corps glisse dans la direction du tir. */
    private static final int FALL_SPEED = 5;

    /** Image du corps au sol, une fois la chute finie. */
    private static final int REMAINS_FRAME = 129;

    /** Image de tir, prise d'un bloc par un ennemi qui s'arrete pour tirer. */
    private static final int FIRE_FRAME = 40;

    // Bits de obj_subtype (o_param10).
    /** Bit 0 : l'ennemi tire en marchant. */
    private static final int KEEPS_WALKING_WHEN_FIRING = 0x01;
    /** Bit 1 : l'ennemi ne s'arrete pas quand il est touche. */
    private static final int KEEPS_WALKING_WHEN_HIT = 0x02;
    /** Bit 2 : apres avoir touche le joueur, il lui reste dessus. */
    private static final int CLINGS_TO_PLAYER = 0x04;

    // Bits de obj_bmstatus.
    private static final int BLOCKED_X = 0x20;
    private static final int BLOCKED_Z = 0x40;
    private static final int IN_VIEW = 0x80;

    private final World world;
    private final Rnd rnd;
    private boolean oddTick;
    private int playerY;

    /** {@code VBTimer} : les cinquantiemes ecoules, qui rythment la marche. */
    private int vbTimer;

    private SoundPlayer sound = SoundPlayer.SILENT;

    /** Branche le son : les ennemis grondent en entrant en scene. */
    public void setSoundPlayer(SoundPlayer player) {
        this.sound = (player == null) ? SoundPlayer.SILENT : player;
    }

    public EnemyAi(World world) {
        this(world, new Rnd());
    }

    public EnemyAi(World world, Rnd rnd) {
        this.world = world;
        this.rnd = rnd;
        for (SceneObject object : world.objects()) {
            if (object.definition().objectType() == ObjectDefinition.TYPE_ENEMY) {
                init(object);
            }
        }
    }

    /**
     * Etat de depart d'un ennemi. {@code InitObjects} (TMapMain.asm) : statut 0,
     * {@code obj_cont1 = $0F04}, vitesse de marche, et un delai d'attaque tire entre 0 et
     * 63 — sans quoi tous les ennemis d'une piece tireraient au meme instant.
     */
    private void init(SceneObject enemy) {
        enemy.setStatus(SEEK);
        enemy.setCont1(15);
        enemy.setCont2(4);
        enemy.setSpeed(walkSpeed(enemy));
        enemy.setRotDir(0);
        enemy.setPlayerColl(0);
        enemy.setBmStatus(0);
        enemy.setAttackDelay((byte) rnd.next(64));
    }

    private static int walkSpeed(SceneObject enemy) {
        return enemy.definition().param(8) & 0xFF;
    }

    private static int subtype(SceneObject enemy) {
        return enemy.definition().param(10) & 0xFF;
    }

    /** Avance les ennemis. Un pas sur deux seulement, comme dans l'original. */
    public void update(Camera camera, PlayerState state, Combat combat) {
        vbTimer++;
        this.playerY = camera.y();
        oddTick = !oddTick;
        if (!oddTick) {
            return;
        }

        int playerX = camera.x() >> 16;
        int playerZ = camera.z() >> 16;

        List<SceneObject> objects = world.objects();
        for (int i = 0; i < objects.size(); i++) {
            SceneObject enemy = objects.get(i);
            if (enemy.definition().objectType() != ObjectDefinition.TYPE_ENEMY
                    || enemy.isCollected() || enemy.isRemains() || enemy.isDormant()) {
                continue;
            }
            step(enemy, playerX, playerZ, state, combat);
        }
    }

    /** Un passage de {@code MNloop} pour un ennemi. */
    private void step(SceneObject enemy, int playerX, int playerZ,
                      PlayerState state, Combat combat) {
        int speed = enemy.speed();
        if (speed != 0 && !tryMove(enemy, speed, playerX, playerZ, state, combat)) {
            return;   // MNnext : trop loin, ou l'attaque vient d'etre declenchee
        }

        // MNnomove
        enemy.setCont1(enemy.cont1() - 1);
        if (enemy.cont1() <= 0) {
            chooseDirection(enemy, playerX, playerZ, combat);
        }
        int status = enemy.status();
        if (status >= 0 && status != HURT) {
            walkAnimation(enemy);
        }
    }

    /**
     * La partie mouvement de {@code MNloop}.
     *
     * @return {@code false} pour sauter la fin du passage ({@code bra MNnext})
     */
    private boolean tryMove(SceneObject enemy, int speed, int playerX, int playerZ,
                            PlayerState state, Combat combat) {
        int direction = (enemy.heading() >> 6) & 31;
        int x = (short) (enemy.x() + ((COS[direction] * speed) >> 8));
        int z = (short) (enemy.z() + ((SIN[direction] * speed) >> 8));

        int dx = (short) (playerX - x);
        int dz = (short) (playerZ - z);
        long distance = (long) dx * dx + (long) dz * dz;

        // Seuls bougent les ennemis vus a la derniere image, ou a moins de vingt blocs.
        if ((enemy.bmStatus() & IN_VIEW) == 0 && distance > MAX_DISTANCE_SQUARED) {
            return false;
        }
        enemy.setBmStatus(enemy.bmStatus() & ~IN_VIEW);

        // Gestion du tir : le delai ne court que si le joueur est a portee.
        long attackRange = (long) (enemy.definition().param(1) & 0xFFFF)
                * (enemy.definition().param(1) & 0xFFFF);
        if (distance <= attackRange) {
            int delay = (byte) (enemy.attackDelay() - 1);
            enemy.setAttackDelay(delay);
            if (delay <= 0) {
                if (enemy.status() >= 0) {
                    enemy.setStatus(AIMING);
                    enemy.setCont1(1);
                    enemy.setCont2(4);
                    enemy.setSpeed(0);
                }
                // lsl.b #2 / add.b / addq.b #8 : tout se calcule sur un octet signe, et un
                // resultat de 128 ou plus repasse negatif — l'ennemi rattaquera aussitot.
                int probability = enemy.definition().param(9) & 0xFF;
                int draw = rnd.next(probability);
                enemy.setAttackDelay((byte) (((draw << 2) & 0xFF) + probability + 8));
                return false;
            }
        }

        // Collision avec le joueur.
        int width = enemy.definition().radius() & 0xFFFF;
        long reach = (long) width * width + 2L * PLAYER_WIDTH * PLAYER_WIDTH;
        if (distance <= reach) {
            playerCollision(enemy, state);
            return true;
        }
        if (enemy.playerColl() > 0) {
            enemy.setPlayerColl(enemy.playerColl() - 1);
        }

        int cellX = Math.floorDiv(x, Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(z, Level.BLOCK_SIZE);
        int code = world.cell(cellX, cellZ);
        if (code < 0) {
            blocked(enemy);
            return true;
        }
        Block target = world.level().blocks().get(code);
        if (target.blocksEnemies()) {
            blocked(enemy);
            return true;
        }
        int newFloor = world.floorHeight(code);
        int newCeil = world.ceilHeight(code);

        int fromX = Math.floorDiv(enemy.x(), Level.BLOCK_SIZE);
        int fromZ = Math.floorDiv(enemy.z(), Level.BLOCK_SIZE);
        boolean changesCell = cellX != fromX || cellZ != fromZ;
        if (changesCell) {
            int from = Math.abs(world.cell(fromX, fromZ));
            if (!passable(enemy, newFloor, newCeil, world.floorHeight(from), world.ceilHeight(from))) {
                blocked(enemy);
                return true;
            }
        }

        // Tests de coin : la case du centre ne suffit pas, l'ennemi a une largeur.
        enemy.setBmStatus(enemy.bmStatus() & 0b1001_1111);
        if (!cornersFree(enemy, x, z, cellX, cellZ, newFloor, newCeil)) {
            blocked(enemy);
            return true;
        }

        // MNctrlobjcoll : un ennemi ou un decor sur la case d'arrivee barre le passage.
        if (changesCell && occupied(enemy, cellX, cellZ)) {
            blocked(enemy);
            return true;
        }

        enemy.setPosition(x, z);
        enemy.setRotDir(0);
        return true;
    }

    /**
     * Le passage d'une case a l'autre est-il possible ? Meme test dans {@code MNloop} et
     * dans {@code CTRL_OBJ_COLLISION}.
     *
     * <p>En montee, la marche ne doit pas depasser 24 et le plus bas des deux plafonds doit
     * laisser passer l'ennemi au-dessus du nouveau sol. En descente, c'est le nouveau
     * plafond qui se compare au sol actuel.
     */
    private static boolean passable(SceneObject enemy, int newFloor, int newCeil,
                                    int oldFloor, int oldCeil) {
        int rise = (short) (newFloor - oldFloor);
        int height = enemy.definition().height();
        if (rise >= 0) {
            if (rise > MAX_RISE) {
                return false;
            }
            int lowest = Math.min(newCeil, oldCeil);
            return (short) (lowest - newFloor) >= height;
        }
        if (-rise > MAX_RISE) {
            return false;
        }
        return (short) (newCeil - oldFloor) >= height;
    }

    /**
     * {@code CT0} a {@code CT7} : trois points a une largeur du centre, choisis selon
     * l'octant du cap. Un coin qui bute selon un seul axe le signale dans les bits 5 ou 6
     * de {@code obj_bmstatus}, pour que le contournement sache de quel cote tourner.
     */
    private boolean cornersFree(SceneObject enemy, int x, int z, int cellX, int cellZ,
                                int floor, int ceil) {
        int w = enemy.definition().radius();
        int[][] points = switch ((enemy.heading() >> 8) & 7) {
            case 0 -> new int[][]{{x + w, z - w, 0}, {x + w, z, 0}, {x + w, z + w, 0}};
            case 1 -> new int[][]{{x + w, z, BLOCKED_X}, {x, z + w, BLOCKED_Z}, {x + w, z + w, 0}};
            case 2 -> new int[][]{{x + w, z + w, 0}, {x, z + w, 0}, {x - w, z + w, 0}};
            case 3 -> new int[][]{{x, z + w, BLOCKED_Z}, {x - w, z, BLOCKED_X}, {x - w, z + w, 0}};
            case 4 -> new int[][]{{x - w, z + w, 0}, {x - w, z, 0}, {x - w, z - w, 0}};
            case 5 -> new int[][]{{x - w, z, BLOCKED_X}, {x, z - w, BLOCKED_Z}, {x - w, z - w, 0}};
            case 6 -> new int[][]{{x - w, z - w, 0}, {x, z - w, 0}, {x + w, z - w, 0}};
            default -> new int[][]{{x, z - w, BLOCKED_Z}, {x + w, z, BLOCKED_X}, {x + w, z - w, 0}};
        };
        for (int[] point : points) {
            int px = Math.floorDiv((short) point[0], Level.BLOCK_SIZE);
            int pz = Math.floorDiv((short) point[1], Level.BLOCK_SIZE);
            if (px == cellX && pz == cellZ) {
                continue;
            }
            int code = world.cell(px, pz);
            boolean ok = code >= 0 && cornerPassable(enemy, floor, ceil,
                    world.floorHeight(code), world.ceilHeight(code));
            if (!ok) {
                enemy.setBmStatus(enemy.bmStatus() | point[2]);
                return false;
            }
        }
        return true;
    }

    /**
     * Le test de {@code CTRL_OBJ_COLLISION}, dont la descente se compare strictement :
     * {@code bgt CTexit}, un plafond tout juste a la hauteur de l'ennemi le bloque.
     */
    private static boolean cornerPassable(SceneObject enemy, int floor, int ceil,
                                          int cornerFloor, int cornerCeil) {
        int drop = (short) (floor - cornerFloor);
        int height = enemy.definition().height();
        if (drop >= 0) {
            if (drop > MAX_RISE) {
                return false;
            }
            int lowest = Math.min(ceil, cornerCeil);
            return (short) (lowest - floor) >= height;
        }
        if (-drop > MAX_RISE) {
            return false;
        }
        return (short) (ceil - cornerFloor) > height;
    }

    /** Un ennemi ou un decor occupe-t-il deja cette case ? Types 0 a 2, morts exclus. */
    private boolean occupied(SceneObject enemy, int cellX, int cellZ) {
        for (SceneObject other : world.objects()) {
            if (other == enemy || other.isCollected() || other.isRemains()
                    || other.isSelfAnimated()
                    || other.definition().objectType() > ObjectDefinition.TYPE_ENEMY) {
                continue;
            }
            if (Math.floorDiv(other.x(), Level.BLOCK_SIZE) == cellX
                    && Math.floorDiv(other.z(), Level.BLOCK_SIZE) == cellZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code MNnewdirP} : l'ennemi touche le joueur. Il le frappe au premier contact,
     * puis une fois tous les dix passages tant qu'il reste dessus.
     */
    private void playerCollision(SceneObject enemy, PlayerState state) {
        enemy.setPlayerColl(enemy.playerColl() - 1);
        if (enemy.playerColl() <= 0) {
            enemy.setPlayerColl(10);
            if (state != null) {
                int before = state.health() + state.shields();
                state.hit(enemy.definition().param(7) & 0xFF);
                if (state.health() + state.shields() < before) {
                    sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SCREAM);
                }
            }
        }
        if ((subtype(enemy) & CLINGS_TO_PLAYER) != 0) {
            enemy.setStatus(CLINGING);
            enemy.setCont1(3);
            enemy.setCont2(1);
            enemy.setSpeed(0);
            return;
        }
        blocked(enemy);
    }

    /**
     * {@code MNnewdir} : l'ennemi bute. Il s'arrete, et decidera au prochain passage d'un
     * contournement. Un corps qui tombe s'arrete seulement.
     *
     * <p>L'original ajoutait 2 au statut ; la ligne a ete remplacee par un 2 force « pour
     * rendre les ennemis moins bêtes », et c'est cette version qui a ete livree.
     */
    private static void blocked(SceneObject enemy) {
        if (enemy.status() > DEAD) {
            enemy.setStatus(SEEK_AFTER_WALL);
            enemy.setCont1(3);
            enemy.setCont2(1);
        }
        enemy.setSpeed(0);
    }

    /** {@code ChooseEnemyDir} : la decision, quand {@code obj_cont1} est epuise. */
    private void chooseDirection(SceneObject enemy, int playerX, int playerZ, Combat combat) {
        int dx = (short) (playerX - enemy.x());
        int dz = (short) (playerZ - enemy.z());
        switch (enemy.status()) {
            case DEAD -> fall(enemy);
            case FIRING -> {
                if (combat != null) {
                    combat.enemyShot(enemy, enemy.definition().param(5) & 0xFF,
                            playerX, playerZ, playerY);
                }
                enemy.setSpeed(walkSpeed(enemy));
                int next = rnd.next(2);
                enemy.setStatus(next);
                enemy.setCont1(2);
                enemy.setCont2(next != 0 ? 2 : 4);
                walkAnimation(enemy);
            }
            case SEEK -> {
                enemy.setSpeed(walkSpeed(enemy));
                enemy.setCont1(15);
                enemy.setCont2(enemy.cont2() - 1);
                if (enemy.cont2() > 0) {
                    turnTowards(enemy, dx, dz);
                } else {
                    enemy.setCont2(2);
                    enemy.setStatus(WANDER);
                    wander(enemy);
                }
            }
            case WANDER -> {
                enemy.setSpeed(walkSpeed(enemy));
                enemy.setCont1(15);
                enemy.setCont2(enemy.cont2() - 1);
                if (enemy.cont2() > 0) {
                    wander(enemy);
                } else {
                    enemy.setCont2(5);
                    enemy.setStatus(SEEK);
                    turnTowards(enemy, dx, dz);
                }
            }
            case SEEK_AFTER_WALL -> {
                // CEDnewdir1 : le sens de contournement se choisit vers le joueur, en
                // ignorant l'axe sur lequel un coin a bute ; il est garde tant que
                // l'ennemi ne reussit pas un pas.
                if (enemy.rotDir() == 0) {
                    int ax = (enemy.bmStatus() & BLOCKED_X) != 0 ? 0 : dx;
                    int az = (enemy.bmStatus() & BLOCKED_Z) != 0 ? 0 : dz;
                    enemy.setRotDir(side(enemy.heading(), ax, az) < 0 ? -1 : 1);
                }
                enemy.setHeading(enemy.heading() + enemy.rotDir() * TURN);
                enemy.setCont1(47);
                enemy.setCont2(5);
                enemy.setStatus(SEEK);
                enemy.setSpeed(walkSpeed(enemy));
            }
            case WANDER_AFTER_WALL -> {
                if (enemy.rotDir() == 0) {
                    enemy.setRotDir(rnd.next(2) == 0 ? 1 : -1);
                }
                enemy.setHeading(enemy.heading() + enemy.rotDir() * TURN);
                enemy.setCont1(15);
                enemy.setCont2(3);
                enemy.setStatus(WANDER);
                enemy.setSpeed(walkSpeed(enemy));
            }
            case AIMING -> {
                enemy.setCont2(enemy.cont2() - 1);
                if (enemy.cont2() <= 0) {
                    startFiring(enemy);
                    return;
                }
                // Deja tourne vers le joueur : il tirera a la decision suivante.
                if (facing(enemy.heading(), dx, dz) >= 0) {
                    enemy.setCont2(1);
                }
                enemy.setCont1(3);
                turnTowards(enemy, dx, dz);
            }
            case HURT -> {
                int next = rnd.next(2);
                enemy.setStatus(next);
                enemy.setCont1(1);
                enemy.setCont2(next != 0 ? 2 : 4);
                walkAnimation(enemy);
            }
            case CLINGING -> {
                turnTowards(enemy, dx, dz);
                enemy.setCont1(15);
                enemy.setCont2(5);
                enemy.setStatus(SEEK);
                enemy.setSpeed(walkSpeed(enemy));
            }
            default -> {
                // Statut -2 : aucune action dans l'original.
            }
        }
    }

    /**
     * {@code CEDgofire} : un ennemi qui s'arrete pour tirer prend sa pose de tir pour dix
     * passages ; celui qui tire en marchant garde son allure.
     */
    private static void startFiring(SceneObject enemy) {
        enemy.setStatus(FIRING);
        if ((subtype(enemy) & KEEPS_WALKING_WHEN_FIRING) == 0) {
            enemy.setAnimCounter(FIRE_FRAME);
            enemy.setCont1(10);
            enemy.setCont2(1);
            enemy.setSpeed(0);
        } else {
            enemy.setCont1(1);
            enemy.setCont2(1);
        }
    }

    /**
     * {@code EPangleTable} et {@code NewDirTable} : de quel cote du cap se trouve le
     * point (dx, dz) ? Le signe d'un produit vectoriel simplifie, une formule par octant.
     * Les calculs se font sur seize bits, comme les {@code add.w} d'origine.
     */
    private static int side(int heading, int dx, int dz) {
        return switch ((heading >> 8) & 7) {
            case 0 -> (short) dz;
            case 1 -> (short) (dz - dx);
            case 2 -> (short) -dx;
            case 3 -> (short) -(dz + dx);
            case 4 -> (short) -dz;
            case 5 -> (short) (dx - dz);
            case 6 -> (short) dx;
            default -> (short) (dz + dx);
        };
    }

    /** {@code ProdScalTable} : le point (dx, dz) est-il devant ? Produit scalaire. */
    private static int facing(int heading, int dx, int dz) {
        return switch ((heading >> 8) & 7) {
            case 0 -> (short) dx;
            case 1 -> (short) (dx + dz);
            case 2 -> (short) dz;
            case 3 -> (short) (dz - dx);
            case 4 -> (short) -dx;
            case 5 -> (short) -(dx + dz);
            case 6 -> (short) -dz;
            default -> (short) (dx - dz);
        };
    }

    /**
     * {@code CEDseekret} : un huitieme de tour du cote du joueur, ou rien s'il est
     * exactement dans l'axe. Sans zone morte, l'approche se fait en zigzag.
     */
    private static void turnTowards(SceneObject enemy, int dx, int dz) {
        int side = side(enemy.heading(), dx, dz);
        if (side > 0) {
            enemy.setHeading(enemy.heading() + TURN);
        } else if (side < 0) {
            enemy.setHeading(enemy.heading() - TURN);
        }
    }

    /** {@code CEDrandomok} : garde le cap trois fois sur quatre, sinon tourne. */
    private void wander(SceneObject enemy) {
        int draw = rnd.next(32767);
        if (draw <= 24576) {
            return;
        }
        enemy.setHeading(enemy.heading() + (draw > 28672 ? TURN : -TURN));
    }

    /**
     * {@code WALKANIM} : la pose de marche suit l'horloge, pas les pas de l'ennemi.
     * Huit poses, une toutes les quatre cinquantiemes ; elle tourne meme a l'arret.
     */
    private void walkAnimation(SceneObject enemy) {
        enemy.setAnimCounter((vbTimer & 0x1C) >> 2);
    }

    /**
     * Un tir atteint un ennemi. {@code MSctrlobjcoll1} : sauf si son sous-type le lui
     * epargne, il s'arrete et encaisse pendant deux passages. La perte de resistance est
     * a la charge de l'appelant.
     */
    public static void wound(SceneObject enemy) {
        if ((subtype(enemy) & KEEPS_WALKING_WHEN_HIT) != 0) {
            return;
        }
        enemy.setStatus(HURT);
        enemy.setCont1(2);
        enemy.setCont2(1);
        enemy.setSpeed(0);
    }

    /**
     * L'ennemi est-il deja en train de s'abattre ?
     *
     * <p>{@code MSctrlobjcoll1} porte la remarque en toutes lettres : « se il nemico sta
     * cadendo non deve ucciderlo di nuovo ». Un corps qui tombe n'encaisse plus rien — le
     * blesser lui rendrait un statut vivant, et il se figerait debout au milieu de sa
     * chute.
     */
    public static boolean isFalling(SceneObject enemy) {
        return enemy.status() == DEAD;
    }

    /**
     * Abat un ennemi, d'un tir qui n'explose pas.
     *
     * @see #kill(SceneObject, int, boolean)
     */
    public static boolean kill(SceneObject enemy, int heading) {
        return kill(enemy, heading, false);
    }

    /**
     * Abat un ennemi.
     *
     * <p>{@code DestroyEnemy} : un tir qui declare une explosion fait exploser sa cible.
     * Sinon, si son type decrit une animation de chute — les images 42 a 44 —, elle tombe
     * dans la direction du tir avant de rester au sol. Dans les autres cas elle explose,
     * et c'est a l'appelant de poser l'explosion.
     *
     * @param heading       direction du tir, qui devient celle de la chute
     * @param shotExplodes  le tir declare une explosion ({@code o_param9} positif ou nul)
     * @return {@code true} si une chute a commence
     */
    public static boolean kill(SceneObject enemy, int heading, boolean shotExplodes) {
        if (shotExplodes || !hasFallAnimation(enemy)) {
            enemy.setStatus(-2);
            return false;
        }
        enemy.setStatus(DEAD);
        enemy.setAnimCounter(FALL_FIRST_FRAME);
        enemy.setCont1(FALL_FRAME_TICKS);
        enemy.setHeading(heading);
        enemy.setSpeed(FALL_SPEED);
        return true;
    }

    /**
     * Le type decrit-il une chute ?
     *
     * <p>L'original compare la derniere image de tir a la premiere de chute : si les deux
     * pointent au meme endroit, il n'y a pas d'animation.
     */
    private static boolean hasFallAnimation(SceneObject enemy) {
        var frames = enemy.definition().frames();
        if (frames.size() <= FALL_FIRST_FRAME) {
            return false;
        }
        return frames.get(FALL_FIRST_FRAME - 1) != frames.get(FALL_FIRST_FRAME);
    }

    /**
     * {@code CEDcaduta} : une image toutes les huit decisions, jusqu'a la
     * quarante-quatrieme ou jusqu'a ce que l'image ne change plus. Ensuite le corps prend
     * l'image 129 et devient un simple decor.
     */
    private static void fall(SceneObject enemy) {
        var frames = enemy.definition().frames();
        int current = enemy.animCounter();
        boolean finished = current == FALL_LAST_FRAME
                || current + 1 >= frames.size()
                || frames.get(current) == frames.get(current + 1);
        if (finished) {
            enemy.setAnimCounter(REMAINS_FRAME);
            enemy.setSpeed(0);
            enemy.setRemains(true);
            return;
        }
        enemy.setCont1(FALL_FRAME_TICKS);
        enemy.setAnimCounter(current + 1);
    }
}
