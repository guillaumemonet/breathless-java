package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.PlayerStart;

/**
 * Position et orientation de l'observateur.
 *
 * <p>Les coordonnees horizontales sont en 16.16 comme dans le moteur d'origine ;
 * la hauteur de l'oeil est un entier en unites monde.
 */
public final class Camera {

    /** Hauteur des yeux au-dessus du sol, {@code PLAYER_EYES_HEIGHT} de TMap.i. */
    public static final int EYES_HEIGHT = 54;

    private int x;
    private int z;
    private int y;
    private int heading;
    private int lookHeight;

    public Camera(int x, int z, int y, int heading) {
        this.x = x;
        this.z = z;
        this.y = y;
        this.heading = heading;
    }

    /** Place la camera au point de depart du niveau, a hauteur d'oeil. */
    public static Camera atPlayerStart(Level level) {
        PlayerStart start = level.playerStart();
        int cellX = Math.floorDiv(start.x(), Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(start.z(), Level.BLOCK_SIZE);
        Block block = level.blockAt(cellX, cellZ);
        int floor = (block == null) ? 0 : block.floorHeight();
        // InitPlayerPos : move.w #$3795,CPlayerX+2 — le joueur ne part pas pile sur une
        // valeur entiere, ce qui evite de commencer exactement sur une ligne de la grille.
        return new Camera(Fixed.of(start.x()) | 0x3795, Fixed.of(start.z()) | 0x3795,
                floor + EYES_HEIGHT, start.heading());
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int y() {
        return y;
    }

    public int heading() {
        return heading;
    }

    /** Decalage vertical du regard, en lignes ecran. Equivalent de {@code LookHeight}. */
    public int lookHeight() {
        return lookHeight;
    }

    public void setPosition(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setHeading(int heading) {
        this.heading = heading & Trig.MASK;
    }

    public void setLookHeight(int lookHeight) {
        this.lookHeight = lookHeight;
    }

    /**
     * Inclinaison du regard en crans, de -72 a +72. {@code LookHeightNum} : c'est elle,
     * et non le decalage en lignes, qui donne leur inclinaison aux tirs du joueur.
     */
    private int lookStep;

    public int lookStep() {
        return lookStep;
    }

    public void setLookStep(int lookStep) {
        this.lookStep = lookStep;
    }

    /** Composante en x de la direction du regard, en 16.16 : {@code cos(cap)}. */
    public int viewDirX() {
        return Trig.cos(heading);
    }

    /** Composante en z de la direction du regard, en 16.16 : {@code sin(cap)}. */
    public int viewDirZ() {
        return Trig.sin(heading);
    }

    /** Colonne de la carte occupee par la camera. */
    public int cellX() {
        return (x >> 16) / Level.BLOCK_SIZE;
    }

    /** Ligne de la carte occupee par la camera. */
    public int cellZ() {
        return (z >> 16) / Level.BLOCK_SIZE;
    }
}
