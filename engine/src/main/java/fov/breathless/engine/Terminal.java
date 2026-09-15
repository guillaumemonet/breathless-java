package fov.breathless.engine;


/**
 * Le terminal : la boutique du jeu, et la seule source d'armes.
 *
 * <p>Portage de {@code Terminal} et {@code DoSelection} (Sorgenti/Terminal.asm). Un
 * effet de bloc l'ouvre ({@code Terminal} dans Animations.asm) ; le jeu s'arrete tant
 * qu'il est la. Quatre pages : l'accueil, les armes, leurs ameliorations, les
 * accessoires. Tout se paie en credits ramasses dans les niveaux.
 *
 * <p>Ni {@code CollectWeapon} ni {@code BoostWeapon} ne sont appeles ailleurs que d'ici :
 * sans terminal, un joueur ne quitte jamais l'arme de base.
 *
 * <p><strong>Apparence</strong> : l'original dessine ce menu avec des sprites materiels,
 * sur un « moniteur » de 128 pixels sur 132 pose en haut a gauche de la vue, en deux
 * couleurs, le fond transparent laissant voir le decor. Les textes, leurs positions et
 * les prix sont ceux d'origine. Deux ecarts assumes : le fond est ici opaque, faute de
 * quoi le texte se perd dans le decor, et les teintes exactes des sprites ne se deduisent
 * pas de la source.
 */
public final class Terminal {

    /** Les quatre pages, dans l'ordre ou {@code TermPages} les range. */
    public enum Page { MAIN, WEAPONS, BOOSTS, ACCESSORIES }

    /**
     * Une ligne choisissable.
     *
     * @param label  texte affiche, tel quel dans les donnees d'origine
     * @param y      ordonnee dans le moniteur
     * @param price  cout en credits, negatif si l'article n'existe pas
     * @param target arme concernee, ou sous-type d'objet a ramasser
     * @param amount quantite pour un accessoire
     */
    private record Entry(String label, int y, int price, int target, int amount) {
    }

    private static final Entry[] MAIN_ENTRIES = {
        new Entry("- WEAPONS", 40, 0, 0, 0),
        new Entry("- WEAPONS BOOST", 48, 0, 0, 0),
        new Entry("- ACCESSORIES", 56, 0, 0, 0),
        new Entry("- EXIT", 64, 0, 0, 0),
    };

    private static final Entry[] WEAPON_ENTRIES = {
        new Entry("-FIREBALLS_____  4000", 47, 4000, 1, 0),
        new Entry("-PLASMA_GUN____  9000", 54, 9000, 2, 0),
        new Entry("-FLAME-THROWER_ 13000", 61, 13000, 3, 0),
        new Entry("-MAGNETIC_GUN__ 20000", 68, 20000, 4, 0),
        new Entry("-DEATH_MACHINE_ 35000", 75, 35000, 5, 0),
        new Entry("-EXIT", 82, 0, -1, 0),
    };

    private static final Entry[] BOOST_ENTRIES = {
        new Entry("-SIMPLE_SHOT___  1500", 40, 1500, 0, 0),
        new Entry("-FIREBALLS_____  2000", 47, 2000, 1, 0),
        new Entry("-PLASMA_GUN____  5000", 54, 5000, 2, 0),
        new Entry("-FLAME-THROWER_   N A", 61, -1, 3, 0),
        new Entry("-MAGNETIC_GUN__ 20000", 68, 20000, 4, 0),
        new Entry("-DEATH_MACHINE_ 20000", 75, 20000, 5, 0),
        new Entry("-EXIT", 82, 0, -1, 0),
    };

    private static final Entry[] ACCESSORY_ENTRIES = {
        new Entry("- HEALTH_+10_____ 200", 40, 200, PlayerState.PICKUP_HEALTH, 10),
        new Entry("- SHIELDS_+10____ 150", 47, 150, PlayerState.PICKUP_SHIELDS, 10),
        new Entry("- ENERGY_+100____ 200", 54, 200, PlayerState.PICKUP_ENERGY, 100),
        new Entry("- GREEN_KEY_____ 5000", 61, 5000, PlayerState.PICKUP_GREEN_KEY, 1),
        new Entry("- YELLOW_KEY____ 5000", 68, 5000, PlayerState.PICKUP_YELLOW_KEY, 1),
        new Entry("- RED_KEY_______ 5000", 75, 5000, PlayerState.PICKUP_RED_KEY, 1),
        new Entry("- BLUE_KEY______ 5000", 82, 5000, PlayerState.PICKUP_BLUE_KEY, 1),
        new Entry("- EXIT", 89, 0, -1, 0),
    };

    private final PlayerState state;
    private final Weapons weapons;

    private boolean open;
    private int number;
    private Page page = Page.MAIN;
    private int selection;
    private boolean refused;

    public Terminal(PlayerState state, Weapons weapons) {
        this.state = state;
        this.weapons = weapons;
    }

    /** Le terminal est-il affiche ? Le jeu est alors suspendu. */
    public boolean isOpen() {
        return open;
    }

    /** Numero du terminal, tel que l'effet le donne. */
    public int number() {
        return number;
    }

