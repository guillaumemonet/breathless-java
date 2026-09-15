package fov.breathless.gld.object;

import java.util.List;

/**
 * Une image de sprite, rangee en colonnes de segments verticaux.
 *
 * <p>Chaque colonne est une suite de <em>segments</em> : une ligne de depart, une
 * longueur, puis les index de palette. Ce qui n'appartient a aucun segment est
 * transparent. C'est le meme principe que les « posts » de Doom, et c'est ce qui permet
 * de dessiner un sprite decoupe sans masque ni couleur reservee.
 *
 * <p>Les decalages {@code xOffset} et {@code yOffset} recalent le dessin par rapport au
 * point d'ancrage de l'objet dans le monde : ils servent a poser un ennemi sur ses pieds
 * plutot que par son centre.
 */
public final class ObjectFrame {

    /** Un segment vertical opaque dans une colonne. */
    public record Segment(int top, byte[] pixels) {

        /** Nombre de lignes couvertes. */
        public int length() {
            return pixels.length;
        }

        /** Index de palette de la ligne demandee, relative au haut du segment. */
        public int pixel(int row) {
            return pixels[row] & 0xFF;
        }
    }

    private final int width;
    private final int height;
    private final int pixelHeight;
    private final int xOffset;
    private final int yOffset;
    private final List<List<Segment>> columns;

    ObjectFrame(int width, int height, int xOffset, int yOffset, List<List<Segment>> columns) {
        this.width = width;
        this.height = height;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.columns = columns;

        int lowest = height;
        for (List<Segment> column : columns) {
            for (Segment segment : column) {
                lowest = Math.max(lowest, segment.top() + segment.length());
            }
        }
        this.pixelHeight = lowest;
    }

    public int width() {
        return width;
    }

    /**
     * Hauteur declaree par l'en-tete. C'est elle qui sert au calcul de la taille a
     * l'ecran.
     */
    public int height() {
        return height;
    }

    /**
     * Hauteur reellement couverte par les segments.
     *
     * <p>Certains sprites d'origine debordent d'une ligne sous la hauteur declaree. Le
     * moteur ne verifiait pas cette borne : il decoupait a l'ecran, pas au sprite. On
     * conserve donc les deux valeurs plutot que d'en corriger une.
     */
    public int pixelHeight() {
        return pixelHeight;
    }

    /** Decalage horizontal du dessin par rapport a l'ancrage de l'objet. */
    public int xOffset() {
        return xOffset;
    }

    /** Decalage vertical du dessin par rapport a l'ancrage de l'objet. */
    public int yOffset() {
        return yOffset;
    }

    /** Segments opaques d'une colonne, de haut en bas. */
    public List<Segment> column(int index) {
        return columns.get(index);
    }

    @Override
    public String toString() {
        return width + "x" + height + " (" + xOffset + "," + yOffset + ")";
    }
}
