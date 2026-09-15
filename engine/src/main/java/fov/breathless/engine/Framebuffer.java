package fov.breathless.engine;

import fov.breathless.gld.gfx.Palette;

/**
 * Tampon d'affichage en index de palette, un octet par pixel.
 *
 * <p>C'est l'exact equivalent du « fake chunky buffer » du moteur d'origine, que la
 * conversion chunky-to-planar transferait ensuite vers l'ecran AGA. Ici il sera
 * simplement televerse comme texture.
 */
public final class Framebuffer {

    private final int width;
    private final int height;
    private final byte[] pixels;

    public Framebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new byte[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Acces direct aux index de palette, pour le rendu. */
    public byte[] pixels() {
        return pixels;
    }

    public void clear(int paletteIndex) {
        java.util.Arrays.fill(pixels, (byte) paletteIndex);
    }

    public void set(int x, int y, int paletteIndex) {
        pixels[y * width + x] = (byte) paletteIndex;
    }

    public int get(int x, int y) {
        return pixels[y * width + x] & 0xFF;
    }

    /** Convertit le tampon en pixels ARGB opaques. */
    public int[] toArgb(Palette palette) {
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            out[i] = palette.argb(pixels[i] & 0xFF);
        }
        return out;
    }
}
