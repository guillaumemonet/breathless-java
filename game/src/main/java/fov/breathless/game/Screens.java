package fov.breathless.game;

import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.Picture;

import java.util.HashMap;
import java.util.Map;

/**
 * Les ecrans fixes du jeu : titre, presentation des mondes, fin de partie.
 *
 * <p>Portage de {@code ShowPic} (Sorgenti/Presentation.asm). Chaque image porte sa propre
 * palette, qui remplace celle du jeu le temps de l'afficher, et sa propre position : le
 * titre couvre l'ecran, la banniere de fin de partie ne fait que 288 sur 40 et se pose au
 * milieu.
 *
 * <p>Les images sont lues une fois puis gardees : elles sont peu nombreuses et toutes
 * comprimees, autant ne pas les redecompresser a chaque affichage.
 */
final class Screens {

    /** Ecran de titre. */
    static final String TITLE = "BTIT";

    /** Banniere de fin de partie. */
    static final String GAME_OVER = "GAOV";

    /** Les deux logos d'ouverture, dans l'ordre. */
    static final String LOGO_1 = "LOG1";
    static final String LOGO_2 = "LOG2";

    /** Ecran des credits. */
    static final String CREDITS = "CRED";

    /** Ecran de fin, quand le jeu est acheve. {@code EndGamePicName}. */
    static final String END = "LAST";

    /** Duree d'affichage des logos, en images, telle que l'original les temporise. */
    static final int LOGO_1_TICKS = 250;
    static final int LOGO_2_TICKS = 400;

    /** Les quatre presentations de monde, dans l'ordre. */
    private static final String[] WORLDS = {"WLD1", "WLD2", "WLD3", "WLD4"};

    private final GfxBank gfx;
    private final Map<String, Picture> cache = new HashMap<>();

    Screens(GfxBank gfx) {
        this.gfx = gfx;
    }

    /** Nom de l'image presentant un monde, de 1 a 4. */
    static String world(int number) {
        return WORLDS[Math.max(0, Math.min(WORLDS.length - 1, number - 1))];
    }

    /**
     * Une image par son nom, ou {@code null} si elle manque ou refuse de se lire.
     *
     * <p>Une image illisible ne doit pas empecher de jouer : on le signale et on passe.
     */
    Picture get(String name) {
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        Picture picture = null;
        try {
            if (gfx.pictures().containsKey(name)) {
                picture = Picture.read(gfx.pictureData(name), name);
            }
        } catch (RuntimeException failure) {
            System.out.println("Image " + name + " illisible : " + failure.getMessage());
        }
        cache.put(name, picture);
        return picture;
    }

    /**
     * Pose une image sur un ecran, a la place qu'elle indique.
     *
     * <p>Le fond est efface : l'original appelle {@code ClearScreen} avant de montrer une
     * image.
     */
    static void draw(Picture picture, byte[] target, int width, int height) {
        java.util.Arrays.fill(target, (byte) 0);
        for (int y = 0; y < picture.height(); y++) {
            int line = picture.y() + y;
            if (line < 0 || line >= height) {
                continue;
            }
            int source = y * picture.width();
            int destination = line * width + picture.x();
            for (int x = 0; x < picture.width(); x++) {
                int at = picture.x() + x;
                if (at >= 0 && at < width) {
                    target[destination + x] = picture.pixels()[source + x];
                }
            }
        }
    }
}
