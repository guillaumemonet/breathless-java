package fov.breathless.gld.sound;

import fov.breathless.gld.GldContainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les sept morceaux P61A du jeu, decodes et convertis en ProTracker.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class P61aModuleIT {

    /** Les soixante periodes ProTracker sans finetune. */
    private static final Set<Integer> PERIODS = new HashSet<>(List.of(
        1712, 1616, 1525, 1440, 1357, 1281, 1209, 1141, 1077, 1017, 961, 907,
        856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
        428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226,
        214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113,
        107, 101, 95, 90, 85, 80, 76, 71, 67, 64, 60, 57));

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
    @DisplayName("Les sept morceaux se decodent")
    void everyModuleDecodes() throws IOException {
        List<Sound> modules = bank().modules();
        assertEquals(7, modules.size());

        for (Sound sound : modules) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            assertTrue(module.patternCount() > 0, sound.name() + " : aucun motif");
            assertTrue(module.sampleCount() > 0, sound.name() + " : aucun instrument");
            assertTrue(module.sequence().length > 0, sound.name() + " : sequence vide");
            for (int position : module.sequence()) {
                assertTrue(position < module.patternCount(),
                        sound.name() + " : la sequence vise le motif " + position
                                + ", au-dela des " + module.patternCount() + " existants");
            }
        }
    }

    @Test
    @DisplayName("Toutes les notes tombent sur une periode ProTracker legale")
    void everyNoteIsALegalPeriod() throws IOException {
        int notes = 0;

        for (Sound sound : bank().modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            for (int pattern = 0; pattern < module.patternCount(); pattern++) {
                for (int row = 0; row < P61aModule.ROWS; row++) {
                    for (int channel = 0; channel < P61aModule.CHANNELS; channel++) {
                        int[] cell = module.cell(pattern, row, channel);
                        if (cell[0] == 0) {
                            continue;
                        }
                        assertTrue(PERIODS.contains(cell[0]),
                                sound.name() + " motif " + pattern + " ligne " + row
                                        + " voie " + channel + " : periode " + cell[0]
                                        + " hors de la table ProTracker");
                        notes++;
                    }
                }
            }
        }
        // Une erreur de decalage d'un bit produirait des periodes aberrantes bien avant.
        assertTrue(notes > 5000, "seulement " + notes + " notes decodees");
    }

    @Test
    @DisplayName("Aucune cellule ne designe un instrument inexistant")
    void samplesStayInRange() throws IOException {
        for (Sound sound : bank().modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            for (int pattern = 0; pattern < module.patternCount(); pattern++) {
                for (int row = 0; row < P61aModule.ROWS; row++) {
                    for (int channel = 0; channel < P61aModule.CHANNELS; channel++) {
                        int sample = module.cell(pattern, row, channel)[1];
                        assertTrue(sample <= module.sampleCount(),
                                sound.name() + " : instrument " + sample + " pour "
                                        + module.sampleCount() + " declares");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Les instruments portent du signal et des bornes de boucle coherentes")
    void samplesAreSound() throws IOException {
        for (Sound sound : bank().modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            int audible = 0;
            for (int i = 0; i < module.sampleCount(); i++) {
                P61aModule.SampleInfo info = module.sample(i);
                assertTrue(info.volume() <= 64,
                        sound.name() + " instrument " + i + " : volume " + info.volume());
                if (info.loops()) {
                    assertTrue(info.repeatStartWords() <= info.lengthWords(),
                            sound.name() + " instrument " + i + " : boucle hors de l'instrument");
                }
                byte[] pcm = module.sampleData(i);
                assertEquals(info.lengthWords() * 2, pcm.length,
                        sound.name() + " instrument " + i + " : longueur inattendue");
                int peak = 0;
                for (byte value : pcm) {
                    peak = Math.max(peak, Math.abs(value));
                }
                // Un instrument de quelques octets ne sert qu'a poser un volume : c'est
                // un usage courant du ProTracker, et il est muet par construction. Tout
                // instrument de vraie longueur, lui, doit porter du son.
                if (pcm.length <= 4) {
                    continue;
                }
                assertTrue(peak > 16,
                        sound.name() + " instrument " + i + " : " + pcm.length
                                + " octets sans signal, l'echantillon s'est perdu");
                audible++;
            }
            assertTrue(audible >= 3,
                    sound.name() + " : seulement " + audible + " instrument(s) audible(s)");
        }
    }

    @Test
    @DisplayName("Le fichier ProTracker produit a exactement la taille qu'il annonce")
    void theProTrackerFileIsWellFormed() throws IOException {
        for (Sound sound : bank().modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            byte[] mod = module.toProTracker(sound.name());

            int sampleBytes = 0;
            for (int i = 0; i < module.sampleCount(); i++) {
                sampleBytes += module.sample(i).lengthWords() * 2;
            }
            int expected = 1084 + module.patternCount() * 1024 + sampleBytes;
            assertEquals(expected, mod.length, sound.name() + " : taille inattendue");

            assertEquals('M', mod[1080]);
            assertEquals('.', mod[1081]);
            assertEquals('K', mod[1082]);
            assertEquals('.', mod[1083]);
            assertEquals(module.sequence().length, mod[950] & 0xFF,
                    sound.name() + " : longueur de sequence mal ecrite");
        }
    }

    @Test
    @DisplayName("Les motifs ne sont pas vides : le decodeur produit de la musique")
    void patternsCarryMusic() throws IOException {
        for (Sound sound : bank().modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            int cells = module.patternCount() * P61aModule.ROWS * P61aModule.CHANNELS;
            int notes = 0;
            for (int pattern = 0; pattern < module.patternCount(); pattern++) {
                for (int row = 0; row < P61aModule.ROWS; row++) {
                    for (int channel = 0; channel < P61aModule.CHANNELS; channel++) {
                        if (module.cell(pattern, row, channel)[0] != 0) {
                            notes++;
                        }
                    }
                }
            }
            // Un morceau reellement joue remplit au moins un dixieme de ses cellules.
            assertTrue(notes * 10 > cells,
                    sound.name() + " : " + notes + " notes pour " + cells
                            + " cellules, le decodage semble s'etre perdu");
        }
    }
}
