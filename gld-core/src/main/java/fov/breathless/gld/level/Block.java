package fov.breathless.gld.level;

/**
 * Une case de decor : hauteurs de sol et de plafond, textures, eclairage et
 * quatre aretes. Disposition sur fichier identique a la structure runtime
 * {@code bl_*} de Sorgenti/TMap.i (32 octets).
 *
 * <p>Les index de texture sont relatifs a la table de noms du niveau : 0 signifie
 * « aucune texture », 1..n designe la n-ieme entree. Les index d'aretes sont eux
 * 0-based dans le tableau d'aretes du niveau.
 *
 * @param floorHeight  hauteur du sol
 * @param ceilHeight   hauteur du plafond
 * @param floorTexture index de la texture de sol
 * @param ceilTexture  index de la texture de plafond, negatif si le plafond est le ciel
 * @param blockNumber  numero de bloc, remis a zero par l'editeur a l'ecriture
 * @param illumination octet haut = intensite lumineuse, bit 7 de l'octet bas = brouillard
 * @param edge1        index de l'arete est
 * @param edge2        index de l'arete sud
 * @param edge3        index de l'arete ouest
 * @param edge4        index de l'arete nord
 * @param effectList   liste d'effets declenchee en entrant (0 = aucune), 1-based
 * @param trigger2     trigger secondaire subi par ce bloc
 * @param attributes   attributs binaires, voir les accesseurs
 * @param trigger      trigger principal subi par ce bloc
 */
public record Block(
        int floorHeight,
        int ceilHeight,
        int floorTexture,
        int ceilTexture,
        int blockNumber,
        int illumination,
        int edge1,
        int edge2,
        int edge3,
        int edge4,
        int effectList,
        int trigger2,
        int attributes,
        int trigger) {

    /** Taille de la structure sur fichier et en memoire. */
    public static final int SIZE = 32;

    /** Le plafond de ce bloc est le ciel : il n'est pas dessine comme une surface. */
    public boolean hasSkyCeiling() {
        return ceilTexture < 0;
    }

    /** Index de texture de plafond effectif, sans le marqueur de ciel. */
    public int ceilTextureIndex() {
        return Math.abs(ceilTexture);
    }

    /**
     * Correction d'eclairage du bloc, en niveaux de la table d'eclairage.
     *
     * <p>C'est un decalage <strong>signe</strong> : le moteur l'etend en signe
     * ({@code extb.l} dans MakeFrame) avant de l'ajouter au niveau deduit de la
     * distance. Une valeur negative eclaircit le bloc, une valeur positive l'assombrit.
     */
    public int lightOffset() {
        return (byte) (illumination >> 8);
    }

    /** Ce bloc utilise le brouillard plutot que l'assombrissement par la distance. */
    public boolean hasFog() {
        return (illumination & 0x80) != 0;
    }

    /** Degats infliges au joueur qui marche ici : 0, 2, 5 ou 10 points de sante. */
    public int floorDamage() {
        return switch (attributes & 0x03) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 10;
            default -> 0;
        };
    }

    /** Les ennemis ne peuvent pas entrer sur ce bloc. */
    public boolean blocksEnemies() {
        return (attributes & 0x08) != 0;
    }

    /**
     * L'arete indiquee est un interrupteur, actionnable a la barre d'espace.
     *
     * @param edge numero d'arete, de 1 a 4
     */
    public boolean isSwitch(int edge) {
        if (edge < 1 || edge > 4) {
            throw new IllegalArgumentException("Numero d'arete hors bornes : " + edge);
        }
        return (attributes & (0x10 << (edge - 1))) != 0;
    }

    /** Index de l'arete demandee, de 1 (est) a 4 (nord). */
    public int edge(int edge) {
        return switch (edge) {
            case 1 -> edge1;
            case 2 -> edge2;
            case 3 -> edge3;
            case 4 -> edge4;
            default -> throw new IllegalArgumentException("Numero d'arete hors bornes : " + edge);
        };
    }
}
