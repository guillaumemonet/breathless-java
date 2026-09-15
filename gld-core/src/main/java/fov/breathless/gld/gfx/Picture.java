package fov.breathless.gld.gfx;

import fov.breathless.gld.ByteReader;
import fov.breathless.gld.GldFormatException;
import fov.breathless.gld.Slz;

/**
 * Une image plein ecran de la banque graphique : titre, logos, credits, fins de partie.
 *
 * <p>Portage de {@code LoadPic} (Sorgenti/Loader.asm) et de {@code ShowPic}
 * (Sorgenti/Presentation.asm). Disposition :
 *
 * <pre>
 *   +0   LONG   inutilise
 *   +4   WORD   abscisse d'affichage
 *   +6   WORD   ordonnee d'affichage
 *   +8   WORD   largeur
 *   +10  WORD   hauteur
 *   +12  768    palette, ou 'VDCO' si ce qui suit est comprime
 *   +780        pixels, un octet par pixel
 * </pre>
 *
 * <p>Une image comprimee porte {@code VDCO} a l'emplacement de sa palette : sa
 * decompression rend la palette et les pixels, l'en-tete restant celui d'origine. C'est
 * la meme compression que celle des conteneurs {@code .GLD}.
 */
public final class Picture {

    /** Marque de compression, a l'emplacement de la palette. */
    private static final String VDCO = "VDCO";

    /** Taille de l'en-tete precedant la palette. */
    private static final int HEADER = 12;

    /** Position des pixels une fois l'image en clair. */
    private static final int PIXELS = HEADER + Palette.SIZE;

    private final String name;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final Palette palette;
    private final byte[] pixels;
    private final boolean compressed;

    private Picture(String name, int x, int y, int width, int height, Palette palette,
                    byte[] pixels, boolean compressed) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.palette = palette;
        this.pixels = pixels;
        this.compressed = compressed;
    }

    /**
     * Lit une image telle que {@link GfxBank#pictureData} la livre.
     *
     * @param data octets bruts, en-tete comprise
     * @param name nom de l'image, pour les messages d'erreur
     */
    public static Picture read(byte[] data, String name) {
        if (data.length < PIXELS) {
            throw new GldFormatException(name + " : " + data.length
                    + " octets, trop court pour une image");
        }
        ByteReader r = new ByteReader(data, 4);
        int x = r.u16();
        int y = r.u16();
        int width = r.u16();
        int height = r.u16();

        if (width <= 0 || height <= 0 || width > 1024 || height > 1024) {
            throw new GldFormatException(name + " : taille " + width + "x" + height
                    + ", valeurs aberrantes");
        }

        String marker = new String(data, HEADER, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] clear;
        boolean compressed = VDCO.equals(marker);
        if (compressed) {
            ByteReader head = new ByteReader(data, HEADER + 4);
            long originalSize = head.u32();
            if (originalSize < Palette.SIZE || originalSize > (1 << 24)) {
                throw new GldFormatException(name + " : taille decompressee irrealiste ("
                        + originalSize + ")");
            }
            clear = Slz.decompress(data, HEADER + 12, (int) originalSize);
        } else {
            clear = new byte[data.length - HEADER];
            System.arraycopy(data, HEADER, clear, 0, clear.length);
        }

        if (clear.length < Palette.SIZE) {
            throw new GldFormatException(name + " : palette incomplete");
        }
        byte[] paletteBytes = new byte[Palette.SIZE];
        System.arraycopy(clear, 0, paletteBytes, 0, Palette.SIZE);

        int available = clear.length - Palette.SIZE;
        int needed = width * height;
        if (available < needed) {
            throw new GldFormatException(name + " : " + needed + " pixels annonces pour "
                    + available + " disponibles");
        }
        byte[] pixels = new byte[needed];
        System.arraycopy(clear, Palette.SIZE, pixels, 0, needed);

        return new Picture(name, x, y, width, height, new Palette(paletteBytes), pixels,
                compressed);
    }

    public String name() {
        return name;
    }

    /** Abscisse a laquelle l'image se pose a l'ecran. */
    public int x() {
        return x;
    }

    /** Ordonnee a laquelle l'image se pose a l'ecran. */
    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** L'image porte sa propre palette, qui remplace celle du jeu le temps de l'afficher. */
    public Palette palette() {
        return palette;
    }

    /** Pixels, un octet par pixel, ligne par ligne. */
    public byte[] pixels() {
        return pixels;
    }

    public boolean isCompressed() {
        return compressed;
    }

    @Override
    public String toString() {
        return name + " " + width + "x" + height + " en " + x + "," + y
                + (compressed ? ", comprimee" : "");
    }
}
