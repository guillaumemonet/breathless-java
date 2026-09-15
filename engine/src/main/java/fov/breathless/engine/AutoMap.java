package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;

/**
 * La carte automatique : ce que le joueur a vu, et son trace a l'ecran.
 *
 * <p>Portage de {@code AutoMapping}, {@code TraceRay} et {@code MapMode}
 * (Sorgenti/Map.asm). Deux moitiés bien distinctes :
 *
 * <ul>
 *   <li><strong>relever</strong> — a chaque pas, trois rayons partent du joueur dans la
 *       direction de son regard et marquent les cases traversees, jusqu'a un mur ou une
 *       porte fermee ;</li>
 *   <li><strong>tracer</strong> — une fenetre de 80 sur 50 cases, centree sur le joueur,
 *       a quatre pixels par case, soit exactement les 320 sur 200 de la vue.</li>
 * </ul>
 */
public final class AutoMap {

    /** Cases visibles a l'ecran, en largeur et en hauteur. */
    public static final int VIEW_COLUMNS = 80;
    public static final int VIEW_ROWS = 50;

    /** Cote d'une case a l'ecran, en pixels. */
    public static final int CELL = 4;

    /** Place du joueur dans la fenetre, tant que celle-ci n'est pas contre un bord. */
    private static final int PLAYER_COLUMN = 39;
    private static final int PLAYER_ROW = 24;

    /** Couleur des passages infranchissables. {@code MAPCOLORL1}. */
    private static final int COLOUR_BLOCKED = 2;

    /** Couleur des passages franchissables. {@code MAPCOLORL2}. */
    private static final int COLOUR_OPEN = 16;

    /** Couleur des differences de plafond ou de texture. {@code MAPCOLORL3}. */
    private static final int COLOUR_STEP = 195;

    /** Couleur du sol d'une case relevee. */
    private static final int COLOUR_FLOOR = 243;

    /** Les deux couleurs entre lesquelles clignote le reperé du joueur. */
    private static final int COLOUR_PLAYER = 41;
    private static final int COLOUR_PLAYER_FLASH = 243;

    /** Periode du clignotement, en pas de simulation. */
    private static final int FLASH_PERIOD = 8;

    /**
     * Le repere du joueur, huit orientations de trois lignes sur trois.
     *
     * <p>{@code PlayerMapPics}. Le bit 2 est la colonne de gauche.
     */
    private static final int[][] PLAYER_MARKS = {
        {0b100, 0b111, 0b100},
        {0b110, 0b110, 0b001},
        {0b111, 0b010, 0b010},
        {0b011, 0b011, 0b100},
        {0b001, 0b111, 0b001},
        {0b100, 0b011, 0b011},
        {0b010, 0b010, 0b111},
        {0b001, 0b110, 0b110},
    };

    private final World world;
    private final Level level;
    private final boolean[] seen = new boolean[Level.MAP_SIZE * Level.MAP_SIZE];
    private int flash;

    public AutoMap(World world) {
        this.world = world;
        this.level = world.level();
    }

    /** Oublie tout le releve. {@code InitAutomap}. */
    public void clear() {
        java.util.Arrays.fill(seen, false);
    }

    /** La case a-t-elle deja ete relevee ? */
    public boolean isSeen(int x, int z) {
        return inside(x, z) && seen[z * Level.MAP_SIZE + x];
    }

    /** Nombre de cases relevees, pour les tests et les mesures. */
    public int seenCount() {
        int count = 0;
        for (boolean cell : seen) {
            if (cell) {
                count++;
            }
        }
        return count;
    }

    /**
     * Releve ce que le joueur voit depuis sa position.
     *
     * <p>{@code AutoMapping} : trois rayons cote a cote, decales d'une case de part et
     * d'autre, perpendiculairement a l'axe dominant du regard.
     */
    public void survey(Camera camera) {
        flash++;
        for (int offset = -1; offset <= 1; offset++) {
            traceRay(camera, offset);
        }
    }

