package fov.breathless.gld.gfx;

import fov.breathless.gld.GldFormatException;

/**
 * Une palette de 256 couleurs, telle que stockee dans le fichier GGLD :
 * 256 triplets RVB de un octet chacun, soit 768 octets.
 *
 * <p>Le moteur d'origine charge ces octets directement dans la variable
 * {@code Palette} (Sorgenti/TMap.asm), puis les pousse dans le viewport AGA.
 */
public final class Palette {

    /** Nombre de couleurs d'une palette. */
    public static final int COLORS = 256;

    /** Taille d'une palette sur fichier. */
    public static final int SIZE = COLORS * 3;

    private final byte[] rgb;

    public Palette(byte[] rgb) {
        if (rgb.length != SIZE) {
            throw new GldFormatException("Palette de " + rgb.length + " octets, " + SIZE + " attendus");
        }
        this.rgb = rgb.clone();
    }

    public int red(int index) {
        return rgb[index * 3] & 0xFF;
    }

    public int green(int index) {
        return rgb[index * 3 + 1] & 0xFF;
    }

    public int blue(int index) {
        return rgb[index * 3 + 2] & 0xFF;
    }

    /** Couleur au format 0xAARRGGBB, opaque. */
    public int argb(int index) {
        return 0xFF000000 | (red(index) << 16) | (green(index) << 8) | blue(index);
    }

    /**
     * Convertit une image en index de palette vers un tableau ARGB.
     *
     * @param indexes  un octet par pixel, index dans la palette
     * @param transparent index considere comme transparent, ou -1 pour tout rendre opaque
     */
    public int[] toArgb(byte[] indexes, int transparent) {
        int[] out = new int[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            int index = indexes[i] & 0xFF;
            out[i] = (index == transparent) ? 0 : argb(index);
        }
        return out;
    }

    /** Copie brute des 768 octets. */
    public byte[] toByteArray() {
        return rgb.clone();
    }
}
