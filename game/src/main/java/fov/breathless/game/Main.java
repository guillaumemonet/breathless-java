package fov.breathless.game;

import com.jme3.system.AppSettings;
import fov.breathless.engine.LevelCode;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Point d'entree du jeu.
 *
 * <p>Le repertoire des donnees d'origine se donne en argument, ou par la propriete
 * {@code -Dbreathless.data=<chemin>}. A defaut, {@code ../Breathless} est essaye.
 *
 * <p>Un code de niveau peut suivre, pour reprendre une partie la ou elle en etait :
 * {@code gradle run --args="../Breathless 181CEIGGLJRJSE2T"}.
 */
public final class Main {

    private static final int DEFAULT_SCALE = 3;

    private Main() {
    }

    /**
     * Cherche un code de niveau parmi les arguments.
     *
     * <p>Un code fait seize caracteres et ne designe aucun repertoire : il se reconnait
     * sans qu'on ait a l'annoncer.
     */
    private static LevelCode.State savedGame(String[] args) {
        for (String argument : args) {
            LevelCode.State state = LevelCode.decode(argument);
            if (state != null) {
                return state;
            }
        }
        String property = System.getProperty("breathless.code");
        if (property == null) {
            return null;
        }
        LevelCode.State state = LevelCode.decode(property);
        if (state == null) {
            System.out.println("Code de niveau refuse : " + property);
        }
        return state;
    }

    public static void main(String[] args) {
        Path directory = dataDirectory(args);

        GameData data;
        try {
            data = GameData.load(directory);
        } catch (IOException e) {
            System.err.println("Chargement impossible : " + e.getMessage());
            System.err.println("Indiquez le repertoire des .GLD en argument, "
                    + "ou avec -Dbreathless.data=<chemin>");
            System.exit(1);
            return;
        }

        System.out.println(data.levels().size() + " niveaux, "
                + data.textures().size() + " textures, "
                + data.objects().size() + " types d'objet");
        System.out.println("Deplacement : fleches ou ZQSD/WASD | Maj : courir | "
                + "Alt : pas de cote force | , et . : pas de cote");
        System.out.println("Debut / Fin : regarder en haut / en bas | Inser : regard droit");
        System.out.println("Espace : ouvrir une porte, actionner | Ctrl : tirer | P : pause");
        System.out.println("Au titre : une touche ouvre le menu, START GAME lance la partie");
        System.out.println("Tab : carte | 1-6 : choisir une arme | X : arme suivante");
        System.out.println("Au terminal : avancer/reculer pour choisir, Espace pour valider");
        System.out.println("+ / - : taille de la fenetre | Pave 7 8 9 0 : grosseur des pixels");
        System.out.println("Pg. prec./suiv. : changer de niveau | Echap : menu");

        LevelCode.State saved = savedGame(args);
        if (saved != null) {
            System.out.printf("Code accepte : monde %d niveau %d, sante %d, boucliers %d,"
                    + " energie %d, credits %d%n", saved.game(), saved.level(),
                    saved.health(), saved.shields(), saved.energy(), saved.credits());
        }

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Breathless");
        // 240 lignes : les 200 de la vue, plus les 40 du panneau de bord.
        settings.setResolution(320 * DEFAULT_SCALE, 240 * DEFAULT_SCALE);
        settings.setResizable(true);
        // Le rendu est logiciel : la synchronisation verticale suffit largement.
        settings.setVSync(true);

        BreathlessApp app = new BreathlessApp(data, saved);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    /**
     * Emplacements ou chercher les donnees, dans l'ordre.
     *
     * <p>Le repertoire courant depend de la facon dont le jeu est lance : la racine du
     * projet en ligne de commande, le sous-projet {@code game} sous Gradle. On remonte
     * donc de quelques crans plutot que de parier sur l'un des deux.
     */
    private static final String[] CANDIDATES = {
        "Breathless", "../Breathless", "../../Breathless", "../../../Breathless",
        ".", "..", "../..",
    };

    /**
     * Ou prendre les donnees.
     *
     * <p>Une copie locale l'emporte toujours, dans cet ordre : le repertoire donne en
     * argument, la propriete {@code breathless.data}, l'archive posee a cote du jeu, puis
     * celle qu'un lancement precedent a rangee. A defaut, le jeu propose d'aller la
     * chercher — c'est le cas du paquet distribue, qui ne contient pas les donnees.
     */
    private static Path dataDirectory(String[] args) {
        if (args.length > 0) {
            return Path.of(args[0]);
        }
        String configured = System.getProperty("breathless.data");
        if (configured != null) {
            return Path.of(configured);
        }
        for (String candidate : CANDIDATES) {
            Path path = Path.of(candidate);
            if (DataStore.holdsGameData(path)) {
                return path;
            }
        }
        Path stored = DataStore.localDirectory();
        if (DataStore.holdsGameData(stored)) {
            return stored;
        }
        Path fetched = FirstRun.obtain();
        return (fetched != null) ? fetched : Path.of("Breathless");
    }
}
