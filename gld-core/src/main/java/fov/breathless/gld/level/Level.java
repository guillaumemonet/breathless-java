package fov.breathless.gld.level;

import java.util.List;

/**
 * Un niveau de Breathless, tel que decrit par un fichier LGLD.
 *
 * <p>La carte fait 128 x 128 cases. Chaque case porte un entier signe :
 * <ul>
 *   <li>0 : vide, hors du monde jouable ;</li>
 *   <li>positif : index du bloc traversable occupant la case ;</li>
 *   <li>negatif : meme index, mais la case est un mur plein ou le lancer de rayons
 *       s'arrete.</li>
 * </ul>
 * Le signe est donc le drapeau de solidite, exactement comme dans le moteur d'origine.
 */
public final class Level {

    /** Cote de la carte, en cases. */
    public static final int MAP_SIZE = 128;

    /** Cote d'une case, en unites monde. */
    public static final int BLOCK_SIZE = 64;

    private final String name;
    private final List<Block> blocks;
    private final List<Edge> edges;
    private final List<List<EffectEntry>> effectLists;
    private final short[] map;
    private final List<String> textureNames;
    private final List<String> objectNames;
    private final List<String> soundNames;
    private final List<MapObject> objects;
    private final PlayerStart playerStart;
    private final String loadingPicture;

    Level(String name,
          List<Block> blocks,
          List<Edge> edges,
          List<List<EffectEntry>> effectLists,
          short[] map,
          List<String> textureNames,
          List<String> objectNames,
          List<String> soundNames,
          List<MapObject> objects,
          PlayerStart playerStart,
          String loadingPicture) {
        this.name = name;
        this.blocks = List.copyOf(blocks);
        this.edges = List.copyOf(edges);
        this.effectLists = effectLists.stream().map(List::copyOf).toList();
        this.map = map;
        this.textureNames = List.copyOf(textureNames);
        this.objectNames = List.copyOf(objectNames);
        this.soundNames = List.copyOf(soundNames);
        this.objects = List.copyOf(objects);
        this.playerStart = playerStart;
        this.loadingPicture = loadingPicture;
    }

    /** Nom du fichier dont provient ce niveau. */
    public String name() {
        return name;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Listes d'effets, dans l'ordre du fichier. Un bloc les designe par index 1-based. */
    public List<List<EffectEntry>> effectLists() {
        return effectLists;
    }

    public List<String> textureNames() {
        return textureNames;
    }

    public List<String> objectNames() {
        return objectNames;
    }

    public List<String> soundNames() {
        return soundNames;
    }

    /** Objets places dans le niveau, hors position de depart du joueur. */
    public List<MapObject> objects() {
        return objects;
    }

    public PlayerStart playerStart() {
        return playerStart;
    }

    /** Nom de l'image affichee pendant le chargement du niveau. */
    public String loadingPicture() {
        return loadingPicture;
    }

    /** Valeur brute et signee de la case, telle qu'elle figure sur le fichier. */
    public int rawCell(int x, int y) {
        checkBounds(x, y);
        return map[(y << 7) + x];
    }

    /** Index du bloc occupant la case, ou 0 si la case est vide. */
    public int blockIndex(int x, int y) {
        return Math.abs(rawCell(x, y));
    }

    /** La case est un mur plein : le rayon s'y arrete. */
    public boolean isWall(int x, int y) {
        return rawCell(x, y) < 0;
    }

    /** La case est hors du monde jouable. */
    public boolean isVoid(int x, int y) {
        return rawCell(x, y) == 0;
    }

    /** Bloc occupant la case, ou {@code null} si la case est vide. */
    public Block blockAt(int x, int y) {
        int index = blockIndex(x, y);
        return index == 0 ? null : blocks.get(index);
    }

    /** Nom de la texture designee par un index de bloc ou d'arete, vide si aucune. */
    public String textureName(int index) {
        if (index <= 0 || index > textureNames.size()) {
            return "";
        }
        return textureNames.get(index - 1);
    }

    /** Nom de l'objet designe par l'index porte par un {@link MapObject}. */
    public String objectName(int index) {
        if (index <= 0 || index > objectNames.size()) {
            return "";
        }
        return objectNames.get(index - 1);
    }

    private static void checkBounds(int x, int y) {
        if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE) {
            throw new IndexOutOfBoundsException("Case hors carte : " + x + "," + y);
        }
    }
}
