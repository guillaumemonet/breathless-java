package fov.breathless.gld.level;

/**
 * Un effet a appliquer (10 octets sur fichier).
 *
 * <p>Les effets sont groupes en listes ; un bloc declenche une liste entiere en y
 * entrant. Chaque entree vise tous les blocs portant le meme numero de trigger.
 * Le catalogue des codes d'effet est dans MapEditor/FxList.h.
 *
 * @param trigger numero de trigger vise
 * @param effect  code de l'effet a executer
 * @param param1  premier parametre, sens dependant de l'effet
 * @param param2  second parametre, sens dependant de l'effet
 * @param key     cle requise pour declencher l'effet, 0 si aucune
 */
public record EffectEntry(int trigger, int effect, int param1, int param2, int key) {

    /** Taille de la structure sur fichier. */
    public static final int SIZE = 10;
}
