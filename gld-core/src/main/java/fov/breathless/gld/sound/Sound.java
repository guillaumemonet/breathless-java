package fov.breathless.gld.sound;

/**
 * Un son de la banque : son echantillon et la facon de le jouer.
 *
 * <p>Portage de la structure {@code snd_} (Sorgenti/TMap.i). Elle tient en seize octets,
 * suivis le plus souvent de l'echantillon lui-meme :
 *
 * <pre>
 *   +0   LONG  0, ou le nom du son dont il faut reprendre l'echantillon
 *   +4   WORD  longueur de l'echantillon, en mots de seize bits
 *   +6   WORD  periode Amiga, qui fixe la frequence de lecture
 *   +8   WORD  volume, de 0 a 64
 *   +10  WORD  depart de la boucle, en octets depuis le debut ; 0 s'il n'y en a pas
 *   +12  BYTE  priorite, de 0 a 7
 *   +13  BYTE  canaux utilisables, un bit par canal
 *   +14  BYTE  type : 0 module ProTracker, 1 son global, 2 son d'objet, -1 tirage
 *   +15  BYTE  code du son global
 * </pre>
 *
 * <p>Un enregistrement de type -1 ne porte pas d'echantillon : ses douze premiers octets
 * sont trois noms de sons parmi lesquels le moteur tire au sort a chaque lecture.
 */
public final class Sound {

    /** Un morceau ProTracker, non un bruitage. */
    public static final int TYPE_MODULE = 0;

    /** Un son global, designe par son {@link #code()}. */
    public static final int TYPE_GLOBAL = 1;

    /** Un son attache a un type d'objet. */
    public static final int TYPE_OBJECT = 2;

    /** Un tirage au sort entre plusieurs sons. */
    public static final int TYPE_RANDOM = -1;

    /** Horloge Amiga en PAL, qui relie la periode a la frequence. */
    public static final int PAL_CLOCK = 3_546_895;

    /** Codes des sons globaux, tels que les commente TMap.i. */
    public static final int GLOBAL_DOOR_START = 0;
    public static final int GLOBAL_DOOR_STOP = 1;
    public static final int GLOBAL_SWITCH = 2;
    public static final int GLOBAL_TELEPORT = 3;
    public static final int GLOBAL_SCREAM = 4;
    public static final int GLOBAL_STEP = 5;
    public static final int GLOBAL_FAULT = 6;

    private final String name;
    private final String link;
    private final int lengthInWords;
    private final int period;
    private final int volume;
    private final int loopStart;
    private final int priority;
    private final int channelMask;
    private final int type;
    private final int code;
    private final String[] alternatives;
    private byte[] samples;

    Sound(String name, String link, int lengthInWords, int period, int volume, int loopStart,
          int priority, int channelMask, int type, int code, String[] alternatives,
          byte[] samples) {
        this.name = name;
        this.link = link;
        this.lengthInWords = lengthInWords;
        this.period = period;
        this.volume = volume;
        this.loopStart = loopStart;
        this.priority = priority;
        this.channelMask = channelMask;
        this.type = type;
        this.code = code;
        this.alternatives = alternatives;
        this.samples = samples;
    }

    public String name() {
        return name;
    }

    /**
     * Nom du son dont celui-ci reprend l'echantillon, ou {@code null}.
     *
     * <p>Une dizaine de sons du jeu ne sont qu'un reglage different d'un echantillon deja
     * present : le cri d'un devil est celui d'un autre monstre, joue plus bas.
     */
    public String link() {
        return link;
    }

    /** Les trois sons entre lesquels tirer, pour un son de type {@link #TYPE_RANDOM}. */
    public String[] alternatives() {
        return alternatives.clone();
    }

    /** Echantillon, en huit bits signes. Vide tant qu'un lien n'est pas resolu. */
    public byte[] samples() {
        return samples;
    }

    void setSamples(byte[] resolved) {
        this.samples = resolved;
    }

    /** Longueur declaree, en mots de seize bits. */
    public int lengthInWords() {
        return lengthInWords;
    }

    /** Periode Amiga : plus elle est courte, plus le son est aigu. */
    public int period() {
        return period;
    }

    /** Frequence de lecture, en hertz, deduite de la periode. */
    public int sampleRate() {
        return (period <= 0) ? 0 : PAL_CLOCK / period;
    }

    /** Volume, de 0 a 64. */
    public int volume() {
        return volume;
    }

    /** Debut de la boucle, en octets, ou 0 si le son ne boucle pas. */
    public int loopStart() {
        return loopStart;
    }

    public boolean loops() {
        return loopStart > 0;
    }

    /** Priorite, de 0 a 7 : elle decide quel son chasse quel autre. */
    public int priority() {
        return priority;
    }

    /** Canaux utilisables, un bit par canal Amiga. */
    public int channelMask() {
        return channelMask;
    }

    /** De {@link #TYPE_MODULE} a {@link #TYPE_RANDOM}. */
    public int type() {
        return type;
    }

    /** Code d'un son global : {@link #GLOBAL_DOOR_START} et suivants. */
    public int code() {
        return code;
    }

    @Override
    public String toString() {
        return name + " (" + lengthInWords * 2 + " octets, " + sampleRate() + " Hz)";
    }
}
