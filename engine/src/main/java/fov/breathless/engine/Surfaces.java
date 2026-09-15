package fov.breathless.engine;

/**
 * Carte des surfaces horizontales restant a peindre apres la passe des murs.
 *
 * <p>La passe des murs sait, pour chaque pixel qu'elle ne peint pas, s'il montre le sol
 * ou le plafond, et de quel bloc. Elle le consigne ici ; la passe des sols regroupe
 * ensuite ces pixels en segments horizontaux et les texture en perspective.
 *
 * <p>Le moteur d'origine fait la meme chose avec sa table {@code OTable}, en construisant
 * les segments au fil de l'eau plutot qu'en remplissant un tampon. Le regroupement par
 * ligne est indispensable dans les deux cas : sur une ligne donnee, la profondeur est
 * constante, ce qui rend le placage de texture affine et donc lineaire.
 */
public final class Surfaces {

    /** Pixel deja peint par un mur : rien a faire. */
    public static final int NONE = -1;

    /** Le pixel montre le sol d'un bloc. */
    public static final int FLOOR = 0;

    /** Le pixel montre le plafond d'un bloc. */
    public static final int CEILING = 1;

    /** Le pixel montre le ciel. */
    public static final int SKY = 2;

    private final int width;
    private final int height;
    private final int[] cells;

    public Surfaces(int width, int height) {
        this.width = width;
        this.height = height;
        this.cells = new int[width * height];
    }

    public void clear() {
        java.util.Arrays.fill(cells, NONE);
    }

    /** Marque une portion de colonne comme montrant une surface horizontale. */
    public void mark(int column, int fromRow, int toRow, int blockIndex, int kind) {
        int from = Math.max(fromRow, 0);
        int to = Math.min(toRow, height - 1);
        int value = (blockIndex << 2) | kind;
        for (int y = from; y <= to; y++) {
            cells[y * width + column] = value;
        }
    }

    /** Valeur encodee d'un pixel, ou {@link #NONE}. */
    public int at(int column, int row) {
        return cells[row * width + column];
    }

    /** Index du bloc porteur de la surface. */
    public static int blockOf(int value) {
        return value >> 2;
    }

    /** Nature de la surface : {@link #FLOOR}, {@link #CEILING} ou {@link #SKY}. */
    public static int kindOf(int value) {
        return value & 3;
    }
}
