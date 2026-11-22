package fov.breathless.game;

import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.scene.Node;
import fov.breathless.engine.SoundPlayer;
import fov.breathless.gld.sound.P61aModule;
import fov.breathless.gld.sound.ProTrackerPlayer;
import fov.breathless.gld.sound.Sound;
import fov.breathless.gld.sound.SoundBank;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Joue les bruitages du jeu par jMonkeyEngine.
 *
 * <p>Les echantillons d'origine sont du huit bits signe a une frequence donnee par la
 * periode Amiga. OpenAL veut du huit bits <em>non</em> signe : il suffit de decaler de
 * cent vingt-huit, sans rien perdre.
 *
 * <p>Chaque echantillon est prepare une fois puis rejoue. Les sons ne sont pas places en
 * stereo : l'original les joue sur quatre canaux, et ne tient compte de la position d'un
 * objet que pour le volume et la priorite.
 *
 * <p>Un son qui boucle reprend tout l'echantillon, la ou l'original ne reprend que la
 * partie qui suit le point de boucle. Seul le lance-flammes boucle dans les donnees.
 */
final class JmeSoundPlayer implements SoundPlayer {

    /** Volume maximal d'un son dans les donnees. */
    private static final float MAX_VOLUME = 64f;

    /** Frequence a laquelle la musique est rendue. */
    private static final int MUSIC_RATE = 22050;

    /** Duree au-dela de laquelle le rendu d'un morceau s'arrete. */
    private static final int MAX_MUSIC_SECONDS = 300;

    /** La musique reste en retrait des bruitages. */
    private static final float MUSIC_VOLUME = 0.5f;

    private final SoundBank bank;
    private final Node root;

    /**
     * Ce qui fait executer une tache sur le fil de rendu.
     *
     * <p>jMonkeyEngine n'accepte de creer et de lancer un son que la : le rendu d'un
     * morceau se fait a l'ecart, mais sa mise en place revient ici.
     */
    private final java.util.function.Consumer<Runnable> onRenderThread;
    private final Map<String, AudioBuffer> buffers = new HashMap<>();
    private final Random random = new Random();
    private AudioNode music;
    private volatile String pendingMusic;
    private boolean musicEnabled = true;
    private boolean soundEnabled = true;
    private float musicLevel = MUSIC_VOLUME;
    private String lastMusic;

    /** Coupe ou rend la musique. */
    void setMusicEnabled(boolean enabled) {
        if (musicEnabled == enabled) {
            return;
        }
        musicEnabled = enabled;
        if (!enabled) {
            stopMusic();
        } else if (lastMusic != null) {
            playMusic(lastMusic);
        }
    }

