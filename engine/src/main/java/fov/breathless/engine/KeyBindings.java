package fov.breathless.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les touches du jeu, et de quoi les changer.
 *
 * <p>Portage des pages {@code conf_page3} et {@code conf_page4} (Sorgenti/Terminal.asm),
 * ou l'original laisse redefinir chaque commande.
 *
 * <p>Le moteur ne sait rien des codes de touches : il ne range qu'un entier opaque, celui
 * que lui donne la couche graphique, accompagne du nom a afficher. C'est cette couche qui
 * sait les lire et les nommer.
 */
public final class KeyBindings {

    /** Une commande et la touche qui l'actionne. */
    public record Binding(String action, String label, int code) {
    }

    /**
     * Les commandes redefinissables, dans l'ordre ou {@code conf_page3} les presente. La
     * carte, la pause et les armes ont des touches fixes, que la page refuse d'affecter.
     */
    public static final String[] ACTIONS = {
        "avant", "arriere", "gauche", "droite",
        "pas-gauche", "pas-droite", "tirer", "courir", "pas-de-cote",
        "regard-haut", "regard-centre", "regard-bas", "actionner",
    };

    /** Les libelles de {@code conf_page3}. */
    private static final Map<String, String> TITLES = Map.ofEntries(
        Map.entry("avant", "FORWARDS"),
        Map.entry("arriere", "BACKWARDS"),
        Map.entry("gauche", "ROTATE LEFT"),
        Map.entry("droite", "ROTATE RIGHT"),
        Map.entry("pas-gauche", "SIDESTEP LEFT"),
        Map.entry("pas-droite", "SIDESTEP RIGHT"),
        Map.entry("tirer", "FIRE"),
        Map.entry("courir", "ACCELERATION"),
        Map.entry("pas-de-cote", "FORCE SIDESTEP"),
        Map.entry("regard-haut", "LOOK UP"),
        Map.entry("regard-centre", "RESET LOOK"),
        Map.entry("regard-bas", "LOOK DOWN"),
        Map.entry("actionner", "SWITCH"));

    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    /**
     * Declare la touche d'une commande.
     *
     * @param action commande, l'un des {@link #ACTIONS}
     * @param label  nom de la touche, tel qu'il s'affiche
     * @param code   code opaque, celui de la couche graphique
     */
    public void set(String action, String label, int code) {
        bindings.put(action, new Binding(action, label, code));
    }

    /** La touche d'une commande, ou {@code null} si elle n'est pas declaree. */
    public Binding get(String action) {
        return bindings.get(action);
    }

    /** Nom de la touche d'une commande, ou {@code "?"}. */
    public String label(String action) {
        Binding binding = bindings.get(action);
        return (binding == null) ? "?" : binding.label();
    }

    /** Titre court d'une commande, pour le menu. */
    public static String title(String action) {
        return TITLES.getOrDefault(action, action);
    }

    /**
     * La commande qui emploie deja cette touche, ou {@code null}.
     *
     * <p>Sert a liberer l'ancienne commande quand on reaffecte une touche : deux
     * commandes sur la meme touche rendraient le jeu injouable.
     */
    public String userOf(int code, String except) {
        for (Binding binding : bindings.values()) {
            if (binding.code() == code && !binding.action().equals(except)) {
                return binding.action();
            }
        }
        return null;
    }

    /** Toutes les affectations, dans l'ordre de declaration. */
    public Iterable<Binding> all() {
        return bindings.values();
    }
}
