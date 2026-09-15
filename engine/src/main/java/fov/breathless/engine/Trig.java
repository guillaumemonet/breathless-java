package fov.breathless.engine;

/**
 * Tables de sinus et cosinus du moteur, regenerees a l'identique.
 *
 * <p>Le moteur d'origine embarque 2048 valeurs par tour en 16.16 (Sorgenti/sincos.asm).
 * Les caps du jeu, y compris ceux stockes dans les fichiers de niveau, sont exprimes
 * dans cette echelle : 0 = est, 512 = nord, 1024 = ouest, 1536 = sud.
 *
 * <p>Deux details de l'original sont reproduits, parce qu'ils changent des valeurs :
 *
 * <ul>
 *   <li>l'arrondi est un <strong>plancher</strong>, pas un arrondi au plus proche ni une
 *       troncature vers zero : {@code sin[15]} vaut 3014 pour 3014,99 et
 *       {@code sin[1096]} vaut -14360 pour -14359,02 ;</li>
 *   <li>il n'existe qu'<strong>une seule table</strong> de 2561 entrees :
 *       {@code costable} est une etiquette posee 512 entrees plus loin dans
 *       {@code sintable}, puisque cos(x) = sin(x + 90 degres). Le cosinus se lit donc
 *       dans la table des sinus avec un decalage, et non par un calcul separe.</li>
 * </ul>
 *
 * <p>Cette definition reproduit les 2561 entrees du fichier d'origine sans aucun ecart,
 * y compris {@code cos[1536]} qui vaut exactement 0 la ou un calcul direct en virgule
 * flottante donnerait -1.
 */
public final class Trig {

    /** Nombre de pas pour un tour complet. */
    public static final int STEPS = 2048;

    /** Masque pour ramener un cap dans un tour. */
    public static final int MASK = STEPS - 1;

    /** Decalage du cosinus dans la table des sinus : un quart de tour. */
    private static final int QUARTER_TURN = STEPS / 4;

    private static final int[] SIN = new int[STEPS];

    static {
        for (int i = 0; i < STEPS; i++) {
            SIN[i] = (int) Math.floor(Math.sin(2.0 * Math.PI * i / STEPS) * Fixed.ONE);
        }
    }

    private Trig() {
    }

    /** Sinus d'un cap, en 16.16. */
    public static int sin(int heading) {
        return SIN[heading & MASK];
    }

    /** Cosinus d'un cap, en 16.16 : la table des sinus lue un quart de tour plus loin. */
    public static int cos(int heading) {
        return SIN[(heading + QUARTER_TURN) & MASK];
    }
}
