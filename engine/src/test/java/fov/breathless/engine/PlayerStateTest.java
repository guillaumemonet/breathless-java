package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comptabilite du joueur : degats, ramassage, plafonds. */
class PlayerStateTest {

    @Test
    @DisplayName("Les boucliers absorbent trois quarts des degats, arrondis au plus proche")
    void shieldsAbsorbThreeQuarters() {
        PlayerState state = new PlayerState();

        state.hit(10);
        // 7,5 arrondi a 8 pour les boucliers, le reste pour la sante.
        assertEquals(PlayerState.MAX_SHIELDS - 8, state.shields());
        assertEquals(PlayerState.MAX_HEALTH - 2, state.health());
    }

    @Test
    @DisplayName("Un degat impair s'arrondit vers le haut cote boucliers")
    void oddDamageRoundsUp() {
        PlayerState state = new PlayerState();

        state.hit(1);
        // 0,75 arrondi a 1 : le bouclier encaisse tout, la sante est intacte.
        assertEquals(PlayerState.MAX_SHIELDS - 1, state.shields());
        assertEquals(PlayerState.MAX_HEALTH, state.health());
    }

    @Test
    @DisplayName("Sans boucliers, le joueur encaisse tout")
    void withoutShieldsEverythingHurts() {
        PlayerState state = new PlayerState();

        // Vide les boucliers.
        while (state.shields() > 0) {
            state.hit(4);
        }
        int health = state.health();
        state.hit(10);
        assertEquals(health - 10, state.health(), "toute l'attaque devrait passer");
    }

    @Test
    @DisplayName("La sante tombee a zero tue le joueur, et il ne meurt qu'une fois")
    void deathIsFinal() {
        PlayerState state = new PlayerState();

        assertFalse(state.isDead());
        for (int i = 0; i < 200; i++) {
            state.hit(20);
        }
        assertTrue(state.isDead());
        assertEquals(0, state.health());

        // Un mort n'encaisse plus rien.
        int shields = state.shields();
        state.hit(50);
        assertEquals(shields, state.shields());
    }

    @Test
    @DisplayName("Le mode invincible annule les degats")
    void invincibilityBlocksDamage() {
        PlayerState state = new PlayerState();
        state.setInvincible(true);

        state.hit(100);
        assertEquals(PlayerState.MAX_HEALTH, state.health());
        assertEquals(PlayerState.MAX_SHIELDS, state.shields());
        assertFalse(state.isDead());
    }

    @Test
    @DisplayName("Un objet dont on a deja son content reste en place")
    void alreadyFullRefusesThePickup() {
        PlayerState state = new PlayerState();

        assertFalse(state.collect(PlayerState.PICKUP_HEALTH, 10),
                "la sante est deja au maximum");

        state.hit(40);
        assertTrue(state.collect(PlayerState.PICKUP_HEALTH, 10));
    }

    @Test
    @DisplayName("Le ramassage ne depasse jamais les plafonds")
    void pickupsAreCapped() {
        PlayerState state = new PlayerState();

        state.hit(80);
        state.collect(PlayerState.PICKUP_HEALTH, 1000);
        assertEquals(PlayerState.MAX_HEALTH, state.health());

        for (int i = 0; i < 200; i++) {
            state.collect(PlayerState.PICKUP_CREDITS, 1000);
        }
        assertEquals(PlayerState.MAX_CREDITS, state.credits());
    }

    @Test
    @DisplayName("Une cle ne se ramasse qu'une fois")
    void keysAreUnique() {
        PlayerState state = new PlayerState();

        assertFalse(state.hasKey(PlayerState.PICKUP_RED_KEY));
        assertTrue(state.collect(PlayerState.PICKUP_RED_KEY, 0));
        assertTrue(state.hasKey(PlayerState.PICKUP_RED_KEY));
        assertFalse(state.collect(PlayerState.PICKUP_RED_KEY, 0),
                "la seconde cle rouge ne sert a rien");

        assertFalse(state.hasKey(PlayerState.PICKUP_BLUE_KEY));
    }

    @Test
    @DisplayName("Un sol nocif mord une fois par seconde, pas a chaque image")
    void harmfulFloorBitesOncePerSecond() {
        PlayerState state = new PlayerState();
        int health = state.health();
        int shields = state.shields();

        // La premiere morsure tombe des le premier pas.
        state.standOn(10, 1);
        assertTrue(state.shields() < shields, "la premiere morsure n'a pas eu lieu");

        int afterFirst = state.shields();
        for (int tick = 0; tick < 49; tick++) {
            state.standOn(10, 1);
        }
        assertEquals(afterFirst, state.shields(), "le sol a mordu trop souvent");

        state.standOn(10, 1);
        assertTrue(state.shields() < afterFirst, "la seconde morsure n'a pas eu lieu");
        assertTrue(state.health() <= health);
    }

    @Test
    @DisplayName("Un sol inoffensif ne fait rien et reinitialise le compte a rebours")
    void harmlessFloorDoesNothing() {
        PlayerState state = new PlayerState();

        for (int tick = 0; tick < 200; tick++) {
            state.standOn(0, 1);
        }
        assertEquals(PlayerState.MAX_HEALTH, state.health());
        assertEquals(PlayerState.MAX_SHIELDS, state.shields());
    }

    @Test
    @DisplayName("Encaisser fait virer l'ecran au rouge, treize pas durant")
    void takingAHitTurnsTheScreenRed() {
        PlayerState state = new PlayerState();
        assertFalse(state.isFlashing());

        state.hit(10);
        assertTrue(state.isFlashing());

        state.advance(PlayerState.RED_SCREEN_TICKS - 1);
        assertTrue(state.isFlashing(), "le voile s'efface trop tot");
        state.advance(1);
        assertFalse(state.isFlashing(), "le voile s'attarde");
    }

    @Test
    @DisplayName("Un coup sans effet ne declenche pas le voile rouge")
    void harmlessHitsDoNotFlash() {
        PlayerState state = new PlayerState();
        state.setInvincible(true);
        state.hit(10);
        assertFalse(state.isFlashing());

        PlayerState other = new PlayerState();
        other.hit(0);
        assertFalse(other.isFlashing());
    }
}
