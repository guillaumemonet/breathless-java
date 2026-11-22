package fov.breathless.engine;

import fov.breathless.gld.gfx.PlanarImage;
import fov.breathless.gld.gfx.ScoreFont;

/**
 * Le panneau de bord, sous la vue.
 *
 * <p>Portage de {@code PanelRefresh} (Sorgenti/Scores.asm) et de {@code ScorePrint}
 * (Sorgenti/Text.asm). L'ecran d'origine fait 320x240 : la vue occupe les deux cents
 * premieres lignes, le panneau les quarante dernieres. Son fond est une image fixe,
 * {@code Graphic/Panel04.raw}, sur laquelle le jeu ne reecrit que les chiffres et les
 * voyants qui ont change.
 *
 * <p>Ici le fond est recopie a chaque image avant d'etre surcharge : c'est plus simple que
 * de suivre les drapeaux de modification de l'original, et douze mille octets ne pesent
 * rien au regard du rendu lui-meme.
 */
public final class Hud {

    /** Hauteur du panneau, {@code PANEL_HEIGHT} dans TMap.i. */
    public static final int HEIGHT = 40;

    /** Abscisses et ordonnees des cinq compteurs, telles quelles dans {@code PanelRefresh}. */
    private static final int SCORE_X = 46;
    private static final int SCORE_Y = 20;
    private static final int SCORE_DIGITS = 7;
    private static final int HEALTH_X = 123;
    private static final int SHIELDS_X = 151;
    private static final int ENERGY_X = 177;
    private static final int GAUGE_Y = 18;
    private static final int CREDITS_X = 250;
    private static final int CREDITS_Y = 20;
    private static final int CREDITS_DIGITS = 5;

    /** Un voyant de cle : un rectangle plein de huit pixels sur six. */
    private static final int[] KEY_LIGHTS = {
        8, 8, 61,       // verte  : x, y, couleur allumee
        24, 8, 195,     // jaune
        8, 23, 62,      // rouge
        24, 23, 44,     // bleue
    };

    /** Couleur d'un voyant de cle eteint. */
    private static final int KEY_OFF = 21;

    /**
     * Les six numeros d'armes, graves a droite du panneau.
     *
     * <p>Pour chacun : abscisse, ordonnee, puis cinq octets de huit pixels de large.
     * Repris de {@code WeaponLightData}.
     */
    private static final int[][] WEAPON_LIGHTS = {
        {288, 7, 16, 16, 16, 16, 16},
        {304, 7, 60, 4, 60, 32, 60},
        {288, 19, 60, 4, 28, 4, 60},
        {304, 19, 36, 36, 60, 4, 4},
        {288, 31, 60, 32, 60, 4, 60},
        {304, 31, 60, 32, 60, 36, 60},
    };

    /** Couleurs d'un numero d'arme : non possedee, possedee, active. */
    private static final int WEAPON_ABSENT = 0;
    private static final int WEAPON_OWNED = 195;
    private static final int WEAPON_ACTIVE = 62;

    private final int width;
    private final byte[] background;
    private final byte[] pixels;

    /**
     * @param panel image de fond, 320x40, telle que la livre {@code Graphic/Panel04.raw}
     */
    public Hud(PlanarImage panel) {
        if (panel.height() != HEIGHT) {
            throw new IllegalArgumentException(
                    "Panneau de " + panel.height() + " lignes la ou " + HEIGHT + " sont attendues");
        }
        this.width = panel.width();
        this.background = panel.pixels();
        this.pixels = new byte[background.length];
    }

    public int width() {
        return width;
    }

