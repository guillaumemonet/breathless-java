package fov.breathless.gld.cli;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.sound.Sound;
import fov.breathless.gld.sound.SoundBank;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inventorie la banque sonore et exporte ses bruitages en WAV.
 *
 * <p>Les morceaux ProTracker ne sont pas exportes : ils sont ranges au format P61A, dont
 * le lecteur n'appartient pas aux auteurs du jeu. Leur presence est seulement signalee.
 */
final class SoundExport {

    private SoundExport() {
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

        System.out.printf("%-5s %-9s %8s %7s %4s %5s %s%n",
                "NOM", "TYPE", "OCTETS", "HZ", "VOL", "PRIO", "REMARQUE");
        System.out.println("-".repeat(70));

        int exported = 0;
        int modules = 0;
        for (Sound sound : bank.sounds().values()) {
            String kind = switch (sound.type()) {
                case Sound.TYPE_MODULE -> "module";
                case Sound.TYPE_GLOBAL -> "global";
                case Sound.TYPE_OBJECT -> "objet";
                case Sound.TYPE_RANDOM -> "tirage";
                default -> "?";
            };
            StringBuilder note = new StringBuilder();
            if (sound.type() == Sound.TYPE_RANDOM) {
                note.append("parmi ").append(String.join(", ", sound.alternatives()));
            } else if (sound.link() != null) {
                note.append("echantillon de ").append(sound.link());
            }
            if (sound.type() == Sound.TYPE_GLOBAL) {
                note.append(note.isEmpty() ? "" : ", ").append(globalName(sound.code()));
            }
            if (sound.loops()) {
                note.append(note.isEmpty() ? "" : ", ")
                        .append("boucle a ").append(sound.loopStart());
            }

            System.out.printf("%-5s %-9s %8d %7d %4d %5d %s%n",
                    sound.name(), kind, sound.samples().length, sound.sampleRate(),
                    sound.volume(), sound.priority(), note);

            if (sound.type() == Sound.TYPE_MODULE) {
                modules++;
                continue;
            }
            if (sound.samples().length == 0) {
                continue;
            }
            writeWav(outDir.resolve(sound.name() + ".wav"), sound);
            exported++;
        }

        System.out.println();
        System.out.println(exported + " bruitage(s) exporte(s) en WAV, "
                + modules + " morceau(x) ProTracker laisse(s) de cote");
    }

    private static String globalName(int code) {
        return switch (code) {
            case Sound.GLOBAL_DOOR_START -> "porte, depart et boucle";
            case Sound.GLOBAL_DOOR_STOP -> "porte, arret";
            case Sound.GLOBAL_SWITCH -> "interrupteur";
            case Sound.GLOBAL_TELEPORT -> "teleport";
            case Sound.GLOBAL_SCREAM -> "cri du joueur";
            case Sound.GLOBAL_STEP -> "pas";
            case Sound.GLOBAL_FAULT -> "arme muette";
            default -> "code " + code;
        };
    }

    /**
     * Ecrit un WAV mono huit bits.
     *
     * <p>Les echantillons Amiga sont <em>signes</em>, ceux d'un WAV huit bits ne le sont
     * pas : il faut donc decaler de 128.
     */
    private static void writeWav(Path file, Sound sound) throws IOException {
        byte[] samples = sound.samples();
        int rate = Math.max(1, sound.sampleRate());

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(36 + samples.length);
        header.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);              // taille du bloc de format
        header.putShort((short) 1);     // PCM
        header.putShort((short) 1);     // mono
        header.putInt(rate);
        header.putInt(rate);            // octets par seconde
        header.putShort((short) 1);     // alignement
        header.putShort((short) 8);     // bits par echantillon
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(samples.length);

        byte[] unsigned = new byte[samples.length];
        for (int i = 0; i < samples.length; i++) {
            unsigned[i] = (byte) (samples[i] + 128);
        }

        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            out.write(unsigned);
        }
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
