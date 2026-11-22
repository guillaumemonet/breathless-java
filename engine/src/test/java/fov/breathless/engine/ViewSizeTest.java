package fov.breathless.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Taille de fenetre et grosseur de pixel. */
class ViewSizeTest {

    @Test
    @DisplayName("La taille de depart est la fenetre entiere, 320 sur 200")
    void theDefaultIsTheFullWindow() {
        ViewSize size = new ViewSize();

        assertEquals(320, size.width());
        assertEquals(200, size.height());
        assertEquals(ViewSize.PIXEL_1X1, size.pixelType());
        assertEquals(320, size.renderWidth());
        assertEquals(200, size.renderHeight());
    }

    @Test
    @DisplayName("Les huit tailles vont de 96x60 a 320x200, par pas de 32 sur 20")
    void theEightSizesFollowTheOriginalTable() {
        ViewSize size = new ViewSize();
        while (size.resize(-1)) {
            // On descend jusqu'au plus petit.
        }
        assertEquals(96, size.width());
        assertEquals(60, size.height());

        int steps = 0;
        int previousWidth = size.width();
        int previousHeight = size.height();
        while (size.resize(+1)) {
            assertEquals(previousWidth + 32, size.width());
            assertEquals(previousHeight + 20, size.height());
            previousWidth = size.width();
            previousHeight = size.height();
            steps++;
        }
        assertEquals(320, size.width());
        assertEquals(200, size.height());
        assertEquals(ViewSize.count() - 1, steps);
    }

    @Test
    @DisplayName("Un pixel double divise ce qu'il faut calculer")
    void doublePixelsHalveTheWork() {
        ViewSize size = new ViewSize();

        assertTrue(size.setPixelType(ViewSize.PIXEL_2X1));
        assertEquals(160, size.renderWidth(), "la largeur calculee doit etre divisee");
        assertEquals(200, size.renderHeight(), "la hauteur ne change pas");
        assertEquals(2, size.scaleX());
        assertEquals(1, size.scaleY());

        assertTrue(size.setPixelType(ViewSize.PIXEL_1X2));
        assertEquals(320, size.renderWidth());
        assertEquals(100, size.renderHeight());

        assertTrue(size.setPixelType(ViewSize.PIXEL_2X2));
        assertEquals(160, size.renderWidth());
        assertEquals(100, size.renderHeight());
        assertEquals(2, size.scaleX());
        assertEquals(2, size.scaleY());

        // La taille affichee, elle, ne bouge pas : c'est tout l'interet.
        assertEquals(320, size.width());
        assertEquals(200, size.height());
    }

    @Test
    @DisplayName("Les bornes tiennent, et un reglage inchange se signale comme tel")
    void boundsHold() {
        ViewSize size = new ViewSize();

        for (int step = 0; step < 20; step++) {
            size.resize(+1);
        }
        assertEquals(320, size.width());
        assertFalse(size.resize(+1), "au maximum, agrandir ne doit rien changer");

        for (int step = 0; step < 20; step++) {
            size.resize(-1);
        }
        assertEquals(96, size.width());
        assertFalse(size.resize(-1), "au minimum, reduire ne doit rien changer");

        assertFalse(size.setPixelType(size.pixelType()), "le meme reglage ne change rien");
        assertFalse(size.setPixelType(-1));
        assertFalse(size.setPixelType(4));
    }

    @Test
    @DisplayName("Toute taille calculee reste une fenetre de rendu valide")
    void everyCombinationMakesAUsableViewport() {
        ViewSize size = new ViewSize();
        while (size.resize(-1)) {
            // On repart du plus petit.
        }
        do {
            for (int pixels = ViewSize.PIXEL_1X1; pixels <= ViewSize.PIXEL_2X2; pixels++) {
                size.setPixelType(pixels);
                Viewport viewport = new Viewport(size.renderWidth(), size.renderHeight());
                assertEquals(size.renderWidth(), viewport.width());
                assertEquals(size.renderHeight(), viewport.height());
                assertTrue(size.renderWidth() * size.scaleX() == size.width(),
                        "largeur affichee incoherente pour " + size);
                assertTrue(size.renderHeight() * size.scaleY() == size.height(),
                        "hauteur affichee incoherente pour " + size);
            }
        } while (size.resize(+1));
    }
}
