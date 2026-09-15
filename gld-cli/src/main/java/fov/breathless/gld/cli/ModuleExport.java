package fov.breathless.gld.cli;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.sound.P61aModule;
import fov.breathless.gld.sound.ProTrackerPlayer;
import fov.breathless.gld.sound.Sound;
import fov.breathless.gld.sound.SoundBank;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Convertit les morceaux P61A du jeu en modules ProTracker.
 *
 * <p>Le decodage est ecrit d'apres la description publique du format ; il n'emprunte rien
 * au lecteur d'origine.
 */
final class ModuleExport {

    /** Frequence du rendu audio de verification. */
    private static final int RENDER_RATE = 22050;

    /** Duree au-dela de laquelle le rendu s'arrete, quoi qu'il arrive. */
    private static final int MAX_SECONDS = 300;

    private ModuleExport() {
    }

    /** Ecrit un WAV mono seize bits. */
    private static void writeWav(Path file, short[] pcm) throws IOException {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer
                .allocate(44 + pcm.length * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm.length * 2);
        buffer.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(RENDER_RATE);
        buffer.putInt(RENDER_RATE * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(pcm.length * 2);
        for (short value : pcm) {
            buffer.putShort(value);
        }
        Files.write(file, buffer.array());
    }

    static void run(Path dataDir, Path outDir) throws IOException {
        SoundBank bank = null;
        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            if (SoundBank.SGLD.equals(gld.payloadId())) {
                bank = SoundBank.read(gld);
            }
        }
        if (bank == null) {
            throw new IOException("Aucune banque sonore (SGLD) dans " + dataDir);
        }
        Files.createDirectories(outDir);

        System.out.printf("%-5s %8s %8s %11s %9s %s%n",
                "NOM", "MOTIFS", "INSTR.", "POSITIONS", "OCTETS", "REMARQUE");
        System.out.println("-".repeat(70));

        int converted = 0;
        for (Sound sound : bank.modules()) {
            P61aModule module = P61aModule.read(sound.samples(), sound.name());
            byte[] mod = module.toProTracker("Breathless " + sound.name());

            StringBuilder note = new StringBuilder();
            if (module.hasDeltaSamples()) {
                note.append("echantillons en delta");
            }
            if (module.hasPackedSamples()) {
                note.append(note.isEmpty() ? "" : ", ").append("compression quatre bits");
            }

            Path file = outDir.resolve(sound.name() + ".mod");
            Files.write(file, mod);

            // Et un rendu audio, pour ecouter le resultat sans lecteur de modules.
            short[] pcm = ProTrackerPlayer.render(module, RENDER_RATE, MAX_SECONDS);
            writeWav(outDir.resolve(sound.name() + ".wav"), pcm);
            note.append(note.isEmpty() ? "" : ", ")
                    .append(String.format("%d s rendues", pcm.length / RENDER_RATE));
            System.out.printf("%-5s %8d %8d %11d %9d %s%n",
                    sound.name(), module.patternCount(), module.sampleCount(),
                    module.sequence().length, mod.length, note);
            converted++;
        }
        System.out.println();
        System.out.println(converted + " module(s) converti(s) en ProTracker");
    }

    private static List<Path> gldFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Repertoire introuvable : " + dir);
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }
}
