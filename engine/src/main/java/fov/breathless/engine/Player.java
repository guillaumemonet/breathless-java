package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;

/**
 * Deplacement du joueur et collisions avec le decor.
 *
 * <p>Portage de {@code DoMovement} (Sorgenti/movement.asm). Le mouvement a de l'inertie :
 * les commandes fixent une vitesse <em>visee</em>, la vitesse reelle s'en approche par
 * paliers. Il en va de meme pour la rotation, avec une acceleration legerement plus forte
 * quand on inverse le sens, pour que le demi-tour reste vif.
 *
 * <p>Les collisions traitent le joueur comme un carre de {@link #WIDTH} de demi-cote dans
 * une grille de 64. Selon les bords de case qu'il chevauche, une a trois cases voisines
 * sont testees ; celles qui le refusent provoquent un recalage de la coordonnee
 * correspondante contre le bord. C'est ce recalage par axe qui produit le glissement le
 * long des murs.
 */
public final class Player {

    /** Demi-cote du joueur, {@code PLAYER_WIDTH}. */
    public static final int WIDTH = 16;

    /** Hauteur du joueur, {@code PLAYER_HEIGHT}. */
    public static final int HEIGHT = 56;

    /** Denivele maximal franchissable, {@code PLAYER_MAX_RISE}. */
    public static final int MAX_RISE = 24;

    /** Hauteur libre exigee pour passer : le moteur ajoute huit unites a la taille. */
    public static final int CLEARANCE = HEIGHT + 8;

    // Valeurs par defaut reellement appliquees au demarrage (Sorgenti/TMapMain.asm).
    // Attention : les constantes PLAYER_WALK_SPEED et consorts de TMap.i sont des
    // vestiges, annotes « old= » dans le fichier, et ne sont utilisees nulle part.
    // Les vitesses de deplacement sont stockees multipliees par seize.
    private static final int WALK_SPEED = 4 << 4;
    private static final int RUN_SPEED = 6 << 4;
    private static final int ACCEL = 4;
    private static final int ROT_WALK_SPEED = 8;
    private static final int ROT_RUN_SPEED = 20;
    private static final int ROT_ACCEL = 4;

    private static final int BLOCK = Level.BLOCK_SIZE;
    private static final int GRID_MASK = ((Level.MAP_SIZE * BLOCK) - BLOCK) << 16;

    /** Codes de direction : bits pas-a-droite, pas-a-gauche, arriere, avant. */
    private static final int DIR_RIGHT = 1;
    private static final int DIR_LEFT = 2;
    private static final int DIR_BACK = 4;
    private static final int DIR_FORWARD = 8;

    /**
     * Pour chaque code de direction, l'angle a ajouter au cap et le signe de la vitesse.
     * Reprend {@code movingdirtable} : un pas de cote est un cap tourne d'un quart de
     * tour, une diagonale d'un huitieme.
     */
    private static final int[] DIR_ANGLE = new int[16];
    private static final int[] DIR_SIGN = new int[16];

    static {
        setDirection(1, 512, 1);      // pas a droite
        setDirection(2, 512, -1);     // pas a gauche
        setDirection(4, 0, -1);       // arriere
        setDirection(5, -256, -1);    // arriere-droite
        setDirection(6, 256, -1);     // arriere-gauche
        setDirection(8, 0, 1);        // avant
        setDirection(9, 256, 1);      // avant-droite
        setDirection(10, -256, 1);    // avant-gauche
    }

    private static void setDirection(int code, int angle, int sign) {
        DIR_ANGLE[code] = angle;
        DIR_SIGN[code] = sign;
    }

    /**
     * Effet d'un changement de direction sur la vitesse acquise, d'apres
     * {@code updspeedtable} : 1 remet la vitesse a zero, -1 l'inverse, 0 la conserve.
     * Lignes indexees par la nouvelle direction, colonnes par l'ancienne, de 1 a 10.
     */
    private static final byte[][] SPEED_TRANSITION = {
            {0, 0, 0, 1, -1, 1, 0, 1, 0, 1},
            {0, 0, 0, 1, 1, 0, 0, 1, 1, -1},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {1, 1, 0, 0, 0, 0, 0, 0, 1, 1},
            {-1, 1, 0, 0, 0, 0, 0, 1, 0, 0},
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {1, 1, 0, 0, 1, 1, 0, 0, 0, 0},
            {0, 1, 0, 1, 0, 0, 0, 0, 0, 0},
            {1, -1, 0, 1, 0, 0, 0, 0, 0, 0},
    };