    /** Redessine le panneau et renvoie ses pixels, en index de palette. */
    public byte[] render(PlayerState state, Weapons weapons) {
        System.arraycopy(background, 0, pixels, 0, background.length);

        print(ScoreFont.MINI, SCORE_X, SCORE_Y, state.score(), SCORE_DIGITS);
        print(ScoreFont.DIGITAL, HEALTH_X, GAUGE_Y, state.health(), 3);
        print(ScoreFont.DIGITAL, SHIELDS_X, GAUGE_Y, state.shields(), 3);
        print(ScoreFont.DIGITAL, ENERGY_X, GAUGE_Y, state.energy(), 4);
        print(ScoreFont.MINI, CREDITS_X, CREDITS_Y, state.credits(), CREDITS_DIGITS);

        for (int key = 0; key < KEY_LIGHTS.length / 3; key++) {
            boolean held = state.hasKey(PlayerState.PICKUP_GREEN_KEY + key);
            int colour = held ? KEY_LIGHTS[key * 3 + 2] : KEY_OFF;
            fill(KEY_LIGHTS[key * 3], KEY_LIGHTS[key * 3 + 1], 8, 6, colour);
        }

        for (int weapon = 0; weapon < WEAPON_LIGHTS.length; weapon++) {
            int colour = WEAPON_ABSENT;
            if (weapons.owns(weapon)) {
                colour = (weapons.active() == weapon) ? WEAPON_ACTIVE : WEAPON_OWNED;
            }
            stencil(WEAPON_LIGHTS[weapon], colour);
        }
        return pixels;
    }

    /**
     * Imprime un nombre, chiffre le plus significatif a gauche.
     *
     * <p>Les positions inutilisees recoivent un zero : l'original remplit sa chaine avec
     * le code zero, qui designe le glyphe du chiffre zero. Une sante de 79 s'affiche donc
     * « 079 », comme sur un afficheur a segments.
     */
    private void print(ScoreFont font, int x, int y, int value, int digits) {
        int remaining = Math.max(0, value);
        for (int place = digits - 1; place >= 0; place--) {
            blit(font, x + place * font.width(), y, remaining % 10);
            remaining /= 10;
        }
    }

    /**
     * Plans de bits qu'une police reecrit : {@code %11101} pour l'afficheur DIGITAL,
     * {@code %10001} pour la MINI (Text.asm, « Bitplane usati »).
     */
    private static int planesOf(ScoreFont font) {
        return (font == ScoreFont.MINI) ? 0x11 : 0x1D;
    }

    /**
     * Pose un chiffre. Deborder du panneau est sans effet plutot qu'une erreur.
     *
     * <p>{@code ScorePrint} n'ecrit que dans les plans de sa police : les autres gardent le
     * fond du panneau. Sous les compteurs, ce fond porte des plans que le glyphe ne touche
     * pas, et c'est leur combinaison qui fait des chiffres bleus — recopier le glyphe tel
     * quel les rendrait gris.
     */
    private void blit(ScoreFont font, int x, int y, int digit) {
        byte[] glyph = font.glyph(digit);
        int keep = ~planesOf(font) & 0xFF;
        for (int row = 0; row < font.height(); row++) {
            int target = y + row;
            if (target < 0 || target >= HEIGHT) {
                continue;
            }
            for (int column = 0; column < font.width(); column++) {
                int at = x + column;
                if (at >= 0 && at < width) {
                    int index = target * width + at;
                    pixels[index] = (byte) ((background[index] & keep)
                            | (glyph[row * font.width() + column] & 0xFF));
                }
            }
        }
    }

    /** Remplit un rectangle d'une couleur unie : voyant de cle. */
    private void fill(int x, int y, int columns, int rows, int colour) {
        for (int row = 0; row < rows; row++) {
            int target = y + row;
            if (target < 0 || target >= HEIGHT) {
                continue;
            }
            java.util.Arrays.fill(pixels, target * width + x,
                    target * width + Math.min(width, x + columns), (byte) colour);
        }
    }

    /**
     * Colore les pixels d'un petit motif monochrome : numero d'arme.
     *
     * <p>Portage de {@code TurnLight} : le motif dit <em>ou</em> peindre, la couleur dit
     * <em>quoi</em>. Les pixels hors motif gardent le fond du panneau.
     */
    private void stencil(int[] light, int colour) {
        int x = light[0];
        int y = light[1];
        for (int row = 0; row < light.length - 2; row++) {
            int target = y + row;
            if (target < 0 || target >= HEIGHT) {
                continue;
            }
            int bits = light[row + 2];
            for (int bit = 0; bit < 8; bit++) {
                if ((bits & (0x80 >> bit)) != 0 && x + bit < width) {
                    pixels[target * width + x + bit] = (byte) colour;
                }
            }
        }
    }
}
