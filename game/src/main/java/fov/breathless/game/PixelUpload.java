package fov.breathless.game;

import fov.breathless.engine.Framebuffer;
import fov.breathless.gld.gfx.Palette;

import java.nio.ByteBuffer;

/**
 * Conversion du tampon en index de palette vers les octets attendus par la carte
 * graphique.
 *
 * <p>Deux details sont a leur place ici plutot que dans le moteur : le format RGBA sur
 * quatre octets, et le <strong>retournement vertical</strong>. Une texture OpenGL a son
 * origine en bas a gauche, alors que le tampon du moteur, comme l'ecran Amiga d'origine,
 * commence en haut a gauche. La derniere ligne du tampon est donc ecrite en premier.
 */
final class PixelUpload {

    /** Octets par pixel dans le tampon televerse. */
    static final int BYTES_PER_PIXEL = 4;

    private PixelUpload() {
    }

    /** Taille du tampon necessaire pour une image donnee. */
    static int bufferSize(Framebuffer frame) {
        return frame.width() * frame.height() * BYTES_PER_PIXEL;
    }

    /**
     * Remplit le tampon a partir de l'image et de la palette.
     *
     * <p>Le tampon est rembobine avant et apres, pour etre directement consommable.
     */
    static void fill(ByteBuffer target, Framebuffer frame, Palette palette) {
        fill(target, frame.pixels(), frame.width(), frame.height(), palette);
    }

    /**
     * Meme chose pour un tampon compose a la main : la vue et le panneau de bord l'un
     * au-dessus de l'autre, comme sur l'ecran de 320x240 du jeu d'origine.
     */
    static void fill(ByteBuffer target, byte[] pixels, int width, int height, Palette palette) {
        int needed = width * height * BYTES_PER_PIXEL;
        if (target.capacity() < needed) {
            throw new IllegalArgumentException(
                    "Tampon de " + target.capacity() + " octets pour une image en demandant "
                            + needed);
        }

        target.clear();
        for (int y = height - 1; y >= 0; y--) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int index = pixels[row + x] & 0xFF;
                target.put((byte) palette.red(index));
                target.put((byte) palette.green(index));
                target.put((byte) palette.blue(index));
                target.put((byte) 0xFF);
            }
        }
        target.flip();
    }
}
