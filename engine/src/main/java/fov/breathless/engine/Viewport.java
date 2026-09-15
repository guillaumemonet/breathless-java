package fov.breathless.engine;

/**
 * Dimensions de la fenetre de rendu et facteurs derives.
 *
 * <p>Portage de {@code ComputeVars} (Sorgenti/TMapMain.asm). Les deux constantes de
 * reference viennent de TMap.i et se lisent differemment l'une de l'autre :
 * {@code WINDOW_STANDARD_WIDTH} est la <em>demi</em>-largeur de la fenetre standard,
 * {@code WINDOW_STANDARD_HEIGHT} sa hauteur <em>entiere</em>.
 */
public final class Viewport {

    /** Demi-largeur de la fenetre standard. */
    public static final int STANDARD_HALF_WIDTH = 160;

    /** Hauteur de la fenetre standard. */
    public static final int STANDARD_HEIGHT = 110;

    private final int width;
    private final int height;
    private final int halfWidth;
    private final int halfHeight;
    private final int xRatio;
    private final int yRatio;

    public Viewport(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Fenetre de rendu vide : " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.halfWidth = width / 2;
        this.halfHeight = height / 2;
        this.xRatio = (STANDARD_HALF_WIDTH << 16) / width;
        this.yRatio = (int) ((((long) height) << 16) / STANDARD_HEIGHT);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int halfWidth() {
        return halfWidth;
    }

    /** Ligne d'horizon, avant application du decalage du regard. */
    public int halfHeight() {
        return halfHeight;
    }

    /** Pas horizontal entre deux colonnes, en 16.16. Equivalent de {@code windowXratio}. */
    public int xRatio() {
        return xRatio;
    }

    /**
     * Facteur d'echelle vertical, en 16.16. Equivalent de {@code windowYratio}.
     *
     * <p>Il multiplie la direction du rayon, ce qui divise d'autant la distance
     * calculee et donc multiplie la hauteur projetee des murs.
     */
    public int yRatio() {
        return yRatio;
    }

    /** Abscisse du rayon de la colonne indiquee, en 16.16, dans l'intervalle [-80, +80[. */
    public int rayOffset(int column) {
        return -(STANDARD_HALF_WIDTH << 15) + column * xRatio;
    }
}
