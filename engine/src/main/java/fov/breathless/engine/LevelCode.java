package fov.breathless.engine;

/**
 * Les codes de niveau : seize caracteres qui rendent une partie en cours.
 *
 * <p>Portage de {@code LevelCodeOut}, {@code LevelCodeIn} et {@code CheckAccessCode}
 * (Sorgenti/Scores.asm). Le code porte tout l'etat qui compte : sante, boucliers,
 * energie, credits, armes possedees et amelioration de chacune, arme en main, monde et
 * niveau.
 *
 * <p>Dix octets sont d'abord assembles :
 *
 * <pre>
 *   +0  BYTE  sante
 *   +1  BYTE  boucliers
 *   +2  WORD  energie
 *   +4  BYTE  somme de controle, et le bit de poids faible des credits hauts
 *   +5  WORD  credits, seize bits de poids faible
 *   +7  WORD  arme en main sur trois bits, puis six fois deux bits de puissance d'arme
 *   +9  BYTE  monde sur quatre bits, niveau sur quatre bits
 * </pre>
 *
 * <p>Ils sont ensuite brouilles par un ou exclusif avec une cle de trente-deux bits, puis
 * decoupes en seize groupes de cinq bits, chacun devenant un caractere parmi
 * {@code 1} a {@code 9} et {@code A} a {@code W}.
 */
public final class LevelCode {

    /** Longueur d'un code, en caracteres. */
    public static final int LENGTH = 16;

    /** Nombre d'octets du code avant mise en caracteres. */
    private static final int BYTES = 10;

    /** Cle de brouillage. */
    private static final int KEY = 0x65A4B52D;

    /** Le code du tout premier niveau, tel que {@code ClearLevelCode} l'ecrit. */
    public static final String FIRST_LEVEL = "181CEIGGLJRJSE2T";

    /** Ce qu'un code rend une fois decode. */
    public record State(int health, int shields, int energy, int credits,
                        int activeWeapon, int[] weaponPower, int game, int level) {

        public State {
            weaponPower = weaponPower.clone();
        }

        @Override
        public int[] weaponPower() {
            return weaponPower.clone();
        }
    }

    private LevelCode() {
    }

    /**
     * Fabrique le code d'une partie.
     *
     * @param game  numero de monde, de 1 a 4
     * @param level numero de niveau dans le monde, de 1 a 5
     */
    public static String encode(PlayerState state, Weapons weapons, int game, int level) {
        int[] power = new int[Weapons.COUNT];
        for (int weapon = 0; weapon < Weapons.COUNT; weapon++) {
            power[weapon] = weapons.power(weapon);
        }
        return encode(state.health(), state.shields(), state.energy(), state.credits(),
                weapons.active(), power, game, level);
    }

    /**
     * Fabrique un code a partir de valeurs nues.
     *
     * <p>Cette forme sert aux tests du codage lui-meme, qui n'ont besoin ni d'un joueur
     * ni d'une banque d'objets.
     */
    public static String encode(int health, int shields, int energy, int credits,
                                int activeWeapon, int[] weaponPower, int game, int level) {
        int[] raw = new int[BYTES];
        raw[0] = health & 0xFF;
        raw[1] = shields & 0xFF;
        raw[2] = (energy >> 8) & 0xFF;
        raw[3] = energy & 0xFF;

        int creditsHigh = (credits >> 16) & 0xFF;
        raw[4] = creditsHigh;
        raw[5] = (credits >> 8) & 0xFF;
        raw[6] = credits & 0xFF;

        int packed = (activeWeapon & 0x7) << 13;
        for (int weapon = 0; weapon < Weapons.COUNT && weapon < weaponPower.length; weapon++) {
            packed |= (weaponPower[weapon] & 0x3) << (weapon * 2);
        }
        raw[7] = (packed >> 8) & 0xFF;
        raw[8] = packed & 0xFF;

        raw[9] = ((game & 0xF) << 4) | (level & 0xF);

        // La somme de controle occupe les sept bits hauts de l'octet 4, dont le bit de
        // poids faible reste celui des credits : ceux-ci tiennent donc sur dix-sept bits.
        int sum = 0;
        for (int value : raw) {
            sum += value;
        }
        raw[4] = ((sum << 1) & 0xFF) | (creditsHigh & 1);

        scramble(raw);
        return toText(raw);
    }