    private final World world;
    private int speed;
    private int rotSpeed;
    private int movingDir;
    private int previousDir;

    public Player(World world) {
        this.world = world;
    }

    /** Vitesse acquise, dans l'echelle du moteur (seizieme d'unite par image). */
    public int speed() {
        return speed;
    }

    /**
     * Coupe net l'elan acquis.
     *
     * <p>{@code ResetMoveVars} : une teleportation ne fait pas glisser le joueur sur sa
     * lancee a l'arrivee.
     */
    public void stop() {
        speed = 0;
        rotSpeed = 0;
        movingDir = 0;
        previousDir = 0;
    }

    /** Avance la simulation d'une image et deplace la camera. */
    public void update(Camera camera, PlayerInput input) {
        int maxWalk = input.run ? RUN_SPEED : WALK_SPEED;
        int maxRot = input.run ? ROT_RUN_SPEED : ROT_WALK_SPEED;

        int targetSpeed = 0;
        int targetRot = 0;

        int direction = 0;
        if (input.strafe) {
            if (input.right) {
                direction |= DIR_RIGHT;
            } else if (input.left) {
                direction |= DIR_LEFT;
            }
        } else {
            if (input.right) {
                targetRot = maxRot;
            } else if (input.left) {
                targetRot = -maxRot;
            }
        }
        if (input.forward) {
            direction |= DIR_FORWARD;
        }
        if (input.backward) {
            direction |= DIR_BACK;
        }

        if (direction != 0 && DIR_SIGN[direction] != 0) {
            movingDir = DIR_ANGLE[direction];
            targetSpeed = DIR_SIGN[direction] < 0 ? -maxWalk : maxWalk;
            applySpeedTransition(direction);
            previousDir = direction;
        }

        speed = approach(speed, targetSpeed, ACCEL);

        // L'acceleration augmente d'un cran quand on inverse le sens de rotation ou
        // quand on relache : sans cela le demi-tour tarderait trop.
        int rotAccel = ROT_ACCEL;
        if (targetRot == 0 || ((targetRot ^ rotSpeed) < 0)) {
            rotAccel++;
        }
        rotSpeed = approach(rotSpeed, targetRot, rotAccel);
        camera.setHeading(camera.heading() + rotSpeed);

        moveAndCollide(camera);
        followFloor(camera);
    }

    /** Une direction opposee a la precedente annule ou inverse la vitesse acquise. */
    private void applySpeedTransition(int direction) {
        if (previousDir < 1 || previousDir > 10 || direction < 1 || direction > 10) {
            return;
        }
        int effect = SPEED_TRANSITION[direction - 1][previousDir - 1];
        if (effect > 0) {
            speed = 0;
        } else if (effect < 0) {
            speed = -speed;
        }
    }

    private static int approach(int current, int target, int step) {
        if (current < target) {
            return Math.min(current + step, target);
        }
        if (current > target) {
            return Math.max(current - step, target);
        }
        return current;
    }

    /**
     * Applique le deplacement puis recale la position contre les cases refusees.
     *
     * <p>La vitesse est exprimee en seiziemes d'unite monde, d'ou le decalage de quatre
     * bits apres multiplication par la direction en 16.16.
     */
    private void moveAndCollide(Camera camera) {
        if (speed == 0) {
            return;
        }
        int angle = camera.heading() + movingDir;
        int dirX = Trig.cos(angle);
        int dirZ = Trig.sin(angle);

        int x = camera.x() + (int) (((long) dirX * speed) >> 4);
        int z = camera.z() + (int) (((long) dirZ * speed) >> 4);

        int currentIndex = Math.abs(world.cell(camera.cellX(), camera.cellZ()));
        int cellX = (x >> 16) / BLOCK;
        int cellZ = (z >> 16) / BLOCK;

        int inX = (x >> 16) & (BLOCK - 1);
        int inZ = (z >> 16) & (BLOCK - 1);

        // Quels bords de case le joueur chevauche-t-il ?
        int borders = 0;
        if (inX < WIDTH) {
            borders |= 4;
        } else if (inX >= BLOCK - WIDTH) {
            borders |= 1;
        }
        if (inZ < WIDTH) {
            borders |= 8;
        } else if (inZ >= BLOCK - WIDTH) {
            borders |= 2;
        }

        int blocked = testBorders(borders, cellX, cellZ, inX, inZ, currentIndex);

        if ((blocked & 1) != 0) {
            x = (x & GRID_MASK) + ((BLOCK - WIDTH) << 16);
        } else if ((blocked & 4) != 0) {
            x = (x & GRID_MASK) + (WIDTH << 16);
        }
        if ((blocked & 2) != 0) {
            z = (z & GRID_MASK) + ((BLOCK - WIDTH) << 16);
        } else if ((blocked & 8) != 0) {
            z = (z & GRID_MASK) + (WIDTH << 16);
        }

        camera.setPosition(x, z);
    }

