package fov.breathless.engine;

import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.object.ObjectDefinition;

/**
 * L'arsenal du joueur.
 *
 * <p>Chaque arme est decrite par le <em>projectile</em> qu'elle tire : une definition
 * d'objet de type « projectile » declare l'arme a laquelle elle appartient dans son
 * septieme parametre. Le moteur d'origine construit ainsi sa table {@code GunObj1..5} au
 * chargement ({@code InitObjects} dans TMapMain.asm), et c'est la meme table qu'on
 * reconstitue ici.
 *
 * <p>Le tir coute de l'energie, indiquee par le troisieme parametre du projectile : sans
 * energie, pas de tir.
 */
public final class Weapons {

    /** Nombre d'armes du jeu, du tir simple a la Death Machine. */
    public static final int COUNT = 6;

    private final ObjectDefinition[] byWeapon = new ObjectDefinition[COUNT];
    /**
     * Puissance de chaque arme : 0 si le joueur ne l'a pas, 1 s'il l'a, 2 si elle est
     * amelioree. {@code PlayerWeapons} (Sorgenti/Scores.asm) compte de la meme facon.
     */
    private final int[] power = new int[COUNT];
    private int active;

    /**
     * L'arsenal de depart.
     *
     * <p>Une banque absente laisse les six armes sans description : leur puissance se suit
     * quand meme, ce dont se contentent le code de niveau et la reprise d'un niveau.
     */
    public Weapons(ObjectBank bank) {
        for (ObjectDefinition definition
                : (bank == null) ? java.util.List.<ObjectDefinition>of() : bank.objects().values()) {
            if (definition.objectType() != ObjectDefinition.TYPE_SHOT) {
                continue;
            }
            int weapon = definition.param(7);
            if (weapon >= 0 && weapon < COUNT && byWeapon[weapon] == null) {
                byWeapon[weapon] = definition;
            }
        }
        // Le joueur commence avec l'arme de base, celle qu'on ne perd jamais.
        power[0] = 1;
    }

    /** Arme selectionnee. */
    public int active() {
        return active;
    }

    public boolean owns(int weapon) {
        return weapon >= 0 && weapon < COUNT && power[weapon] > 0;
    }

    /**
     * L'arme est-elle amelioree ? Elle tire alors deux projectiles en eventail, et le
     * second ne coute rien.
     *
     * <p>Les ameliorations ne s'achetent qu'au terminal, qui n'est pas encore porte.
     */
    public boolean isBoosted(int weapon) {
        return weapon >= 0 && weapon < COUNT && power[weapon] > 1;
    }

    /** Donne une arme au joueur. {@code CollectWeapon}. */
    public void give(int weapon) {
        if (weapon >= 0 && weapon < COUNT && byWeapon[weapon] != null && power[weapon] == 0) {
            power[weapon] = 1;
        }
    }

    /** Puissance d'une arme : 0 absente, 1 possedee, 2 amelioree. */
    public int power(int weapon) {
        return (weapon >= 0 && weapon < COUNT) ? power[weapon] : 0;
    }

    /**
     * Impose la puissance d'une arme.
     *
     * <p>Sert a rendre une partie depuis son code de niveau, ou l'etat des six armes est
     * range tel quel.
     */
    public void setPower(int weapon, int value) {
        if (weapon >= 0 && weapon < COUNT && byWeapon[weapon] != null) {
            power[weapon] = Math.max(0, Math.min(2, value));
        }
    }

    /**
     * L'etat des six armes, rendu sous forme de copie.
     *
     * <p>{@code InitScores2} met {@code PlayerWeapons} de cote a chaque debut de niveau,
     * pour pouvoir le rendre si le joueur y revient apres etre mort.
     */
    public int[] powers() {
        return power.clone();
    }

    /** Rend a l'arsenal l'etat qu'il avait. */
    public void setPowers(int[] values) {
        for (int weapon = 0; weapon < Math.min(values.length, COUNT); weapon++) {
            setPower(weapon, values[weapon]);
        }
    }

    /** Ameliore une arme deja possedee, et pas encore amelioree. {@code BoostWeapon}. */
    public void boost(int weapon) {
        if (weapon >= 0 && weapon < COUNT && power[weapon] == 1) {
            power[weapon] = 2;
        }
    }

    /**
     * L'arme courante est-elle un lance-flammes ?
     *
     * <p>Son tir ne part pas droit : {@code PFflame} fait osciller la visee de part et
     * d'autre de l'axe, ce qui etale la flamme.
     */
    public boolean isFlamethrower() {
        ObjectDefinition projectile = projectile();
        return projectile != null && projectile.param(8) != 0;
    }

    /** Selectionne une arme si le joueur la possede. */
    public boolean select(int weapon) {
        if (!owns(weapon) || byWeapon[weapon] == null) {
            return false;
        }
        active = weapon;
        return true;
    }

    /** Passe a l'arme possedee suivante, en bouclant. */
    public void next() {
        for (int step = 1; step <= COUNT; step++) {
            int candidate = (active + step) % COUNT;
            if (owns(candidate) && byWeapon[candidate] != null) {
                active = candidate;
                return;
            }
        }
    }

    /** Definition du projectile de l'arme selectionnee, ou {@code null}. */
    public ObjectDefinition projectile() {
        return byWeapon[active];
    }

    public ObjectDefinition projectileOf(int weapon) {
        return (weapon >= 0 && weapon < COUNT) ? byWeapon[weapon] : null;
    }

    /** Energie consommee par un tir de l'arme selectionnee. */
    public int energyCost() {
        ObjectDefinition projectile = projectile();
        return (projectile == null) ? 0 : projectile.param(3);
    }

    /** Nombre d'armes reellement decrites par les donnees. */
    public int describedCount() {
        int count = 0;
        for (ObjectDefinition definition : byWeapon) {
            if (definition != null) {
                count++;
            }
        }
        return count;
    }
}
