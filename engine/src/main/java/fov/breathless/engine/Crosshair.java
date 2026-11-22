package fov.breathless.engine;

/**
 * Le viseur, au centre de la vue.
 *
 * <p>Portage de {@code InitSight} (Sorgenti/TMapMain.asm). L'original le dessine avec une
 * paire de sprites materiels de soixante-quatre pixels de large, places en {@code (128, 96)},
 * dont seuls onze pixels sur onze portent le dessin : il tombe donc a cheval sur le centre
 * de l'ecran, en {@code (155, 96)}.
 *
 * <p>Le motif ci-dessous est celui de {@code Graphic/Mirino01.raw}, deplie : quatre
 * equerres d'angle et quatre traits de visee.
 */
public final class Crosshair {

    /** Cote du motif, en pixels. */
    public static final int SIZE = 11;

    /** Coin haut gauche du motif dans une vue de 320 sur 200. */
    public static final int LEFT = 155;
    public static final int TOP = 96;

    /**
     * Couleur du viseur.
     *
     * <p>L'original emploie la couleur 14 d'une paire de sprites a seize couleurs, qui se
     * lit dans la plage reservee aux sprites. Faute de pouvoir la deduire de la source
     * avec certitude, on prend ici un gris clair de la rampe du jeu, qui se detache sur
     * les decors sombres comme sur les clairs.
     */
    private static final int COLOUR = 18;

    /** Le motif, une ligne par entree, le bit de poids fort a gauche. */
    private static final int[] SHAPE = {
        0b11000001100,
        0b10000000100,
        0b00001000000,
        0b00001000000,
        0b00000000000,
        0b00110101100,
        0b00000000000,
        0b00001000000,
        0b00001000000,
        0b10000000100,
        0b11000001100,
    };

    private Crosshair() {
    }

    /**
     * Dessine le viseur au centre d'une vue.
     *
     * <p>Le motif est centre sur la vue quelle que soit sa taille : l'original le pose a
     * une position fixe, qui tombe au centre de ses 320 sur 200.
     */
    public static void draw(byte[] target, int width, int height) {
        // La position d'origine, (155, 96), rapportee a la taille de la vue.
        int left = LEFT + (width - 320) / 2;
        int top = TOP + (height - 200) / 2;
        for (int line = 0; line < SIZE; line++) {
            int bits = SHAPE[line];
            if (bits == 0) {
                continue;
            }
            int y = top + line;
            if (y < 0 || y >= height) {
                continue;
            }
            for (int pixel = 0; pixel < SIZE; pixel++) {
                if ((bits & (1 << (SIZE - 1 - pixel))) == 0) {
                    continue;
                }
                int x = left + pixel;
                if (x >= 0 && x < width) {
                    target[y * width + x] = (byte) COLOUR;
                }
            }
        }
    }
}
