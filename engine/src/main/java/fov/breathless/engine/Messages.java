package fov.breathless.engine;

/**
 * Les messages affiches en jeu.
 *
 * <p>Portage de la table {@code Messages} (Sorgenti/Text.asm), que
 * {@code SprDelayPrintMessage} pose en haut de la vue avant de l'effacer une seconde et
 * demie plus tard ({@code MessWait} : soixante cinquantiemes).
 */
public final class Messages {

    /** {@code MessWait} : duree d'affichage d'un message, en cinquantiemes. */
    public static final int DURATION = 60;

    private static final String[] TEXTS = {
        "COLLECTED GREEN KEY",
        "COLLECTED YELLOW KEY",
        "COLLECTED RED KEY",
        "COLLECTED BLUE KEY",
        "NEED GREEN KEY",
        "NEED YELLOW KEY",
        "NEED RED KEY",
        "NEED BLUE KEY",
        "COLLECTED HEALTH ITEM",
        "COLLECTED SHIELDS ITEM",
        "COLLECTED ENERGY ITEM",
        "COLLECTED CREDITS ITEM",
        "WARNING!!! ITEM ERROR",
        "SIMPLE SHOT",
        "FIREBALLS",
        "PLASMA GUN",
        "FLAME-THROWER",
        "MAGNETIC GUN",
        "DEATH MACHINE",
        "PAUSE OFF",
    };

    /** Premier nom d'arme dans la table. */
    private static final int FIRST_WEAPON = 13;

    /** {@code PAUSE OFF}, le dernier message. */
    public static final int PAUSE_OFF = 19;

    private Messages() {
    }

    /** Le texte d'un message, ou {@code null} pour un numero inconnu. */
    public static String text(int index) {
        return (index >= 0 && index < TEXTS.length) ? TEXTS[index] : null;
    }

    /**
     * {@code CollectItem} : sante 8, boucliers 9, energie 10, credits 11, cles 0 a 3, et
     * 12 pour un objet de type inconnu.
     */
    public static int forPickup(int subtype) {
        return switch (subtype) {
            case PlayerState.PICKUP_HEALTH -> 8;
            case PlayerState.PICKUP_SHIELDS -> 9;
            case PlayerState.PICKUP_ENERGY -> 10;
            case PlayerState.PICKUP_CREDITS -> 11;
            case PlayerState.PICKUP_GREEN_KEY, PlayerState.PICKUP_YELLOW_KEY,
                 PlayerState.PICKUP_RED_KEY, PlayerState.PICKUP_BLUE_KEY ->
                    subtype - PlayerState.PICKUP_GREEN_KEY;
            default -> 12;
        };
    }

    /** Le nom d'une arme, de 0 (simple shot) a 5 (death machine). */
    public static int forWeapon(int weapon) {
        return FIRST_WEAPON + weapon;
    }
}
