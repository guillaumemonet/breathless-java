package fov.breathless.game;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Une police de huit pixels sur huit, pour l'ecran de chargement.
 *
 * <p>L'original y ecrit avec {@code Text()} de la graphics.library, donc avec la police
 * systeme de l'Amiga, qui ne fait pas partie des donnees du jeu. Elle est remplacee ici par
 * une police a chasse fixe rasterisee une fois, sans lissage, dans la meme cellule de huit
 * sur huit et avec la meme ligne de base, six pixels sous le haut.
 */
final class SystemFont {

    /** Largeur et hauteur d'un caractere, et position de la ligne de base. */
    static final int SIZE = 8;
    static final int BASELINE = 6;

    private static final boolean[][] GLYPHS = new boolean[128][];

    static {
        try {
            BufferedImage cell = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_BINARY);
            Font font = new Font(Font.MONOSPACED, Font.BOLD, 9);
            for (char c = 32; c < 127; c++) {
                Graphics2D g = cell.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                g.setColor(java.awt.Color.BLACK);
                g.fillRect(0, 0, SIZE, SIZE);
                g.setColor(java.awt.Color.WHITE);
                g.setFont(font);
                int advance = g.getFontMetrics().charWidth(c);
                g.drawString(String.valueOf(c), Math.max(0, (SIZE - advance) / 2), BASELINE + 1);
                g.dispose();
                boolean[] bits = new boolean[SIZE * SIZE];
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        bits[y * SIZE + x] = (cell.getRGB(x, y) & 0xFFFFFF) != 0;
                    }
                }
                GLYPHS[c] = bits;
            }
        } catch (Throwable unavailable) {
            // Sans AWT, l'ecran de chargement reste muet : rien d'indispensable.
        }
    }

    private SystemFont() {
    }

    /**
     * Ecrit un texte, comme {@code Move} puis {@code Text} : {@code y} est la ligne de base.
     */
    static void text(byte[] target, int width, int height, int x, int y, String text, int colour) {
        int top = y - BASELINE;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean[] bits = (c < GLYPHS.length) ? GLYPHS[c] : null;
            if (bits == null) {
                continue;
            }
            for (int row = 0; row < SIZE; row++) {
                for (int column = 0; column < SIZE; column++) {
                    if (!bits[row * SIZE + column]) {
                        continue;
                    }
                    int px = x + i * SIZE + column;
                    int py = top + row;
                    if (px >= 0 && py >= 0 && px < width && py < height) {
                        target[py * width + px] = (byte) colour;
                    }
                }
            }
        }
    }
}
