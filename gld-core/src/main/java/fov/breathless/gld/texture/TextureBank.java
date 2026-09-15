package fov.breathless.gld.texture;

import fov.breathless.gld.ByteReader;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.GldFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Banque de textures TGLD.
 *
 * <p>Disposition du fichier :
 * <pre>
 *   +0    'TGLD'
 *   +4    LONG   offset de la directory
 *   +8    enregistrements de texture, concatenes
 *   dirOffset :
 *         LONG   nombre de textures
 *         entrees de 16 octets : nom sur 8 caracteres, offset, longueur
 * </pre>
 *
 * <p>Un enregistrement de texture (structure {@code FTexture} de l'editeur) :
 * <pre>
 *   +0    WORD   largeur, en colonnes
 *   +2    WORD   nombre d'images de l'animation
 *   +4    WORD   hauteur, en lignes
 *   +6    WORD   exposant tel que 1 &lt;&lt; exposant == hauteur
 *   +8    LONG   offset de la premiere image
 *   +12   LONG   offset de la variante d'interrupteur, 0 s'il n'y en a pas
 *   +16   LONG   liste d'offsets terminee par zero, si la texture est animee
 *         pixels, largeur x hauteur octets par image, ranges par colonnes
 * </pre>
 *
 * <p>Attention : tous ces offsets sont relatifs au champ <em>hauteur</em>, donc a
 * l'octet 4 de l'enregistrement, et non a son debut. C'est une consequence directe
 * du moteur, dont le pointeur de texture vise ce champ (voir les offsets {@code tx_*}
 * dans Sorgenti/TMap.i).
 */
public final class TextureBank {

    /** ID de charge utile d'une banque de textures. */
    public static final String TGLD = "TGLD";

    /** Les offsets internes d'un enregistrement sont relatifs a cet octet. */
    private static final int OFFSET_BASE = 4;

    private final Map<String, Texture> textures;

    private TextureBank(Map<String, Texture> textures) {
        this.textures = Map.copyOf(textures);
    }

    public static TextureBank read(GldContainer gld) {
        String source = gld.source().getFileName().toString();
        ByteReader r = gld.reader();

        String id = r.tag();
        if (!TGLD.equals(id)) {
            throw new GldFormatException(source + " : ID " + id + " la ou " + TGLD + " etait attendu");
        }

        int dirOffset = r.s32();
        r.position(dirOffset);

        int count = r.s32();
        if (count < 0 || count > 10000) {
            throw new GldFormatException(source + " : nombre de textures aberrant (" + count + ")");
        }

        record DirEntry(String name, int offset, int length) {
        }

        List<DirEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = r.fixedString(8);
            int offset = r.s32();
            int length = r.s32();
            if (offset < 0 || length < 0 || offset + length > gld.size()) {
                throw new GldFormatException(source + " : texture " + name
                        + " hors fichier (offset " + offset + ", longueur " + length + ")");
            }
            entries.add(new DirEntry(name, offset, length));
        }

        Map<String, Texture> textures = new LinkedHashMap<>();
        for (DirEntry entry : entries) {
            textures.put(entry.name(),
                    readTexture(gld, entry.name(), entry.offset(), entry.offset() + entry.length(), source));
        }
        return new TextureBank(textures);
    }

    /**
     * Lit un enregistrement de texture.
     *
     * @param start debut de l'enregistrement
     * @param limit premier octet au-dela de l'enregistrement, pour borner les offsets
     */
    private static Texture readTexture(GldContainer gld, String name, int start, int limit, String source) {
        ByteReader r = new ByteReader(gld.payload(), start);

        int width = r.u16();
        int frameCount = r.u16();
        int height = r.u16();
        int heightShift = r.u16();
        int firstFrame = r.s32();
        int switchOffset = r.s32();

        if (width <= 0 || height <= 0) {
            throw new GldFormatException(source + " : texture " + name
                    + " de dimensions invalides (" + width + "x" + height + ")");
        }
        if ((1 << heightShift) != height) {
            throw new GldFormatException(source + " : texture " + name + " de hauteur " + height
                    + " incompatible avec l'exposant " + heightShift);
        }
        if (frameCount < 1) {
            throw new GldFormatException(source + " : texture " + name
                    + " sans image (" + frameCount + ")");
        }

        List<Integer> offsets = new ArrayList<>(frameCount);
        if (frameCount > 1) {
            for (int i = 0; i < frameCount; i++) {
                offsets.add(r.s32());
            }
        } else {
            offsets.add(firstFrame);
        }

        int pixels = width * height;
        List<byte[]> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int at = start + OFFSET_BASE + offsets.get(i);
            if (at < start || at + pixels > limit) {
                throw new GldFormatException(source + " : texture " + name + ", image " + i
                        + " hors de son enregistrement (offset " + offsets.get(i) + ")");
            }
            byte[] data = new byte[pixels];
            System.arraycopy(gld.payload(), at, data, 0, pixels);
            frames.add(data);
        }

        Texture switched = null;
        if (switchOffset != 0) {
            // Contrairement aux offsets d'image, celui-ci designe un enregistrement de
            // texture complet, et il vise son champ hauteur : c'est ainsi que le moteur
            // adresse une texture (les offsets tx_* de TMap.i sont relatifs a tx_Height).
            // Le debut de l'enregistrement imbrique est donc quatre octets plus haut.
            int at = start + switchOffset;
            if (at > start && at < limit) {
                switched = readTexture(gld, name + "_on", at, limit, source);
            }
        }

        return new Texture(name, width, height, heightShift, frames, switched);
    }

    /** Toutes les textures, dans l'ordre du fichier. */
    public Map<String, Texture> textures() {
        return textures;
    }

    /** Texture par son nom, ou {@code null} si absente. */
    public Texture get(String name) {
        return textures.get(name);
    }

    public int size() {
        return textures.size();
    }
}