    /**
     * Un rayon de releve, en Bresenham sur la grille des cases.
     *
     * <p>{@code TraceRay}. Il s'arrete sur un mur, hors de la carte, ou en entrant dans un
     * bloc dont le plafond touche le sol — une porte fermee.
     *
     * @param offset decalage lateral du depart, en cases
     */
    private void traceRay(Camera camera, int offset) {
        int ax = Trig.cos(camera.heading()) >> 8;
        int az = Trig.sin(camera.heading()) >> 8;
        int stepX = (ax >= 0) ? 1 : -1;
        int stepZ = (az >= 0) ? 1 : -1;
        ax = Math.abs(ax);
        az = Math.abs(az);

        int x = camera.cellX();
        int z = camera.cellZ();
        boolean xDominant = az <= ax;
        if (xDominant) {
            z += offset;
        } else {
            x += offset;
        }

        int error = (xDominant ? ax : az) >> 1;
        int previous = 0;

        // La carte est bordee de murs : la boucle s'arrete d'elle-meme. La borne ne sert
        // que de garde-fou, le regard exactement parallele a un axe donnant ax ou az nul.
        for (int step = 0; step < Level.MAP_SIZE * 2; step++) {
            if (!inside(x, z)) {
                return;
            }
            seen[z * Level.MAP_SIZE + x] = true;

            int code = level.rawCell(x, z);
            if (code <= 0) {
                return;                     // mur plein ou vide
            }
            if (code != previous) {
                previous = code;
                Block block = level.blocks().get(code);
                if (block.ceilHeight() == block.floorHeight()) {
                    return;                 // porte fermee
                }
            }

            if (xDominant) {
                error -= az;
                if (error <= 0) {
                    error += ax;
                    z += stepZ;
                }
                x += stepX;
            } else {
                error -= ax;
                if (error <= 0) {
                    error += az;
                    x += stepX;
                }
                z += stepZ;
            }
        }
    }

    /**
     * Complete le releve : une case entouree a gauche et a droite, ou en haut et en bas,
     * est tenue pour vue.
     *
     * <p>Passe de correction de {@code MapMode}, qui bouche les trous que les trois rayons
     * laissent dans les grandes salles. Elle s'execute a chaque ouverture de la carte et
     * son effet est definitif.
     */
    public void fillGaps() {
        boolean[] before = seen.clone();
        for (int z = 0; z < Level.MAP_SIZE; z++) {
            for (int x = 0; x < Level.MAP_SIZE; x++) {
                if (wasSeen(before, x, z)) {
                    continue;
                }
                boolean horizontal = wasSeen(before, x - 1, z) && wasSeen(before, x + 1, z);
                boolean vertical = wasSeen(before, x, z - 1) && wasSeen(before, x, z + 1);
                if (horizontal || vertical) {
                    seen[z * Level.MAP_SIZE + x] = true;
                }
            }
        }
    }

    private static boolean wasSeen(boolean[] snapshot, int x, int z) {
        // L'original ecarte aussi la derniere ligne et la derniere colonne.
        if (x < 0 || z < 0 || x >= Level.MAP_SIZE - 1 || z >= Level.MAP_SIZE - 1) {
            return false;
        }
        return snapshot[z * Level.MAP_SIZE + x];
    }

