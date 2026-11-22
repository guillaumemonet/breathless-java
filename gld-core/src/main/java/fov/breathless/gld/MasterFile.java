package fov.breathless.gld;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Le fichier maitre {@code BREATHLESS.GLD} : les noms des mondes et des niveaux.
 *
 * <p>Format, lu dans les donnees et confirme par {@code LoadingScreen}
 * (Sorgenti/Presentation.asm), qui imprime {@code PunGame+2} et {@code PunLevel+4} :
 *
 * <pre>
 * +0x00  'MGLD'
 * +0x04  nom du jeu, 32 octets
 * +0x24  sept noms de quatre caracteres : musique des titres, logos, titre, credits,
 *        fin de partie, ecran final
 * +0x40  prefixe des fichiers, puis les numeros des quatre banques
 * +0x54  LONG   taille de la liste qui suit
 * +0x58  WORD   nombre de mondes
 *        par monde : WORD nombre de niveaux, nom sur 20 caracteres,
 *                    puis par niveau : numero de fichier sur 4, nom sur 20
 * </pre>
 */
public final class MasterFile {

    /** ID de charge utile du fichier maitre. */
    public static final String MGLD = "MGLD";

    /** Un monde et ses niveaux, noms tels qu'ils figurent dans le fichier. */
    public record World(String name, List<String> levels) {
    }

    private final String presentationMusic;
    private final List<World> worlds;

    private MasterFile(String presentationMusic, List<World> worlds) {
        this.presentationMusic = presentationMusic;
        this.worlds = worlds;
    }

    public static MasterFile read(GldContainer container) {
        byte[] data = container.payload();
        if (data.length < 0x58 || !MGLD.equals(text(data, 0, 4))) {
            throw new GldFormatException("Fichier maitre attendu : " + container.source());
        }
        String music = text(data, 0x24, 4);
        int position = 0x58;
        int count = u16(data, position);
        position += 2;
        List<World> worlds = new ArrayList<>();
        for (int w = 0; w < count && position + 22 <= data.length; w++) {
            int levelCount = u16(data, position);
            String name = text(data, position + 2, 20).strip();
            position += 22;
            List<String> levels = new ArrayList<>();
            for (int l = 0; l < levelCount && position + 24 <= data.length; l++) {
                levels.add(text(data, position + 4, 20).strip());
                position += 24;
            }
            worlds.add(new World(name, List.copyOf(levels)));
        }
        return new MasterFile(music, List.copyOf(worlds));
    }

    /** {@code PresModName} : la musique des titres, des credits et de la fin. */
    public String presentationMusic() {
        return presentationMusic;
    }

    public List<World> worlds() {
        return worlds;
    }

    /**
     * La ligne que l'ecran de chargement imprime en haut : le monde, une espace, le
     * niveau. {@code CheckLen} retire les espaces de fin de chaque nom.
     */
    public String title(int world, int level) {
        if (world < 1 || world > worlds.size()) {
            return "";
        }
        World entry = worlds.get(world - 1);
        String levelName = (level >= 1 && level <= entry.levels().size())
                ? entry.levels().get(level - 1) : "";
        return entry.name() + " " + levelName;
    }

    private static String text(byte[] data, int offset, int length) {
        int end = Math.min(data.length, offset + length);
        int stop = offset;
        while (stop < end && data[stop] != 0) {
            stop++;
        }
        return new String(data, offset, stop - offset, StandardCharsets.ISO_8859_1);
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
