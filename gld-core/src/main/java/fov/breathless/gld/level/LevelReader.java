package fov.breathless.gld.level;

import fov.breathless.gld.ByteReader;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.GldFormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecture d'un fichier de niveau LGLD.
 *
 * <p>Portage de {@code ReadMapGLD} (Sorgenti/Loader.asm) et de son pendant dans
 * l'editeur (MapEditor/GLDAccess.c). La disposition des octets est decrite dans
 * docs/formats-gld.md.
 *
 * <p>La lecture est volontairement stricte : toute incoherence structurelle leve une
 * {@link GldFormatException} plutot que de produire un niveau silencieusement faux.
 * En particulier, la longueur annoncee dans l'en-tete est recoupee avec la position
 * reellement atteinte apres la carte, ce qui detecte un decalage d'un seul octet dans
 * la section des effets.
 */
public final class LevelReader {

    /** ID de charge utile d'un fichier de niveau. */
    public static final String LGLD = "LGLD";

    /** Offset du premier octet couvert par le champ de longueur de l'en-tete. */
    private static final int BODY_START = 15;

    private static final int MAP_CELLS = Level.MAP_SIZE * Level.MAP_SIZE;

    private LevelReader() {
    }

    public static Level read(GldContainer gld) {
        String name = gld.source().getFileName().toString();
        ByteReader r = gld.reader();

        String id = r.tag();
        if (!LGLD.equals(id)) {
            throw new GldFormatException(name + " : ID " + id + " la ou " + LGLD + " etait attendu");
        }

        r.u16();          // nombre de niveaux dans le fichier, toujours 1
        r.s32();          // offset de la directory, toujours 0
        r.u8();           // drapeau de compression interne, toujours 0
        int bodyLength = r.s32();

        List<Block> blocks = readBlocks(r, name);
        List<Edge> edges = readEdges(r, name);
        List<List<EffectEntry>> effectLists = readEffectLists(r);
        short[] map = readMap(r);

        int actualLength = r.position() - BODY_START;
        if (actualLength != bodyLength) {
            throw new GldFormatException(name + " : longueur de corps incoherente, "
                    + bodyLength + " annonces contre " + actualLength + " lus"
                    + " (decalage probable dans la section des effets)");
        }

        List<String> textureNames = readNames(r, 8, "textures", name);
        List<String> objectNames = readNames(r, 4, "objets", name);

        List<MapObject> objects = new ArrayList<>();
        PlayerStart playerStart = readObjects(r, name, objects);

        List<String> soundNames = readNames(r, 4, "sons", name);
        String loadingPicture = r.fixedString(4);

        Level level = new Level(name, blocks, edges, effectLists, map,
                textureNames, objectNames, soundNames, objects, playerStart, loadingPicture);

        validate(level, name);
        return level;
    }

