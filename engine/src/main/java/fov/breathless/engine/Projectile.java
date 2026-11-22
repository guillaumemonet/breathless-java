package fov.breathless.engine;

import fov.breathless.gld.object.ObjectDefinition;

/**
 * Un tir en vol.
 *
 * <p>Portage des champs {@code obj_x0} a {@code obj_y0} de TMap.i. Un projectile ne
 * cumule pas des deplacements : il retient son point de depart et la distance parcourue,
 * et recalcule sa position a chaque image. C'est ce qui lui evite de deriver sur une
 * longue trajectoire.
 *
 * <p>Un pas se fait en deux temps, comme dans {@code MSloop} : {@link #advance} calcule la
 * position visee, les collisions la jugent avec l'altitude <em>precedente</em>, et
 * {@link #commit} ne l'adopte que si rien ne l'a arretee. Un tir qui s'arrete explose donc
 * la ou il etait, pas la ou il allait.
 */
public final class Projectile {

    private final ObjectDefinition definition;
    private final boolean fromPlayer;
    private final int startX;
    private final int startZ;
    private final int startY;
    private final int heading;

    /** {@code obj_hheading} : pente verticale, en 1.15. */
    private final int pitch;

    private final int power;
    private final int maxRange;
    private final int acceleration;
    private final int maxSpeed;

    private int speed;
    private int distance;
    private int x;
    private int z;
    private int y;
    private int nextX;
    private int nextZ;
    private int nextY;
    private int animCounter;
    private boolean spent;

    Projectile(ObjectDefinition definition, boolean fromPlayer,
               int startX, int startZ, int startY, int heading, int pitch, int speed) {
        this.definition = definition;
        this.fromPlayer = fromPlayer;
        this.startX = startX;
        this.startZ = startZ;
        this.startY = startY;
        this.heading = heading;
        this.pitch = pitch;
        this.speed = speed;

        this.power = definition.param(1) & 0xFF;
        this.maxRange = definition.param(4);
        this.acceleration = definition.param(5);
        this.maxSpeed = definition.param(6);

        // Le tir nait a seize unites devant son auteur, pas dans son nez.
        this.distance = 16;
        this.y = startY;
        computeNext();
        commit();
    }

    public ObjectDefinition definition() {
        return definition;
    }

    /** Ce tir vient du joueur ; sinon il vient d'un ennemi. */
    public boolean isFromPlayer() {
        return fromPlayer;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int y() {
        return y;
    }

    /** Position visee par le pas en cours, avant validation. */
    int nextX() {
        return nextX;
    }

    int nextZ() {
        return nextZ;
    }

    int nextY() {
        return nextY;
    }

    public int heading() {
        return heading;
    }

    /** Image courante : un tir s'anime, comme tout objet. */
    public int animCounter() {
        return animCounter;
    }

    /** Degats infliges a l'impact. */
    public int power() {
        return power;
    }

    public int radius() {
        return definition.radius();
    }

    public int height() {
        return definition.height();
    }

    /** Le tir a touche quelque chose ou depasse sa portee. */
    public boolean isSpent() {
        return spent;
    }

    void spend() {
        spent = true;
    }

    /**
     * Calcule la position du pas suivant.
     *
     * <p>{@code cmp.b obj_maxspeed / add.b obj_accel} : l'acceleration se fait sur l'octet
     * bas de la vitesse, compare avec signe.
     *
     * @return {@code false} si le tir a depasse sa portee maximale
     */
    boolean advance() {
        if ((byte) speed < (byte) maxSpeed) {
            speed = (speed & 0xFF00) | ((speed + acceleration) & 0xFF);
        }
        distance = (short) (distance + speed);
        if (distance > maxRange) {
            return false;
        }
        computeNext();
        return true;
    }

    /** Adopte la position calculee : rien ne l'a arretee. */
    void commit() {
        x = nextX;
        z = nextZ;
        y = nextY;
    }

    /**
     * Fait avancer l'animation. {@code MSnolfr} : elle boucle, au rythme de
     * {@code animstep}.
     */
    void animate(int step) {
        int frames = Math.max(1, definition.frames().size());
        int next = animCounter + step;
        animCounter = next >= frames ? 0 : next;
    }

    private void computeNext() {
        // Directions en 2.14, comme les tables du moteur reduites d'un quart.
        int cos = Trig.cos(heading) >> 2;
        int sin = Trig.sin(heading) >> 2;

        nextX = (short) (startX + (int) (((long) distance * cos) >> 14));
        nextZ = (short) (startZ + (int) (((long) distance * sin) >> 14));
        nextY = (short) (startY + (int) ((((long) pitch * distance) << 1) >> 16));
    }

    /**
     * Index de l'explosion a produire a l'impact, ou -1 si ce tir se dissipe sans bruit.
     */
    public int explosionIndex() {
        int index = (byte) definition.param(9);
        return index < 0 ? -1 : index;
    }
}
