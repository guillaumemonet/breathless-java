package fov.breathless.game;

import fov.breathless.engine.Framebuffer;
import fov.breathless.gld.gfx.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Conversion du tampon du moteur vers la texture.
 *
 * <p>Le retournement vertical est le genre de detail qui ne casse rien et affiche
 * simplement le jeu a l'envers : il merite une assertion.
 */
class PixelUploadTest {

    /** Palette ou l'index n est le gris (n, n, n), pour retrouver l'index dans la sortie. */
    private static Palette rampPalette() {
        byte[] rgb = new byte[Palette.SIZE];
        for (int i = 0; i < Palette.COLORS; i++) {
            rgb[i * 3] = (byte) i;
            rgb[i * 3 + 1] = (byte) i;
            rgb[i * 3 + 2] = (byte) i;
        }
        return new Palette(rgb);
    }

    @Test
    @DisplayName("La derniere ligne du tampon est ecrite en premier")
    void bufferIsFlippedVertically() {
        Framebuffer frame = new Framebuffer(2, 3);
        frame.set(0, 0, 10);
        frame.set(1, 0, 11);
        frame.set(0, 2, 20);
        frame.set(1, 2, 21);

        ByteBuffer target = ByteBuffer.allocate(PixelUpload.bufferSize(frame));
        PixelUpload.fill(target, frame, rampPalette());

        // Premier pixel du tampon televerse : coin bas-gauche de l'image.
        assertEquals(20, target.get(0) & 0xFF, "rouge du premier pixel");
        assertEquals(21, target.get(4) & 0xFF, "rouge du deuxieme pixel");

        // Dernier pixel : coin haut-droit de l'image.
        int last = (2 * 3 - 1) * PixelUpload.BYTES_PER_PIXEL;
        assertEquals(11, target.get(last) & 0xFF, "rouge du dernier pixel");
    }

    @Test
    @DisplayName("Chaque pixel sort en rouge, vert, bleu puis alpha opaque")
    void channelsAreInRgbaOrder() {
        byte[] rgb = new byte[Palette.SIZE];
        rgb[3] = (byte) 0x12;
        rgb[4] = (byte) 0x34;
        rgb[5] = (byte) 0x56;
        Palette palette = new Palette(rgb);

        Framebuffer frame = new Framebuffer(1, 1);
        frame.set(0, 0, 1);

        ByteBuffer target = ByteBuffer.allocate(PixelUpload.bufferSize(frame));
        PixelUpload.fill(target, frame, palette);

        assertEquals(0x12, target.get(0) & 0xFF);
        assertEquals(0x34, target.get(1) & 0xFF);
        assertEquals(0x56, target.get(2) & 0xFF);
        assertEquals(0xFF, target.get(3) & 0xFF, "l'image doit etre opaque");
    }

    @Test
    @DisplayName("Le tampon est pret a etre lu apres remplissage")
    void bufferIsReadyToRead() {
        Framebuffer frame = new Framebuffer(4, 4);
        ByteBuffer target = ByteBuffer.allocate(PixelUpload.bufferSize(frame));
        PixelUpload.fill(target, frame, rampPalette());

        assertEquals(0, target.position());
        assertEquals(PixelUpload.bufferSize(frame), target.limit());
    }

    @Test
    @DisplayName("Un tampon trop petit est refuse plutot que tronque")
    void undersizedBufferRejected() {
        Framebuffer frame = new Framebuffer(320, 200);
        ByteBuffer target = ByteBuffer.allocate(16);

        assertThrows(IllegalArgumentException.class,
                () -> PixelUpload.fill(target, frame, rampPalette()));
    }
}
