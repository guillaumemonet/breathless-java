package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Les tables trigonometriques regenerees doivent coincider avec celles gravees dans
 * Sorgenti/sincos.asm : c'est ce qui garantit que les caps lus dans les fichiers de
 * niveau produisent exactement les memes directions qu'a l'epoque.
 */
class TrigTest {

    /** Seize premieres entrees de {@code sintable}, relevees dans sincos.asm. */
    private static final int[] SIN_HEAD = {
            0, 201, 402, 603, 804, 1005, 1206, 1407,
            1608, 1809, 2010, 2211, 2412, 2613, 2814, 3014,
    };

    /**
     * Huit premieres entrees de {@code costable}, relevees dans sincos.asm. Dans le
     * fichier d'origine c'est la meme table que les sinus, lue 512 entrees plus loin.
     */
    private static final int[] COS_HEAD = {
            65536, 65535, 65534, 65533, 65531, 65528, 65524, 65520,
    };

    @Test
    @DisplayName("Le debut de la table des sinus est reproduit a l'identique")
    void sineMatchesOriginalTable() {
        for (int i = 0; i < SIN_HEAD.length; i++) {
            assertEquals(SIN_HEAD[i], Trig.sin(i), "sintable[" + i + "]");
        }
    }

    @Test
    @DisplayName("Le debut de la table des cosinus est reproduit a l'identique")
    void cosineMatchesOriginalTable() {
        for (int i = 0; i < COS_HEAD.length; i++) {
            assertEquals(COS_HEAD[i], Trig.cos(i), "costable[" + i + "]");
        }
    }

    /**
     * Huit entrees de {@code sintable} a partir de l'index 1096, relevees dans
     * sincos.asm. Elles seules distinguent le plancher de la troncature vers zero.
     */
    private static final int[] SIN_NEGATIVE = {
            -14360, -14556, -14752, -14947, -15143, -15339, -15534, -15729,
    };

    @Test
    @DisplayName("La zone negative de la table confirme un arrondi par plancher")
    void negativeSineMatchesOriginalTable() {
        for (int i = 0; i < SIN_NEGATIVE.length; i++) {
            assertEquals(SIN_NEGATIVE[i], Trig.sin(1096 + i), "sintable[" + (1096 + i) + "]");
        }
    }

    @Test
    @DisplayName("Les quatre points cardinaux du moteur : 0 est, 512 nord, 1024 ouest, 1536 sud")
    void cardinalHeadings() {
        assertEquals(Fixed.ONE, Trig.cos(0));
        assertEquals(0, Trig.sin(0));

        assertEquals(0, Trig.cos(512));
        assertEquals(Fixed.ONE, Trig.sin(512));

        assertEquals(-Fixed.ONE, Trig.cos(1024));
        assertEquals(0, Trig.sin(1024));

        // cos(1536) vaut exactement 0 parce que la table est lue avec un decalage :
        // un calcul direct en virgule flottante donnerait -1.
        assertEquals(0, Trig.cos(1536));
        assertEquals(-Fixed.ONE, Trig.sin(1536));
    }

    @Test
    @DisplayName("Un cap hors bornes est ramene dans le tour")
    void headingsWrap() {
        assertEquals(Trig.sin(1), Trig.sin(Trig.STEPS + 1));
        assertEquals(Trig.cos(3), Trig.cos(-Trig.STEPS + 3));
    }
}
