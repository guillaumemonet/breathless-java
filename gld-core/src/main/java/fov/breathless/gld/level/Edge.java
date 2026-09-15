package fov.breathless.gld.level;

/**
 * Un cote de bloc et son habillage (16 octets sur fichier).
 *
 * <p>Trois textures, comme dans Doom : la texture principale couvre un mur plein,
 * les textures haute et basse habillent les denivelés entre deux blocs de hauteurs
 * differentes. Les index sont relatifs a la table de noms du niveau, 0 = aucune.
 *
 * @param normTexture index de la texture principale
 * @param upTexture   index de la texture haute
 * @param lowTexture  index de la texture basse
 * @param attributes  bit 0 = texture haute unpegged, bit 1 = texture basse unpegged
 */
public record Edge(int normTexture, int upTexture, int lowTexture, int attributes) {

    /** Taille de la structure sur fichier et en memoire. */
    public static final int SIZE = 16;

    /** La texture haute est alignee sur le bas plutot que sur le haut du pan de mur. */
    public boolean isUpperUnpegged() {
        return (attributes & 0x01) != 0;
    }

    /** La texture basse est alignee sur le haut plutot que sur le bas du pan de mur. */
    public boolean isLowerUnpegged() {
        return (attributes & 0x02) != 0;
    }
}