    /**
     * Determine quels bords refusent le passage.
     *
     * <p>Quand le joueur vise un coin, les deux cases orthogonales peuvent etre libres et
     * la diagonale non : il faut alors choisir l'axe a bloquer. Le moteur compare la
     * penetration sur chacun des deux axes et bloque celui ou elle est la plus faible.
     *
     * @return combinaison des bits 1 (est), 2 (sud), 4 (ouest) et 8 (nord)
     */
    private int testBorders(int borders, int cellX, int cellZ, int inX, int inZ, int current) {
        int blocked = 0;
        switch (borders) {
            case 1 -> blocked = test(cellX + 1, cellZ, current, 1);
            case 2 -> blocked = test(cellX, cellZ + 1, current, 2);
            case 4 -> blocked = test(cellX - 1, cellZ, current, 4);
            case 8 -> blocked = test(cellX, cellZ - 1, current, 8);
            case 3 -> {
                blocked = test(cellX + 1, cellZ, current, 1) | test(cellX, cellZ + 1, current, 2);
                if (blocked == 0 && test(cellX + 1, cellZ + 1, current, 1) != 0) {
                    blocked = (inX <= inZ) ? 1 : 2;
                }
            }
            case 6 -> {
                blocked = test(cellX - 1, cellZ, current, 4) | test(cellX, cellZ + 1, current, 2);
                if (blocked == 0 && test(cellX - 1, cellZ + 1, current, 1) != 0) {
                    blocked = ((BLOCK - 1 - inZ) > inX) ? 2 : 4;
                }
            }
            case 9 -> {
                blocked = test(cellX + 1, cellZ, current, 1) | test(cellX, cellZ - 1, current, 8);
                if (blocked == 0 && test(cellX + 1, cellZ - 1, current, 1) != 0) {
                    blocked = (inZ > (BLOCK - 1 - inX)) ? 8 : 1;
                }
            }
            case 12 -> {
                blocked = test(cellX - 1, cellZ, current, 4) | test(cellX, cellZ - 1, current, 8);
                if (blocked == 0 && test(cellX - 1, cellZ - 1, current, 1) != 0) {
                    blocked = (inZ > inX) ? 8 : 4;
                }
            }
            default -> {
                // Aucun bord chevauche, ou combinaison impossible : rien a tester.
            }
        }
        return blocked;
    }

    /**
     * Le joueur peut-il occuper cette case ?
     *
     * <p>Portage de la macro {@code TESTBLOCK}. Trois refus possibles : un mur plein, une
     * marche trop haute, ou un plafond trop bas. En descente, seule la hauteur libre de la
     * case visee compte ; en montee, c'est le plus bas des deux plafonds qui limite.
     *
     * @return {@code bit} si le passage est refuse, 0 sinon
     */
    private int test(int cellX, int cellZ, int currentIndex, int bit) {
        int code = world.cell(cellX, cellZ);
        if (code < 0) {
            return bit;
        }
        int currentFloor = world.floorHeight(currentIndex);
        int targetFloor = world.floorHeight(code);
        int rise = targetFloor - currentFloor;

        if (rise < 0) {
            return (world.ceilHeight(code) - currentFloor > CLEARANCE) ? 0 : bit;
        }
        if (rise > MAX_RISE) {
            return bit;
        }
        int ceiling = Math.min(world.ceilHeight(currentIndex), world.ceilHeight(code));
        return (ceiling - targetFloor > CLEARANCE) ? 0 : bit;
    }

    /**
     * Cale l'oeil sur le sol de la case occupee.
     *
     * <p>Le moteur d'origine y ajoute une vitesse verticale, la chute et son oscillation
     * de marche ; ces trois elements ne sont pas encore portes.
     */
    private void followFloor(Camera camera) {
        int index = Math.abs(world.cell(camera.cellX(), camera.cellZ()));
        camera.setY(world.floorHeight(index) + Camera.EYES_HEIGHT);
    }
}
