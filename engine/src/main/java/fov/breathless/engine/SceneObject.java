package fov.breathless.engine;

import fov.breathless.gld.object.ObjectDefinition;

/**
 * Un objet pose dans le niveau : ennemi, ramassable ou decor.
 *
 * <p>La position est en unites monde entieres, comme dans le moteur d'origine, ou les
 * objets se deplacent sur une grille plus grossiere que le joueur.
 */
public final class SceneObject {

    /**
     * Type courant. {@code obj_image} : il change quand un ennemi explose, puis revient
     * quand l'explosion laisse un cadavre.
     */
    private ObjectDefinition definition;

    /** Type d'avant l'explosion, {@code obj_oldimage}, ou {@code null}. */
    private ObjectDefinition previousDefinition;
    private int x;
    private int z;
    private int y;
    private int heading;
    private int animCounter;
    private final int dormantTrigger;
    private boolean dormant;
    private boolean collected;
    private boolean remains;
    private boolean selfAnimated;
    private int strength;
    private int status;
    private int speed;
    private int cont1 = 1;
    private int cont2 = 1;
    private int attackDelay = 1;

    /** {@code obj_rotdir} : sens de contournement d'un obstacle, -1, 0 ou +1. */
    private int rotDir;

    /** {@code obj_playercoll} : decompte entre deux coups portes au contact. */
    private int playerColl;

    /**
     * {@code obj_bmstatus} : bit 7, l'ennemi a ete dessine a la derniere image ; bits 5 et
     * 6, le dernier pas a bute sur un coin selon x ou selon z.
     */
    private int bmStatus;

    public int rotDir() {
        return rotDir;
    }

    public void setRotDir(int rotDir) {
        this.rotDir = rotDir;
    }

    public int playerColl() {
        return playerColl;
    }

    public void setPlayerColl(int playerColl) {
        this.playerColl = playerColl;
    }

    public int bmStatus() {
        return bmStatus;
    }

    public void setBmStatus(int bmStatus) {
        this.bmStatus = bmStatus & 0xFF;
    }

    /** Le rendu signale l'ennemi comme vu : il bougera meme au-dela de vingt blocs. */
    public void markInView() {
        bmStatus |= 0x80;
    }

    public SceneObject(ObjectDefinition definition, int x, int z, int y, int heading, int dormantTrigger) {
        this.definition = definition;
        this.x = x;
        this.z = z;
        this.y = y;
        this.heading = heading;
        this.dormantTrigger = dormantTrigger;
        this.dormant = dormantTrigger != 0;
    }

    public ObjectDefinition definition() {
        return definition;
    }

    /**
     * L'objet prend la place d'une explosion, en gardant memoire de ce qu'il etait.
     *
     * <p>{@code DEexpl} : l'ennemi abattu n'est pas retire, son type est remplace par
     * celui de l'explosion et l'ancien est mis de cote — c'est lui qui dira, une fois
     * l'explosion finie, s'il reste un cadavre.
     */
    public void becomeExplosion(ObjectDefinition explosion) {
        this.previousDefinition = definition;
        this.definition = explosion;
        this.animCounter = 0;
        this.selfAnimated = true;
    }

    /** Rend a l'objet le type qu'il avait avant d'exploser. */
    public void restorePreviousDefinition() {
        if (previousDefinition != null) {
            this.definition = previousDefinition;
            this.previousDefinition = null;
        }
    }

    /** L'objet etait-il autre chose avant d'exploser ? */
    public boolean hasPreviousDefinition() {
        return previousDefinition != null;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    /** Altitude de la base de l'objet : le sol du bloc qu'il occupe. */
    public int y() {
        return y;
    }

    public int heading() {
        return heading;
    }

    /** Image courante dans le cycle d'animation. */
    public int animCounter() {
        return animCounter;
    }

    /** L'objet attend son declenchement et n'est pas encore entre en scene. */
    public boolean isDormant() {
        return dormant;
    }

    /**
     * Numero de trigger qui reveillera cet objet, 0 s'il est actif d'emblee.
     *
     * <p>Plus de la moitie des objets places dans les niveaux d'origine attendent ainsi :
     * c'est de loin l'effet le plus utilise du jeu.
     */
    public int dormantTrigger() {
        return dormantTrigger;
    }

    /** Fait entrer l'objet en scene. */
    public void awaken() {
        dormant = false;
    }

    /**
     * L'objet n'est plus qu'un cadavre.
     *
     * <p>{@code CEDcadutaend} change son type en 10 : il n'est plus un ennemi, ne bouge
     * plus, n'attaque plus et n'arrete plus les tirs — il reste seulement visible.
     */
    public boolean isRemains() {
        return remains;
    }

    public void setRemains(boolean value) {
        this.remains = value;
    }

    /** L'objet a ete ramasse et ne doit plus etre ni dessine ni teste. */
    public boolean isCollected() {
        return collected;
    }

    public void collect() {
        collected = true;
    }

    /**
     * Cet objet joue son animation pour lui-meme, puis disparait. C'est le cas des
     * explosions, qui ne partagent pas le compteur de leur type.
     */
    public boolean isSelfAnimated() {
        return selfAnimated;
    }

    public void setSelfAnimated(boolean selfAnimated) {
        this.selfAnimated = selfAnimated;
    }

    /** Etat de comportement d'un ennemi : chercher, errer, viser, encaisser... */
    public int status() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    /** Vitesse de deplacement courante, en unites monde par pas. */
    public int speed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    /** Compteur d'animation de marche. */
    public int cont1() {
        return cont1;
    }

    public void setCont1(int cont1) {
        this.cont1 = cont1;
    }

    /** Compteur de duree de l'etat courant. */
    public int cont2() {
        return cont2;
    }

    public void setCont2(int cont2) {
        this.cont2 = cont2;
    }

    /** Compte a rebours avant la prochaine attaque. */
    public int attackDelay() {
        return attackDelay;
    }

    public void setAttackDelay(int attackDelay) {
        this.attackDelay = attackDelay;
    }

    /** Points de vie restants d'un ennemi. */
    public int strength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = strength;
    }

    /**
     * Encaisse des degats.
     *
     * @return {@code true} si l'ennemi vient d'etre abattu
     */
    public boolean damage(int amount) {
        if (strength <= 0) {
            return false;
        }
        strength -= amount;
        return strength <= 0;
    }

    /**
     * Sous-type d'un objet a ramasser : sante, boucliers, energie, credits ou l'une des
     * quatre cles. Les instances le tiennent du premier parametre de leur type.
     */
    public int pickupSubtype() {
        return definition.param(1) & 0xFF;
    }

    /** Quantite apportee par un objet a ramasser. */
    public int pickupValue() {
        return definition.param(2);
    }

    public void setPosition(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setHeading(int heading) {
        this.heading = heading & Trig.MASK;
    }

    public void setAnimCounter(int animCounter) {
        this.animCounter = animCounter;
    }
}
