package fov.breathless.gld.sound;

import fov.breathless.gld.GldFormatException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Un morceau au format P61A, et sa conversion en ProTracker.
 *
 * <p>The Player 6.1 est un empaqueteur de modules ProTracker : memes instruments, memes
 * effets, mais les motifs sont ranges piste par piste et comprimes par repetition. Les
 * sept morceaux de Breathless sont dans ce format.
 *
 * <p>Ce decodeur n'emprunte rien au lecteur d'origine, qui appartient a Jarno Paananen :
 * il est ecrit d'apres la description publique du format, celle de l'outil
 * <a href="https://github.com/howprice/p61aconv">p61aconv</a> de Huw Price, sous licence
 * MIT, elle-meme adossee au guide Player61A et au convertisseur d'origine.
 *
 * <p>Disposition du fichier :
 *
 * <pre>
 *   ['P61A']                signature facultative
 *   WORD  offset des donnees d'echantillons, depuis le debut
 *   BYTE  nombre de motifs
 *   BYTE  nombre d'instruments ; bit 7 : echantillons en delta huit bits
 *                                bit 6 : echantillons empaquetes en quatre bits
 *   [LONG taille decompressee, seulement si le bit 6 est mis]
 *   par instrument, six octets : longueur en mots, finetune et compression,
 *                                volume, depart de boucle en mots (0xffff : pas de boucle)
 *   par motif, quatre WORD : offset des donnees de chaque piste
 *   sequence : un octet par position, terminee par 0xff
 *   donnees des motifs, puis donnees des echantillons
 * </pre>
 */
public final class P61aModule {

    /** Voies d'un module ProTracker. */
    public static final int CHANNELS = 4;

    /** Lignes d'un motif. */
    public static final int ROWS = 64;

    /** Instruments d'un module ProTracker. */
    private static final int MAX_SAMPLES = 31;

    /**
     * Periodes ProTracker sans finetune, cinq octaves.
     *
     * <p>La conversion n'emploie que cette table : {@code P61AToMod} calcule ses periodes
     * avec un finetune nul, celui-ci restant porte par l'instrument.
     */
    private static final int[] PERIODS = {
        1712, 1616, 1525, 1440, 1357, 1281, 1209, 1141, 1077, 1017, 961, 907,
        856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
        428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226,
        214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113,
        107, 101, 95, 90, 85, 80, 76, 71, 67, 64, 60, 57,
    };

    /** Aucune note sur cette ligne. */
    private static final int NO_NOTE = -1;

    /** Un instrument du module. */
    public record SampleInfo(int lengthWords, int fineTune, int volume, int repeatStartWords,
                             boolean packed) {

        /** L'instrument boucle-t-il ? */
        public boolean loops() {
            return repeatStartWords != 0xFFFF;
        }
    }

    private final int patternCount;
    private final int sampleCount;
    private final boolean deltaSamples;
    private final boolean packedSamples;
    private final SampleInfo[] samples;
    private final int[] sequence;
    /** Cellules decodees : [motif][ligne][voie] = {periode, instrument, effet}. */
    private final int[][][][] patterns;
    private final byte[][] sampleData;

    private P61aModule(int patternCount, int sampleCount, boolean deltaSamples,
                       boolean packedSamples, SampleInfo[] samples, int[] sequence,
                       int[][][][] patterns, byte[][] sampleData) {
        this.patternCount = patternCount;
        this.sampleCount = sampleCount;
        this.deltaSamples = deltaSamples;
        this.packedSamples = packedSamples;
        this.samples = samples;
        this.sequence = sequence;
        this.patterns = patterns;
        this.sampleData = sampleData;
    }

    public int patternCount() {
        return patternCount;
    }

    public int sampleCount() {
        return sampleCount;
    }

    /** Les positions de la sequence, chacune designant un motif. */
    public int[] sequence() {
        return sequence.clone();
    }

    public SampleInfo sample(int index) {
        return samples[index];
    }

    /** Les echantillons sont-ils ranges en differences plutot qu'en valeurs ? */
    public boolean hasDeltaSamples() {
        return deltaSamples;
    }

    /** Les echantillons sont-ils comprimes sur quatre bits ? Cette compression est destructive. */
    public boolean hasPackedSamples() {
        return packedSamples;
    }

    /**
     * Une cellule decodee.
     *
     * @return trois entiers : periode, numero d'instrument, effet ProTracker
     */
    public int[] cell(int pattern, int row, int channel) {
        return patterns[pattern][row][channel].clone();
    }

    /** Echantillon d'un instrument, en huit bits signes. */
    public byte[] sampleData(int index) {
        return sampleData[index];
    }

