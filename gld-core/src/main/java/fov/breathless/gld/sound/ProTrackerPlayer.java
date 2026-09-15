package fov.breathless.gld.sound;

/**
 * Un lecteur ProTracker : il rend un morceau en echantillons audio.
 *
 * <p>Quatre voies, chacune lisant son instrument a la frequence que donne sa periode, le
 * tout melange en un flux mono de seize bits. Le tempo suit la convention ProTracker :
 * une ligne dure {@code speed} ticks, et un tick dure {@code 2,5 / bpm} seconde.
 *
 * <p>Les effets portes sont ceux qu'emploient les sept morceaux du jeu : arpege,
 * glissements de hauteur et de volume, portamento, vibrato, position de depart de
 * l'instrument, sauts de position et de motif, reglage de volume et de vitesse, et les
 * commandes etendues utiles. Les autres sont ignores plutot que mal rendus.
 *
 * <p>Le rendu est <em>hors ligne</em> : on demande le morceau entier, une fois, et on le
 * rejoue ensuite en boucle. Cela evite d'avoir a synchroniser un lecteur avec la carte
 * son, au prix de quelques megaoctets.
 */
public final class ProTrackerPlayer {

    /** Horloge Amiga en PAL : la periode s'y convertit en frequence. */
    private static final double PAL_CLOCK = 3_546_895.0;

    /** Tempo et vitesse par defaut d'un module. */
    private static final int DEFAULT_TEMPO = 125;
    private static final int DEFAULT_SPEED = 6;

    /** Volume maximal d'une voie. */
    private static final int MAX_VOLUME = 64;

    /** Bornes de periode admises par ProTracker. */
    private static final int MIN_PERIOD = 113;
    private static final int MAX_PERIOD = 856;

    /** Table de sinus du vibrato, un quart de periode. */
    private static final int[] SINE = {
        0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253,
        255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24,
    };

    /** Etat d'une voie. */
    private static final class Channel {
        int sample;             // 1..31, 0 si aucun
        int period;
        int volume;
        double position;
        double step;
        boolean playing;

        int portamentoTarget;
        int portamentoSpeed;
        int vibratoSpeed;
        int vibratoDepth;
        int vibratoPosition;
        int lastOffset;
        int retrigCount;
        int noteCutTick;
    }

    private final P61aModule module;
    private final int sampleRate;

    private ProTrackerPlayer(P61aModule module, int sampleRate) {
        this.module = module;
        this.sampleRate = sampleRate;
    }

    /**
     * Rend un morceau entier en seize bits mono.
     *
     * @param module     le morceau
     * @param sampleRate frequence de sortie, en hertz
     * @param maxSeconds duree au-dela de laquelle on s'arrete, quoi qu'il arrive
     * @return les echantillons, un {@code short} par point
     */
    public static short[] render(P61aModule module, int sampleRate, int maxSeconds) {
        return new ProTrackerPlayer(module, sampleRate).run(maxSeconds);
    }

    private short[] run(int maxSeconds) {
        Channel[] channels = new Channel[P61aModule.CHANNELS];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel();
            channels[i].period = 0;
        }

        int[] sequence = module.sequence();
        int tempo = DEFAULT_TEMPO;
        int speed = DEFAULT_SPEED;

        int limit = sampleRate * maxSeconds;
        short[] out = new short[limit];
        int written = 0;

        int position = 0;
        int row = 0;
        int patternDelay = 0;
        boolean[] visited = new boolean[sequence.length];

        while (position < sequence.length && written < limit) {
            if (row == 0) {
                // Une position deja jouee signe la fin : on s'arrete plutot que de tourner.
                if (visited[position]) {
                    break;
                }
                visited[position] = true;
            }
            int pattern = sequence[position];
            int nextPosition = position;
            int nextRow = row + 1;
            boolean jumped = false;

            // Un « pattern delay » rejoue la ligne sans relancer les notes.
            for (int repeat = 0; repeat <= patternDelay && written < limit; repeat++) {
                for (int tick = 0; tick < speed && written < limit; tick++) {
                    if (tick == 0 && repeat == 0) {
                        int[] control = startRow(channels, pattern, row);
                        if (control[0] >= 0) {
                            speed = control[0];
                        }
                        if (control[1] >= 0) {
                            tempo = control[1];
                        }
                        if (control[2] >= 0) {
                            nextPosition = control[2];
                            nextRow = 0;
                            jumped = true;
                        }
                        if (control[3] >= 0) {
                            nextPosition = position + 1;
                            nextRow = control[3];
                            jumped = true;
                        }
                        patternDelay = Math.max(patternDelay, control[4]);
                    } else {
                        applyTickEffects(channels, pattern, row, tick);
                    }
                    written += mix(out, written, limit, channels, samplesPerTick(tempo));
                }
            }
            patternDelay = 0;

            if (!jumped) {
                nextRow = row + 1;
                if (nextRow >= P61aModule.ROWS) {
                    nextRow = 0;
                    nextPosition = position + 1;
                }
            }
            position = nextPosition;
            row = nextRow;
        }

