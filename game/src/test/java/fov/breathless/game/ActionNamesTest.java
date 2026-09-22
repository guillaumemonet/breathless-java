package fov.breathless.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Noms des commandes recues du clavier.
 *
 * <p>Les touches secondaires doublent les premieres sous le meme nom suffixe de « 2 ».
 * Deduire ce doublement du seul suffixe attrape aussi des commandes qui finissent par un
 * chiffre sans en etre — et {@code arme-2}, retaillee en {@code arme-}, faisait tomber le
 * fil de rendu sur un {@code NumberFormatException}, donc le jeu entier, a chaque appui
 * sur la touche 3.
 */
class ActionNamesTest {

    @Test
    @DisplayName("Une touche secondaire se ramene a la commande qu'elle double")
    void secondaryKeysFoldOntoTheirCommand() {
        assertEquals("avant", BreathlessApp.resolveAction("avant2"));
        assertEquals("arriere", BreathlessApp.resolveAction("arriere2"));
        assertEquals("gauche", BreathlessApp.resolveAction("gauche2"));
        assertEquals("droite", BreathlessApp.resolveAction("droite2"));
        assertEquals("courir", BreathlessApp.resolveAction("courir2"));
        assertEquals("pas-de-cote", BreathlessApp.resolveAction("pas-de-cote2"));
        assertEquals("tirer", BreathlessApp.resolveAction("tirer2"));
        assertEquals("actionner", BreathlessApp.resolveAction("actionner2"));
    }

    @Test
    @DisplayName("Une commande qui finit par un chiffre garde son nom entier")
    void commandsEndingInADigitKeepTheirName() {
        for (int weapon = 0; weapon < 6; weapon++) {
            String name = "arme-" + weapon;
            assertEquals(name, BreathlessApp.resolveAction(name),
                    "la touche de l'arme " + weapon + " a ete retaillee");
        }
        assertEquals("pixel-11", BreathlessApp.resolveAction("pixel-11"));
        assertEquals("pixel-21", BreathlessApp.resolveAction("pixel-21"));
        assertEquals("pixel-12", BreathlessApp.resolveAction("pixel-12"));
        assertEquals("pixel-22", BreathlessApp.resolveAction("pixel-22"));
    }

    @Test
    @DisplayName("Les autres commandes passent telles quelles")
    void otherCommandsPassThrough() {
        assertEquals("menu", BreathlessApp.resolveAction("menu"));
        assertEquals("carte", BreathlessApp.resolveAction("carte"));
        assertEquals("arme-suivante", BreathlessApp.resolveAction("arme-suivante"));
        assertEquals("niveau-suivant", BreathlessApp.resolveAction("niveau-suivant"));
    }
}
