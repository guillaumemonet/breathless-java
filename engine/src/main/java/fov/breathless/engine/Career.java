package fov.breathless.engine;

import fov.breathless.gld.object.ObjectBank;

/**
 * Ce que le joueur emporte d'un niveau au suivant.
 *
 * <p>Portage de {@code InitScores} et {@code InitScores2} (Sorgenti/Scores.asm). Le moteur
 * d'origine garde l'etat du joueur dans ses variables globales : il <strong>traverse</strong>
 * les niveaux, et seul un nouveau depart le remet a neuf. Chaque niveau en prend au passage
 * une photographie, qui sert a reprendre apres une mort.
 *
 * <p>Trois regles, et elles ne se devinent pas :
 *
 * <ul>
 *   <li>sante, boucliers, energie, credits, score, armes et arme en main <strong>se
 *       gardent</strong> d'un niveau au suivant ;</li>
 *   <li>mourir fait reprendre le niveau <strong>dans l'etat ou on y est entre</strong>, et
 *       non a neuf : qui entre a trente points de vie recommence a trente ;</li>
 *   <li>les quatre cles sont la seule chose qui ne se garde pas — {@code clr.l GreenKey}
 *       les retire a chaque debut de niveau, apres la photographie.</li>
 * </ul>
 */
public final class Career {

    /** Etat d'un debut de niveau, mis de cote pour pouvoir y revenir. {@code SPlayer*}. */
    private record Snapshot(int health, int shields, int energy, int credits, int score,
                            boolean[] keys, int[] weapons, int activeWeapon) {
    }

    private final PlayerState state = new PlayerState();
    private final Weapons weapons;
    private Snapshot snapshot;

    /**
     * Une partie neuve : {@code InitScores}, puis l'arsenal de depart.
     *
     * @param bank banque d'objets, d'ou les armes tiennent leur description
     */
    public Career(ObjectBank bank) {
        this.weapons = new Weapons(bank);
    }

    public PlayerState state() {
        return state;
    }

    public Weapons weapons() {
        return weapons;
    }

    /**
     * Entre dans un niveau. Portage de {@code InitScores2}.
     *
     * <p>L'ordre compte : on rend d'abord l'etat d'entree si l'on rejoue, on photographie
     * ensuite, et l'on retire les cles en dernier — de sorte qu'une reprise ne rende jamais
     * les cles, que le joueur devra retrouver.
     *
     * @param playAgain vrai si l'on reprend un niveau deja joue, apres une mort ou par un
     *     code de niveau
     */
    public void enterLevel(boolean playAgain) {
        if (playAgain && snapshot != null) {
            state.restore(snapshot.health(), snapshot.shields(),
                    snapshot.energy(), snapshot.credits());
            state.setScore(snapshot.score());
            state.setKeys(snapshot.keys());
            weapons.setPowers(snapshot.weapons());
            weapons.select(snapshot.activeWeapon());
        }

        snapshot = new Snapshot(state.health(), state.shields(), state.energy(),
                state.credits(), state.score(), state.keys(),
                weapons.powers(), weapons.active());

        state.clearKeys();
    }
}
