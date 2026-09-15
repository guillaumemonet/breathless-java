package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.RawPictures;
import fov.breathless.gld.gfx.PlanarImage;
import fov.breathless.gld.object.ObjectBank;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le panneau de bord dessine sur l'image d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class HudIT {

    private static Path dataDir() {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");
        return dir;
    }

    private static PlanarImage panel() throws IOException {
        Path dir = dataDir();
        Path raw = RawPictures.locate(dir);
        Assumptions.assumeTrue(raw != null,
                "Image du panneau absente (" + RawPictures.RELATIVE_PATH + ") : test ignore");
        return RawPictures.read(raw);
    }

    private static ObjectBank objects() throws IOException {
        Path dir = dataDir();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                if (ObjectBank.OGLD.equals(gld.payloadId())) {
                    return ObjectBank.read(gld);
                }
            }
        }
        throw new AssertionError("aucune banque d'objets dans " + dir);
    }

    @Test
    @DisplayName("Le panneau fait 320 sur 40 et n'est pas uni")
    void thePanelIsTheOriginalPicture() throws IOException {
        PlanarImage panel = panel();

        assertEquals(RawPictures.WIDTH, panel.width());
        assertEquals(Hud.HEIGHT, panel.height());

        boolean[] seen = new boolean[256];
        int colours = 0;
        for (byte pixel : panel.pixels()) {
            int index = pixel & 0xFF;
            if (!seen[index]) {
                seen[index] = true;
                colours++;
            }
        }
        assertTrue(colours > 32, "panneau a " + colours + " couleurs : depliage suspect");
    }

    @Test
    @DisplayName("Un compteur qui change ne repeint que sa propre zone")
    void eachGaugeStaysInItsOwnPlace() throws IOException {
        Hud hud = new Hud(panel());
        Weapons weapons = new Weapons(objects());

        PlayerState intact = new PlayerState();
        byte[] before = hud.render(intact, weapons).clone();

        PlayerState hurt = new PlayerState();
        hurt.hit(40);
        byte[] after = hud.render(hurt, weapons).clone();

        assertNotEquals(0, differences(before, after), "la sante n'apparait nulle part");

        // Les colonnes touchees doivent toutes tomber dans la zone sante-boucliers.
        int minColumn = RawPictures.WIDTH;
        int maxColumn = -1;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                int column = i % RawPictures.WIDTH;
                minColumn = Math.min(minColumn, column);
                maxColumn = Math.max(maxColumn, column);
            }
        }
        assertTrue(minColumn >= 120 && maxColumn < 177,
                "un coup encaisse repeint les colonnes " + minColumn + " a " + maxColumn
                        + ", hors de la zone sante et boucliers");
    }

    @Test
    @DisplayName("Une cle ramassee allume son voyant, et lui seul")
    void aKeyLightsUp() throws IOException {
        Hud hud = new Hud(panel());
        Weapons weapons = new Weapons(objects());

        PlayerState without = new PlayerState();
        byte[] dark = hud.render(without, weapons).clone();

        PlayerState with = new PlayerState();
        with.collect(PlayerState.PICKUP_GREEN_KEY, 1);
        byte[] lit = hud.render(with, weapons).clone();

        // Le voyant vert est un rectangle de 8 sur 6 en 8,8 : quarante-huit pixels.
        assertEquals(48, differences(dark, lit),
                "le voyant de la cle verte ne couvre pas exactement son rectangle");
    }

    @Test
    @DisplayName("L'arme en main se distingue d'une arme simplement possedee")
    void theActiveWeaponStandsOut() throws IOException {
        Hud hud = new Hud(panel());

        Weapons owned = new Weapons(objects());
        owned.give(1);
        byte[] resting = hud.render(new PlayerState(), owned).clone();

        Weapons active = new Weapons(objects());
        active.give(1);
        assertTrue(active.select(1), "la deuxieme arme n'a pas pu etre prise en main");
        byte[] wielded = hud.render(new PlayerState(), active).clone();

        assertNotEquals(0, differences(resting, wielded),
                "l'arme en main s'affiche comme une arme au repos");
    }

    private static int differences(byte[] a, byte[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                count++;
            }
        }
        return count;
    }
}
