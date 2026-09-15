package fov.breathless.gld;

import java.nio.charset.StandardCharsets;

/**
 * Curseur de lecture big-endian sur un tableau d'octets.
 *
 * <p>Toutes les donnees Breathless proviennent d'un 68000 : les entiers sont
 * stockes gros-boutistes et les champs sont ceux des structures C de l'editeur
 * ({@code WORD} = 16 bits, {@code LONG} = 32 bits, signes sauf mention contraire).
 */
public final class ByteReader {

    private final byte[] data;
    private int pos;

    public ByteReader(byte[] data) {
        this(data, 0);
    }

    public ByteReader(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
    }

    public int position() {
        return pos;
    }

    public void position(int newPos) {
        if (newPos < 0 || newPos > data.length) {
            throw new GldFormatException("Position hors fichier : " + newPos + " (taille " + data.length + ")");
        }
        pos = newPos;
    }

    public void skip(int n) {
        position(pos + n);
    }

    public int remaining() {
        return data.length - pos;
    }

    public boolean hasRemaining() {
        return pos < data.length;
    }

    /** Lit un octet non signe (0..255). */
    public int u8() {
        require(1);
        return data[pos++] & 0xFF;
    }

    /** Lit un octet signe (-128..127). */
    public int s8() {
        require(1);
        return data[pos++];
    }

    /** Lit un WORD non signe (0..65535). */
    public int u16() {
        require(2);
        return ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
    }

    /** Lit un WORD signe (-32768..32767). */
    public int s16() {
        return (short) u16();
    }

    /** Lit un LONG signe. */
    public int s32() {
        require(4);
        return ((data[pos++] & 0xFF) << 24)
                | ((data[pos++] & 0xFF) << 16)
                | ((data[pos++] & 0xFF) << 8)
                | (data[pos++] & 0xFF);
    }

    /** Lit un LONG non signe, elargi en {@code long}. */
    public long u32() {
        return s32() & 0xFFFFFFFFL;
    }

    public byte[] bytes(int n) {
        require(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    /**
     * Lit une chaine de longueur fixe et la nettoie : tout ce qui suit un octet nul
     * est ignore, puis les espaces de tete et de fin sont retires. Les noms Breathless
     * sont des champs de 4 ou 8 caracteres completes par des espaces.
     */
    public String fixedString(int n) {
        byte[] raw = bytes(n);
        int end = raw.length;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(raw, 0, end, StandardCharsets.ISO_8859_1).trim();
    }

    /** Lit une ID de 4 caracteres ASCII, telle que {@code MGLD} ou {@code VDCO}. */
    public String tag() {
        byte[] raw = bytes(4);
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private void require(int n) {
        if (pos + n > data.length) {
            throw new GldFormatException(
                    "Lecture hors fichier : " + n + " octet(s) a l'offset " + pos + " (taille " + data.length + ")");
        }
    }
}
