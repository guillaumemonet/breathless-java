package fov.breathless.gld.gfx;

import fov.breathless.gld.GldFormatException;

/**
 * Image Amiga en plans de bits, ramenee a un octet par pixel.
 *
 * <p>Le jeu garde quelques images hors des conteneurs {@code .GLD}, dans
 * {@code Sorgenti/Graphic}, sous la forme ou le materiel les attendait : huit plans
 * successifs, chacun d'un bit par pixel, ligne par ligne, le bit de poids fort en tete.
 * L'index de palette d'un pixel se reconstitue en rassemblant son bit dans chaque plan.
 *
 * <p>Le panneau de bord est de celles-la : {@code Graphic/Panel04.raw}, 320x40 en huit
 * plans, que {@code PanelSetup} (Sorgenti/TMapMain.asm) recopiait tel quel sous la vue.
 */
public final class PlanarImage {

    private final int width;
    private final int height;
    private final byte[] pixels;

    private PlanarImage(int width, int height, byte[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * Deplie une image planaire.
     *
     * @param data   octets bruts, plans a la suite
     * @param width  largeur en pixels, multiple de huit
     * @param height hauteur en pixels
     * @param planes nombre de plans, de un a huit
     * @param what   nom de l'image, pour les messages d'erreur
     */
    public static PlanarImage read(byte[] data, int width, int height, int planes, String what) {
        if (width <= 0 || height <= 0 || width % 8 != 0) {
            throw new GldFormatException(what + " : taille invalide " + width + "x" + height);
        }
        if (planes < 1 || planes > 8) {
            throw new GldFormatException(what + " : " + planes + " plans, un a huit attendus");
        }
        int bytesPerRow = width / 8;
        int expected = bytesPerRow * height * planes;
        if (data.length < expected) {
            throw new GldFormatException(what + " : " + data.length + " octets la ou "
                    + expected + " etaient attendus");
        }

        byte[] pixels = new byte[width * height];
        for (int plane = 0; plane < planes; plane++) {
            int base = plane * bytesPerRow * height;
            int mask = 1 << plane;
            for (int y = 0; y < height; y++) {
                int row = base + y * bytesPerRow;
                int out = y * width;
                for (int chunk = 0; chunk < bytesPerRow; chunk++) {
                    int bits = data[row + chunk] & 0xFF;
                    if (bits == 0) {
                        continue;
                    }
                    for (int bit = 0; bit < 8; bit++) {
                        if ((bits & (0x80 >> bit)) != 0) {
                            pixels[out + chunk * 8 + bit] |= (byte) mask;
                        }
                    }
                }
            }
        }
        return new PlanarImage(width, height, pixels);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Index de palette, un octet par pixel, ligne par ligne. */
    public byte[] pixels() {
        return pixels;
    }
}
