package fov.breathless.game;

/**
 * Une police de huit pixels sur huit, pour l'ecran de chargement.
 *
 * <p>L'original y ecrit avec {@code Text()} de la graphics.library, donc avec la police
 * systeme de l'Amiga, qui ne fait pas partie des donnees du jeu. Elle est remplacee ici par
 * une police a chasse fixe, rasterisee une fois sans lissage dans la meme cellule de huit
 * sur huit, avec la meme ligne de base six pixels sous le haut, et rangee ci-dessous :
 * elle ne depend ainsi ni d'AWT ni du systeme, et sert telle quelle sur Android.
 */
final class SystemFont {

    /** Largeur et hauteur d'un caractere, et position de la ligne de base. */
    static final int SIZE = 8;
    static final int BASELINE = 6;

    /** Un caractere par entree, de l'espace au tilde ; une ligne par octet, bit fort a gauche. */
    private static final long[] GLYPHS = {
        0x0000000000000000L,   //  
        0x0010101010001000L,   // !
        0x0028282800000000L,   // "
        0x005050F850F85050L,   // #
        0x0020784030087020L,   // $
        0x0070507878283800L,   // %
        0x0010202078503000L,   // &
        0x0010101000000000L,   // '
        0x0010202020202010L,   // (
        0x0020101010101020L,   // )
        0x0010381000000000L,   // *
        0x000010107C101000L,   // +
        0x0000000000001020L,   // ,
        0x0000000038000000L,   // -
        0x0000000000001000L,   // .
        0x0008081010102020L,   // /
        0x0030484848483000L,   // 0
        0x0070101010107C00L,   // 1
        0x0030480810207800L,   // 2
        0x0070083008087000L,   // 3
        0x0010305078103800L,   // 4
        0x0078407008087000L,   // 5
        0x0038407048483000L,   // 6
        0x0078480810101000L,   // 7
        0x0030483048483000L,   // 8
        0x0030484838087000L,   // 9
        0x0000001000001000L,   // :
        0x0000001000001020L,   // ;
        0x0000083040300800L,   // <
        0x0000007800780000L,   // =
        0x0000403008304000L,   // >
        0x0030481020002000L,   // ?
        0x0030485858584038L,   // @
        0x007030487848EC00L,   // A
        0x00F048704848F000L,   // B
        0x0038484040483800L,   // C
        0x00F048484848F000L,   // D
        0x00F848705048F800L,   // E
        0x00F848705040F000L,   // F
        0x0038484058483800L,   // G
        0x00DC48784848EC00L,   // H
        0x0070202020207000L,   // I
        0x003C080848487000L,   // J
        0x00EC48507048EC00L,   // K
        0x00E040404048F800L,   // L
        0x00CC48786848EC00L,   // M
        0x00DC68685858E800L,   // N
        0x0030484848483000L,   // O
        0x00F048487040E000L,   // P
        0x0030484848483038L,   // Q
        0x00F048487050EC00L,   // R
        0x0038482010487000L,   // S
        0x007C541010103800L,   // T
        0x00EC484848483000L,   // U
        0x00DC484848303000L,   // V
        0x00EE545454282800L,   // W
        0x00DC48303048EC00L,   // X
        0x00CC483020207000L,   // Y
        0x0078485028487800L,   // Z
        0x0038202020202038L,   // [
        0x0040402020100808L,   // \\
        0x0070101010101070L,   // ]
        0x0010102800000000L,   // ^
        0x0000000000000000L,   // _
        0x1008000000000000L,   // `
        0x0000007038487C00L,   // a
        0x00C040704848F000L,   // b
        0x0000003848403800L,   // c
        0x0018083848483C00L,   // d
        0x0000003078403800L,   // e
        0x001C207020207800L,   // f
        0x0000003C48483808L,   // g
        0x00C040704848EC00L,   // h
        0x0010007010107C00L,   // i
        0x0020007010101010L,   // j
        0x00C040586050D800L,   // k
        0x0070101010107C00L,   // l
        0x000000F85454D600L,   // m
        0x000000F04848EC00L,   // n
        0x0000003048483000L,   // o
        0x000000F048487040L,   // p
        0x0000003C48483808L,   // q
        0x0000007820207800L,   // r
        0x0000003860187000L,   // s
        0x0000207820201800L,   // t
        0x000000D848483C00L,   // u
        0x000000CC48483000L,   // v
        0x000000EC54282800L,   // w
        0x0000006C38386C00L,   // x
        0x000000EC48302020L,   // y
        0x0000007850287800L,   // z
        0x0018101020101018L,   // {
        0x0010101010101010L,   // |
        0x0060202010202060L,   // }
        0x0000006858000000L,   // ~
    };

    private SystemFont() {
    }

    /**
     * Ecrit un texte, comme {@code Move} puis {@code Text} : {@code y} est la ligne de base.
     */
    static void text(byte[] target, int width, int height, int x, int y, String text, int colour) {
        int top = y - BASELINE;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i) - 32;
            if (c < 0 || c >= GLYPHS.length) {
                continue;
            }
            long bits = GLYPHS[c];
            for (int row = 0; row < SIZE; row++) {
                int line = (int) (bits >>> ((SIZE - 1 - row) * 8)) & 0xFF;
                for (int column = 0; column < SIZE; column++) {
                    if ((line & (0x80 >> column)) == 0) {
                        continue;
                    }
                    int px = x + i * SIZE + column;
                    int py = top + row;
                    if (px >= 0 && py >= 0 && px < width && py < height) {
                        target[py * width + px] = (byte) colour;
                    }
                }
            }
        }
    }
}
