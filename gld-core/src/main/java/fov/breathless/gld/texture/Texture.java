package fov.breathless.gld.texture;

import java.util.List;

/**
 * Une texture de decor, eventuellement animee.
 *
 * <p>Les pixels sont ranges <strong>par colonnes</strong> : la colonne {@code x}
 * occupe les {@code height} octets consecutifs a partir de {@code x * height}.
 * C'est ce qui permet au raycaster de tracer un trait vertical en parcourant des
 * octets contigus, et c'est aussi la raison d'etre du champ « height shift » du
 * fichier : la multiplication par la hauteur se fait par decalage.
 *
 * <p>Chaque octet est un index dans la palette du jeu.
 */
public final class Texture {

    private final String name;
    private final int width;
    private final int height;
    private final int heightShift;
    private final List<byte[]> frames;
    private final Texture switched;

    Texture(String name, int width, int height, int heightShift, List<byte[]> frames, Texture switched) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.heightShift = heightShift;
        this.frames = List.copyOf(frames);
        this.switched = switched;
    }

    public String name() {
        return name;
    }

    /** Nombre de colonnes. */
    public int width() {
        return width;
    }

    /** Nombre de lignes. */
    public int height() {
        return height;
    }

    /** Exposant tel que {@code 1 << heightShift == height}. */
    public int heightShift() {
        return heightShift;
    }

    /** Nombre d'images de l'animation ; 1 si la texture est fixe. */
    public int frameCount() {
        return frames.size();
    }

    public boolean isAnimated() {
        return frames.size() > 1;
    }

    /** Pixels d'une image, ranges par colonnes. */
    public byte[] frame(int index) {
        return frames.get(index);
    }

    /** Index de palette du pixel demande, dans l'image indiquee. */
    public int pixel(int frame, int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("Pixel hors texture " + name + " : " + x + "," + y);
        }
        return frames.get(frame)[(x << heightShift) + y] & 0xFF;
    }

    /**
     * Variante affichee une fois l'interrupteur actionne, ou {@code null} si cette
     * texture n'en a pas. Le format l'encode comme une seconde texture complete
     * placee juste apres les pixels de la premiere.
     */
    public Texture switched() {
        return switched;
    }

    public boolean isSwitch() {
        return switched != null;
    }

    @Override
    public String toString() {
        return name + " " + width + "x" + height
                + (isAnimated() ? " (" + frames.size() + " images)" : "")
                + (isSwitch() ? " + variante" : "");
    }
}