    /**
     * Relit un code.
     *
     * @return l'etat qu'il porte, ou {@code null} si le code est mal forme ou altere
     */
    public static State decode(String code) {
        if (code == null) {
            return null;
        }
        String text = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (text.length() != LENGTH) {
            return null;
        }
        int[] raw = fromText(text);
        if (raw == null) {
            return null;
        }
        scramble(raw);

        int stored = raw[4];
        int creditsHighBit = stored & 1;
        raw[4] = creditsHighBit;

        int sum = 0;
        for (int value : raw) {
            sum += value;
        }
        if (((sum << 1) & 0xFF | creditsHighBit) != stored) {
            return null;
        }

        int health = raw[0];
        int shields = raw[1];
        int energy = (raw[2] << 8) | raw[3];
        int credits = (creditsHighBit << 16) | (raw[5] << 8) | raw[6];

        int packed = (raw[7] << 8) | raw[8];
        int activeWeapon = (packed >> 13) & 0x7;
        int[] power = new int[Weapons.COUNT];
        for (int weapon = 0; weapon < Weapons.COUNT; weapon++) {
            power[weapon] = (packed >> (weapon * 2)) & 0x3;
        }

        int game = (raw[9] >> 4) & 0xF;
        int level = raw[9] & 0xF;

        return new State(health, shields, energy, credits, activeWeapon, power, game, level);
    }

    /**
     * Le brouillage est son propre inverse : un ou exclusif avec la cle.
     *
     * <p>L'original l'applique en deux mots longs puis <strong>un mot</strong> :
     * les deux derniers octets voient donc le mot <em>bas</em> de la cle, non son mot
     * haut. C'est ce detail qui distingue un code valide d'un code seulement coherent
     * avec lui-meme.
     */
    private static void scramble(int[] raw) {
        for (int i = 0; i < 8; i++) {
            raw[i] ^= (KEY >>> (24 - (i % 4) * 8)) & 0xFF;
        }
        raw[8] ^= (KEY >>> 8) & 0xFF;
        raw[9] ^= KEY & 0xFF;
    }

    /** Decoupe les quatre-vingts bits en seize caracteres. */
    private static String toText(int[] raw) {
        StringBuilder text = new StringBuilder(LENGTH);
        for (int group = 0; group < LENGTH; group++) {
            int value = extract(raw, group * 5);
            int character = value + '1';
            if (character > '9') {
                character += 7;   // on saute de '9' a 'A'
            }
            text.append((char) character);
        }
        return text.toString();
    }

    /** Refait les dix octets a partir des seize caracteres, ou {@code null}. */
    private static int[] fromText(String text) {
        int[] raw = new int[BYTES];
        for (int group = 0; group < LENGTH; group++) {
            int character = text.charAt(group);
            int value = character - '1';
            if (character >= 'A') {
                value -= 7;
            }
            if (value < 0 || value > 31) {
                return null;
            }
            insert(raw, group * 5, value);
        }
        return raw;
    }

    /** Lit cinq bits a une position quelconque, le bit de poids fort en tete. */
    private static int extract(int[] raw, int bit) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int at = bit + i;
            int b = (raw[at >> 3] >> (7 - (at & 7))) & 1;
            value = (value << 1) | b;
        }
        return value;
    }

    private static void insert(int[] raw, int bit, int value) {
        for (int i = 0; i < 5; i++) {
            int at = bit + i;
            int b = (value >> (4 - i)) & 1;
            int mask = 1 << (7 - (at & 7));
            if (b != 0) {
                raw[at >> 3] |= mask;
            } else {
                raw[at >> 3] &= ~mask;
            }
        }
    }
}
