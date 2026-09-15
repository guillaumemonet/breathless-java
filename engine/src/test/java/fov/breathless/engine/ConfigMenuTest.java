package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le menu de configuration et les reglages qu'il porte. */
class ConfigMenuTest {

    private static ConfigMenu menu(Settings settings) {
        ConfigMenu menu = new ConfigMenu(settings);
        menu.open();
        return menu;
    }

    /** Descend jusqu'a la ligne voulue, puis valide. */
    private static void choose(ConfigMenu menu, int line) {
        while (menu.selection() > line) {
            menu.up();
        }
        while (menu.selection() < line) {
            menu.down();
        }
        menu.select();
    }

    @Test
    @DisplayName("Le menu s'ouvre sur sa page d'accueil et s'en referme")
    void itOpensAndCloses() {
        ConfigMenu menu = menu(new Settings());

        assertTrue(menu.isOpen());
        assertEquals(ConfigMenu.Page.MAIN, menu.page());
        assertEquals(0, menu.selection());
        assertFalse(menu.isQuitting());

        choose(menu, 0);                                 // RETURN TO GAME
        assertFalse(menu.isOpen());
        assertFalse(menu.isQuitting(), "revenir au jeu n'est pas quitter");
    }

    @Test
    @DisplayName("La page fenetre change vraiment la taille et les pixels")
    void theWindowPageDrivesTheViewSize() {
        Settings settings = new Settings();
        ConfigMenu menu = menu(settings);

        choose(menu, 1);                                 // WINDOW
        assertEquals(ConfigMenu.Page.WINDOW, menu.page());

        int before = settings.viewSize().width();
        choose(menu, 0);                                 // WINDOW_SIZE, un cran
        assertTrue(settings.viewSize().width() != before, "la taille n'a pas change");

        // Le choix boucle : autant de crans que de tailles, et on revient au depart.
        for (int step = 1; step < ViewSize.count(); step++) {
            menu.select();
        }
        assertEquals(before, settings.viewSize().width(), "le choix ne boucle pas");

        choose(menu, 1);                                 // PIXEL_SIZE
        assertEquals(ViewSize.PIXEL_2X1, settings.viewSize().pixelType());
        menu.select();
        assertEquals(ViewSize.PIXEL_1X2, settings.viewSize().pixelType());
    }

    @Test
    @DisplayName("Le viseur s'eteint et se rallume")
    void theSightCanBeTurnedOff() {
        Settings settings = new Settings();
        ConfigMenu menu = menu(settings);

        assertTrue(settings.sight(), "le viseur est visible au depart");
        choose(menu, 1);                                 // WINDOW
        choose(menu, 2);                                 // SIGHT
        assertFalse(settings.sight());
        menu.select();
        assertTrue(settings.sight());
    }

    @Test
    @DisplayName("La page son regle le volume et coupe la musique")
    void theSoundPageDrivesTheAudio() {
        Settings settings = new Settings();
        ConfigMenu menu = menu(settings);

        choose(menu, 2);                                 // SOUND
        assertEquals(ConfigMenu.Page.SOUND, menu.page());

        int before = settings.musicVolume();
        choose(menu, 0);                                 // MUSIC_VOLUME
        assertTrue(settings.musicVolume() != before, "le volume n'a pas change");
        assertTrue(settings.musicVolume() >= 1
                && settings.musicVolume() <= Settings.MAX_MUSIC_VOLUME);

        choose(menu, 1);                                 // MUSIC_STATE
        assertFalse(settings.music());
        choose(menu, 2);                                 // SOUND_STATE
        assertFalse(settings.sound());
    }

    @Test
    @DisplayName("La page des options montre le code de la partie")
    void theOptionsPageShowsTheCode() {
        Settings settings = new Settings();
        ConfigMenu menu = menu(settings);
        menu.setAccessCode(LevelCode.FIRST_LEVEL);

        choose(menu, 3);                                 // GAME OPTIONS
        assertEquals(ConfigMenu.Page.OPTIONS, menu.page());
        assertTrue(menu.line(0).contains(LevelCode.FIRST_LEVEL),
                "le code n'apparait pas : " + menu.line(0));

        // Valider une ligne d'affichage ne doit rien casser.
        menu.select();
        assertEquals(ConfigMenu.Page.OPTIONS, menu.page());
    }

    @Test
    @DisplayName("Quitter se signale, et chaque page sait revenir a l'accueil")
    void quittingAndGoingBack() {
        Settings settings = new Settings();

        for (int page = 1; page <= 3; page++) {
            ConfigMenu menu = menu(settings);
            choose(menu, page);
            assertTrue(menu.page() != ConfigMenu.Page.MAIN);
            choose(menu, menu.lineCount() - 1);          // MAIN PAGE
            assertEquals(ConfigMenu.Page.MAIN, menu.page(), "retour depuis la page " + page);
        }

        ConfigMenu menu = menu(settings);
        choose(menu, 4);                                 // QUIT GAME
        assertTrue(menu.isQuitting());
        assertFalse(menu.isOpen());
    }

    @Test
    @DisplayName("Le menu se dessine dans le moniteur sans deborder")
    void theMenuFitsTheMonitor() {
        ConfigMenu menu = menu(new Settings());
        byte[] pixels = new byte[320 * 200];
        menu.render(pixels, 320, 200);

        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 320; x++) {
                boolean inside = x >= Monitor.LEFT && x < Monitor.LEFT + Monitor.WIDTH
                        && y >= Monitor.TOP && y < Monitor.TOP + Monitor.HEIGHT;
                if (!inside) {
                    assertEquals(0, pixels[y * 320 + x],
                            "pixel peint hors du moniteur en " + x + "," + y);
                }
            }
        }
    }

    @Test
    @DisplayName("Un reglage inchange ne fait pas croire a un changement")
    void theRevisionOnlyMovesOnRealChanges() {
        Settings settings = new Settings();
        int revision = settings.revision();

        settings.setSight(settings.sight());
        settings.setMusic(settings.music());
        settings.setMusicVolume(settings.musicVolume());
        assertEquals(revision, settings.revision(), "rien n'a change");

        settings.setSight(!settings.sight());
        assertTrue(settings.revision() != revision, "le changement n'est pas signale");
    }
}
