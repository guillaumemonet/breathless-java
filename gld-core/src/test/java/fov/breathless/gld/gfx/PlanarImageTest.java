package fov.breathless.gld.gfx;

import fov.breathless.gld.GldFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Depliage des images en plans de bits. */
class PlanarImageTest {

    @Test
    @DisplayName("Chaque plan apporte son bit, du poids faible au poids fort")
    void planesContributeTheirOwnBit() {
        // Huit pixels, trois plans. Le bit de poids fort d'un octet est le pixel de gauche.
        byte[] data = {
            (byte) 0b10101010,   // plan 0 : pixels pairs
            (byte) 0b11001100,   // plan 1
            (byte) 0b11110000,   // plan 2
        };
        PlanarImage image = PlanarImage.read(data, 8, 1, 3, "essai");

        assertEquals(8, image.width());
        assertEquals(1, image.height());
        assertArrayEquals(new byte[]{7, 6, 5, 4, 3, 2, 1, 0}, image.pixels());
    }

    @Test
    @DisplayName("Les lignes d'un plan se suivent avant le plan suivant")
    void planesAreStoredWhole() {
        // Deux lignes de huit pixels, deux plans : le second plan commence a l'octet 2.
        byte[] data = {
            (byte) 0b11110000, (byte) 0b00001111,   // plan 0, lignes 0 et 1
            (byte) 0b11000000, (byte) 0b00000011,   // plan 1, lignes 0 et 1
        };
        PlanarImage image = PlanarImage.read(data, 8, 2, 2, "essai");

        assertArrayEquals(new byte[]{
            3, 3, 1, 1, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 1, 3, 3,
        }, image.pixels());
    }

    @Test
    @DisplayName("Une image tronquee est refusee plutot que lue de travers")
    void truncatedDataIsRejected() {
        GldFormatException error = assertThrows(GldFormatException.class,
                () -> PlanarImage.read(new byte[10], 320, 40, 8, "Panel04.raw"));
        assertEquals(true, error.getMessage().contains("Panel04.raw"));
    }

    @Test
    @DisplayName("Une largeur qui n'est pas un multiple de huit est refusee")
    void widthMustBeWholeBytes() {
        assertThrows(GldFormatException.class,
                () -> PlanarImage.read(new byte[64], 12, 1, 1, "essai"));
    }
}
