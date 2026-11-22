package fov.breathless.game;

import fov.breathless.engine.KeyBindings;
import fov.breathless.engine.Settings;
import fov.breathless.engine.ViewSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lecture et ecriture des reglages, d'une session a l'autre.
 *
 * <p>Portage de {@code ReadConfig} et {@code WriteConfig} (Sorgenti/Terminal.asm), qui
 * gardent la taille de fenetre, la grosseur des pixels, le son et les touches. Le fichier
 * est ici un simple fichier de proprietes, lisible et modifiable a la main.
 *
 * <p>Un fichier absent ou abime n'est pas une erreur : on garde les reglages par defaut.
 */
final class Configuration {

    /** Nom du fichier, a cote du jeu. */
    static final String FILE = "breathless.config";

    private Configuration() {
    }

    /** Charge les reglages, s'il y en a. */
    static void load(Path file, Settings settings) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException failure) {
            System.out.println("Reglages illisibles (" + file + ") : " + failure.getMessage());
            return;
        }

        ViewSize size = settings.viewSize();
        size.resize(readInt(properties, "fenetre", size.index()) - size.index());
        size.setPixelType(readInt(properties, "pixels", size.pixelType()));
        settings.setSight(readBoolean(properties, "viseur", settings.sight()));
        settings.setMusic(readBoolean(properties, "musique", settings.music()));
        settings.setSound(readBoolean(properties, "bruitages", settings.sound()));
        settings.setMusicVolume(readInt(properties, "volume", settings.musicVolume()));

        for (String action : KeyBindings.ACTIONS) {
            String value = properties.getProperty("touche." + action);
            if (value == null) {
                continue;
            }
            // « nom:code » : le nom pour l'affichage, le code pour le clavier.
            int separator = value.lastIndexOf(':');
            if (separator <= 0) {
                continue;
            }
            try {
                int code = Integer.parseInt(value.substring(separator + 1).trim());
                settings.keys().set(action, value.substring(0, separator), code);
            } catch (NumberFormatException ignored) {
                // Ligne abimee : on garde la touche par defaut.
            }
        }
        System.out.println("Reglages repris de " + file.toAbsolutePath());
    }

    /** Enregistre les reglages. */
    static void save(Path file, Settings settings) {
        Properties properties = new Properties();
        properties.setProperty("fenetre", Integer.toString(settings.viewSize().index()));
        properties.setProperty("pixels", Integer.toString(settings.viewSize().pixelType()));
        properties.setProperty("viseur", Boolean.toString(settings.sight()));
        properties.setProperty("musique", Boolean.toString(settings.music()));
        properties.setProperty("bruitages", Boolean.toString(settings.sound()));
        properties.setProperty("volume", Integer.toString(settings.musicVolume()));
        for (KeyBindings.Binding binding : settings.keys().all()) {
            properties.setProperty("touche." + binding.action(),
                    binding.label() + ":" + binding.code());
        }

        try (var out = Files.newOutputStream(file)) {
            properties.store(out, "Reglages de Breathless Java");
            System.out.println("Reglages enregistres dans " + file.toAbsolutePath());
        } catch (IOException failure) {
            System.out.println("Reglages non enregistres : " + failure.getMessage());
        }
    }

    private static int readInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return (value == null) ? fallback : Boolean.parseBoolean(value);
    }
}
