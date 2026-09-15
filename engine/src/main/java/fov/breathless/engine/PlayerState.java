package fov.breathless.engine;

/**
 * Ce que le joueur possede et ce qu'il lui reste.
 *
 * <p>Portage de la comptabilite de {@code Scores.asm}. Les valeurs initiales et les
 * plafonds viennent de TMap.i.
 */
public final class PlayerState {

    public static final int MAX_HEALTH = 100;
    public static final int MAX_SHIELDS = 100;
    public static final int MAX_ENERGY = 9999;
    public static final int MAX_CREDITS = 99999;

    /** Delai entre deux morsures d'un sol nocif, en cinquantiemes de seconde. */
    private static final int HURT_PERIOD = 50;

    /** Sous-types des objets a ramasser. */
    public static final int PICKUP_HEALTH = 0;
    public static final int PICKUP_SHIELDS = 1;
    public static final int PICKUP_ENERGY = 2;
    public static final int PICKUP_CREDITS = 3;
    public static final int PICKUP_GREEN_KEY = 4;
    public static final int PICKUP_YELLOW_KEY = 5;
    public static final int PICKUP_RED_KEY = 6;
    public static final int PICKUP_BLUE_KEY = 7;

    /**
     * Duree du voile rouge apres un coup, en cinquantiemes de seconde.
     *
     * <p>{@code RedScreenCont} (Sorgenti/Scores.asm) : l'original bascule sur la seconde
     * palette du fichier graphique, puis rend la premiere au bout de treize pas.
     */
    public static final int RED_SCREEN_TICKS = 13;

    private int redScreen;
    private int health = MAX_HEALTH;
    private int shields = MAX_SHIELDS;
    private int energy = 1000;
    private int credits;
    private int score;
    private final boolean[] keys = new boolean[4];
    private boolean dead;
    private boolean invincible;
    private int hurtTimer;

    public int health() {
        return health;
    }

    public int shields() {
        return shields;
    }

    public int energy() {
        return energy;
    }

    public int credits() {
        return credits;
    }

    public int score() {
        return score;
    }

    public boolean isDead() {
        return dead;
    }

    public boolean isInvincible() {
        return invincible;
    }

    public void setInvincible(boolean invincible) {
        this.invincible = invincible;
    }

    /**
     * Le joueur possede-t-il cette cle ?
     *
     * @param key {@link #PICKUP_GREEN_KEY} a {@link #PICKUP_BLUE_KEY}
     */
    public boolean hasKey(int key) {
        int index = key - PICKUP_GREEN_KEY;
        return index >= 0 && index < keys.length && keys[index];
    }

    /** L'ecran doit-il virer au rouge, le joueur venant d'encaisser ? */
    public boolean isFlashing() {
        return redScreen > 0;
    }

    /** Fait s'ecouler le temps propre a l'etat du joueur. */
    public void advance(int ticks) {
        if (redScreen > 0) {
            redScreen = Math.max(0, redScreen - ticks);
        }
    }

    public void addScore(int points) {
        score += points;
    }

    /**
     * Remet le joueur dans l'etat que porte un code de niveau.
     *
     * <p>{@code LevelCodeIn} ecrit ces quatre valeurs telles quelles, sans les borner :
     * on s'en tient donc aux maxima du jeu.
     */
    public void restore(int newHealth, int newShields, int newEnergy, int newCredits) {
        health = Math.max(0, Math.min(MAX_HEALTH, newHealth));
        shields = Math.max(0, Math.min(MAX_SHIELDS, newShields));
        energy = Math.max(0, Math.min(MAX_ENERGY, newEnergy));
        credits = Math.max(0, Math.min(MAX_CREDITS, newCredits));
        dead = health <= 0;
    }

    /** Retire des credits : un achat au terminal. */
    public void spendCredits(int amount) {
        credits = Math.max(0, credits - amount);
    }

    public void spendEnergy(int amount) {
        energy = Math.max(0, energy - amount);
    }

    /**
     * Inflige des degats.
     *
     * <p>Les boucliers absorbent <strong>75 %</strong> de l'attaque, arrondis au plus
     * proche, dans la limite de ce qu'il en reste ; le reliquat entame la sante. Un joueur
     * sans bouclier encaisse donc tout, et un joueur bien protege presque rien.
     *
     * @return {@code true} si le coup a ete fatal
     */
    public boolean hit(int damage) {
        if (invincible || dead || damage <= 0) {
            return false;
        }
        redScreen = RED_SCREEN_TICKS;
        int absorbed = Math.min((damage * 3 + 2) / 4, shields);
        shields -= absorbed;
        int remaining = damage - absorbed;

        health -= remaining;
        if (health <= 0) {
            health = 0;
            dead = true;
        }
        return dead;
    }

    /**
     * Applique la morsure d'un sol nocif : deux, cinq ou dix points, une fois par seconde.
     *
     * @param damagePerBite degats du bloc, 0 s'il est inoffensif
     */
    public void standOn(int damagePerBite, int ticks) {
        if (damagePerBite <= 0) {
            hurtTimer = 0;
            return;
        }
        hurtTimer -= ticks;
        if (hurtTimer <= 0) {
            hurtTimer = HURT_PERIOD;
            hit(damagePerBite);
        }
    }

    /**
     * Tente de ramasser un objet.
     *
     * @param subtype sous-type de l'objet ramassable
     * @param value   quantite associee, ignoree pour les cles
     * @return {@code false} si le joueur en a deja assez : l'objet reste alors en place
     */
    public boolean collect(int subtype, int value) {
        return switch (subtype) {
            case PICKUP_HEALTH -> {
                if (health >= MAX_HEALTH) {
                    yield false;
                }
                health = Math.min(MAX_HEALTH, health + value);
                yield true;
            }
            case PICKUP_SHIELDS -> {
                if (shields >= MAX_SHIELDS) {
                    yield false;
                }
                shields = Math.min(MAX_SHIELDS, shields + value);
                yield true;
            }
            case PICKUP_ENERGY -> {
                if (energy >= MAX_ENERGY) {
                    yield false;
                }
                energy = Math.min(MAX_ENERGY, energy + value);
                yield true;
            }
            case PICKUP_CREDITS -> {
                if (credits >= MAX_CREDITS) {
                    yield false;
                }
                credits = Math.min(MAX_CREDITS, credits + value);
                yield true;
            }
            case PICKUP_GREEN_KEY, PICKUP_YELLOW_KEY, PICKUP_RED_KEY, PICKUP_BLUE_KEY -> {
                int index = subtype - PICKUP_GREEN_KEY;
                if (keys[index]) {
                    yield false;
                }
                keys[index] = true;
                yield true;
            }
            default -> false;
        };
    }
}