    private static List<Block> readBlocks(ByteReader r, String name) {
        int count = readCount(r, "blocs", name);
        List<Block> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            blocks.add(new Block(
                    r.s16(),   // hauteur du sol
                    r.s16(),   // hauteur du plafond
                    r.s16(),   // texture du sol
                    r.s16(),   // texture du plafond, negative si ciel
                    r.s16(),   // numero de bloc
                    r.u16(),   // eclairage
                    r.s32(),   // arete est
                    r.s32(),   // arete sud
                    r.s32(),   // arete ouest
                    r.s32(),   // arete nord
                    r.u8(),    // liste d'effets declenchee
                    r.u8(),    // trigger secondaire
                    r.u8(),    // attributs
                    r.u8()));  // trigger principal
        }
        return blocks;
    }

    private static List<Edge> readEdges(ByteReader r, String name) {
        int count = readCount(r, "aretes", name);
        List<Edge> edges = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int norm = r.s32();
            int up = r.s32();
            int low = r.s32();
            int attributes = r.u16();
            r.u16();   // inutilise
            edges.add(new Edge(norm, up, low, attributes));
        }
        return edges;
    }

    /**
     * Les effets forment des listes : un LONG a 0 termine une liste, un LONG negatif
     * termine l'ensemble. Le couple trigger/effet du premier enregistrement d'une liste
     * sert aussi de marqueur, d'ou la lecture en avance d'un LONG.
     */
    private static List<List<EffectEntry>> readEffectLists(ByteReader r) {
        List<List<EffectEntry>> lists = new ArrayList<>();
        while (true) {
            int header = r.s32();
            if (header < 0) {
                return lists;
            }
            List<EffectEntry> list = new ArrayList<>();
            while (header != 0) {
                int trigger = (short) (header >> 16);
                int effect = (short) (header & 0xFFFF);
                int params = r.s32();
                int param1 = (short) (params >> 16);
                int param2 = (short) (params & 0xFFFF);
                int key = (r.u16() >> 8) & 0xFF;
                list.add(new EffectEntry(trigger, effect, param1, param2, key));
                header = r.s32();
            }
            lists.add(list);
        }
    }

    private static short[] readMap(ByteReader r) {
        short[] map = new short[MAP_CELLS];
        for (int i = 0; i < MAP_CELLS; i++) {
            map[i] = (short) r.s16();
        }
        return map;
    }

    private static List<String> readNames(ByteReader r, int width, String what, String name) {
        int count = readCount(r, what, name);
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(r.fixedString(width));
        }
        return names;
    }

    /**
     * Lit les objets places dans le niveau. L'entree d'index 0 n'est pas un objet mais
     * la position de depart du joueur ; comme dans le moteur d'origine, la derniere
     * rencontree l'emporte.
     */
    private static PlayerStart readObjects(ByteReader r, String name, List<MapObject> objects) {
        int count = readCount(r, "objets places", name);
        PlayerStart start = null;
        for (int i = 0; i < count; i++) {
            int index = r.u16();
            int x = r.s16();
            int z = r.s16();
            int heading = r.u16();
            int flags = r.u8();
            int trigger = r.u8();
            if (index == 0) {
                start = new PlayerStart(x, z, heading);
            } else {
                objects.add(new MapObject(index, x, z, heading, flags, trigger));
            }
        }
        if (start == null) {
            throw new GldFormatException(name + " : aucune position de depart du joueur");
        }
        return start;
    }

    private static int readCount(ByteReader r, String what, String name) {
        int count = r.s32();
        if (count < 0) {
            throw new GldFormatException(name + " : nombre de " + what + " negatif (" + count + ")");
        }
        // Garde-fou : un compteur aberrant signale une desynchronisation de lecture
        // bien avant que la taille du fichier ne soit depassee.
        if (count > 100000) {
            throw new GldFormatException(name + " : nombre de " + what + " aberrant (" + count + ")");
        }
        return count;
    }

    /** Recoupe les index croises : aretes, textures, effets, objets. */
    private static void validate(Level level, String name) {
        int blockCount = level.blocks().size();
        int edgeCount = level.edges().size();
        int textureCount = level.textureNames().size();
        int effectListCount = level.effectLists().size();

        for (int i = 0; i < blockCount; i++) {
            Block b = level.blocks().get(i);
            for (int e = 1; e <= 4; e++) {
                int edge = b.edge(e);
                if (edge < 0 || edge >= edgeCount) {
                    throw new GldFormatException(name + " : bloc " + i + ", arete " + e
                            + " pointe l'index " + edge + " hors des " + edgeCount + " aretes");
                }
            }
            checkTexture(name, textureCount, b.floorTexture(), "bloc " + i + " sol");
            checkTexture(name, textureCount, b.ceilTextureIndex(), "bloc " + i + " plafond");
            if (b.effectList() > effectListCount) {
                throw new GldFormatException(name + " : bloc " + i + " declenche la liste d'effets "
                        + b.effectList() + " alors qu'il n'y en a que " + effectListCount);
            }
        }

        for (int i = 0; i < edgeCount; i++) {
            Edge e = level.edges().get(i);
            checkTexture(name, textureCount, e.normTexture(), "arete " + i + " principale");
            checkTexture(name, textureCount, e.upTexture(), "arete " + i + " haute");
            checkTexture(name, textureCount, e.lowTexture(), "arete " + i + " basse");
        }

        for (int y = 0; y < Level.MAP_SIZE; y++) {
            for (int x = 0; x < Level.MAP_SIZE; x++) {
                int index = level.blockIndex(x, y);
                if (index >= blockCount) {
                    throw new GldFormatException(name + " : case " + x + "," + y
                            + " designe le bloc " + index + " hors des " + blockCount + " blocs");
                }
            }
        }

        int objectCount = level.objectNames().size();
        for (MapObject o : level.objects()) {
            if (o.objectIndex() > objectCount) {
                throw new GldFormatException(name + " : objet place d'index " + o.objectIndex()
                        + " hors des " + objectCount + " objets declares");
            }
        }
    }

    private static void checkTexture(String name, int textureCount, int index, String where) {
        if (index < 0 || index > textureCount) {
            throw new GldFormatException(name + " : " + where + " designe la texture "
                    + index + " hors des " + textureCount + " textures declarees");
        }
    }
}