    /** Coupe ou rend les bruitages. */
    void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
    }

    /** Volume de la musique, de 1 a 4 comme dans l'original. */
    void setMusicVolume(int volume) {
        musicLevel = MUSIC_VOLUME * Math.max(1, Math.min(4, volume)) / 4f;
        synchronized (this) {
            if (music != null) {
                music.setVolume(musicLevel);
            }
        }
    }

    JmeSoundPlayer(SoundBank bank, Node root,
                   java.util.function.Consumer<Runnable> onRenderThread) {
        this.bank = bank;
        this.root = root;
        this.onRenderThread = onRenderThread;
    }

    /**
     * Un des quatre canaux de Paula. {@code AUDx_sound}, {@code AUDx_priority},
     * {@code AUDx_code}.
     */
    private static final class Channel {
        AudioNode node;
        Sound sound;
        int priority;
        int code;

        boolean busy() {
            return node != null && node.getStatus() == com.jme3.audio.AudioSource.Status.Playing;
        }
    }

    private final Channel[] channels = {new Channel(), new Channel(), new Channel(), new Channel()};

    /** Position du joueur, d'ou se mesure la distance des sons d'objets. */
    private int listenerX;
    private int listenerZ;

    /** {@code ChgPriTable} : ce que la distance retire a la priorite, selon le volume. */
    private static final int[] PRIORITY_LOSS = {
        7,
        6, 5, 4, 4, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    @Override
    public void setListener(int x, int z) {
        listenerX = x;
        listenerZ = z;
    }

    @Override
    public void play(String name) {
        play(name, 0, 0);
    }

    @Override
    public void playAt(String name, int x, int z) {
        long dx = (short) (x - listenerX);
        long dz = (short) (z - listenerZ);
        play(name, dx * dx + dz * dz, 0);
    }

    @Override
    public void playGlobal(int code) {
        playGlobalOn(code, 0);
    }

    @Override
    public void playGlobalOn(int code, int channelCode) {
        Sound sound = bank.global(code);
        if (sound != null) {
            play(sound.name(), 0, channelCode);
        }
    }

    /**
     * Le serveur de sons ({@code SoundFXServer}) et {@code AllocSoundFX}.
     *
     * <p>Quatre canaux, comme la puce Paula. Le volume baisse avec la distance — plein
     * jusqu'a une distance au carre de 32768, puis {@code 64 - d²/32768}, et le son est
     * coupe au-dela de 1448 unites —, et la priorite avec lui. Un son prend un canal libre
     * parmi ceux que son masque autorise, sinon chasse le premier dont la priorite n'est
     * pas superieure a la sienne, sinon ne joue pas. Un son marque « seul » (bit 7 du
     * masque) ne se relance pas tant qu'il joue deja. Un code non nul — le trigger d'une
     * porte — ramene le son sur le canal qui porte deja ce code : le choc de fin de
     * course coupe ainsi le roulement.
     */
    private void play(String name, long distanceSquared, int code) {
        if (!soundEnabled) {
            return;
        }
        Sound sound = bank.get(name);
        if (sound == null) {
            return;
        }
        if (sound.type() == Sound.TYPE_RANDOM) {
            String[] choices = sound.alternatives();
            if (choices.length == 0) {
                return;
            }
            play(choices[random.nextInt(choices.length)], distanceSquared, code);
            return;
        }
        if (sound.type() == Sound.TYPE_MODULE) {
            return;
        }

        int mask = sound.channelMask();
        if ((mask & 0x80) != 0) {
            for (Channel channel : channels) {
                if (channel.busy() && channel.sound == sound) {
                    return;
                }
            }
        }

        int level;
        if (distanceSquared <= 32768) {
            level = 64;
        } else {
            int far = (int) Math.min(64, distanceSquared >> 15);
            if (far >= 64) {
                return;
            }
            level = 64 - far;
        }
        int priority = (byte) (sound.priority() - PRIORITY_LOSS[level]);
        int volume = (level * sound.volume()) >> 6;
        if (volume <= 0) {
            return;
        }

        int allowed = mask & 0x0F;
        if (code != 0) {
            for (int i = 0; i < channels.length; i++) {
                if (channels[i].code == code) {
                    allowed = 1 << i;
                    break;
                }
            }
        }

        int chosen = -1;
        for (int i = 0; i < channels.length && chosen < 0; i++) {
            if ((allowed & (1 << i)) != 0 && !channels[i].busy()) {
                chosen = i;
            }
        }
        for (int i = 0; i < channels.length && chosen < 0; i++) {
            if ((allowed & (1 << i)) != 0 && priority >= channels[i].priority) {
                chosen = i;
            }
        }
        if (chosen < 0) {
            return;
        }

        AudioBuffer data = buffer(sound);
        if (data == null) {
            return;
        }
        Channel channel = channels[chosen];
        if (channel.node != null) {
            channel.node.stop();
            root.detachChild(channel.node);
        }
        AudioNode node = new AudioNode(data, new AudioKey(sound.name()));
        node.setPositional(false);
        node.setVolume(volume / MAX_VOLUME);
        node.setLooping(sound.loops());
        root.attachChild(node);
        node.play();

        channel.node = node;
        channel.sound = sound;
        channel.priority = priority;
        channel.code = code;
    }

    /**
     * Lance le morceau du niveau.
     *
     * <p>Le rendu d'un morceau entier prend une fraction de seconde : il se fait donc a
     * l'ecart, et la lecture demarre quand il est pret. Changer de niveau entre-temps
     * annule simplement le rendu en cours.
     */
    @Override
    public void playMusic(String name) {
        stopMusic();
        lastMusic = name;
        if (!musicEnabled) {
            return;
        }
        Sound sound = bank.get(name);
        if (sound == null || sound.type() != Sound.TYPE_MODULE) {
            return;
        }
        String wanted = name;
        pendingMusic = wanted;

        Thread worker = new Thread(() -> {
            short[] pcm;
            try {
                P61aModule module = P61aModule.read(sound.samples(), wanted);
                pcm = ProTrackerPlayer.render(module, MUSIC_RATE, MAX_MUSIC_SECONDS);
            } catch (RuntimeException failure) {
                System.out.println("Morceau " + wanted + " illisible : " + failure.getMessage());
                return;
            }
            if (pcm.length == 0) {
                return;
            }
            // Le son ne se met en place que sur le fil de rendu : on y repasse la main.
            short[] rendered = pcm;
            onRenderThread.accept(() -> {
                synchronized (this) {
                    if (!wanted.equals(pendingMusic)) {
                        return;   // on a change de niveau entre-temps
                    }
                    music = musicNode(wanted, rendered);
                    music.play();
                }
            });
        }, "musique-" + name);
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void stopMusic() {
        pendingMusic = null;
        if (music != null) {
            music.stop();
            root.detachChild(music);
            music = null;
        }
    }

    /** Prepare un noeud audio a partir d'un rendu en seize bits. */
    private AudioNode musicNode(String name, short[] pcm) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(pcm.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short value : pcm) {
            buffer.putShort(value);
        }
        buffer.flip();

        AudioBuffer data = new AudioBuffer();
        data.setupFormat(1, 16, MUSIC_RATE);
        data.updateData(buffer);

        AudioNode node = new AudioNode(data, new AudioKey(name));
        node.setPositional(false);
        node.setLooping(true);
        node.setVolume(musicLevel);
        root.attachChild(node);
        return node;
    }

    /**
     * {@code StopSoundFX} : arrete les canaux qui jouent ce son. C'est ainsi que le
     * lance-flammes, qui boucle, se tait.
     */
    @Override
    public void stop(String name) {
        Sound sound = bank.get(name);
        if (sound == null) {
            return;
        }
        for (Channel channel : channels) {
            if (channel.node != null && channel.sound == sound) {
                channel.node.stop();
                root.detachChild(channel.node);
                channel.node = null;
                channel.sound = null;
                channel.code = 0;
            }
        }
    }

    /**
     * Prepare l'echantillon a sa premiere lecture, puis le garde. Chaque lecture cree son
     * propre noeud sur ce tampon partage : c'est la seule facon, avec jMonkeyEngine,
     * d'avoir un son qui boucle et qu'on puisse arreter.
     */
    private AudioBuffer buffer(Sound sound) {
        AudioBuffer existing = buffers.get(sound.name());
        if (existing != null) {
            return existing;
        }
        byte[] samples = sound.samples();
        if (samples.length == 0 || sound.sampleRate() <= 0) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(samples.length).order(ByteOrder.nativeOrder());
        for (byte sample : samples) {
            buffer.put((byte) (sample + 128));
        }
        buffer.flip();

        AudioBuffer data = new AudioBuffer();
        data.setupFormat(1, 8, sound.sampleRate());
        data.updateData(buffer);
        buffers.put(sound.name(), data);
        return data;
    }
}
