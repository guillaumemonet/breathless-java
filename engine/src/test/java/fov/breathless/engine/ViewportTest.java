package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Facteurs derives de la fenetre de rendu, d'apres {@code ComputeVars}. */
class ViewportTest {

    @Test
    @DisplayName("La fenetre standard 320x200 donne un pas d'un demi et une echelle verticale de 200/110")
    void standardWindow() {
        Viewport viewport = new Viewport(320, 200);

        // 160 unites de demi-largeur reparties sur 320 colonnes : un demi par colonne.
        assertEquals(Fixed.ONE / 2, viewport.xRatio());

        // La hauteur de reference est 110, pas 200 : l'echelle vaut donc 200/110.
        assertEquals((200 << 16) / 110, viewport.yRatio());

        assertEquals(160, viewport.halfWidth());
        assertEquals(100, viewport.halfHeight());
    }

    @Test
    @DisplayName("Les rayons balaient exactement l'intervalle [-80, +80[")
    void raysSpanTheStandardHalfWidth() {
        Viewport viewport = new Viewport(320, 200);

        assertEquals(-80 * Fixed.ONE, viewport.rayOffset(0));
        assertEquals(0, viewport.rayOffset(160), "la colonne centrale regarde droit devant");

        int last = viewport.rayOffset(viewport.width() - 1);
        assertTrue(last < 80 * Fixed.ONE, "la derniere colonne doit rester sous la borne");
        assertEquals(80 * Fixed.ONE, last + viewport.xRatio(), "un pas de plus atteint la borne");
    }

    @Test
    @DisplayName("Une fenetre reduite conserve le meme champ de vision horizontal")
    void smallerWindowKeepsTheField() {
        Viewport small = new Viewport(160, 100);

        assertEquals(-80 * Fixed.ONE, small.rayOffset(0));
        assertEquals(80 * Fixed.ONE, small.rayOffset(small.width() - 1) + small.xRatio());
    }
}
