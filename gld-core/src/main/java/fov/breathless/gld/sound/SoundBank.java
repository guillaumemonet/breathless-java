package fov.breathless.gld.sound;

import fov.breathless.gld.ByteReader;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.GldFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Banque sonore SGLD : bruitages et morceaux.
 *
 * <p>Portage de {@code ReadSoundsDir}, {@code ReadSounds} (Sorgenti/Loader.asm) et de la
 * passe de liaison de {@code InitAudio} (Sorgenti/Audio.asm). Le fichier a la meme forme
 * que la banque graphique :
 *
 * <pre>
 *   +0    'SGLD'
 *   +4    LONG   offset de la directory
 *         donnees des sons
 *   dirOffset :
 *         LONG   nombre de sons
 *         entrees de 12 octets : nom sur 4 caracteres, offset, longueur
 * </pre>
 *
 * <p>Chaque son commence par un en-tete de seize octets, decrit par {@link Sound}. Trois
 * cas s'y presentent, et c'est {@code InitAudio} qui les demele :
 *
 * <ul>
 *   <li>l'echantillon suit l'en-tete ;</li>
 *   <li>l'en-tete porte le <em>nom</em> d'un autre son, dont il reprend l'echantillon
 *       avec ses propres periode et volume ;</li>
 *   <li>l'en-tete est un tirage au sort entre trois sons nommes.</li>
 * </ul>
 */
public final class SoundBank {

    /** ID de charge utile d'une banque sonore. */
    public static final String SGLD = "SGLD";

    /** Taille de l'en-tete d'un son. {@code snd_SIZE}. */
    private static final int HEADER_SIZE = 16;

    private final Map<String, Sound> sounds;
    private final Map<Integer, Sound> globals;

    private SoundBank(Map<String, Sound> sounds, Map<Integer, Sound> globals) {
        this.sounds = Map.copyOf(sounds);
        this.globals = Map.copyOf(globals);
    }

    public static SoundBank read(GldContainer gld) {
        String file = gld.source().getFileName().toString();
        ByteReader r = gld.reader();

        String id = r.tag();
        if (!SGLD.equals(id)) {
            throw new GldFormatException(file + " : ID " + id + " la ou " + SGLD + " etait attendu");
        }
        int dirOffset = r.s32();

        r.position(dirOffset);
        int count = r.s32();
        if (count < 0 || count > 10000) {
            throw new GldFormatException(file + " : nombre de sons aberrant (" + count + ")");
        }

        record Location(String name, int offset, int length) {
        }
        List<Location> locations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = r.fixedString(4);
            int offset = r.s32();
            int length = r.s32();
            if (offset < 0 || length < HEADER_SIZE || offset + length > gld.size()) {
                throw new GldFormatException(file + " : son " + name + " hors fichier (offset "
                        + offset + ", longueur " + length + ")");
            }
            locations.add(new Location(name, offset, length));
        }

        Map<String, Sound> sounds = new LinkedHashMap<>();
        for (Location location : locations) {
            sounds.put(location.name(), readSound(gld, file, location.name(),
                    location.offset(), location.length()));
        }

        resolveLinks(file, sounds);

        Map<Integer, Sound> globals = new LinkedHashMap<>();
        for (Sound sound : sounds.values()) {
            if (sound.type() == Sound.TYPE_GLOBAL) {
                globals.putIfAbsent(sound.code(), sound);
            }
        }
        return new SoundBank(sounds, globals);
    }

    private static Sound readSound(GldContainer gld, String file, String name,
                                   int offset, int length) {
        ByteReader r = gld.reader();
        r.position(offset);

        byte[] head = r.bytes(4);
        String link = readName(head);
        int lengthInWords = r.u16();
        int period = r.u16();
        int volume = r.u16();
        int loopStart = r.u16();
        int priority = r.u8();
        int channelMask = r.u8();
        int type = r.s8();
        int code = r.u8();

        if (type == Sound.TYPE_RANDOM) {
            // Douze octets de noms : jusqu'a trois sons entre lesquels tirer.
            r.position(offset);
            List<String> alternatives = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                String alternative = readName(r.bytes(4));
                if (alternative != null) {
                    alternatives.add(alternative);
                }
            }
            return new Sound(name, null, 0, 0, 0, 0, 0, 0, type, 0,
                    alternatives.toArray(new String[0]), new byte[0]);
        }

        byte[] samples = new byte[0];
        if (link == null) {
            int available = length - HEADER_SIZE;
            int declared = lengthInWords * 2;
            if (declared > available) {
                throw new GldFormatException(file + " : son " + name + " annonce " + declared
                        + " octets pour " + available + " disponibles");
            }
            r.position(offset + HEADER_SIZE);
            samples = r.bytes(declared);
        }
        return new Sound(name, link, lengthInWords, period, volume, loopStart,
                priority, channelMask, type, code, new String[0], samples);
    }

    /** Quatre octets imprimables forment un nom ; quatre zeros n'en sont pas un. */
    private static String readName(byte[] four) {
        for (byte b : four) {
            int value = b & 0xFF;
            if (value < 32 || value > 126) {
                return null;
            }
        }
        return new String(four, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Donne son echantillon a chaque son qui n'en portait qu'un nom. */
    private static void resolveLinks(String file, Map<String, Sound> sounds) {
        for (Sound sound : sounds.values()) {
            if (sound.link() == null) {
                continue;
            }
            Sound source = sounds.get(sound.link());
            if (source == null) {
                throw new GldFormatException(file + " : le son " + sound.name()
                        + " renvoie a " + sound.link() + ", absent de la banque");
            }
            if (source.link() != null) {
                throw new GldFormatException(file + " : le son " + sound.name()
                        + " renvoie a " + sound.link() + ", qui renvoie lui-meme ailleurs");
            }
            sound.setSamples(source.samples());
        }
    }

    /** Les sons de la banque, dans l'ordre du fichier. */
    public Map<String, Sound> sounds() {
        return sounds;
    }

    /** Un son par son nom, ou {@code null}. */
    public Sound get(String name) {
        return sounds.get(name);
    }

    /**
     * Un son global par son code.
     *
     * @param code {@link Sound#GLOBAL_DOOR_START} et suivants
     */
    public Sound global(int code) {
        return globals.get(code);
    }

    /** Les morceaux ProTracker de la banque, dans l'ordre du fichier. */
    public List<Sound> modules() {
        List<Sound> found = new ArrayList<>();
        for (Sound sound : sounds.values()) {
            if (sound.type() == Sound.TYPE_MODULE) {
                found.add(sound);
            }
        }
        return found;
    }
}
