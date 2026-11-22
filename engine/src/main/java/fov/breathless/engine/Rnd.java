package fov.breathless.engine;

/**
 * Le generateur de nombres du moteur d'origine.
 *
 * <p>Portage de {@code Rnd} (Sorgenti/Objects.asm) : un registre a decalage a retroaction
 * sur 32 bits. La graine double a chaque tirage ; si le bit sorti est a un, ou si le
 * resultat est nul, on la brouille par {@code $1D872B41}. Le tirage est le reste de la
 * division du mot bas par l'etendue demandee.
 *
 * <p>Une seule instance par niveau, partagee par les ennemis et les effets, comme la
 * variable {@code rndseed} de l'original : l'ordre des tirages fait partie du
 * comportement.
 */
public final class Rnd {

    /** Graine de depart, celle qu'un commentaire de l'original conserve. */
    public static final int DEFAULT_SEED = 0x4C277839;

    private int seed;

    public Rnd() {
        this(DEFAULT_SEED);
    }

    public Rnd(int seed) {
        this.seed = seed;
    }

    /**
     * Un nombre entre 0 et {@code range - 1}.
     *
     * <p>{@code divu} sur le mot bas : une etendue nulle ferait diviser par zero, on rend
     * alors zero.
     */
    public int next(int range) {
        boolean carry = seed < 0;
        seed <<= 1;
        // bhi : on garde le resultat tel quel s'il n'y a ni retenue ni zero.
        if (carry || seed == 0) {
            seed ^= 0x1D872B41;
        }
        int word = seed & 0xFFFF;
        int divisor = range & 0xFFFF;
        if (divisor == 0) {
            return 0;
        }
        return word % divisor;
    }
}
