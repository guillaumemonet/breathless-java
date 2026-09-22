package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.object.ObjectDefinition;

import java.util.List;
import java.util.Random;

/**
 * Comportement des ennemis.
 *
 * <p>Portage de la boucle {@code MNloop} et de {@code ChooseEnemyDir}
 * (Sorgenti/Objects.asm). Chaque ennemi suit une petite machine a etats, dont les noms
 * viennent des commentaires d'origine :
 *
 * <ul>
 *   <li><strong>0 chercher</strong> — se tourne de 45 degres vers le joueur a chaque
 *       decision, jusqu'a lui faire face ;</li>
 *   <li><strong>1 errer</strong> — garde son cap trois fois sur quatre, sinon tourne d'un
 *       cote ou de l'autre ;</li>
 *   <li><strong>2 et 3</strong> — apres avoir bute sur un mur, reprennent l'un ou l'autre
 *       des deux comportements precedents ;</li>
 *   <li><strong>4 viser</strong> — s'arrete et se tourne vers le joueur avant de tirer ;</li>
 *   <li><strong>5 encaisser</strong> — repart dans une direction tiree au sort ;</li>
 *   <li><strong>6 coller</strong> — apres avoir touche le joueur, reste sur lui.</li>
 * </ul>
 *
 * <p>Deux economies du moteur d'origine sont conservees parce qu'elles changent le
 * comportement observable : les ennemis ne sont mis a jour qu'<strong>une image sur
 * deux</strong>, et seuls ceux qui sont en vue ou a moins de vingt blocs bougent. Un
 * ennemi lointain et hors champ reste donc fige jusqu'a ce qu'on s'en approche.
 */
public final class EnemyAi {

    /** Distance au-dela de laquelle un ennemi hors du champ de vision ne bouge pas. */
    private static final int MAX_DISTANCE = 20 * Level.BLOCK_SIZE;

    /** Denivele franchissable par un ennemi. */
    private static final int MAX_RISE = 24;

    /** Un quart de tour vaut 512 pas ; les ennemis tournent par huitiemes. */
    private static final int TURN = 256;

    /** Table des 32 directions en 8.8, {@code ObjSinCos} dans l'original. */
    private static final int[] COS = new int[32];
    private static final int[] SIN = new int[32];

    static {
        for (int i = 0; i < 32; i++) {
            double angle = 2.0 * Math.PI * i / 32;
            COS[i] = (int) Math.round(Math.cos(angle) * 256);
            SIN[i] = (int) Math.round(Math.sin(angle) * 256);
        }
    }

    // Etats, tels que numerotes par la table de repartition d'origine.
    private static final int DEAD = -3;

    /** Premiere et derniere image de la chute. {@code CEDcaduta}. */
    private static final int FALL_FIRST_FRAME = 42;
    private static final int FALL_LAST_FRAME = 44;

    /** Duree d'une image de chute, en pas. */
    private static final int FALL_FRAME_TICKS = 8;

    /** Vitesse a laquelle le corps glisse dans la direction du tir. */
    private static final int FALL_SPEED = 5;

    /** Image du corps au sol, une fois la chute finie. */
    private static final int REMAINS_FRAME = 129;
    private static final int FIRING = -1;
    private static final int SEEK = 0;
    private static final int WANDER = 1;
    private static final int SEEK_AFTER_WALL = 2;
    private static final int WANDER_AFTER_WALL = 3;
    private static final int AIMING = 4;
    private static final int HURT = 5;
    private static final int CLINGING = 6;

    private final World world;
    private final Random random = new Random(0x4E454D49);
    private boolean oddTick;
    private int playerY;

    private SoundPlayer sound = SoundPlayer.SILENT;

    /** Branche le son : les ennemis grondent en entrant en scene. */
    public void setSoundPlayer(SoundPlayer player) {
        this.sound = (player == null) ? SoundPlayer.SILENT : player;
    }

    public EnemyAi(World world) {
        this.world = world;
    }