    /** Lit un module. */
    public static P61aModule read(byte[] data, String what) {
        int at = 0;
        if (data.length >= 4 && data[0] == 'P' && data[1] == '6' && data[2] == '1'
                && data[3] == 'A') {
            at = 4;   // la signature est facultative
        }
        if (data.length < at + 4) {
            throw new GldFormatException(what + " : trop court pour un module P61A");
        }

        int sampleDataOffset = u16(data, at);
        int patternCount = data[at + 2] & 0xFF;
        int rawSampleCount = data[at + 3] & 0xFF;
        at += 4;

        boolean packedSamples = (rawSampleCount & 0x40) != 0;
        boolean deltaSamples = (rawSampleCount & 0x80) != 0;
        int sampleCount = rawSampleCount & 0x3F;

        if (packedSamples) {
            at += 4;   // taille decompressee, dont le decodeur n'a pas besoin
        }
        if (patternCount == 0 || patternCount > 128 || sampleCount == 0
                || sampleCount > MAX_SAMPLES) {
            throw new GldFormatException(what + " : " + patternCount + " motif(s) et "
                    + sampleCount + " instrument(s), valeurs aberrantes");
        }

        SampleInfo[] samples = new SampleInfo[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int lengthWords = u16(data, at);
            int fineTuneAndCompression = data[at + 2] & 0xFF;
            int volume = data[at + 3] & 0xFF;
            int repeatStart = u16(data, at + 4);
            at += 6;
            samples[i] = new SampleInfo(lengthWords, signedNibble(fineTuneAndCompression & 0xF),
                    volume, repeatStart, (fineTuneAndCompression & 0x80) != 0);
        }

        int[][] trackOffsets = new int[patternCount][CHANNELS];
        for (int pattern = 0; pattern < patternCount; pattern++) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                trackOffsets[pattern][channel] = u16(data, at);
                at += 2;
            }
        }

        // La sequence se termine par 0xff.
        int start = at;
        while (at < data.length && (data[at] & 0xFF) != 0xFF) {
            at++;
        }
        if (at >= data.length) {
            throw new GldFormatException(what + " : sequence sans terminateur");
        }
        int[] sequence = new int[at - start];
        for (int i = 0; i < sequence.length; i++) {
            sequence[i] = data[start + i] & 0xFF;
        }
        at++;

        int patternDataStart = at;
        if (sampleDataOffset < patternDataStart || sampleDataOffset > data.length) {
            throw new GldFormatException(what + " : les echantillons commencent a "
                    + sampleDataOffset + ", hors des donnees de motifs");
        }
        byte[] patternData = new byte[sampleDataOffset - patternDataStart];
        System.arraycopy(data, patternDataStart, patternData, 0, patternData.length);

        int[][][][] patterns = decodePatterns(patternData, trackOffsets, patternCount, what);

        byte[][] sampleData = readSamples(data, sampleDataOffset, samples, deltaSamples, what);

        return new P61aModule(patternCount, sampleCount, deltaSamples, packedSamples,
                samples, sequence, patterns, sampleData);
    }

    /** Etat d'une piste pendant le decodage : c'est lui qui porte les repetitions. */
    private static final class Track {
        int at;
        int sampleIndex;
        int noteIndex = NO_NOTE;
        int effect;
        int emptyRows;
        int repeatRows;
        int repeatChunks;
        int afterRepeat;
    }

    private static int[][][][] decodePatterns(byte[] data, int[][] trackOffsets,
                                              int patternCount, String what) {
        int[][][][] patterns = new int[patternCount][ROWS][CHANNELS][];

        for (int pattern = 0; pattern < patternCount; pattern++) {
            Track[] tracks = new Track[CHANNELS];
            for (int channel = 0; channel < CHANNELS; channel++) {
                tracks[channel] = new Track();
                tracks[channel].at = trackOffsets[pattern][channel];
            }

            int maxRow = ROWS;
            int row = 0;
            for (; row < maxRow; row++) {
                for (int channel = 0; channel < CHANNELS; channel++) {
                    Track track = tracks[channel];
                    maxRow = stepRow(data, track, maxRow, row, what);
                    patterns[pattern][row][channel] = new int[]{
                        (track.noteIndex == NO_NOTE) ? 0 : PERIODS[track.noteIndex],
                        track.sampleIndex,
                        track.effect,
                    };
                }
            }
            // Un « pattern break » raccourcit le motif : le reste est vide.
            for (; row < ROWS; row++) {
                for (int channel = 0; channel < CHANNELS; channel++) {
                    patterns[pattern][row][channel] = new int[]{0, 0, 0};
                }
            }
        }
        return patterns;
    }

    /** Avance une piste d'une ligne, en traversant autant de morceaux que necessaire. */
    private static int stepRow(byte[] data, Track track, int maxRow, int row, String what) {
        for (int guard = 0; guard < 1024; guard++) {
            int[] result = processChunk(data, track, maxRow, row, what);
            if (result[0] != 0) {
                return result[1];
            }
            maxRow = result[1];
        }
        throw new GldFormatException(what + " : morceau de motif sans fin a la ligne " + row);
    }

    /**
     * Traite un morceau du flux.
     *
     * @return {@code {avanceDUneLigne, maxRow}}
     */
    private static int[] processChunk(byte[] data, Track track, int maxRow, int row,
                                      String what) {
        if (track.emptyRows > 0) {
            track.noteIndex = NO_NOTE;
            track.sampleIndex = 0;
            track.effect = 0;
            track.emptyRows--;
            return new int[]{1, maxRow};
        }
        if (track.repeatRows > 0) {
            // On laisse note, instrument et effet en place : c'est tout l'interet.
            track.repeatRows--;
            return new int[]{1, maxRow};
        }

        if (track.at < 0 || track.at >= data.length) {
            // La piste n'a plus rien a dire : le reste du motif est silencieux. Les
            // donnees sont completees a la limite du mot, et une piste peut s'arreter
            // bien avant la soixante-quatrieme ligne.
            track.noteIndex = NO_NOTE;
            track.sampleIndex = 0;
            track.effect = 0;
            return new int[]{1, maxRow};
        }
        // Les deux ou trois octets d'un morceau doivent tenir dans ce qui reste.
        if (needsBytes(data, track.at) > data.length - track.at) {
            track.at = data.length;
            track.noteIndex = NO_NOTE;
            track.sampleIndex = 0;
            track.effect = 0;
            return new int[]{1, maxRow};
        }
        int c0 = data[track.at++] & 0xFF;
        int note = 0;
        int sample = 0;
        int effect = 0;

        if ((c0 & 0x7F) == 0x7F) {
            // Cellule vide : note, instrument et effet restent nuls. Avec compression,
            // elle ne sert que de porteuse pour les octets qui suivent.
            note = 0;
        } else if ((c0 & 0x78) == 0x70) {
            // Note et instrument : o1110nnn nnnsssss
            int c1 = data[track.at++] & 0xFF;
            note = ((c0 & 0x07) << 3) | (c1 >> 5);
            sample = c1 & 0x1F;
        } else if ((c0 & 0x70) == 0x60) {
            // Effet seul : o110cccc bbbbbbbb
            int c1 = data[track.at++] & 0xFF;
            effect = toProTracker(((c0 & 0x0F) << 8) | c1);
        } else {
            // Note, instrument et effet : onnnnnns sssscccc bbbbbbbb
            int c1 = data[track.at++] & 0xFF;
            int c2 = data[track.at++] & 0xFF;
            note = (c0 >> 1) & 0x3F;
            sample = ((c0 & 1) << 4) | ((c1 >> 4) & 0x0F);
            effect = toProTracker(((c1 & 0x0F) << 8) | c2);
        }

        // Une note P61A est une valeur sur trois octaves comptee a partir de un : on la
        // ramene a l'index sur cinq octaves de la table des periodes.
        track.noteIndex = (note >= 1) ? (note - 1 + 12) : NO_NOTE;
        track.sampleIndex = sample;
        track.effect = effect;

        // Un « pattern break » arrete le motif : le flux ne contient plus rien au-dela.
        if ((track.effect & 0xF00) == 0xD00) {
            return new int[]{1, row};
        }

        if ((c0 & 0x80) != 0) {
            int compression = data[track.at++] & 0xFF;
            int type = compression & 0xC0;
            int count = compression & 0x3F;

            if (type == 0x00) {
                track.emptyRows = count;
            } else if (type == 0x80) {
                track.repeatRows = count;
            } else {
                // Saut en arriere, pour rejouer des morceaux deja lus.
                track.repeatChunks = count + 1;
                int distance;
                if (type == 0x40) {
                    distance = data[track.at++] & 0xFF;
                } else {
                    int high = data[track.at++] & 0xFF;
                    int low = data[track.at++] & 0xFF;
                    distance = (high << 8) | low;
                }
                track.afterRepeat = track.at;
                track.at -= distance;
                // Un saut suit toujours une cellule purement porteuse : aucune ligne n'a
                // ete produite, il faut relire.
                return new int[]{0, maxRow};
            }
        }

        if (track.repeatChunks > 0) {
            track.repeatChunks--;
            if (track.repeatChunks == 0) {
                track.at = track.afterRepeat;
            }
        }
        return new int[]{1, maxRow};
    }

    /** Nombre d'octets de commande qu'annonce le premier octet d'un morceau. */
    private static int needsBytes(byte[] data, int at) {
        int c0 = data[at] & 0xFF;
        int control;
        if ((c0 & 0x7F) == 0x7F) {
            control = 1;
        } else if ((c0 & 0x78) == 0x70 || (c0 & 0x70) == 0x60) {
            control = 2;
        } else {
            control = 3;
        }
        // Les octets de compression eventuels sont lus apres coup ; on ne compte ici que
        // le minimum sans lequel le morceau n'a pas de sens.
        return control;
    }

    /**
     * Ramene un effet P61A a sa forme ProTracker.
     *
     * <p>Deux familles seulement changent : la commande 8, inutilisee en ProTracker, y
     * designe l'arpege 0xy ; et les trois glissements de volume rangent leur valeur dans
     * le quartet bas quand elle est negative, la ou ProTracker attend le quartet haut.
     */
    private static int toProTracker(int effect) {
        int command = (effect >> 8) & 0xF;
        return switch (command) {
            case 0x0 -> 0x000;                       // ni arpege ni rien
            case 0x8 -> effect & 0x0FF;              // 8xy devient l'arpege 0xy
            case 0x5, 0x6, 0xA -> volumeSlide(effect);
            default -> effect;
        };
    }

    /** Un glissement de volume negatif se lit dans le quartet bas. */
    private static int volumeSlide(int effect) {
        byte value = (byte) effect;
        if (value >= 0) {
            return effect;
        }
        int up = (-value) << 4;
        return (effect & 0xF00) | (up & 0xF0);
    }

    private static byte[][] readSamples(byte[] data, int offset, SampleInfo[] samples,
                                        boolean delta, String what) {
        byte[][] out = new byte[samples.length][];
        int at = offset;
        for (int i = 0; i < samples.length; i++) {
            int bytes = samples[i].lengthWords() * 2;
            if (samples[i].packed()) {
                // Compression quatre bits : non portee, l'instrument reste muet.
                out[i] = new byte[0];
                at += samples[i].lengthWords();
                continue;
            }
            if (at + bytes > data.length) {
                throw new GldFormatException(what + " : instrument " + (i + 1)
                        + " annonce " + bytes + " octets au-dela du fichier");
            }
            byte[] pcm = new byte[bytes];
            System.arraycopy(data, at, pcm, 0, bytes);
            if (delta) {
                byte previous = 0;
                for (int b = 0; b < bytes; b++) {
                    previous = (byte) (previous - pcm[b]);
                    pcm[b] = previous;
                }
            }
            out[i] = pcm;
            at += bytes;
        }
        return out;
    }

    /**
     * Ecrit le module au format ProTracker, quatre voies, trente et un instruments.
     *
     * @return le contenu d'un fichier {@code .mod}
     */
    public byte[] toProTracker(String title) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] name = new byte[20];
        byte[] source = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(source, 0, name, 0, Math.min(source.length, name.length - 1));
        out.writeBytes(name);

        for (int i = 0; i < MAX_SAMPLES; i++) {
            out.writeBytes(new byte[22]);                 // nom de l'instrument
            if (i < sampleCount) {
                SampleInfo info = samples[i];
                writeWord(out, info.lengthWords());
                out.write(info.fineTune() & 0x0F);
                out.write(Math.min(64, info.volume()));
                if (info.loops()) {
                    writeWord(out, info.repeatStartWords());
                    writeWord(out, info.lengthWords() - info.repeatStartWords());
                } else {
                    writeWord(out, 0);
                    // Un instrument boucle des que cette longueur depasse un : on ecrit un.
                    writeWord(out, 1);
                }
            } else {
                writeWord(out, 0);
                out.write(0);
                out.write(0);
                writeWord(out, 0);
                writeWord(out, 1);
            }
        }

        out.write(sequence.length);
        out.write(127);                                   // position de reprise
        for (int i = 0; i < 128; i++) {
            out.write(i < sequence.length ? sequence[i] : 0);
        }
        out.writeBytes("M.K.".getBytes(StandardCharsets.US_ASCII));

        for (int pattern = 0; pattern < patternCount; pattern++) {
            for (int row = 0; row < ROWS; row++) {
                for (int channel = 0; channel < CHANNELS; channel++) {
                    int[] cell = patterns[pattern][row][channel];
                    int period = cell[0];
                    int sample = cell[1];
                    int effect = cell[2];
                    out.write((sample & 0xF0) | ((period >> 8) & 0x0F));
                    out.write(period & 0xFF);
                    out.write(((sample & 0x0F) << 4) | ((effect >> 8) & 0x0F));
                    out.write(effect & 0xFF);
                }
            }
        }

        for (int i = 0; i < sampleCount; i++) {
            byte[] pcm = sampleData[i];
            out.writeBytes(pcm);
            // Un instrument dont la compression n'est pas portee garde sa place, muet.
            int declared = samples[i].lengthWords() * 2;
            for (int pad = pcm.length; pad < declared; pad++) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    private static void writeWord(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /** Le finetune est un quartet signe. */
    private static int signedNibble(int nibble) {
        return (nibble > 7) ? nibble - 16 : nibble;
    }
}
