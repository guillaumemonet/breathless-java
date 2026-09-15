package fov.breathless.engine;

import fov.breathless.gld.gfx.MonitorFont;

/**
 * Le « moniteur » sur lequel le jeu pose ses menus.
 *
 * <p>Portage de {@code SprPrint} et {@code SprCharPrint} (Sorgenti/Text.asm). L'original
 * dessine ces pages avec une paire de sprites materiels de 128 pixels sur 132, posee en
 * haut a gauche de la vue, en deux couleurs sur fond transparent, chaque caractere etant
 * imprime deux fois : une ombre decalee d'un pixel, puis le trait.
 *
 * <p>Deux ecarts assumes, communs au terminal et aux menus : le fond est ici opaque, faute
 * de quoi le texte se perd dans le decor, et les teintes exactes des sprites ne se
 * deduisent pas de la source.
 */
public final class Monitor {

    /** Largeur du moniteur. {@code SPRMON_NSPRITE * 64}. */
    public static final int WIDTH = 128;

    /** Hauteur du moniteur. {@code SPRMON_HEIGHT}. */
    public static final int HEIGHT = 22 * MonitorFont.LINE_HEIGHT;

    /** Coin haut gauche du moniteur dans la vue, ou l'original place ses sprites. */
    public static final int LEFT = 8;
    public static final int TOP = 0;

    /** Fond du moniteur. */
    public static final int COLOUR_BACKGROUND = 243;

    /** Couleur ordinaire du texte. */
    public static final int COLOUR_TEXT = 16;

    /** Ombre portee sous chaque caractere. */
    public static final int COLOUR_SHADOW = 31;

    /** Couleur de la ligne choisie. */
    public static final int COLOUR_SELECTED = 2;

    /** Couleur d'un refus. */
    public static final int COLOUR_REFUSED = 62;

    private Monitor() {
    }

    /** Efface le moniteur et le pose sur la vue. */
    public static void clear(byte[] target, int width, int height) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                plot(target, width, height, LEFT + x, TOP + y, COLOUR_BACKGROUND);
            }
        }
    }

    /** Imprime une ligne, ombre portee d'abord, comme {@code SprPrint}. */
    public static void print(byte[] target, int width, int height,
                             int x, int y, String text, int colour) {
        for (int i = 0; i < text.length(); i++) {
            int left = LEFT + x + i * MonitorFont.ADVANCE;
            glyph(target, width, height, left + 1, TOP + y + 1, text.charAt(i), COLOUR_SHADOW);
            glyph(target, width, height, left, TOP + y, text.charAt(i), colour);
        }
    }

    /** Largeur d'un texte, en pixels. */
    public static int textWidth(String text) {
        return text.length() * MonitorFont.ADVANCE;
    }

    private static void glyph(byte[] target, int width, int height,
                              int x, int y, char character, int colour) {
        for (int line = 0; line < MonitorFont.HEIGHT; line++) {
            int bits = MonitorFont.row(character, line);
            if (bits == 0) {
                continue;
            }
            for (int pixel = 0; pixel < MonitorFont.WIDTH; pixel++) {
                if ((bits & (1 << (MonitorFont.WIDTH - 1 - pixel))) != 0) {
                    plot(target, width, height, x + pixel, y + line, colour);
                }
            }
        }
    }

    private static void plot(byte[] target, int width, int height, int x, int y, int colour) {
        if (x >= 0 && y >= 0 && x < width && y < height) {
            target[y * width + x] = (byte) colour;
        }
    }
}
