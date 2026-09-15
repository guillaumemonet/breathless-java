package fov.breathless.engine;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.object.ObjectBank;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le terminal : la seule boutique du jeu.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class TerminalIT {

    private static ObjectBank objects() throws IOException {
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
                if (ObjectBank.OGLD.equals(gld.payloadId())) {
                    return ObjectBank.read(gld);
                }
            }
        }
        throw new AssertionError("aucune banque d'objets dans " + dir);
    }

    private record Shop(Terminal terminal, PlayerState state, Weapons weapons) {
    }

    private static Shop shop(int credits) throws IOException {
        PlayerState state = new PlayerState();
        state.collect(PlayerState.PICKUP_CREDITS, credits);
        Weapons weapons = new Weapons(objects());
        Terminal terminal = new Terminal(state, weapons);
        terminal.open(3);
        return new Shop(terminal, state, weapons);
    }

    /** Descend jusqu'a la ligne voulue, puis valide. */
    private static void choose(Terminal terminal, int line) {
        while (terminal.selection() > line) {
            terminal.up();
        }
        while (terminal.selection() < line) {
            terminal.down();
        }
        terminal.select();
    }

    @Test
    @DisplayName("Acheter une arme la donne et retire son prix")
    void buyingAWeaponCostsItsPrice() throws IOException {
        Shop shop = shop(10000);
        assertFalse(shop.weapons().owns(1), "l'arme ne doit pas etre possedee au depart");

        choose(shop.terminal(), 0);                 // page armes
        assertEquals(Terminal.Page.WEAPONS, shop.terminal().page());

        choose(shop.terminal(), 0);                 // FIREBALLS, 4000
        assertTrue(shop.weapons().owns(1), "l'arme n'a pas ete donnee");
        assertEquals(6000, shop.state().credits(), "le prix n'a pas ete retire");
        assertFalse(shop.terminal().refused());
    }

    @Test
    @DisplayName("Sans les credits, rien n'est vendu et rien n'est pris")
    void aPurchaseNeedsTheCredits() throws IOException {
        Shop shop = shop(1000);

        choose(shop.terminal(), 0);                 // page armes
        choose(shop.terminal(), 0);                 // FIREBALLS, 4000

        assertFalse(shop.weapons().owns(1), "l'arme a ete donnee sans etre payee");
        assertEquals(1000, shop.state().credits(), "des credits ont ete pris pour rien");
        assertTrue(shop.terminal().refused(), "le refus n'est pas signale");
    }

    @Test
    @DisplayName("Une arme deja possedee ne se rachete pas")
    void aWeaponIsNotSoldTwice() throws IOException {
        Shop shop = shop(20000);

        choose(shop.terminal(), 0);
        choose(shop.terminal(), 0);                 // achat
        int left = shop.state().credits();

        choose(shop.terminal(), 0);                 // deuxieme tentative
        assertEquals(left, shop.state().credits(), "l'arme a ete payee deux fois");
        assertTrue(shop.terminal().refused());
    }

    @Test
    @DisplayName("L'amelioration exige de posseder l'arme, et ne vaut qu'une fois")
    void boostsNeedTheWeapon() throws IOException {
        Shop shop = shop(30000);

        choose(shop.terminal(), 1);                 // page ameliorations
        assertEquals(Terminal.Page.BOOSTS, shop.terminal().page());

        choose(shop.terminal(), 1);                 // FIREBALLS, arme non possedee
        assertTrue(shop.terminal().refused(), "on a pu ameliorer une arme absente");
        assertEquals(30000, shop.state().credits());

        // L'arme de base, elle, est toujours la.
        choose(shop.terminal(), 0);                 // SIMPLE SHOT, 1500
        assertTrue(shop.weapons().isBoosted(0), "l'arme de base n'a pas ete amelioree");
        assertEquals(28500, shop.state().credits());

        choose(shop.terminal(), 0);                 // une seconde fois
        assertEquals(28500, shop.state().credits(), "l'amelioration a ete payee deux fois");
    }

    @Test
    @DisplayName("Le lance-flammes n'a pas d'amelioration a vendre")
    void theFlamethrowerHasNoBoost() throws IOException {
        Shop shop = shop(99000);

        choose(shop.terminal(), 1);                 // page ameliorations
        choose(shop.terminal(), 3);                 // FLAME-THROWER, prix N/A

        assertTrue(shop.terminal().refused(), "un article inexistant a ete vendu");
        assertEquals(99000, shop.state().credits());
    }

    @Test
    @DisplayName("Les accessoires soignent, rechargent et donnent les cles")
    void accessoriesDoWhatTheySay() throws IOException {
        Shop shop = shop(20000);
        shop.state().hit(40);
        int hurt = shop.state().health();

        choose(shop.terminal(), 2);                 // page accessoires
        assertEquals(Terminal.Page.ACCESSORIES, shop.terminal().page());

        choose(shop.terminal(), 0);                 // HEALTH +10, 200
        assertEquals(hurt + 10, shop.state().health(), "la sante n'a pas monte de dix");
        assertEquals(19800, shop.state().credits());

        choose(shop.terminal(), 3);                 // GREEN KEY, 5000
        assertTrue(shop.state().hasKey(PlayerState.PICKUP_GREEN_KEY), "la cle verte manque");
        assertEquals(14800, shop.state().credits());
    }

    @Test
    @DisplayName("La sortie d'une page ramene a l'accueil, celle de l'accueil ferme")
    void exitsWalkBackUp() throws IOException {
        Shop shop = shop(0);

        choose(shop.terminal(), 0);                 // page armes
        choose(shop.terminal(), shop.terminal().lineCount() - 1);   // EXIT
        assertEquals(Terminal.Page.MAIN, shop.terminal().page());
        assertTrue(shop.terminal().isOpen());

        choose(shop.terminal(), shop.terminal().lineCount() - 1);   // EXIT
        assertFalse(shop.terminal().isOpen(), "le terminal ne s'est pas ferme");
    }

    @Test
    @DisplayName("Le choix ne sort jamais de la liste")
    void theSelectionStaysInRange() throws IOException {
        Shop shop = shop(0);

        for (int step = 0; step < 20; step++) {
            shop.terminal().up();
        }
        assertEquals(0, shop.terminal().selection());

        for (int step = 0; step < 20; step++) {
            shop.terminal().down();
        }
        assertEquals(shop.terminal().lineCount() - 1, shop.terminal().selection());
    }

    @Test
    @DisplayName("Le moniteur se dessine dans la vue sans deborder")
    void theMonitorFitsTheView() throws IOException {
        Shop shop = shop(25000);
        byte[] pixels = new byte[320 * 200];
        shop.terminal().render(pixels, 320, 200);

        // Rien au-dela du moniteur.
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

        // Et du texte s'y trouve bien.
        int painted = 0;
        for (byte pixel : pixels) {
            if (pixel != 0) {
                painted++;
            }
        }
        assertTrue(painted > Monitor.WIDTH * Monitor.HEIGHT / 2,
                "le moniteur est presque vide : " + painted + " pixels");
    }
}
