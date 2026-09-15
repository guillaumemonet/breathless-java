package fov.breathless.gld;

/**
 * Decompression SLZ, portage de {@code UnPack} / {@code UnPack0} / {@code UnPack1}
 * (Sorgenti/FileAccess.asm, lignes 154-340).
 *
 * <p>Le flux est un LZ77 a fenetre glissante, pilote par des octets TAG :
 * <ul>
 *   <li>TAG == 0 : 8 octets litteraux suivent, puis un nouveau TAG ;</li>
 *   <li>sinon, les 8 bits du TAG sont lus du plus significatif au moins significatif.
 *       Bit a 0 : un octet litteral. Bit a 1 : une reference arriere.</li>
 * </ul>
 *
 * <p>Une reference arriere est codee sur deux octets {@code cs} et {@code lo} :
 * <pre>
 *   longueur = (cs &amp; MASK) + 2
 *   distance = ((cs &lt;&lt; LSL) &amp; 0xFF00) | lo
 * </pre>
 * Un {@code cs} nul termine le flux. La copie se fait octet par octet et peut
 * chevaucher la zone en cours d'ecriture (repetition de motif), il ne faut donc
 * surtout pas la remplacer par un {@code System.arraycopy}.
 *
 * <p>Deux variantes coexistent, selectionnees par le premier octet du bloc compresse :
 * <ul>
 *   <li>mode 0 « normal »  : MASK=0x0F, LSL=4 -&gt; longueurs 2..17, fenetre 4096 ;</li>
 *   <li>mode 1 « grafica » : MASK=0x1F, LSL=3 -&gt; longueurs 2..33, fenetre 2048.</li>
 * </ul>
 */
public final class Slz {

    private Slz() {
    }

    /**
     * Decompresse un bloc SLZ.
     *
     * @param src           donnees compressees, l'octet a {@code srcOff} etant l'octet de mode
     * @param srcOff        position de l'octet de mode dans {@code src}
     * @param expectedSize  taille attendue en sortie (celle annoncee par l'entete VDCO)
     * @return un tableau de exactement {@code expectedSize} octets
     * @throws GldFormatException si le flux est tronque, incoherent, ou ne produit
     *                            pas exactement {@code expectedSize} octets
     */
    public static byte[] decompress(byte[] src, int srcOff, int expectedSize) {
        if (expectedSize < 0) {
            throw new GldFormatException("Taille de sortie negative : " + expectedSize);
        }
        if (srcOff < 0 || srcOff >= src.length) {
            throw new GldFormatException("Offset de depart hors bloc : " + srcOff);
        }

        int mode = src[srcOff] & 0xFF;
        final int mask;
        final int lsl;
        switch (mode) {
            case 0 -> {
                mask = 0x0F;
                lsl = 4;
            }
            case 1 -> {
                mask = 0x1F;
                lsl = 3;
            }
            default -> throw new GldFormatException("Mode de compression SLZ inconnu : " + mode);
        }

        byte[] out = new byte[expectedSize];
        int p = srcOff + 1;
        int d = 0;

        while (true) {
            if (p >= src.length) {
                throw new GldFormatException(
                        "Flux SLZ tronque : TAG attendu a l'offset " + p + " (" + d + "/" + expectedSize + " octets produits)");
            }
            int tag = src[p++] & 0xFF;

            if (tag == 0) {
                // slz_literal : 8 octets litteraux d'un coup, puis TAG suivant.
                requireSrc(src, p, 8, d, expectedSize);
                requireDst(d, 8, expectedSize);
                System.arraycopy(src, p, out, d, 8);
                p += 8;
                d += 8;
                continue;
            }

            for (int bit = 0; bit < 8; bit++) {
                boolean compressed = (tag & 0x80) != 0;
                tag = (tag << 1) & 0xFF;

                if (!compressed) {
                    requireSrc(src, p, 1, d, expectedSize);
                    requireDst(d, 1, expectedSize);
                    out[d++] = src[p++];
                    continue;
                }

                requireSrc(src, p, 1, d, expectedSize);
                int cs = src[p++] & 0xFF;
                if (cs == 0) {
                    // slz_exit
                    if (d != expectedSize) {
                        throw new GldFormatException(
                                "Fin de flux SLZ prematuree : " + d + " octets produits, " + expectedSize + " attendus");
                    }
                    return out;
                }

                requireSrc(src, p, 1, d, expectedSize);
                int lo = src[p++] & 0xFF;

                int length = (cs & mask) + 2;
                int distance = ((cs << lsl) & 0xFF00) | lo;

                if (distance == 0 || distance > d) {
                    throw new GldFormatException(
                            "Distance SLZ invalide : " + distance + " a la position de sortie " + d);
                }
                requireDst(d, length, expectedSize);

                // Copie octet par octet : le chevauchement est volontaire.
                int from = d - distance;
                for (int i = 0; i < length; i++) {
                    out[d++] = out[from++];
                }
            }
        }
    }

    private static void requireSrc(byte[] src, int p, int n, int produced, int expected) {
        if (p + n > src.length) {
            throw new GldFormatException(
                    "Flux SLZ tronque : " + n + " octet(s) attendu(s) a l'offset " + p
                            + " (" + produced + "/" + expected + " octets produits)");
        }
    }

    private static void requireDst(int d, int n, int expected) {
        if (d + n > expected) {
            throw new GldFormatException(
                    "Depassement de la taille annoncee : ecriture de " + n + " octet(s) en " + d
                            + ", limite " + expected);
        }
    }
}
