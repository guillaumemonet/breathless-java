package fov.breathless.gld.gfx;

/**
 * La police du terminal, celle du « sprite-monitor » du jeu.
 *
 * <p>Portage de {@code CaratteriSprMon} (Sorgenti/Text.asm) : des caracteres de cinq
 * pixels sur cinq, monochromes, du code 32 au code 106. L'original les avance de six
 * pixels et les imprime deux fois, une ombre decalee d'un pixel puis le trait.
 *
 * <p><strong>Fichier engendre</strong> depuis la source d'origine : ne pas le modifier a
 * la main.
 */
public final class MonitorFont {

    /** Premier caractere decrit. */
    public static final int FIRST = 32;

    /** Nombre de caracteres decrits. */
    public static final int COUNT = 75;

    /** Largeur utile d'un caractere, en pixels. */
    public static final int WIDTH = 5;

    /** Hauteur d'un caractere, en pixels. */
    public static final int HEIGHT = 5;

    /** Pas entre deux caracteres. {@code SPRMON_CHARWIDTH}. */
    public static final int ADVANCE = 6;

    /** Interligne. {@code SPRMON_CHARHEIGHT}. */
    public static final int LINE_HEIGHT = 6;

    /** Cinq lignes par caractere, le bit de poids fort a gauche des cinq colonnes. */
    private static final int[] ROWS = {
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 32 ' '
        0b01000, 0b01000, 0b01000, 0b00000, 0b01000,   // 33 '!'
        0b10100, 0b10100, 0b00000, 0b00000, 0b00000,   // 34 '"'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 35 '#'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 36 '$'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 37 '%'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 38 '&'
        0b00100, 0b00100, 0b00000, 0b00000, 0b00000,   // 39 "'"
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 40 '('
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 41 ')'
        0b00000, 0b01010, 0b00100, 0b01010, 0b00000,   // 42 '*'
        0b00000, 0b01000, 0b11100, 0b01000, 0b00000,   // 43 '+'
        0b00000, 0b00000, 0b00000, 0b00100, 0b01000,   // 44 ','
        0b00000, 0b00000, 0b11100, 0b00000, 0b00000,   // 45 '-'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00100,   // 46 '.'
        0b00001, 0b00010, 0b00100, 0b01000, 0b10000,   // 47 '/'
        0b01110, 0b11001, 0b10101, 0b10011, 0b01110,   // 48 '0'
        0b00100, 0b01100, 0b00100, 0b00100, 0b01110,   // 49 '1'
        0b11110, 0b00001, 0b01110, 0b10000, 0b11111,   // 50 '2'
        0b11110, 0b00001, 0b01111, 0b00001, 0b11110,   // 51 '3'
        0b10010, 0b10010, 0b11111, 0b00010, 0b00010,   // 52 '4'
        0b11111, 0b10000, 0b01111, 0b00001, 0b11110,   // 53 '5'
        0b01110, 0b10000, 0b11111, 0b10001, 0b01110,   // 54 '6'
        0b11111, 0b00001, 0b00010, 0b00100, 0b00100,   // 55 '7'
        0b01110, 0b10001, 0b01110, 0b10001, 0b01110,   // 56 '8'
        0b01110, 0b10001, 0b11111, 0b00001, 0b01110,   // 57 '9'
        0b00000, 0b00100, 0b00000, 0b00100, 0b00000,   // 58 ':'
        0b00000, 0b00100, 0b00000, 0b00100, 0b01000,   // 59 ';'
        0b00010, 0b00100, 0b01000, 0b00100, 0b00010,   // 60 '<'
        0b00000, 0b11100, 0b00000, 0b11100, 0b00000,   // 61 '='
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 62 '>'
        0b00110, 0b01001, 0b00010, 0b00000, 0b00010,   // 63 '?'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 64 '@'
        0b01110, 0b10001, 0b11111, 0b10001, 0b10001,   // 65 'A'
        0b11110, 0b10001, 0b11110, 0b10001, 0b11110,   // 66 'B'
        0b01111, 0b10000, 0b10000, 0b10000, 0b01111,   // 67 'C'
        0b11110, 0b10001, 0b10001, 0b10001, 0b11110,   // 68 'D'
        0b11111, 0b10000, 0b11110, 0b10000, 0b11111,   // 69 'E'
        0b11111, 0b10000, 0b11110, 0b10000, 0b10000,   // 70 'F'
        0b01110, 0b10000, 0b10111, 0b10001, 0b01110,   // 71 'G'
        0b10001, 0b10001, 0b11111, 0b10001, 0b10001,   // 72 'H'
        0b01110, 0b00100, 0b00100, 0b00100, 0b01110,   // 73 'I'
        0b00001, 0b00001, 0b00001, 0b10001, 0b01110,   // 74 'J'
        0b10001, 0b10010, 0b11100, 0b10010, 0b10001,   // 75 'K'
        0b01000, 0b01000, 0b01000, 0b01000, 0b01110,   // 76 'L'
        0b10001, 0b11011, 0b10101, 0b10001, 0b10001,   // 77 'M'
        0b10001, 0b11001, 0b10101, 0b10011, 0b10001,   // 78 'N'
        0b01110, 0b10001, 0b10001, 0b10001, 0b01110,   // 79 'O'
        0b11110, 0b10001, 0b11110, 0b10000, 0b10000,   // 80 'P'
        0b01110, 0b10001, 0b10001, 0b10010, 0b01101,   // 81 'Q'
        0b11110, 0b10001, 0b11110, 0b10001, 0b10001,   // 82 'R'
        0b01111, 0b10000, 0b01110, 0b00001, 0b11110,   // 83 'S'
        0b11111, 0b00100, 0b00100, 0b00100, 0b00100,   // 84 'T'
        0b10001, 0b10001, 0b10001, 0b10001, 0b01110,   // 85 'U'
        0b10001, 0b10001, 0b10001, 0b01010, 0b00100,   // 86 'V'
        0b10001, 0b10001, 0b10101, 0b11011, 0b10001,   // 87 'W'
        0b10001, 0b10001, 0b01110, 0b10001, 0b10001,   // 88 'X'
        0b10001, 0b10001, 0b01010, 0b00100, 0b00100,   // 89 'Y'
        0b11111, 0b00001, 0b01110, 0b10000, 0b11111,   // 90 'Z'
        0b01100, 0b01000, 0b01000, 0b01000, 0b01100,   // 91 '['
        0b10000, 0b01000, 0b00100, 0b00010, 0b00001,   // 92 '\\'
        0b00110, 0b00010, 0b00010, 0b00010, 0b00110,   // 93 ']'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 94 '^'
        0b00000, 0b00000, 0b00000, 0b00000, 0b00000,   // 95 '_'
        0b01000, 0b00100, 0b00000, 0b00000, 0b00000,   // 96 '`'
        0b11111, 0b10101, 0b00100, 0b00100, 0b11111,   // 97 'a'
        0b00100, 0b01111, 0b00100, 0b11110, 0b00100,   // 98 'b'
        0b00100, 0b00100, 0b00111, 0b00100, 0b11100,   // 99 'c'
        0b00100, 0b00100, 0b11111, 0b10101, 0b10101,   // 100 'd'
        0b10101, 0b10101, 0b11111, 0b00100, 0b00100,   // 101 'e'
        0b00100, 0b10101, 0b10001, 0b10101, 0b00100,   // 102 'f'
        0b01110, 0b10101, 0b10101, 0b00100, 0b00100,   // 103 'g'
        0b10101, 0b00101, 0b11111, 0b10100, 0b10101,   // 104 'h'
        0b01100, 0b10100, 0b10111, 0b10100, 0b01100,   // 105 'i'
        0b00110, 0b11101, 0b00101, 0b11101, 0b00110,   // 106 'j'
    };

    private MonitorFont() {
    }

    /**
     * Le motif d'une ligne d'un caractere : cinq bits, celui de poids fort a gauche.
     *
     * @return 0 pour un caractere hors table, ce qui l'imprime comme une espace
     */
    public static int row(char character, int line) {
        int index = character - FIRST;
        if (index < 0 || index >= COUNT || line < 0 || line >= HEIGHT) {
            return 0;
        }
        return ROWS[index * HEIGHT + line];
    }
}