    private static boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < Level.MAP_SIZE && z < Level.MAP_SIZE;
    }

    /**
     * Trace la carte dans un tampon de 320 sur 200.
     *
     * <p>{@code MapMode}. Chaque case relevee recoit son sol, puis les traits de ses
     * cotes haut et gauche, dont la couleur dit ce que vaut le passage : infranchissable,
     * franchissable, ou marche et changement de plafond.
     */
    public void render(byte[] target, int width, int height, Camera camera) {
        java.util.Arrays.fill(target, (byte) 0);
        fillGaps();

        int startX = clampWindow(camera.cellX(), PLAYER_COLUMN, VIEW_COLUMNS);
        int startZ = clampWindow(camera.cellZ(), PLAYER_ROW, VIEW_ROWS);
        int playerColumn = camera.cellX() - startX;
        int playerRow = camera.cellZ() - startZ;

        int upColour = 0;
        for (int row = 0; row < VIEW_ROWS; row++) {
            upColour = 0;
            for (int column = 0; column < VIEW_COLUMNS; column++) {
                int previousUp = upColour;
                upColour = 0;
                int leftColour = 0;

                int x = startX + column;
                int z = startZ + row;
                if (isSeen(x, z)) {
                    int code = level.rawCell(x, z);
                    if (code > 0) {
                        fillCell(target, width, height, column, row, COLOUR_FLOOR);
                    }
                    upColour = edgeColour(code, (z == 0) ? 0 : level.rawCell(x, z - 1));
                    if (upColour != 0) {
                        drawUpLine(target, width, height, column, row, upColour);
                    }
                    leftColour = edgeColour(code, (x == 0) ? 0 : level.rawCell(x - 1, z));
                    if (leftColour != 0) {
                        drawLeftLine(target, width, height, column, row, leftColour);
                    }
                }

                // Le point d'angle ferme les jonctions que les deux traits laissent ouvertes.
                int corner = cornerColour(leftColour, upColour, previousUp);
                if (corner != 0) {
                    plot(target, width, height, column * CELL, row * CELL, corner);
                }
            }
        }

        drawPlayer(target, width, height, playerColumn, playerRow, camera.heading());
    }

    /** Premiere case affichee sur un axe, la fenetre restant dans la carte. */
    private static int clampWindow(int playerCell, int centre, int span) {
        int start = playerCell - centre;
        return Math.max(0, Math.min(Level.MAP_SIZE - span, start));
    }

    /**
     * Couleur du trait entre deux cases, ou zero s'il n'y a rien a tracer.
     *
     * <p>Meme suite de tests que {@code MMdupl1} : d'abord le franchissement, puis la
     * hauteur de plafond, puis les textures de sol et de plafond. Deux cases identiques a
     * tout point de vue ne montrent aucune separation.
     */
    private int edgeColour(int code, int neighbourCode) {
        if (code == neighbourCode) {
            return 0;
        }
        if (code <= 0) {
            return (neighbourCode <= 0) ? 0 : COLOUR_BLOCKED;
        }
        if (neighbourCode <= 0) {
            return COLOUR_BLOCKED;
        }

        Block here = level.blocks().get(code);
        Block there = level.blocks().get(neighbourCode);

        int rise = Math.abs(there.floorHeight() - here.floorHeight());
        if (rise != 0) {
            return (rise <= Player.MAX_RISE) ? COLOUR_OPEN : COLOUR_BLOCKED;
        }
        if (there.ceilHeight() != here.ceilHeight()) {
            return COLOUR_STEP;
        }
        if (there.floorTexture() != here.floorTexture()) {
            return COLOUR_OPEN;
        }
        return (there.ceilTexture() != here.ceilTexture()) ? COLOUR_STEP : 0;
    }

    /** Couleur du point d'angle en haut a gauche de la case, ou zero. */
    private static int cornerColour(int leftColour, int upColour, int previousUpColour) {
        if (leftColour != 0) {
            if (upColour == 0 || upColour == leftColour || upColour != COLOUR_BLOCKED) {
                return 0;
            }
            return COLOUR_BLOCKED;
        }
        return (upColour == 0) ? previousUpColour : 0;
    }

    private static void fillCell(byte[] target, int width, int height,
                                 int column, int row, int colour) {
        for (int line = 0; line < CELL; line++) {
            for (int pixel = 0; pixel < CELL; pixel++) {
                plot(target, width, height, column * CELL + pixel, row * CELL + line, colour);
            }
        }
    }

    /** Le trait du haut : toute la largeur de la case, sur sa premiere ligne. */
    private static void drawUpLine(byte[] target, int width, int height,
                                   int column, int row, int colour) {
        for (int pixel = 0; pixel < CELL; pixel++) {
            plot(target, width, height, column * CELL + pixel, row * CELL, colour);
        }
    }

    /** Le trait de gauche : toute la hauteur de la case, sur sa premiere colonne. */
    private static void drawLeftLine(byte[] target, int width, int height,
                                     int column, int row, int colour) {
        for (int line = 0; line < CELL; line++) {
            plot(target, width, height, column * CELL, row * CELL + line, colour);
        }
    }

    /** Le repere du joueur, trois lignes sur trois, au centre de sa case. */
    private void drawPlayer(byte[] target, int width, int height,
                            int column, int row, int heading) {
        int direction = ((heading + 128) & Trig.MASK) >> 8;
        int[] mark = PLAYER_MARKS[direction & 7];
        int colour = ((flash & FLASH_PERIOD) == 0) ? COLOUR_PLAYER : COLOUR_PLAYER_FLASH;

        for (int line = 0; line < mark.length; line++) {
            for (int pixel = 0; pixel < 3; pixel++) {
                if ((mark[line] & (0b100 >> pixel)) != 0) {
                    plot(target, width, height,
                            column * CELL + 1 + pixel, row * CELL + 1 + line, colour);
                }
            }
        }
    }

    private static void plot(byte[] target, int width, int height, int x, int y, int colour) {
        if (x >= 0 && y >= 0 && x < width && y < height) {
            target[y * width + x] = (byte) colour;
        }
    }
}
