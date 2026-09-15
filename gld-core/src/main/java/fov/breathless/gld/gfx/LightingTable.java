package fov.breathless.gld.gfx;

import fov.breathless.gld.GldFormatException;

/**
 * Table d'assombrissement : 32 niveaux de lumiere x 256 index de palette, soit
 * 8192 octets.
 *
 * <p>C'est le mecanisme d'eclairage integral du moteur d'origine. Plutot que de
 * calculer une couleur, le rendu remplace chaque index de palette par un autre,
 * pris dans la ligne correspondant au niveau de lumiere voulu. Reprendre la table
 * telle quelle garantit un eclairage identique au pixel pres, brouillard compris
 * (le jeu embarque une seconde table dediee).
 *
 * <p>Correspond aux fichiers {@code LightingTable} et {@code LightingTableFog} des
 * sources d'origine, charges depuis le GGLD par {@code ReadGfxDir}.
 */
public final class LightingTable {

    /** Nombre de niveaux de lumiere. */
    public static final int LEVELS = 32;

    /** Taille de la table sur fichier. */
    public static final int SIZE = LEVELS * 256;

    private final byte[] table;

    public LightingTable(byte[] table) {
        if (table.length != SIZE) {
            throw new GldFormatException("Table d'eclairage de " + table.length + " octets, " + SIZE + " attendus");
        }
        this.table = table.clone();
    }

    /**
     * Index de palette assombri.
     *
     * @param level        niveau de lumiere, de 0 (pleine lumiere) a 31 (quasi noir) ;
     *                     la luminance moyenne decroit lineairement le long de la table
     * @param paletteIndex index de palette d'origine
     */
    public int shade(int level, int paletteIndex) {
        if (level < 0 || level >= LEVELS) {
            throw new IllegalArgumentException("Niveau de lumiere hors bornes : " + level);
        }
        return table[(level << 8) | (paletteIndex & 0xFF)] & 0xFF;
    }

    /** Copie brute des 8192 octets, pour une utilisation directe par le rendu. */
    public byte[] toByteArray() {
        return table.clone();
    }
}
