package fov.breathless.gld.object;

import java.util.List;

/**
 * Definition d'un type d'objet : ses images et son comportement.
 *
 * <p>Le sens des douze parametres depend du {@link #objectType()} : ce sont tour a tour
 * des points de vie, des degats, des portees ou des vitesses. Ils sont conserves bruts,
 * leur interpretation appartenant a la logique de jeu.
 */
public final class ObjectDefinition {

    /** Decor inerte. */
    public static final int TYPE_THING = 0;

    /** Le joueur. */
    public static final int TYPE_PLAYER = 1;

    /** Un ennemi. */
    public static final int TYPE_ENEMY = 2;

    /** Un objet ramassable. */
    public static final int TYPE_PICKUP = 3;

    /** Un projectile. */
    public static final int TYPE_SHOT = 4;

    /** Une explosion. */
    public static final int TYPE_EXPLOSION = 5;

    /** Animation directionnelle : le sprite depend de l'angle de vue. */
    public static final int ANIM_DIRECTIONAL = -1;

    /** Aucune animation. */
    public static final int ANIM_NONE = 0;

    /** Animation simple, jouee en boucle. */
    public static final int ANIM_SIMPLE = 1;

    private final String name;
    private final int frameCount;
    private final int radius;
    private final int height;
    private final int animType;
    private final int objectType;
    private final int[] params;
    private final List<String> sounds;
    private final List<ObjectFrame> frames;

    ObjectDefinition(String name, int frameCount, int radius, int height,
                     int animType, int objectType, int[] params,
                     List<String> sounds, List<ObjectFrame> frames) {
        this.name = name;
        this.frameCount = frameCount;
        this.radius = radius;
        this.height = height;
        this.animType = animType;
        this.objectType = objectType;
        this.params = params;
        this.sounds = List.copyOf(sounds);
        this.frames = List.copyOf(frames);
    }

    public String name() {
        return name;
    }

    /** Nombre d'images declare par l'en-tete. */
    public int frameCount() {
        return frameCount;
    }

    /** Rayon en unites monde, pour les collisions. */
    public int radius() {
        return radius;
    }

    /** Hauteur en unites monde, pour les collisions. */
    public int height() {
        return height;
    }

    /** {@link #ANIM_DIRECTIONAL}, {@link #ANIM_NONE} ou {@link #ANIM_SIMPLE}. */
    public int animType() {
        return animType;
    }

    /** De {@link #TYPE_THING} a {@link #TYPE_EXPLOSION}. */
    public int objectType() {
        return objectType;
    }

    /**
     * Parametre de comportement, de 1 a 12. Les quatre premiers sont des mots, les huit
     * suivants des octets ; leur sens depend du type d'objet.
     */
    public int param(int index) {
        if (index < 1 || index > params.length) {
            throw new IllegalArgumentException("Parametre hors bornes : " + index);
        }
        return params[index - 1];
    }

    /** Noms des trois sons associes ; une chaine vide quand il n'y en a pas. */
    public List<String> sounds() {
        return sounds;
    }

    /** Images du sprite, dans l'ordre de la liste du fichier. */
    public List<ObjectFrame> frames() {
        return frames;
    }

    public boolean isDirectional() {
        return animType == ANIM_DIRECTIONAL;
    }

    @Override
    public String toString() {
        return name + " type=" + objectType + " anim=" + animType
                + " " + frames.size() + " image(s)";
    }
}
