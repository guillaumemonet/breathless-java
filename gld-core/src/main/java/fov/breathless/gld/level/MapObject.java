package fov.breathless.gld.level;

/**
 * Un objet place dans le niveau : ennemi, ramassable ou decor (10 octets sur fichier).
 *
 * <p>L'entree d'index 0 du fichier n'est pas un objet mais la position de depart du
 * joueur ; elle est extraite a la lecture et exposee separement par {@link Level}.
 *
 * @param objectIndex index dans la table des objets du niveau, 1-based
 * @param x           coordonnee monde en x
 * @param z           coordonnee monde en z
 * @param heading     cap, dans le repere 2048 pas par tour du moteur
 * @param flags       drapeaux, toujours nuls dans les donnees livrees
 * @param trigger     trigger de reveil : si non nul, l'ennemi reste endormi jusqu'a
 *                    ce que l'effet correspondant se declenche
 */
public record MapObject(int objectIndex, int x, int z, int heading, int flags, int trigger) {

    /** Taille de la structure sur fichier. */
    public static final int SIZE = 10;

    /** Cet objet attend un declenchement pour entrer en scene. */
    public boolean isDormant() {
        return trigger != 0;
    }
}
