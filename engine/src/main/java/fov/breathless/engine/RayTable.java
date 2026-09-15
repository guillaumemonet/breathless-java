package fov.breathless.engine;

/**
 * Resultat du lancer de rayons, equivalent de la {@code vtable} du moteur d'origine.
 *
 * <p>Pour chaque colonne de l'ecran, la liste ordonnee des changements de bloc
 * rencontres le long du rayon, du plus proche au plus lointain, terminee par le mur
 * plein qui arrete le rayon. Ce sont ces transitions qui produisent les pans de mur :
 * un changement de hauteur de plafond donne une texture haute, un changement de
 * hauteur de sol une texture basse, un mur plein une texture principale.
 */
public final class RayTable {

    /** Nombre maximal de blocs distincts visibles sur un rayon ({@code MAX_BLOCK_VIEW}). */
    public static final int MAX_HITS = 32;

    private final int columns;
    private final int[] count;
    private final int[] distance;
    private final int[] block;
    private final int[] coord;
    private final int[] edge;
    private final boolean[] wall;
    private final int[] clipMin;
    private final int[] clipMax;
    private final int[] visible;
    private final boolean[] reached = new boolean[fov.breathless.gld.level.Level.MAP_SIZE
            * fov.breathless.gld.level.Level.MAP_SIZE];
    private final int[] dirX;
    private final int[] dirZ;

    public RayTable(int columns) {
        this.columns = columns;
        this.count = new int[columns];
        this.distance = new int[columns * MAX_HITS];
        this.block = new int[columns * MAX_HITS];
        this.coord = new int[columns * MAX_HITS];
        this.edge = new int[columns * MAX_HITS];
        this.wall = new boolean[columns * MAX_HITS];
        this.clipMin = new int[columns * MAX_HITS];
        this.clipMax = new int[columns * MAX_HITS];
        this.visible = new int[columns];
        // Une entree de plus que de colonnes : le rendu des sols a besoin de la
        // direction de la colonne suivante pour calculer son pas horizontal.
        this.dirX = new int[columns + 1];
        this.dirZ = new int[columns + 1];
    }

    void setDirection(int column, int x, int z) {
        dirX[column] = x;
        dirZ[column] = z;
    }

    /** Composante en x de la direction du rayon d'une colonne, en 16.16. */
    public int dirX(int column) {
        return dirX[column];
    }

    /** Composante en z de la direction du rayon d'une colonne, en 16.16. */
    public int dirZ(int column) {
        return dirZ[column];
    }

    public int columns() {
        return columns;
    }

    /** Nombre de transitions enregistrees pour une colonne. */
    public int count(int column) {
        return count[column];
    }

    void reset(int column) {
        count[column] = 0;
    }

    /**
     * Enregistre une transition.
     *
     * @return {@code false} si la colonne est pleine et que le rayon doit s'arreter
     */
    boolean add(int column, int rayDistance, int blockIndex, int brushCoord, int edgeIndex, boolean isWall) {
        int n = count[column];
        if (n >= MAX_HITS) {
            return false;
        }
        int at = column * MAX_HITS + n;
        distance[at] = rayDistance;
        block[at] = blockIndex;
        coord[at] = brushCoord;
        edge[at] = edgeIndex;
        wall[at] = isWall;
        count[column] = n + 1;
        return true;
    }

    /**
     * Memorise les bornes de decoupe en vigueur avant de traiter cette transition.
     * Le rendu des sprites s'en sert pour savoir jusqu'ou un objet situe a cette
     * profondeur reste visible.
     */
    void setClip(int column, int hit, int min, int max) {
        int at = column * MAX_HITS + hit;
        clipMin[at] = min;
        clipMax[at] = max;
    }

    /** Oublie les cases atteintes a l'image precedente. */
    void forgetReachedCells() {
        java.util.Arrays.fill(reached, false);
    }

    /** Note qu'un rayon a traverse cette case. */
    void reach(int cellX, int cellZ) {
        if (cellX >= 0 && cellX < fov.breathless.gld.level.Level.MAP_SIZE
                && cellZ >= 0 && cellZ < fov.breathless.gld.level.Level.MAP_SIZE) {
            reached[(cellZ << 7) + cellX] = true;
        }
    }

    /**
     * Un rayon a-t-il traverse cette case ?
     *
     * <p>C'est le seul critere de visibilite fiable pour un objet. La profondeur ne suffit
     * pas : un ennemi cache derriere un angle peut se trouver <em>devant</em> le mur que
     * voit sa colonne, et passerait donc une simple comparaison de distances. Le moteur
     * d'origine ne dessine pour cette raison que les objets rencontres par les rayons.
     */
    public boolean reached(int cellX, int cellZ) {
        if (cellX < 0 || cellX >= fov.breathless.gld.level.Level.MAP_SIZE
                || cellZ < 0 || cellZ >= fov.breathless.gld.level.Level.MAP_SIZE) {
            return false;
        }
        return reached[(cellZ << 7) + cellX];
    }

    /**
     * Nombre de transitions que la passe des murs a reellement traitees.
     *
     * <p>Une colonne s'arrete des qu'un mur plein la ferme, mais aussi des que ses deux
     * bornes de decoupe se rejoignent — ce qui arrive devant une porte fermee, dont le
     * pan occupe toute la hauteur sans pour autant etre un mur de la carte. Les
     * transitions au-dela ne portent donc aucune borne valable : <strong>rien de ce qui
     * s'y trouve ne doit etre dessine</strong>.
     */
    void setVisibleCount(int column, int count) {
        visible[column] = count;
    }

    /** Nombre de transitions exploitables pour decouper un objet. */
    public int visibleCount(int column) {
        return visible[column];
    }

    /** Borne haute de decoupe accumulee par tout ce qui est plus proche. */
    public int clipMin(int column, int hit) {
        return clipMin[column * MAX_HITS + hit];
    }

    /** Borne basse de decoupe accumulee par tout ce qui est plus proche. */
    public int clipMax(int column, int hit) {
        return clipMax[column * MAX_HITS + hit];
    }

    /** Parametre le long du rayon, en 16.16. Sert de distance pour la projection. */
    public int distance(int column, int hit) {
        return distance[column * MAX_HITS + hit];
    }

    /** Index du bloc atteint. */
    public int block(int column, int hit) {
        return block[column * MAX_HITS + hit];
    }

    /**
     * Coordonnee monde 16.16 du point d'impact le long de la face traversee.
     * Sa partie entiere modulo 64 donne la colonne de brosse a echantillonner.
     */
    public int coord(int column, int hit) {
        return coord[column * MAX_HITS + hit];
    }

    /** Colonne de brosse, de 0 a 63. */
    public int brushColumn(int column, int hit) {
        return (coord[column * MAX_HITS + hit] >> 16) & 63;
    }

    /** Index de l'arete traversee, de 1 (est) a 4 (nord). */
    public int edge(int column, int hit) {
        return edge[column * MAX_HITS + hit];
    }

    /** Cette transition est le mur plein qui arrete le rayon. */
    public boolean isWall(int column, int hit) {
        return wall[column * MAX_HITS + hit];
    }
}
