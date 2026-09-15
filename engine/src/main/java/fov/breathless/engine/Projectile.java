package fov.breathless.engine;

import fov.breathless.gld.object.ObjectDefinition;

/**
 * Un tir en vol.
 *
 * <p>Portage des champs {@code obj_x0} a {@code obj_y0} de TMap.i. Un projectile ne
 * cumule pas des deplacements : il retient son point de depart et la distance parcourue,
 * et recalcule sa position a chaque image. C'est ce qui lui evite de deriver sur une
 * longue trajectoire.
 */
public final class Projectile {

    private final ObjectDefinition definition;
    private final boolean fromPlayer;
    private final int startX;
    private final int startZ;
    private final int startY;
    private final int heading;

    /** Angle vertical, herite de l'inclinaison du regard au moment du tir. */
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
        recomputePosition();
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

    public int heading() {
        return heading;
    }

    /** Degats infliges a l'impact. */
    public int power() {
        return power;
    }

    public int radius() {
        return definition.radius();
    }

    /** Le tir a touche quelque chose ou depasse sa portee. */
    public boolean isSpent() {
        return spent;
    }

    void spend() {
        spent = true;
    }

    /**
     * Avance d'un pas.
     *
     * @return {@code false} si le tir a depasse sa portee maximale
     */
    boolean advance() {
        if (speed < maxSpeed) {
            speed = Math.min(maxSpeed, speed + acceleration);
        }
        distance += speed;
        if (maxRange > 0 && distance > maxRange) {
            return false;
        }
        recomputePosition();
        return true;
    }

    private void recomputePosition() {
        // Directions en 2.14, comme les tables du moteur reduites d'un quart.
        int cos = Trig.cos(heading) >> 2;
        int sin = Trig.sin(heading) >> 2;

        x = startX + (int) (((long) distance * cos) >> 14);
        z = startZ + (int) (((long) distance * sin) >> 14);
        y = startY + (int) ((((long) pitch * distance) << 1) >> 16);
    }

    /**
     * Index de l'explosion a produire a l'impact, ou -1 si ce tir se dissipe sans bruit.
     */
    public int explosionIndex() {
        int index = (byte) definition.param(9);
        return index < 0 ? -1 : index;
    }
}
