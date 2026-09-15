package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Les codes de niveau : fabrication, relecture et rejet des codes alteres. */
class LevelCodeTest {

    @Test
    @DisplayName("Le code de depart du jeu se relit et designe le premier niveau")
    void theGameStartCodeReadsBack() {
        LevelCode.State state = LevelCode.decode(LevelCode.FIRST_LEVEL);

        assertNotNull(state, "le code de depart du jeu doit etre valide");
        assertEquals(1, state.game(), "monde du code de depart");
        assertEquals(1, state.level(), "niveau du code de depart");
        assertEquals(PlayerState.MAX_HEALTH, state.health());
        assertEquals(PlayerState.MAX_SHIELDS, state.shields());
        assertEquals(0, state.credits());
        assertEquals(0, state.activeWeapon());
        assertEquals(1, state.weaponPower()[0], "l'arme de base est possedee");
        for (int weapon = 1; weapon < Weapons.COUNT; weapon++) {
            assertEquals(0, state.weaponPower()[weapon],
                    "l'arme " + weapon + " ne doit pas etre possedee au depart");
        }
    }

    @Test
    @DisplayName("Le code de depart, relu puis refait, redonne la meme chaine")
    void theGameStartCodeRebuildsItself() {
        LevelCode.State state = LevelCode.decode(LevelCode.FIRST_LEVEL);
        assertNotNull(state);

        String rebuilt = LevelCode.encode(state.health(), state.shields(), state.energy(),
                state.credits(), state.activeWeapon(), state.weaponPower(),
                state.game(), state.level());

        // Un codage seulement coherent avec lui-meme echouerait ici : c'est la chaine
        // ecrite en dur dans le jeu d'origine qu'il faut retrouver, caractere pour
        // caractere.
        assertEquals(LevelCode.FIRST_LEVEL, rebuilt);
    }

    @Test
    @DisplayName("Un code fait puis relu rend exactement le meme etat")
    void everyStateSurvivesARoundTrip() {
        Random random = new Random(20260915);

        for (int round = 0; round < 500; round++) {
            int health = random.nextInt(101);
            int shields = random.nextInt(101);
            int energy = random.nextInt(10000);
            int credits = random.nextInt(99999);

            int[] power = new int[Weapons.COUNT];
            int active = 0;
            for (int weapon = 0; weapon < Weapons.COUNT; weapon++) {
                power[weapon] = random.nextInt(3);
                if (power[weapon] > 0) {
                    active = weapon;
                }
            }
            power[0] = Math.max(1, power[0]);

            int game = 1 + random.nextInt(4);
            int level = 1 + random.nextInt(5);

            String code = LevelCode.encode(health, shields, energy, credits,
                    active, power, game, level);
            assertEquals(LevelCode.LENGTH, code.length(), "longueur du code");

            LevelCode.State back = LevelCode.decode(code);
            assertNotNull(back, "code refuse : " + code);
            assertEquals(health, back.health(), "sante, code " + code);
            assertEquals(shields, back.shields(), "boucliers, code " + code);
            assertEquals(energy, back.energy(), "energie, code " + code);
            assertEquals(credits, back.credits(), "credits, code " + code);
            assertEquals(active, back.activeWeapon(), "arme en main, code " + code);
            assertEquals(game, back.game(), "monde, code " + code);
            assertEquals(level, back.level(), "niveau, code " + code);
            for (int weapon = 0; weapon < Weapons.COUNT; weapon++) {
                assertEquals(power[weapon], back.weaponPower()[weapon],
                        "arme " + weapon + ", code " + code);
            }
        }
    }

    @Test
    @DisplayName("Un code n'emploie que les trente-deux symboles prevus")
    void codesUseTheExpectedAlphabet() {
        Random random = new Random(7);
        for (int round = 0; round < 100; round++) {
            String code = LevelCode.encode(100, 100, 1000, random.nextInt(99999),
                    0, new int[]{1, 0, 0, 0, 0, 0}, 1 + random.nextInt(4), 1);
            for (char symbol : code.toCharArray()) {
                boolean digit = symbol >= '1' && symbol <= '9';
                boolean letter = symbol >= 'A' && symbol <= 'W';
                if (!digit && !letter) {
                    throw new AssertionError("symbole inattendu '" + symbol + "' dans " + code);
                }
            }
        }
    }

    @Test
    @DisplayName("Un caractere change suffit a faire rejeter le code")
    void alteredCodesAreRefused() {
        String code = LevelCode.encode(100, 100, 1000, 4200, 0,
                new int[]{1, 1, 0, 0, 0, 0}, 2, 3);
        assertNotNull(LevelCode.decode(code));

        int refused = 0;
        for (int at = 0; at < code.length(); at++) {
            char original = code.charAt(at);
            char replacement = (original == '1') ? '2' : '1';
            String altered = code.substring(0, at) + replacement + code.substring(at + 1);
            if (LevelCode.decode(altered) == null) {
                refused++;
            }
        }
        // La somme de controle tient sur sept bits : elle ne peut pas tout attraper, mais
        // elle doit arreter la grande majorite des fautes de frappe.
        if (refused < code.length() - 2) {
            throw new AssertionError(refused + " alterations refusees sur " + code.length());
        }
    }

    @Test
    @DisplayName("Ce qui n'est pas un code est refuse plutot qu'interprete")
    void nonsenseIsRefused() {
        assertNull(LevelCode.decode(null));
        assertNull(LevelCode.decode(""));
        assertNull(LevelCode.decode("TROPCOURT"));
        assertNull(LevelCode.decode("181CEIGGLJRJSE2TDETROP"));
        assertNull(LevelCode.decode("181CEIGGLJRJSE2Z"), "Z n'est pas dans l'alphabet");
        assertNull(LevelCode.decode("181CEIGGLJRJSE2!"));
    }

    @Test
    @DisplayName("La casse et les espaces autour ne genent pas")
    void inputIsForgiving() {
        LevelCode.State state = LevelCode.decode("  " + LevelCode.FIRST_LEVEL.toLowerCase() + " ");
        assertNotNull(state);
        assertEquals(1, state.game());
        assertEquals(1, state.level());
    }
}
