package fov.breathless.gld.sound;

import fov.breathless.gld.GldContainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le lecteur ProTracker : ce qu'il rend doit ressembler a de la musique.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class ProTrackerPlayerIT {

    private static final int RATE = 22050;

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
    @DisplayName("Chaque morceau rend une duree plausible, sans saturer ni se taire")
    void everyModuleRendersSoundly() throws IOException {
        for (Sound sound : bank().modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            short[] pcm = ProTrackerPlayer.render(module, RATE, 300);

            int seconds = pcm.length / RATE;
            assertTrue(seconds >= 10, sound.name() + " : " + seconds + " s seulement");
            assertTrue(seconds <= 300, sound.name() + " : " + seconds + " s, rendu sans fin");

            long energy = 0;
            int peak = 0;
            int clipped = 0;
            for (short value : pcm) {
                int magnitude = Math.abs(value);
                peak = Math.max(peak, magnitude);
                energy += (long) magnitude * magnitude;
                if (magnitude >= 32700) {
                    clipped++;
                }
            }
            double rms = Math.sqrt((double) energy / pcm.length);

            assertTrue(peak > 4000, sound.name() + " : crete de " + peak + ", presque muet");
            assertTrue(rms > 500, sound.name() + " : niveau moyen de " + rms + ", trop faible");
            // Une erreur de melange ferait saturer en permanence.
            assertTrue(clipped * 1000L < pcm.length,
                    sound.name() + " : " + clipped + " points satures sur " + pcm.length);
        }
    }

    @Test
    @DisplayName("Le rendu a du relief : ce n'est pas un bourdonnement constant")
    void theRenderHasDynamics() throws IOException {
        Sound sound = bank().modules().get(0);
        P61aModule module = P61aModule.read(sound.samples(), sound.name());
        short[] pcm = ProTrackerPlayer.render(module, RATE, 60);

        // Niveau moyen par tranche d'un dixieme de seconde, sur les vingt premieres.
        int window = RATE / 10;
        int slices = Math.min(200, pcm.length / window);
        assertTrue(slices > 20, "rendu trop court pour juger");

        double[] levels = new double[slices];
        double sum = 0;
        for (int i = 0; i < slices; i++) {
            long energy = 0;
            for (int k = i * window; k < (i + 1) * window; k++) {
                energy += (long) pcm[k] * pcm[k];
            }
            levels[i] = Math.sqrt((double) energy / window);
            sum += levels[i];
        }
        double mean = sum / slices;
        double deviation = 0;
        for (double level : levels) {
            deviation += (level - mean) * (level - mean);
        }
        deviation = Math.sqrt(deviation / slices);

        assertTrue(mean > 0, "rendu silencieux");
        // Du bruit constant donnerait un ecart relatif proche de zero.
        assertTrue(deviation / mean > 0.10,
                sound.name() + " : variation de " + (deviation / mean)
                        + ", le rendu n'a pas de relief");
    }

    @Test
    @DisplayName("Une frequence de sortie plus haute rend proportionnellement plus de points")
    void theSampleRateScalesTheRender() throws IOException {
        Sound sound = bank().modules().get(0);
        P61aModule module = P61aModule.read(sound.samples(), sound.name());

        short[] low = ProTrackerPlayer.render(module, 11025, 30);
        short[] high = ProTrackerPlayer.render(module, 22050, 30);

        double ratio = (double) high.length / low.length;
        assertTrue(ratio > 1.9 && ratio < 2.1,
                "rapport de " + ratio + " entre 22050 et 11025 Hz");
    }
}
