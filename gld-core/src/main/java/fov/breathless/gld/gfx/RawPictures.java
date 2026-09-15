package fov.breathless.gld.gfx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Localise et lit les images brutes du jeu.
 *
 * <p>Trois images ne vivent pas dans un conteneur {@code .GLD} : elles etaient assemblees
 * dans l'executable par {@code incbin} (Sorgenti/TMapMain.asm) et ne se trouvent donc que
 * dans l'archive des sources, sous {@code Sorgenti/Graphic} — le panneau de bord et la
 * tenture de fond.
 *
 * <p>Une installation qui ne comporte que les fichiers de donnees ne les aura pas : la
 * recherche renvoie alors {@code null} et l'appelant s'en passe plutot que de refuser de
 * demarrer.
 */
public final class RawPictures {

    /** Largeur du panneau, en pixels. */
    public static final int WIDTH = 320;

    /** Hauteur du panneau, {@code PANEL_HEIGHT} dans TMap.i. */
    public static final int HEIGHT = 40;

    /** Nombre de plans de bits : l'ecran du jeu est en 256 couleurs. */
    public static final int PLANES = 8;

    /** Emplacement du panneau dans l'archive d'origine. */
    public static final String RELATIVE_PATH = "Sorgenti/Graphic/Panel04.raw";

    /** Cote de la tenture de fond, qui se repete derriere une fenetre reduite. */
    public static final int BACKDROP_SIZE = 128;

    /** Emplacement de la tenture dans l'archive d'origine. */
    public static final String BACKDROP_PATH = "Sorgenti/Graphic/Background01.raw";

    private RawPictures() {
    }

    /** Cherche la tenture de fond. */
    public static Path locateBackdrop(Path dataDir) {
        return locate(dataDir, BACKDROP_PATH, "Graphic/Background01.raw");
    }

    /** Lit la tenture de fond, 128 sur 128 en huit plans. */
    public static PlanarImage readBackdrop(Path file) throws IOException {
        return PlanarImage.read(Files.readAllBytes(file), BACKDROP_SIZE, BACKDROP_SIZE,
                PLANES, file.getFileName().toString());
    }

    /**
     * Cherche l'image du panneau a partir du repertoire de donnees.
     *
     * <p>Sont essayes, dans l'ordre : le repertoire lui-meme, puis son parent — selon que
     * l'on pointe l'archive complete ou seulement les donnees a cote des sources.
     *
     * @return le chemin trouve, ou {@code null}
     */
    public static Path locate(Path dataDir) {
        return locate(dataDir, RELATIVE_PATH, "Graphic/Panel04.raw");
    }

    private static Path locate(Path dataDir, String relative, String bare) {
        Path[] candidates = {
            dataDir.resolve(relative),
            dataDir.resolve(bare),
            dataDir.getParent() == null ? null : dataDir.getParent().resolve(relative),
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Lit l'image et la deplie en index de palette. */
    public static PlanarImage read(Path file) throws IOException {
        return PlanarImage.read(Files.readAllBytes(file), WIDTH, HEIGHT, PLANES,
                file.getFileName().toString());
    }
}
