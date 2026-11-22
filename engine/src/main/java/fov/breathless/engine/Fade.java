package fov.breathless.engine;

/**
 * Les fondus de l'image : entree et sortie de niveau, teleportation.
 *
 * <p>Portage de {@code Fade} (Sorgenti/TMapMain.asm). L'image entiere passe par la table
 * d'eclairage, a un niveau qui avance de 256 — un niveau entier — par cinquantieme :
 *
 * <ul>
 *   <li>entree de niveau, {@code $1F000100} : du noir complet vers l'image, en
 *       trente et un pas ;</li>
 *   <li>teleportation, {@code $04000001} : vers le brouillard puis retour, et le jeu reste
 *       gele tout du long ;</li>
 *   <li>sortie de niveau, {@code $04000002} : vers le noir, dans un seul sens.</li>
 * </ul>
 */
public final class Fade {

    private static final int FULL = 8192;
    private static final int STEP = 256;

    /** {@code TransEffect} : le niveau courant, en 8.8 de niveaux d'eclairage. */
    private int value;
    private boolean down;
    private boolean fog;
    private boolean oneWay;
    private boolean freezes;

    /** Fondu d'entree de niveau : du noir vers l'image. */
    public void levelStart() {
        start(0x1F00, true, false, false, false);
    }

    /** Fondu de teleportation : brouillard aller-retour, jeu gele. */
    public void teleport() {
        start(0x0400, false, true, false, true);
    }

    /** Fondu de fin de niveau : vers le noir, sans retour, jeu gele. */
    public void levelEnd() {
        start(0x0400, false, false, true, true);
    }

    private void start(int from, boolean goingDown, boolean toFog, boolean single, boolean freeze) {
        value = from;
        down = goingDown;
        fog = toFog;
        oneWay = single;
        freezes = freeze;
    }

    /** Un fondu est-il en cours ? */
    public boolean isActive() {
        return value != 0;
    }

    /** Le jeu est-il gele par ce fondu ({@code ProgramState} a -1) ? */
    public boolean freezesGame() {
        return isActive() && freezes;
    }

    /** Le fondu de sortie a-t-il atteint le noir ? */
    public boolean reachedFull() {
        return oneWay && value >= FULL - STEP;
    }

    /** Le fondu va-t-il vers le brouillard plutot que vers le noir ? */
    public boolean isFog() {
        return fog;
    }

    /** Niveau d'eclairage a appliquer a toute l'image, de 0 a 31. */
    public int level() {
        return Math.min(31, value >> 8);
    }

    /** Avance d'un cinquantieme. */
    public void advance(int ticks) {
        if (value == 0) {
            return;
        }
        int delta = Math.min(32, ticks) * STEP;
        if (down) {
            value -= delta;
            if (value <= 0) {
                value = 0;
                down = false;
                freezes = false;
            }
            return;
        }
        value += delta;
        if (value >= FULL) {
            value = FULL - STEP;
            if (!oneWay) {
                down = true;
            }
        }
    }

    /**
     * Passe une image par la table d'eclairage, au niveau du fondu.
     *
     * @param pixels index de palette, modifies sur place
     */
    public void apply(byte[] pixels, int count, fov.breathless.gld.gfx.LightingTable black,
                      fov.breathless.gld.gfx.LightingTable fogTable) {
        if (value == 0) {
            return;
        }
        fov.breathless.gld.gfx.LightingTable table = fog ? fogTable : black;
        int level = level();
        for (int i = 0; i < count; i++) {
            pixels[i] = (byte) table.shade(level, pixels[i] & 0xFF);
        }
    }
}
