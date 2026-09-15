package fov.breathless.gld.sound;

import fov.breathless.gld.GldContainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La banque sonore d'origine, lue telle quelle.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class SoundBankIT {

    private static SoundBank bank() throws IOException {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                if (SoundBank.SGLD.equals(gld.payloadId())) {
                    return SoundBank.read(gld);
                }
            }
        }
        throw new AssertionError("aucune banque sonore dans " + dir);
    }

    @Test
    @DisplayName("Les quarante-sept sons du jeu se lisent")
    void everySoundIsRead() throws IOException {
        SoundBank bank = bank();
        assertEquals(47, bank.sounds().size(), "le fichier d'origine en compte quarante-sept");

        int modules = 0;
        int withSamples = 0;
        for (Sound sound : bank.sounds().values()) {
            if (sound.type() == Sound.TYPE_MODULE) {
                modules++;
            }
            if (sound.samples().length > 0) {
                withSamples++;
            }
        }
        assertEquals(7, modules, "sept morceaux ProTracker sont attendus");
        assertTrue(withSamples >= 37, "seulement " + withSamples + " sons portent un echantillon");
    }

    @Test
    @DisplayName("Chaque son declare bien la longueur qu'il occupe")
    void declaredLengthsMatch() throws IOException {
        for (Sound sound : bank().sounds().values()) {
            if (sound.type() == Sound.TYPE_RANDOM || sound.link() != null) {
                continue;
            }
            assertEquals(sound.lengthInWords() * 2, sound.samples().length,
                    sound.name() + " : longueur annoncee et echantillon ne concordent pas");
        }
    }

    @Test
    @DisplayName("Un son lie reprend l'echantillon d'un autre, avec son propre reglage")
    void linkedSoundsShareTheirSamples() throws IOException {
        SoundBank bank = bank();
        int linked = 0;

        for (Sound sound : bank.sounds().values()) {
            if (sound.link() == null) {
                continue;
            }
            Sound source = bank.get(sound.link());
            assertNotNull(source, sound.name() + " renvoie a " + sound.link() + ", introuvable");
            assertArrayEquals(source.samples(), sound.samples(),
                    sound.name() + " n'a pas repris l'echantillon de " + sound.link());
            linked++;
        }
        assertTrue(linked >= 5, "seulement " + linked + " son(s) lie(s) : le cas n'est pas couvert");

        // Et le reglage, lui, est bien propre au son lie.
        Sound alias = bank.get("ALN3");
        Sound origin = bank.get("ALN1");
        assertNotNull(alias);
        assertNotNull(origin);
        assertTrue(alias.period() != origin.period(),
                "ALN3 devrait jouer l'echantillon d'ALN1 a une autre hauteur");
    }

    @Test
    @DisplayName("Un son de type tirage ne porte que des noms")
    void randomSoundsOnlyNameOthers() throws IOException {
        SoundBank bank = bank();
        int drawn = 0;

        for (Sound sound : bank.sounds().values()) {
            if (sound.type() != Sound.TYPE_RANDOM) {
                continue;
            }
            assertEquals(0, sound.samples().length,
                    sound.name() + " : un tirage ne porte pas d'echantillon");
            assertTrue(sound.alternatives().length >= 2,
                    sound.name() + " : un tirage a moins de deux choix");
            for (String choice : sound.alternatives()) {
                assertNotNull(bank.get(choice),
                        sound.name() + " tire " + choice + ", absent de la banque");
            }
            drawn++;
        }
        assertTrue(drawn >= 3, "seulement " + drawn + " tirage(s) : le cas n'est pas couvert");
    }

    @Test
    @DisplayName("Les sept sons globaux du jeu sont tous la")
    void everyGlobalSoundIsPresent() throws IOException {
        SoundBank bank = bank();

        for (int code = Sound.GLOBAL_DOOR_START; code <= Sound.GLOBAL_FAULT; code++) {
            Sound sound = bank.global(code);
            assertNotNull(sound, "aucun son global de code " + code);
            assertEquals(Sound.TYPE_GLOBAL, sound.type());
            assertTrue(sound.samples().length > 0, sound.name() + " est muet");
        }
        assertEquals("SWT1", bank.global(Sound.GLOBAL_SWITCH).name());
        assertEquals("TSP1", bank.global(Sound.GLOBAL_TELEPORT).name());
        assertEquals("SCM1", bank.global(Sound.GLOBAL_SCREAM).name());
    }

    @Test
    @DisplayName("Les frequences restent dans ce que le materiel savait produire")
    void sampleRatesAreSane() throws IOException {
        for (Sound sound : bank().sounds().values()) {
            if (sound.type() == Sound.TYPE_MODULE || sound.type() == Sound.TYPE_RANDOM) {
                continue;
            }
            int rate = sound.sampleRate();
            assertTrue(rate > 1000 && rate < 30000,
                    sound.name() + " : frequence aberrante (" + rate + " Hz)");
            assertTrue(sound.volume() >= 0 && sound.volume() <= 64,
                    sound.name() + " : volume hors de 0 a 64 (" + sound.volume() + ")");
        }
    }

    @Test
    @DisplayName("Les echantillons portent vraiment du signal")
    void samplesCarrySignal() throws IOException {
        SoundBank bank = bank();

        for (String name : new String[]{"SWT1", "SCM1", "ALN1", "TSP1"}) {
            Sound sound = bank.get(name);
            assertNotNull(sound, name + " absent");
            int peak = 0;
            for (byte sample : sound.samples()) {
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue(peak > 32, name + " : crete de " + peak + ", l'echantillon semble vide");
        }
    }
}
