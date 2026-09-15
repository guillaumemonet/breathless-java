package fov.breathless.gld.gfx;

import fov.breathless.gld.GldContainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les images plein ecran de la banque graphique.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class PictureIT {

    private static GfxBank bank() throws IOException {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                if (GfxBank.GGLD.equals(gld.payloadId())) {
                    return GfxBank.read(gld);
                }
            }
        }
        throw new AssertionError("aucune banque graphique dans " + dir);
    }

    @Test
    @DisplayName("Les dix images du jeu se decompressent et tiennent dans l'ecran")
    void everyPictureDecodes() throws IOException {
        GfxBank gfx = bank();
        assertEquals(10, gfx.pictures().size(), "le fichier d'origine en compte dix");

        for (String name : gfx.pictures().keySet()) {
            Picture picture = Picture.read(gfx.pictureData(name), name);

            assertTrue(picture.isCompressed(), name + " : toutes sont comprimees en VDCO");
            assertTrue(picture.width() > 0 && picture.width() <= 320,
                    name + " : largeur " + picture.width());
            assertTrue(picture.height() > 0 && picture.height() <= 240,
                    name + " : hauteur " + picture.height());
            assertTrue(picture.x() + picture.width() <= 320,
                    name + " : deborde a droite");
            assertTrue(picture.y() + picture.height() <= 240,
                    name + " : deborde en bas");
            assertEquals(picture.width() * picture.height(), picture.pixels().length,
                    name + " : nombre de pixels inattendu");
        }
    }

    @Test
    @DisplayName("Chaque image porte sa propre palette, et du dessin")
    void picturesCarryTheirOwnPalette() throws IOException {
        GfxBank gfx = bank();

        for (String name : gfx.pictures().keySet()) {
            Picture picture = Picture.read(gfx.pictureData(name), name);
            assertNotNull(picture.palette());

            boolean[] seen = new boolean[256];
            int colours = 0;
            for (byte pixel : picture.pixels()) {
                int index = pixel & 0xFF;
                if (!seen[index]) {
                    seen[index] = true;
                    colours++;
                }
            }
            // Les ecrans de texte se contentent de quelques couleurs — les credits n'en
            // emploient que quatre. Les illustrations, elles, en emploient forcement
            // beaucoup : une decompression fautive s'y verrait tout de suite.
            boolean artwork = !name.equals("CRED") && !name.equals("GAOV");
            int minimum = artwork ? 64 : 3;
            assertTrue(colours >= minimum,
                    name + " : " + colours + " couleurs pour " + minimum + " attendues,"
                            + " la decompression semble fautive");

            // Une image toute noire signalerait une palette mal placee.
            int lit = 0;
            for (int index = 0; index < 256; index++) {
                if (seen[index] && (picture.palette().red(index) > 32
                        || picture.palette().green(index) > 32
                        || picture.palette().blue(index) > 32)) {
                    lit++;
                }
            }
            assertTrue(lit >= 2, name + " : seulement " + lit + " couleurs claires employees");
        }
    }

    @Test
    @DisplayName("Les quatre mondes et l'ecran de titre sont bien la")
    void theExpectedPicturesArePresent() throws IOException {
        GfxBank gfx = bank();

        for (String name : new String[]{"BTIT", "GAOV", "WLD1", "WLD2", "WLD3", "WLD4",
                                        "LOG1", "LOG2", "CRED", "LAST"}) {
            assertTrue(gfx.pictures().containsKey(name), name + " manque");
        }

        Picture title = Picture.read(gfx.pictureData("BTIT"), "BTIT");
        assertEquals(320, title.width());
        assertEquals(240, title.height());
        assertEquals(0, title.x());
        assertEquals(0, title.y());

        // La banniere de fin de partie ne couvre qu'une bande, au milieu.
        Picture over = Picture.read(gfx.pictureData("GAOV"), "GAOV");
        assertTrue(over.height() < 80, "la banniere devrait etre une bande");
        assertTrue(over.y() > 0, "elle se pose plus bas que le haut de l'ecran");
    }
}