    /** Avance les ennemis. Un pas sur deux seulement, comme dans l'original. */
    public void update(Camera camera, PlayerState state, Combat combat) {
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
                    || enemy.isCollected() || enemy.isRemains()) {
                continue;
            }
            if (enemy.status() == DEAD) {
                // Un corps continue sur sa lancee le temps de s'abattre.
                move(enemy, playerX, playerZ);
                fall(enemy);
                continue;
            }
            if (enemy.isDormant()) {
                continue;
            }
            step(enemy, playerX, playerZ, state, combat);
        }
    }

    private void step(SceneObject enemy, int playerX, int playerZ,
                      PlayerState state, Combat combat) {
        if (enemy.strength() <= 0) {
            return;
        }

        int dx = playerX - enemy.x();
        int dz = playerZ - enemy.z();
        int distance = dx * dx + dz * dz;

        // Un ennemi lointain et hors du champ de vision ne consomme rien.
        if (distance > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }

        ObjectDefinition definition = enemy.definition();

        // Attaque : le compte a rebours ne tourne que si le joueur est a portee.
        int attackRange = definition.param(1) * definition.param(1);
        if (distance <= attackRange && enemy.status() != FIRING) {
            enemy.setAttackDelay(enemy.attackDelay() - 1);
            if (enemy.attackDelay() <= 0) {
                enemy.setStatus(AIMING);
                enemy.setCont2(4);
                enemy.setSpeed(0);
                resetAttackDelay(enemy);
            }
        }

        decide(enemy, playerX, playerZ, state, combat, distance);
        move(enemy, playerX, playerZ);
        animate(enemy);
    }

    /** Recharge le delai d'attaque : une base aleatoire plus la probabilite de l'ennemi. */
    private void resetAttackDelay(SceneObject enemy) {
        int probability = Math.max(1, enemy.definition().param(9));
        enemy.setAttackDelay((random.nextInt(probability) << 2) + probability + 8);
    }

    private void decide(SceneObject enemy, int playerX, int playerZ,
                        PlayerState state, Combat combat, int distance) {
        switch (enemy.status()) {
            case SEEK -> {
                enemy.setSpeed(enemy.definition().param(8));
                if (countDown(enemy, 2, WANDER)) {
                    wander(enemy);
                } else {
                    turnTowards(enemy, playerX, playerZ);
                }
            }
            case WANDER -> {
                enemy.setSpeed(enemy.definition().param(8));
                if (countDown(enemy, 5, SEEK)) {
                    turnTowards(enemy, playerX, playerZ);
                } else {
                    wander(enemy);
                }
            }
            case SEEK_AFTER_WALL -> {
                enemy.setStatus(SEEK);
                turnTowards(enemy, playerX, playerZ);
            }
            case WANDER_AFTER_WALL -> {
                enemy.setStatus(WANDER);
                wander(enemy);
            }
            case AIMING -> {
                enemy.setCont2(enemy.cont2() - 1);
                if (enemy.cont2() <= 0) {
                    enemy.setStatus(FIRING);
                } else {
                    turnTowards(enemy, playerX, playerZ);
                }
            }
            case FIRING -> {
                attack(enemy, state, combat, distance, playerX, playerZ);
                enemy.setStatus(SEEK);
                enemy.setSpeed(enemy.definition().param(8));
            }
            case HURT -> {
                // Encaisser desoriente : l'ennemi repart dans un comportement tire au sort.
                enemy.setStatus(random.nextInt(3));
                enemy.setCont2(2);
            }
            case CLINGING -> {
                turnTowards(enemy, playerX, playerZ);
                enemy.setStatus(SEEK);
                enemy.setCont2(5);
                enemy.setSpeed(enemy.definition().param(8));
            }
            default -> enemy.setStatus(SEEK);
        }
    }

    /**
     * Decremente le compteur d'etat et bascule quand il s'epuise.
     *
     * @return {@code true} si la bascule a eu lieu
     */
    private static boolean countDown(SceneObject enemy, int reload, int nextStatus) {
        enemy.setCont2(enemy.cont2() - 1);
        if (enemy.cont2() > 0) {
            return false;
        }
        enemy.setCont2(reload);
        enemy.setStatus(nextStatus);
        return true;
    }

    /** Se tourne d'un huitieme de tour vers le joueur, ou garde le cap s'il y fait face. */
    private static void turnTowards(SceneObject enemy, int playerX, int playerZ) {
        int target = angleOf(playerX - enemy.x(), playerZ - enemy.z());
        int difference = ((target - enemy.heading() + Trig.STEPS + Trig.STEPS / 2) & Trig.MASK)
                - Trig.STEPS / 2;

        if (difference > TURN / 2) {
            enemy.setHeading(enemy.heading() + TURN);
        } else if (difference < -TURN / 2) {
            enemy.setHeading(enemy.heading() - TURN);
        }
    }

    /** Garde le cap trois fois sur quatre, sinon tourne d'un cote ou de l'autre. */
    private void wander(SceneObject enemy) {
        int draw = random.nextInt(32768);
        if (draw <= 24576) {
            return;
        }
        enemy.setHeading(enemy.heading() + (draw > 28672 ? TURN : -TURN));
    }

    private static int angleOf(int dx, int dz) {
        double angle = Math.atan2(dz, dx);
        return (int) Math.round(angle * Trig.STEPS / (2 * Math.PI)) & Trig.MASK;
    }

    /**
     * Frappe le joueur.
     *
     * <p>Le sixieme parametre du type designe l'arme : 1 pour un coup porte au corps,
     * 2 pour un tir invisible, et au-dela le projectile a lancer. Les deux premiers cas
     * touchent sans delai, a condition d'etre a portee.
     */
    private void attack(SceneObject enemy, PlayerState state, Combat combat, int distance,
                        int playerX, int playerZ) {
        int gun = enemy.definition().param(5);
        if (gun == 0) {
            return;
        }
        int range = enemy.definition().param(1);
        if (distance > range * range) {
            return;
        }
        if (gun <= 2) {
            state.hit(enemy.definition().param(7));
            return;
        }
        if (combat != null) {
            combat.enemyShot(enemy, gun, playerX, playerZ, playerY);
        }
    }

    /** Avance l'ennemi, en refusant les cases ou il ne tient pas. */
    private void move(SceneObject enemy, int playerX, int playerZ) {
        int speed = enemy.speed();
        if (speed <= 0) {
            return;
        }
        int direction = (enemy.heading() >> 6) & 31;
        int x = enemy.x() + ((COS[direction] * speed) >> 8);
        int z = enemy.z() + ((SIN[direction] * speed) >> 8);

        // Un corps qui tombe ne decide plus rien : il s'arrete ou il bute.
        // {@code MNnewdir} : au-dessous de -3, l'original ne change pas la direction.
        boolean dying = enemy.status() <= DEAD;

        // Collision avec le joueur : l'ennemi lui reste dessus.
        int dx = playerX - x;
        int dz = playerZ - z;
        int radius = enemy.definition().radius();
        if (dx * dx + dz * dz <= radius * radius + 2 * Player.WIDTH * Player.WIDTH) {
            if (!dying) {
                enemy.setStatus(CLINGING);
            } else {
                enemy.setSpeed(0);
            }
            return;
        }

        if (!canStand(enemy, x, z)) {
            if (dying) {
                enemy.setSpeed(0);
                return;
            }
            // Mur, marche trop haute ou plafond trop bas : demi-tour de decision.
            enemy.setStatus(enemy.status() == SEEK ? SEEK_AFTER_WALL : WANDER_AFTER_WALL);
            return;
        }
        enemy.setPosition(x, z);
    }

    private boolean canStand(SceneObject enemy, int x, int z) {
        int cellX = Math.floorDiv(x, Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(z, Level.BLOCK_SIZE);
        int code = world.cell(cellX, cellZ);
        if (code <= 0) {
            return false;
        }
        Block target = world.level().blocks().get(code);
        if (target.blocksEnemies()) {
            return false;
        }

        int from = Math.abs(world.cell(
                Math.floorDiv(enemy.x(), Level.BLOCK_SIZE),
                Math.floorDiv(enemy.z(), Level.BLOCK_SIZE)));
        int rise = world.floorHeight(code) - world.floorHeight(from);
        if (Math.abs(rise) > MAX_RISE) {
            return false;
        }
        int ceiling = Math.min(world.ceilHeight(code), world.ceilHeight(from));
        return ceiling - world.floorHeight(code) >= enemy.definition().height();
    }

    /** Fait defiler les seize poses du cycle de marche tant que l'ennemi avance. */
    private static void animate(SceneObject enemy) {
        if (enemy.speed() <= 0) {
            return;
        }
        enemy.setCont1(enemy.cont1() - 1);
        if (enemy.cont1() > 0) {
            return;
        }
        enemy.setCont1(4);
        enemy.setAnimCounter((enemy.animCounter() + 1) & 15);
    }

    /** Signale a un ennemi qu'il vient d'etre touche sans etre abattu. */
    public static void wound(SceneObject enemy) {
        enemy.setStatus(HURT);
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

    /** Un ennemi abattu bascule dans son animation de chute. */
    /**
     * Abat un ennemi.
     *
     * <p>{@code DestroyEnemy} : si son type decrit une animation de chute — les images 42
     * a 44 — il tombe dans la direction du tir avant de rester au sol. Sinon il disparait
     * sur-le-champ, et c'est a l'appelant de poser l'explosion.
     *
     * @param heading direction du tir, qui devient celle de la chute
     * @return {@code true} si une chute a commence
     */
    public static boolean kill(SceneObject enemy, int heading) {
        enemy.setStatus(DEAD);
        if (!hasFallAnimation(enemy)) {
            return false;
        }
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
     * Fait avancer la chute d'un ennemi abattu.
     *
     * <p>{@code CEDcaduta} : une image toutes les huit images, jusqu'a la quarante-quatrieme
     * ou jusqu'a ce que l'image ne change plus. Ensuite le corps prend l'image 129 et
     * devient un simple decor.
     */
    private void fall(SceneObject enemy) {
        enemy.setCont1(enemy.cont1() - 1);
        if (enemy.cont1() > 0) {
            return;
        }
        var frames = enemy.definition().frames();
        int next = enemy.animCounter() + 1;
        boolean finished = enemy.animCounter() >= FALL_LAST_FRAME
                || next >= frames.size()
                || frames.get(enemy.animCounter()) == frames.get(next);
        if (finished) {
            enemy.setAnimCounter(REMAINS_FRAME);
            enemy.setSpeed(0);
            enemy.setRemains(true);
            return;
        }
        enemy.setAnimCounter(next);
        enemy.setCont1(FALL_FRAME_TICKS);
    }
}
