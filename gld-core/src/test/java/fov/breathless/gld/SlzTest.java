package fov.breathless.gld;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Flux SLZ construits a la main, d'apres la specification lue dans
 * Sorgenti/FileAccess.asm. Chaque cas isole une regle du format.
 */
class SlzTest {

    private static final int MODE_NORMAL = 0;
    private static final int MODE_GRAPHIC = 1;

    /** Octet TAG dont les bits sont donnes du plus significatif au moins significatif. */
    private static int tag(int... bits) {
        int t = 0;
        for (int i = 0; i < 8; i++) {
            t <<= 1;
            if (i < bits.length && bits[i] != 0) {
                t |= 1;
            }
        }
        return t;
    }

    @Test
    @DisplayName("TAG nul : huit octets litteraux d'affilee")
    void eightByteLiteralRun() {
        byte[] stream = build(MODE_NORMAL, s -> {
            s.write(0x00);
            s.writeBytes("ABCDEFGH".getBytes(StandardCharsets.ISO_8859_1));
            s.write(tag(1));   // premier bit a 1 -> reference
            s.write(0x00);     // cs == 0 -> fin du flux
        });

        assertArrayEquals("ABCDEFGH".getBytes(StandardCharsets.ISO_8859_1),
                Slz.decompress(stream, 0, 8));
    }

    @Test
    @DisplayName("Reference arriere : longueur = (cs & 0x0F) + 2, distance sur 12 bits")
    void backReference() {
        byte[] stream = build(MODE_NORMAL, s -> {
            s.write(0x00);
            s.writeBytes("ABCDEFGH".getBytes(StandardCharsets.ISO_8859_1));
            s.write(tag(1, 1));
            s.write(0x06);     // longueur = 6 + 2 = 8
            s.write(0x08);     // distance = 8
            s.write(0x00);     // fin
        });

        assertArrayEquals("ABCDEFGHABCDEFGH".getBytes(StandardCharsets.ISO_8859_1),
                Slz.decompress(stream, 0, 16));
    }

    @Test
    @DisplayName("La copie chevauchante genere une repetition de motif")
    void overlappingCopyRepeatsPattern() {
        byte[] stream = build(MODE_NORMAL, s -> {
            s.write(tag(0, 1, 1));
            s.write('A');      // bit 0 : litteral
            s.write(0x02);     // bit 1 : longueur = 2 + 2 = 4
            s.write(0x01);     //         distance = 1 -> repete le 'A'
            s.write(0x00);     // bit 2 : fin
        });

        assertArrayEquals("AAAAA".getBytes(StandardCharsets.ISO_8859_1),
                Slz.decompress(stream, 0, 5));
    }

    @Test
    @DisplayName("Mode graphique : longueur = (cs & 0x1F) + 2, distance sur 11 bits")
    void graphicModeUsesDifferentSplit() {
        byte[] stream = build(MODE_GRAPHIC, s -> {
            s.write(tag(0, 1, 1));
            s.write('A');
            s.write(0x02);     // longueur = 2 + 2 = 4 (masque 0x1F)
            s.write(0x01);     // distance = 1
            s.write(0x00);
        });

        assertArrayEquals("AAAAA".getBytes(StandardCharsets.ISO_8859_1),
                Slz.decompress(stream, 0, 5));
    }

    @Test
    @DisplayName("Les bits de poids fort de cs completent la distance au dela de 255")
    void longDistanceUsesHighBitsOfCs() {
        // 264 octets litteraux (33 blocs de 8), puis une reference a distance 261.
        byte[] prefix = new byte[264];
        for (int i = 0; i < prefix.length; i++) {
            prefix[i] = (byte) ('A' + (i % 26));
        }

        byte[] stream = build(MODE_NORMAL, s -> {
            for (int block = 0; block < 33; block++) {
                s.write(0x00);
                s.write(prefix, block * 8, 8);
            }
            s.write(tag(1, 1));
            s.write(0x13);     // longueur = 3 + 2 = 5 ; bits hauts -> distance 0x100
            s.write(0x05);     //                        distance = 0x105 = 261
            s.write(0x00);
        });

        byte[] out = Slz.decompress(stream, 0, 269);

        assertEquals(269, out.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(out[264 - 261 + i], out[264 + i], "octet copie " + i);
        }
    }

    @Test
    @DisplayName("Un mode de compression inconnu est rejete")
    void unknownModeRejected() {
        byte[] stream = {0x02, 0x00};
        GldFormatException e = assertThrows(GldFormatException.class, () -> Slz.decompress(stream, 0, 8));
        assertEquals("Mode de compression SLZ inconnu : 2", e.getMessage());
    }

    @Test
    @DisplayName("Un flux tronque est signale plutot que silencieusement complete")
    void truncatedStreamRejected() {
        byte[] stream = build(MODE_NORMAL, s -> {
            s.write(0x00);
            s.writeBytes("ABC".getBytes(StandardCharsets.ISO_8859_1)); // il en manque 5
        });

        assertThrows(GldFormatException.class, () -> Slz.decompress(stream, 0, 8));
    }

    @Test
    @DisplayName("Une fin de flux prematuree ne passe pas pour un succes")
    void shortOutputRejected() {
        byte[] stream = build(MODE_NORMAL, s -> {
            s.write(tag(0, 1));
            s.write('A');
            s.write(0x00);     // fin apres un seul octet
        });

        assertThrows(GldFormatException.class, () -> Slz.decompress(stream, 0, 16));
    }

    @Test
    @DisplayName("Une distance pointant avant le debut de sortie est rejetee")
    void distanceBeyondOutputRejected() {
        byte[] stream = build(MODE_NORMAL, s -> {
            s.write(tag(1));
            s.write(0x02);
            s.write(0x40);     // distance 64 alors que rien n'a encore ete produit
            s.write(0x00);
        });

        assertThrows(GldFormatException.class, () -> Slz.decompress(stream, 0, 8));
    }

    private interface StreamBuilder {
        void accept(ByteArrayOutputStream out);
    }

    private static byte[] build(int mode, StreamBuilder body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(mode);
        body.accept(out);
        return out.toByteArray();
    }
}
