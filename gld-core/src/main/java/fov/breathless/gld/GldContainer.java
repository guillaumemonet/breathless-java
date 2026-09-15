package fov.breathless.gld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/**
 * Enveloppe de fichier Breathless : lit un {@code .GLD} depuis le disque et, s'il
 * est compresse, le decompresse pour exposer la charge utile en clair.
 *
 * <p>Portage de {@code OpenCustom} (Sorgenti/FileAccess.asm lignes 36-120). Un fichier
 * compresse commence par l'ID {@code VDCO} (« Virtual Dreams COmpression ») :
 * <pre>
 *   +0   'VDCO'          4 octets
 *   +4   tailleOrigine   LONG  (taille apres decompression)
 *   +8   taillePackee    LONG  (taille des donnees compressees, hors octet de mode)
 *   +12  mode            1 octet  (0 = normal, 1 = graphique)
 *   +13  donnees         taillePackee octets
 * </pre>
 * Un fichier non compresse est renvoye tel quel : son ID est alors directement
 * {@code MGLD}, {@code LGLD}, {@code TGLD}, {@code OGLD}, {@code GGLD} ou {@code SGLD}.
 */
public final class GldContainer {

    /** ID des fichiers compresses : 'VDCO', Virtual Dreams COmpression. */
    public static final String VDCO = "VDCO";

    private final Path source;
    private final byte[] payload;
    private final boolean compressed;
    private final int packedSize;

    private GldContainer(Path source, byte[] payload, boolean compressed, int packedSize) {
        this.source = source;
        this.payload = payload;
        this.compressed = compressed;
        this.packedSize = packedSize;
    }

    public static GldContainer read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < 4) {
            throw new GldFormatException("Fichier trop court pour etre un GLD : " + file);
        }

        String id = new String(raw, 0, 4, StandardCharsets.ISO_8859_1);
        if (!VDCO.equals(id)) {
            return new GldContainer(file, raw, false, raw.length);
        }

        ByteReader r = new ByteReader(raw, 4);
        long originalSize = r.u32();
        long packed = r.u32();

        if (originalSize > Integer.MAX_VALUE) {
            throw new GldFormatException("Taille decompressee irrealiste (" + originalSize + ") dans " + file);
        }
        // OpenCustom lit packedSize+1 octets : l'octet de mode est compte en plus.
        long declaredEnd = 12L + packed + 1L;
        if (declaredEnd > raw.length) {
            throw new GldFormatException("Bloc compresse tronque dans " + file
                    + " : " + declaredEnd + " octets annonces, " + raw.length + " disponibles");
        }

        byte[] clear = Slz.decompress(raw, 12, (int) originalSize);
        return new GldContainer(file, clear, true, (int) packed);
    }

    /** Contenu en clair, quelle que soit la compression d'origine. */
    public byte[] payload() {
        return payload;
    }

    public ByteReader reader() {
        return new ByteReader(payload);
    }

    /** ID de la charge utile : {@code MGLD}, {@code LGLD}, {@code TGLD}... */
    public String payloadId() {
        if (payload.length < 4) {
            throw new GldFormatException("Charge utile trop courte dans " + source);
        }
        return new String(payload, 0, 4, StandardCharsets.ISO_8859_1);
    }

    public Path source() {
        return source;
    }

    public boolean isCompressed() {
        return compressed;
    }

    public int packedSize() {
        return packedSize;
    }

    public int size() {
        return payload.length;
    }
}
