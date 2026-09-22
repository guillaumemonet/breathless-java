package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ce que le joueur emporte d'un niveau au suivant.
 *
 * <p>Sans banque d'objets : {@link Career} n'a besoin d'elle que pour decrire les armes,
 * et les puissances se manipulent sans description.
 */
class CareerTest {

    private static Career career() {
        return new Career(null);
    }

    @Test
    @DisplayName("Sante, credits et score traversent les niveaux")
    void theStateCrossesLevels() {
        Career career = career();
        career.enterLevel(false);

        PlayerState state = career.state();
        state.hit(40);
        state.collect(PlayerState.PICKUP_CREDITS, 2500);
        state.addScore(7000);
        int health = state.health();
        int shields = state.shields();

        // Niveau suivant : rien n'est remis a neuf.
        career.enterLevel(false);

        assertEquals(health, career.state().health(), "la sante a ete remise a neuf");
        assertEquals(shields, career.state().shields(), "les boucliers ont ete remis a neuf");
        assertEquals(2500, career.state().credits(), "les credits ont ete perdus");
        assertEquals(7000, career.state().score(), "le score a ete perdu");
    }

    @Test
    @DisplayName("Mourir fait reprendre le niveau dans l'etat ou l'on y est entre")
    void dyingRewindsToTheStateOnEntry() {
        Career career = career();
        PlayerState state = career.state();

        state.hit(30);                                   // on arrive amoche
        state.collect(PlayerState.PICKUP_CREDITS, 900);
        career.enterLevel(false);                        // entree dans le niveau
        int healthOnEntry = state.health();
        int shieldsOnEntry = state.shields();

        // On souffre et on depense pendant le niveau, puis on meurt.
        state.hit(60);
        state.spendCredits(400);
        state.addScore(1234);

        career.enterLevel(true);                         // reprise du meme niveau

        assertEquals(healthOnEntry, career.state().health(),
                "la sante d'entree n'a pas ete rendue");
        assertEquals(shieldsOnEntry, career.state().shields(),
                "les boucliers d'entree n'ont pas ete rendus");
        assertEquals(900, career.state().credits(), "les credits depenses n'ont pas ete rendus");
        assertEquals(0, career.state().score(), "le score d'entree n'a pas ete rendu");
    }

    @Test
    @DisplayName("Les cles sont la seule chose qui ne se garde pas")
    void keysAreTheOneThingLeftBehind() {
        Career career = career();
        career.enterLevel(false);

        career.state().collect(PlayerState.PICKUP_GREEN_KEY, 0);
        career.state().collect(PlayerState.PICKUP_RED_KEY, 0);
        assertTrue(career.state().hasKey(PlayerState.PICKUP_GREEN_KEY));

        career.enterLevel(false);

        assertFalse(career.state().hasKey(PlayerState.PICKUP_GREEN_KEY),
                "la cle verte a suivi dans le niveau suivant");
        assertFalse(career.state().hasKey(PlayerState.PICKUP_RED_KEY),
                "la cle rouge a suivi dans le niveau suivant");
    }

    @Test
    @DisplayName("Une reprise ne rend pas les cles trouvees dans le niveau")
    void replayingDoesNotHandBackTheKeys() {
        Career career = career();
        career.enterLevel(false);
        career.state().collect(PlayerState.PICKUP_BLUE_KEY, 0);

        career.enterLevel(true);

        // La photographie est prise avant le retrait : elle ne contient pas la cle, et
        // l'effacement final passe de toute facon en dernier.
        assertFalse(career.state().hasKey(PlayerState.PICKUP_BLUE_KEY),
                "la cle a ete rendue alors qu'il faut la retrouver");
    }
}