        short[] trimmed = new short[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    private int samplesPerTick(int tempo) {
        return (int) Math.round(sampleRate * 2.5 / tempo);
    }

    /**
     * Joue le premier tick d'une ligne : les notes partent, les effets s'arment.
     *
     * @return {@code {vitesse, tempo, sautDePosition, sautDeMotif, attenteDeMotif}},
     *         chaque valeur negative signifiant « rien a changer »
     */
    private int[] startRow(Channel[] channels, int pattern, int row) {
        int speed = -1;
        int tempo = -1;
        int positionJump = -1;
        int patternBreak = -1;
        int patternDelay = 0;

        for (int c = 0; c < channels.length; c++) {
            Channel channel = channels[c];
            int[] cell = module.cell(pattern, row, c);
            int period = cell[0];
            int sample = cell[1];
            int effect = cell[2];
            int command = (effect >> 8) & 0xF;
            int data = effect & 0xFF;

            if (sample > 0 && sample <= module.sampleCount()) {
                channel.sample = sample;
                channel.volume = module.sample(sample - 1).volume();
            }

            boolean tonePortamento = (command == 0x3 || command == 0x5);
            if (period > 0) {
                if (tonePortamento) {
                    channel.portamentoTarget = period;
                } else {
                    channel.period = period;
                    channel.position = (command == 0x9) ? channel.lastOffset : 0;
                    if (command == 0x9 && data != 0) {
                        channel.lastOffset = data * 256;
                        channel.position = channel.lastOffset;
                    }
                    channel.playing = channel.sample > 0;
                    channel.vibratoPosition = 0;
                }
            }

            channel.retrigCount = 0;
            channel.noteCutTick = -1;

            switch (command) {
                case 0x3 -> {
                    if (data != 0) {
                        channel.portamentoSpeed = data;
                    }
                }
                case 0x4 -> {
                    if ((data >> 4) != 0) {
                        channel.vibratoSpeed = data >> 4;
                    }
                    if ((data & 0xF) != 0) {
                        channel.vibratoDepth = data & 0xF;
                    }
                }
                case 0x9 -> {
                    if (data != 0) {
                        channel.lastOffset = data * 256;
                    }
                }
                case 0xB -> positionJump = data;
                case 0xC -> channel.volume = Math.min(MAX_VOLUME, data);
                case 0xD -> patternBreak = (data >> 4) * 10 + (data & 0xF);
                case 0xE -> {
                    int sub = data >> 4;
                    int value = data & 0xF;
                    switch (sub) {
                        case 0x1 -> channel.period = clampPeriod(channel.period - value);
                        case 0x2 -> channel.period = clampPeriod(channel.period + value);
                        case 0x9 -> channel.retrigCount = value;
                        case 0xA -> channel.volume = Math.min(MAX_VOLUME, channel.volume + value);
                        case 0xB -> channel.volume = Math.max(0, channel.volume - value);
                        case 0xC -> channel.noteCutTick = value;
                        case 0xE -> patternDelay = Math.max(patternDelay, value);
                        default -> {
                            // Les autres commandes etendues ne servent pas ici.
                        }
                    }
                }
                case 0xF -> {
                    if (data == 0) {
                        speed = 1;
                    } else if (data < 32) {
                        speed = data;
                    } else {
                        tempo = data;
                    }
                }
                default -> {
                    // Les effets par tick sont traites plus bas.
                }
            }
            refreshStep(channel, channel.period);
        }
        return new int[]{speed, tempo, positionJump, patternBreak, patternDelay};
    }

    /** Applique les effets qui agissent a chaque tick au-dela du premier. */
    private void applyTickEffects(Channel[] channels, int pattern, int row, int tick) {
        for (int c = 0; c < channels.length; c++) {
            Channel channel = channels[c];
            int effect = module.cell(pattern, row, c)[2];
            int command = (effect >> 8) & 0xF;
            int data = effect & 0xFF;
            int period = channel.period;

            switch (command) {
                case 0x0 -> {
                    if (data != 0) {
                        // Arpege : la note, puis deux transpositions, un tick chacune.
                        int step = switch (tick % 3) {
                            case 1 -> data >> 4;
                            case 2 -> data & 0xF;
                            default -> 0;
                        };
                        period = transpose(channel.period, step);
                    }
                }
                case 0x1 -> {
                    channel.period = clampPeriod(channel.period - data);
                    period = channel.period;
                }
                case 0x2 -> {
                    channel.period = clampPeriod(channel.period + data);
                    period = channel.period;
                }
                case 0x3 -> {
                    slideToNote(channel);
                    period = channel.period;
                }
                case 0x4 -> period = vibrato(channel);
                case 0x5 -> {
                    slideToNote(channel);
                    period = channel.period;
                    volumeSlide(channel, data);
                }
                case 0x6 -> {
                    period = vibrato(channel);
                    volumeSlide(channel, data);
                }
                case 0xA -> volumeSlide(channel, data);
                case 0xE -> {
                    int sub = data >> 4;
                    int value = data & 0xF;
                    if (sub == 0x9 && value > 0 && tick % value == 0) {
                        channel.position = 0;
                    } else if (sub == 0xC && tick == channel.noteCutTick) {
                        channel.volume = 0;
                    }
                }
                default -> {
                    // Rien a faire a ce tick.
                }
            }
            refreshStep(channel, period);
        }
    }

    private void slideToNote(Channel channel) {
        if (channel.portamentoTarget == 0) {
            return;
        }
        if (channel.period < channel.portamentoTarget) {
            channel.period = Math.min(channel.portamentoTarget,
                    channel.period + channel.portamentoSpeed);
        } else if (channel.period > channel.portamentoTarget) {
            channel.period = Math.max(channel.portamentoTarget,
                    channel.period - channel.portamentoSpeed);
        }
    }

    /** Le vibrato module la periode sans la modifier durablement. */
    private int vibrato(Channel channel) {
        int index = (channel.vibratoPosition >> 2) & 0x1F;
        int amplitude = SINE[index] * channel.vibratoDepth / 128;
        int period = (channel.vibratoPosition < 128)
                ? channel.period + amplitude : channel.period - amplitude;
        channel.vibratoPosition = (channel.vibratoPosition + channel.vibratoSpeed * 4) & 0xFF;
        return clampPeriod(period);
    }

    private static void volumeSlide(Channel channel, int data) {
        int up = data >> 4;
        int down = data & 0xF;
        if (up > 0) {
            channel.volume = Math.min(MAX_VOLUME, channel.volume + up);
        } else if (down > 0) {
            channel.volume = Math.max(0, channel.volume - down);
        }
    }

    /** Transpose d'un nombre de demi-tons, en restant dans la table des periodes. */
    private static int transpose(int period, int semitones) {
        if (semitones == 0 || period <= 0) {
            return period;
        }
        return clampPeriod((int) Math.round(period / Math.pow(2.0, semitones / 12.0)));
    }

    private static int clampPeriod(int period) {
        return Math.max(MIN_PERIOD, Math.min(MAX_PERIOD, period));
    }

    private void refreshStep(Channel channel, int period) {
        channel.step = (period <= 0) ? 0 : (PAL_CLOCK / period) / sampleRate;
    }

    /** Melange un tick de son et renvoie le nombre de points ecrits. */
    private int mix(short[] out, int at, int limit, Channel[] channels, int count) {
        int written = 0;
        for (int i = 0; i < count && at + written < limit; i++) {
            int total = 0;
            for (Channel channel : channels) {
                if (!channel.playing || channel.sample <= 0 || channel.step <= 0) {
                    continue;
                }
                byte[] pcm = module.sampleData(channel.sample - 1);
                P61aModule.SampleInfo info = module.sample(channel.sample - 1);
                if (pcm.length == 0) {
                    continue;
                }

                int index = (int) channel.position;
                if (index >= pcm.length) {
                    if (info.loops() && info.lengthWords() > info.repeatStartWords()) {
                        int loopStart = info.repeatStartWords() * 2;
                        int loopLength = pcm.length - loopStart;
                        if (loopLength > 0) {
                            channel.position = loopStart + (channel.position - pcm.length)
                                    % loopLength;
                            index = (int) channel.position;
                        } else {
                            channel.playing = false;
                            continue;
                        }
                    } else {
                        channel.playing = false;
                        continue;
                    }
                }
                total += pcm[index] * channel.volume;
                channel.position += channel.step;
            }
            // Quatre voies a pleine puissance tiennent dans seize bits avec cette echelle.
            int value = total * 2;
            out[at + written] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, value));
            written++;
        }
        return written;
    }
}
