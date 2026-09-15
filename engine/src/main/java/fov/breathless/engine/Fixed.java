package fov.breathless.engine;

/**
 * Arithmetique en virgule fixe 16.16, celle du moteur d'origine.
 *
 * <p>Toutes les coordonnees monde, directions de rayon et distances sont des entiers
 * 32 bits dont les 16 bits de poids faible sont la partie fractionnaire.
 */
public final class Fixed {

    /** La valeur 1,0. */
    public static final int ONE = 1 << 16;

    private Fixed() {
    }

    /**
     * Multiplication 16.16, portage de la macro {@code FIXMUL} de MulDiv64.i :
     * {@code (a * b) >> 16} calcule sur 64 bits.
     */
    public static int mul(int a, int b) {
        return (int) (((long) a * (long) b) >> 16);
    }

    /**
     * Division d'un entier par une valeur 16.16, resultat en 16.16.
     *
     * <p>Portage de {@code DIVS64} tel qu'utilise pour les pas de la DDA :
     * le dividende est {@code value} decale de 32 bits.
     */
    public static int divStep(int value, int divisor) {
        return (int) ((((long) value) << 32) / divisor);
    }

    /**
     * Division d'une valeur 16.16 par une autre, resultat en 16.16.
     *
     * <p>Portage de {@code DIVS64} tel qu'utilise pour la distance initiale :
     * le dividende 16.16 est decale de 16 bits de plus.
     */
    public static int divFixed(int value, int divisor) {
        return (int) ((((long) value) << 16) / divisor);
    }

    /** Partie entiere, vers moins l'infini. */
    public static int floor(int value) {
        return value >> 16;
    }

    /** Convertit un entier en 16.16. */
    public static int of(int value) {
        return value << 16;
    }
}