    public Page page() {
        return page;
    }

    public int selection() {
        return selection;
    }

    /** Le dernier achat a-t-il ete refuse, faute de credits ou d'article ? */
    public boolean refused() {
        return refused;
    }

    /** Ouvre le terminal. {@code Terminal} dans Animations.asm. */
    public void open(int terminalNumber) {
        this.number = terminalNumber;
        this.open = true;
        this.page = Page.MAIN;
        this.selection = 0;
        this.refused = false;
    }

    public void close() {
        open = false;
    }

    /** Monte d'une ligne, sans sortir de la liste. */
    public void up() {
        if (selection > 0) {
            selection--;
            refused = false;
        }
    }

    /** Descend d'une ligne, sans sortir de la liste. */
    public void down() {
        if (selection < entries().length - 1) {
            selection++;
            refused = false;
        }
    }

    /**
     * Valide la ligne choisie : change de page, achete, ou sort.
     *
     * <p>{@code DoSelection}. Un achat impossible — credits insuffisants, arme deja
     * possedee, article inexistant — ne prend rien et allume le refus.
     *
     * @return {@code true} si quelque chose a ete achete
     */
    public boolean select() {
        refused = false;
        Entry entry = entries()[selection];

        if (page == Page.MAIN) {
            switch (selection) {
                case 0 -> enter(Page.WEAPONS);
                case 1 -> enter(Page.BOOSTS);
                case 2 -> enter(Page.ACCESSORIES);
                default -> close();
            }
            return false;
        }
        if (entry.target() < 0) {
            enter(Page.MAIN);
            return false;
        }
        if (entry.price() < 0 || state.credits() < entry.price()) {
            refused = true;
            return false;
        }

        boolean bought = switch (page) {
            case WEAPONS -> buyWeapon(entry);
            case BOOSTS -> buyBoost(entry);
            case ACCESSORIES -> state.collect(entry.target(), entry.amount());
            default -> false;
        };
        if (!bought) {
            refused = true;
            return false;
        }
        state.spendCredits(entry.price());
        return true;
    }

    private boolean buyWeapon(Entry entry) {
        if (weapons.owns(entry.target())) {
            return false;   // CollectWeapon refuse une arme deja possedee
        }
        weapons.give(entry.target());
        return weapons.owns(entry.target());
    }

    private boolean buyBoost(Entry entry) {
        if (!weapons.owns(entry.target()) || weapons.isBoosted(entry.target())) {
            return false;   // BoostWeapon exige une arme possedee et pas encore amelioree
        }
        weapons.boost(entry.target());
        return weapons.isBoosted(entry.target());
    }

    private void enter(Page next) {
        page = next;
        selection = 0;
    }

    private Entry[] entries() {
        return switch (page) {
            case MAIN -> MAIN_ENTRIES;
            case WEAPONS -> WEAPON_ENTRIES;
            case BOOSTS -> BOOST_ENTRIES;
            case ACCESSORIES -> ACCESSORY_ENTRIES;
        };
    }

    /** Nombre de lignes de la page courante, pour les tests. */
    public int lineCount() {
        return entries().length;
    }

    /** Texte de la ligne d'index donne sur la page courante, pour les tests. */
    public String line(int index) {
        return entries()[index].label();
    }

    /**
     * Dessine le moniteur par-dessus la vue.
     *
     * @param target tampon de la vue, en index de palette
     */
    public void render(byte[] target, int width, int height) {
        Monitor.clear(target, width, height);

        print(target, width, height, 7, 6, "YOU ARE CONNECTED TO", Monitor.COLOUR_TEXT);
        print(target, width, height, 13, 12, "TERMINAL NUMBER", Monitor.COLOUR_TEXT);
        // Le numero tient dans trois caracteres, cales a droite : c'est le champ que
        // l'original reserve, et c'est ce qui l'empeche de mordre sur le mot qui precede.
        print(target, width, height, 100, 12,
                String.format("%3d", Math.max(0, number)), Monitor.COLOUR_TEXT);

        // Abscisses des titres, telles quelles dans les donnees d'origine.
        String title;
        int titleX;
        switch (page) {
            case WEAPONS -> {
                title = "WEAPONS";
                titleX = 43;
            }
            case BOOSTS -> {
                title = "WEAPONS BOOST";
                titleX = 26;
            }
            case ACCESSORIES -> {
                title = "ACCESSORIES";
                titleX = 31;
            }
            default -> {
                title = "MAIN PAGE";
                titleX = 37;
            }
        }
        print(target, width, height, titleX, 24, title, Monitor.COLOUR_TEXT);

        Entry[] entries = entries();
        for (int i = 0; i < entries.length; i++) {
            boolean chosen = (i == selection);
            int colour = chosen ? (refused ? Monitor.COLOUR_REFUSED : Monitor.COLOUR_SELECTED) : Monitor.COLOUR_TEXT;
            print(target, width, height, 3, entries[i].y(), entries[i].label(), colour);
        }
    }

    private static void print(byte[] target, int width, int height,
                              int x, int y, String text, int colour) {
        Monitor.print(target, width, height, x, y, text, colour);
    }
}
