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
 * <p>Chaque son est prepare une fois puis rejoue. Les sons du jeu ne sont pas places dans
 * l'espace : l'original les joue sur quatre canaux sans notion de position, et le rendu
 * est en deux dimensions.
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
    private final Map<String, AudioNode> nodes = new HashMap<>();
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

    @Override
    public void play(String name) {
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
            play(choices[random.nextInt(choices.length)]);
            return;
        }
        AudioNode node = node(sound);
        if (node != null) {
            node.playInstance();
        }
    }

    @Override
    public void playGlobal(int code) {
        Sound sound = bank.global(code);
        if (sound != null) {
            play(sound.name());
        }
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

    @Override
    public void stop(String name) {
        AudioNode node = nodes.get(name);
        if (node != null) {
            node.stop();
        }
    }

    /** Prepare le son a sa premiere lecture, puis le garde. */
    private AudioNode node(Sound sound) {
        AudioNode existing = nodes.get(sound.name());
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

        AudioNode node = new AudioNode(data, new AudioKey(sound.name()));
        node.setPositional(false);
        node.setVolume(Math.min(1f, sound.volume() / MAX_VOLUME));
        node.setLooping(sound.loops());
        root.attachChild(node);

        nodes.put(sound.name(), node);
        return node;
    }
}
