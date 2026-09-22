package fov.breathless.gld.object;

import fov.breathless.gld.ByteReader;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.GldFormatException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Banque d'objets OGLD : ennemis, objets a ramasser, projectiles, explosions.
 *
 * <p>Meme ossature que les autres banques :
 * <pre>
 *   +0    'OGLD'
 *   +4    LONG   offset de la directory
 *   +8    enregistrements d'objet, concatenes
 *   dirOffset :
 *         LONG   nombre d'objets
 *         entrees de 12 octets : nom sur 4 caracteres, offset, longueur
 * </pre>
 *
 * <p>Un enregistrement d'objet :
 * <pre>
 *   +0    WORD   nombre d'images
 *   +2    WORD   rayon, en unites monde
 *   +4    WORD   hauteur, en unites monde
 *   +6    BYTE   type d'animation
 *   +7    BYTE   type d'objet
 *   +8    WORD   parametres 1 a 4
 *   +16   BYTE   parametres 5 a 12
 *   +24   LONG   noms des sons 1 a 3, sur quatre caracteres
 *   +36   LONG   offset de l'image courante
 *   +40   LONG   inutilise
 *   +44   LONG   liste d'offsets d'images, terminee par zero
 * </pre>
 *
 * <p>Les offsets d'images sont relatifs au debut de l'enregistrement ; ceux des colonnes
 * d'une image sont relatifs au debut de cette image. Le moteur d'origine les convertit en
 * pointeurs absolus au chargement ({@code InitObjects} dans TMapMain.asm), ce qui explique
 * qu'il les dereference ensuite directement.
 */
public final class ObjectBank {

    /** ID de charge utile d'une banque d'objets. */
    public static final String OGLD = "OGLD";

    /** Un octet de ligne de depart superieur ou egal a ceci termine la colonne. */
    private static final int COLUMN_END = 0x80;

    private static final int FRAME_LIST = 44;

    private final Map<String, ObjectDefinition> objects;

    private ObjectBank(Map<String, ObjectDefinition> objects) {
        this.objects = Map.copyOf(objects);
    }

    public static ObjectBank read(GldContainer gld) {
        String source = gld.source().getFileName().toString();
        ByteReader r = gld.reader();

        String id = r.tag();
        if (!OGLD.equals(id)) {
            throw new GldFormatException(source + " : ID " + id + " la ou " + OGLD + " etait attendu");
        }

        int dirOffset = r.s32();
        r.position(dirOffset);

        int count = r.s32();
        if (count < 0 || count > 10000) {
            throw new GldFormatException(source + " : nombre d'objets aberrant (" + count + ")");
        }

        record DirEntry(String name, int offset, int length) {
        }

        List<DirEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = r.fixedString(4);
            int offset = r.s32();
            int length = r.s32();
            if (offset < 0 || length < 0 || offset + length > gld.size()) {
                throw new GldFormatException(source + " : objet " + name
                        + " hors fichier (offset " + offset + ", longueur " + length + ")");
            }
            entries.add(new DirEntry(name, offset, length));
        }

        Map<String, ObjectDefinition> objects = new LinkedHashMap<>();
        for (DirEntry entry : entries) {
            objects.put(entry.name(), readObject(gld, entry.name(), entry.offset(),
                    entry.offset() + entry.length(), source));
        }
        return new ObjectBank(objects);
    }

    private static ObjectDefinition readObject(GldContainer gld, String name,
                                               int start, int limit, String source) {
        ByteReader r = new ByteReader(gld.payload(), start);

        int frameCount = r.u16();
        int radius = r.u16();
        int height = r.u16();
        int animType = r.s8();
        int objectType = r.u8();

        int[] params = new int[12];
        for (int i = 0; i < 4; i++) {
            params[i] = r.s16();
        }
        for (int i = 4; i < 12; i++) {
            params[i] = r.u8();
        }

        List<String> sounds = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            sounds.add(r.fixedString(4));
        }

        r.s32();   // offset de l'image courante, redondant avec la liste
        r.s32();   // inutilise

        // La liste d'offsets d'images se termine par zero. Une meme image peut y figurer
        // plusieurs fois : les animations directionnelles reutilisent des vues.
        List<Integer> offsets = new ArrayList<>();
        r.position(start + FRAME_LIST);
        while (true) {
            int offset = r.s32();
            if (offset == 0) {
                break;
            }
            if (start + offset >= limit) {
                throw new GldFormatException(source + " : objet " + name
                        + " renvoie a une image hors de son enregistrement (offset " + offset + ")");
            }
            offsets.add(offset);
            if (offsets.size() > 1024) {
                throw new GldFormatException(source + " : objet " + name
                        + " declare une liste d'images interminable");
            }
        }

        // Un meme offset repete donne une seule image, partagee par toutes ses entrees.
        // Ce n'est pas qu'une economie : le moteur d'origine compare les *pointeurs* de la
        // liste pour savoir ou s'arrete une animation — deux entrees egales marquent la
        // fin de la chute (CEDcaduta), et l'egalite des images 41 et 42 dit qu'un type
        // n'a pas d'animation de chute du tout (DestroyEnemy). Lire chaque entree
        // separement rendrait ces comparaisons toujours fausses.
        Map<Integer, ObjectFrame> byOffset = new HashMap<>();
        List<ObjectFrame> frames = new ArrayList<>(offsets.size());
        for (int offset : offsets) {
            frames.add(byOffset.computeIfAbsent(offset,
                    o -> readFrame(gld, name, start + o, limit, source)));
        }

        return new ObjectDefinition(name, frameCount, radius, height, animType, objectType,
                params, sounds, frames);
    }

    private static ObjectFrame readFrame(GldContainer gld, String name,
                                         int start, int limit, String source) {
        ByteReader r = new ByteReader(gld.payload(), start);

        int width = r.u16();
        int height = r.u16();
        int xOffset = r.s16();
        int yOffset = r.s16();

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            throw new GldFormatException(source + " : objet " + name
                    + ", image de dimensions invalides (" + width + "x" + height + ")");
        }

        List<Integer> columnOffsets = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            columnOffsets.add(r.s32());
        }

        List<List<ObjectFrame.Segment>> columns = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            columns.add(readColumn(gld, name, start + columnOffsets.get(i), limit, height, source));
        }
        return new ObjectFrame(width, height, xOffset, yOffset, columns);
    }

    private static List<ObjectFrame.Segment> readColumn(GldContainer gld, String name,
                                                        int at, int limit, int height, String source) {
        if (at < 0 || at >= limit) {
            throw new GldFormatException(source + " : objet " + name
                    + ", colonne hors de son enregistrement (offset " + at + ")");
        }
        ByteReader r = new ByteReader(gld.payload(), at);
        List<ObjectFrame.Segment> segments = new ArrayList<>();

        while (true) {
            int top = r.u8();
            if (top >= COLUMN_END) {
                return segments;
            }
            int length = r.u8();
            // Pas de borne sur la hauteur declaree : certains sprites d'origine la
            // depassent d'une ligne, et le moteur ne le verifiait pas non plus.
            segments.add(new ObjectFrame.Segment(top, r.bytes(length)));

            if (segments.size() > height + 1) {
                throw new GldFormatException(source + " : objet " + name
                        + ", colonne aux segments interminables");
            }
        }
    }

    /** Tous les objets, dans l'ordre du fichier. */
    public Map<String, ObjectDefinition> objects() {
        return objects;
    }

    /** Objet par son nom, ou {@code null} s'il est absent. */
    public ObjectDefinition get(String name) {
        return objects.get(name);
    }

    public int size() {
        return objects.size();
    }
}
