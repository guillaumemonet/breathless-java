package fov.breathless.engine;

/**
 * Etat des commandes pour une image.
 *
 * <p>Les touches gauche et droite font tourner, sauf si {@link #strafe} est actif :
 * elles deplacent alors lateralement. C'est le comportement d'origine, ou la touche
 * « force side » bascule le sens des deux memes commandes.
 */
public final class PlayerInput {

    public boolean forward;
    public boolean backward;
    public boolean left;
    public boolean right;

    /** Transforme la rotation gauche/droite en pas de cote. */
    public boolean strafe;

    /**
     * Pas de cote dedies, {@code sideleft} et {@code sideright}. Contrairement a
     * {@link #strafe}, ils laissent gauche et droite faire tourner : on peut tourner et se
     * decaler a la fois.
     */
    public boolean sideLeft;
    public boolean sideRight;

    /** Regard vers le haut ou vers le bas, tant que la touche est tenue. */
    public boolean lookUp;
    public boolean lookDown;

    /** Remet le regard a l'horizontale ({@code KIresetlook}), consomme au premier pas. */
    public boolean lookReset;

    /** Course plutot que marche : vitesse et vitesse de rotation plus elevees. */
    public boolean run;

    /** Tir. */
    public boolean fire;

    /** Changement d'arme, consomme au premier pas qui le voit. */
    public boolean nextWeapon;

    /**
     * Action sur un interrupteur : ouvrir une porte, appeler un ascenseur.
     *
     * <p>Consommee au premier pas qui la voit, pour qu'un appui n'agisse qu'une fois.
     */
    public boolean action;

    /**
     * Nombre d'appuis sur la gachette depuis le dernier pas.
     *
     * <p>Le moteur d'origine ne compte pas le temps mais les <em>pressions</em> : chacune
     * met un tir en file, et la file se vide d'un tir par image. Une arme ordinaire est
     * donc au coup par coup, et tirer vite est affaire de doigt. Seul le lance-flammes
     * lit {@link #fire}, qu'il consomme tant qu'il est tenu.
     */
    public int firePresses;

    public void clear() {
        forward = false;
        backward = false;
        left = false;
        right = false;
        strafe = false;
        sideLeft = false;
        sideRight = false;
        lookUp = false;
        lookDown = false;
        lookReset = false;
        run = false;
        fire = false;
        firePresses = 0;
        nextWeapon = false;
        action = false;
    }
}
