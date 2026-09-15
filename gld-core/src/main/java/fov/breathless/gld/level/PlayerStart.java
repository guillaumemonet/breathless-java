package fov.breathless.gld.level;

/**
 * Position et orientation initiales du joueur, extraites de l'entree d'objet
 * d'index 0 du fichier de niveau.
 *
 * @param x       coordonnee monde en x
 * @param z       coordonnee monde en z
 * @param heading cap, dans le repere 2048 pas par tour du moteur
 */
public record PlayerStart(int x, int z, int heading) {
}
