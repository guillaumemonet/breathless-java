package fov.breathless.engine;

/**
 * Taille de la fenetre de jeu et grosseur de ses pixels.
 *
 * <p>Portage de {@code ViewSizeTable} et de {@code KIchangewinsize}
 * (Sorgenti/TMapMain.asm). Le joueur reglait ces deux choses pour gagner de la vitesse :
 * une fenetre plus petite, ou des pixels doubles, c'est autant de colonnes et de lignes en
 * moins a calculer. Le portage les garde, moins pour la vitesse que par fidelite — et
 * parce qu'une fenetre reduite montre la tenture qui l'entoure.
 *
 * <p>Le rendu se fait a la taille reduite, puis se repete a l'ecran : un pixel double est
 * bien un gros pixel, pas une image lissee.
 */
public final class ViewSize {

    /** Les huit tailles de fenetre, de la plus petite a la plus grande. */
    private static final int[][] SIZES = {
        {96, 60}, {128, 80}, {160, 100}, {192, 120},
        {224, 140}, {256, 160}, {288, 180}, {320, 200},
    };

    /** Taille par defaut : celle que le jeu choisit au lancement. */
    public static final int DEFAULT_INDEX = 3;

    /** Les quatre grosseurs de pixel, dans l'ordre ou les touches du pave les choisissent. */
    public static final int PIXEL_1X1 = 0;
    public static final int PIXEL_2X1 = 1;
    public static final int PIXEL_1X2 = 2;
    public static final int PIXEL_2X2 = 3;

    private int index = DEFAULT_INDEX;
    private int pixelType = PIXEL_1X1;

    /** Nombre de tailles disponibles. */
    public static int count() {
        return SIZES.length;
    }

    /** Taille courante de la fenetre a l'ecran, en pixels d'ecran. */
    public int width() {
        return SIZES[index][0];
    }

    public int height() {
        return SIZES[index][1];
    }

    /** Largeur reellement calculee : la moitie si les pixels sont doubles en largeur. */
    public int renderWidth() {
        return width() >> (((pixelType & 1) != 0) ? 1 : 0);
    }

    /** Hauteur reellement calculee. */
    public int renderHeight() {
        return height() >> (((pixelType & 2) != 0) ? 1 : 0);
    }

    /** Facteur de repetition horizontal d'un pixel calcule. */
    public int scaleX() {
        return ((pixelType & 1) != 0) ? 2 : 1;
    }

    /** Facteur de repetition vertical. */
    public int scaleY() {
        return ((pixelType & 2) != 0) ? 2 : 1;
    }

    public int index() {
        return index;
    }

    public int pixelType() {
        return pixelType;
    }

    /**
     * Agrandit ou reduit la fenetre d'un cran.
     *
     * @return {@code true} si la taille a change
     */
    public boolean resize(int steps) {
        int wanted = Math.max(0, Math.min(SIZES.length - 1, index + steps));
        if (wanted == index) {
            return false;
        }
        index = wanted;
        return true;
    }

    /**
     * Change la grosseur des pixels.
     *
     * @return {@code true} si elle a change
     */
    public boolean setPixelType(int type) {
        if (type < PIXEL_1X1 || type > PIXEL_2X2 || type == pixelType) {
            return false;
        }
        pixelType = type;
        return true;
    }

    /** Decrit le reglage courant, pour le dire au joueur. */
    @Override
    public String toString() {
        String pixels = switch (pixelType) {
            case PIXEL_2X1 -> "pixels 2x1";
            case PIXEL_1X2 -> "pixels 1x2";
            case PIXEL_2X2 -> "pixels 2x2";
            default -> "pixels 1x1";
        };
        return width() + "x" + height() + ", " + pixels
                + " (calcule en " + renderWidth() + "x" + renderHeight() + ")";
    }
}
