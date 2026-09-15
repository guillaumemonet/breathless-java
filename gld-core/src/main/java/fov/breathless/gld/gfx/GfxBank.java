package fov.breathless.gld.gfx;

import fov.breathless.gld.ByteReader;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.GldFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Banque graphique GGLD : palettes, tables d'eclairage et images plein ecran.
 *
 * <p>Portage de {@code ReadGfxDir} (Sorgenti/Loader.asm). Disposition du fichier :
 * <pre>
 *   +0    'GGLD'
 *   +4    LONG   offset de la directory
 *   +8    WORD   nombre de palettes
 *         palettes, 768 octets chacune
 *         WORD   nombre de tables d'eclairage
 *         tables d'eclairage, 8192 octets chacune
 *         donnees des images
 *   dirOffset :
 *         LONG   nombre d'images
 *         entrees de 12 octets : nom sur 4 caracteres, offset, longueur
 * </pre>
 *
 * <p>Le jeu livre deux palettes (la palette normale et celle du flash rouge des
 * degats) et deux tables d'eclairage (normale et brouillard).
 */
public final class GfxBank {

    /** ID de charge utile d'une banque graphique. */
    public static final String GGLD = "GGLD";

    private final List<Palette> palettes;
    private final List<LightingTable> lightingTables;
    private final Map<String, Entry> pictures;
    private final byte[] payload;

    /** Emplacement d'une image dans le fichier. */
    public record Entry(String name, int offset, int length) {
    }

    private GfxBank(List<Palette> palettes,
                    List<LightingTable> lightingTables,
                    Map<String, Entry> pictures,
                    byte[] payload) {
        this.palettes = List.copyOf(palettes);
        this.lightingTables = List.copyOf(lightingTables);
        this.pictures = Map.copyOf(pictures);
        this.payload = payload;
    }

    public static GfxBank read(GldContainer gld) {
        String name = gld.source().getFileName().toString();
        ByteReader r = gld.reader();

        String id = r.tag();
        if (!GGLD.equals(id)) {
            throw new GldFormatException(name + " : ID " + id + " la ou " + GGLD + " etait attendu");
        }

        int dirOffset = r.s32();

        int paletteCount = r.u16();
        if (paletteCount < 1 || paletteCount > 16) {
            throw new GldFormatException(name + " : nombre de palettes aberrant (" + paletteCount + ")");
        }
        List<Palette> palettes = new ArrayList<>(paletteCount);
        for (int i = 0; i < paletteCount; i++) {
            palettes.add(new Palette(r.bytes(Palette.SIZE)));
        }

        int lightingCount = r.u16();
        if (lightingCount < 0 || lightingCount > 16) {
            throw new GldFormatException(name + " : nombre de tables d'eclairage aberrant (" + lightingCount + ")");
        }
        List<LightingTable> lightingTables = new ArrayList<>(lightingCount);
        for (int i = 0; i < lightingCount; i++) {
            lightingTables.add(new LightingTable(r.bytes(LightingTable.SIZE)));
        }

        r.position(dirOffset);
        int pictureCount = r.s32();
        if (pictureCount < 0 || pictureCount > 10000) {
            throw new GldFormatException(name + " : nombre d'images aberrant (" + pictureCount + ")");
        }
        Map<String, Entry> pictures = new LinkedHashMap<>();
        for (int i = 0; i < pictureCount; i++) {
            String picName = r.fixedString(4);
            int offset = r.s32();
            int length = r.s32();
            if (offset < 0 || length < 0 || offset + length > gld.size()) {
                throw new GldFormatException(name + " : image " + picName
                        + " hors fichier (offset " + offset + ", longueur " + length + ")");
            }
            pictures.put(picName, new Entry(picName, offset, length));
        }

        return new GfxBank(palettes, lightingTables, pictures, gld.payload());
    }

    /** Palette principale du jeu. */
    public Palette palette() {
        return palettes.get(0);
    }

    public List<Palette> palettes() {
        return palettes;
    }

    /** Table d'eclairage principale. */
    public LightingTable lightingTable() {
        return lightingTables.get(0);
    }

    /** Table d'eclairage pour le brouillard, si le fichier en fournit une seconde. */
    public LightingTable fogLightingTable() {
        return lightingTables.size() > 1 ? lightingTables.get(1) : lightingTables.get(0);
    }

    public List<LightingTable> lightingTables() {
        return lightingTables;
    }

    /** Entrees du catalogue d'images, dans l'ordre du fichier. */
    public Map<String, Entry> pictures() {
        return pictures;
    }

    /** Octets bruts d'une image, en-tete comprise. */
    public byte[] pictureData(String name) {
        Entry entry = pictures.get(name);
        if (entry == null) {
            throw new GldFormatException("Image inconnue : " + name);
        }
        byte[] out = new byte[entry.length()];
        System.arraycopy(payload, entry.offset(), out, 0, entry.length());
        return out;
    }
}
