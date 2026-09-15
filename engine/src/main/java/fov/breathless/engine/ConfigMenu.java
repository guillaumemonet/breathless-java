package fov.breathless.engine;

/**
 * Le menu de configuration, sur le meme moniteur que le terminal.
 *
 * <p>Portage des pages {@code conf_page} et de {@code DSconfig} (Sorgenti/Terminal.asm).
 * Trois sortes de lignes, comme dans l'original :
 *
 * <ul>
 *   <li>une <strong>action</strong>, qui change de page ou ferme le menu ;</li>
 *   <li>un <strong>choix</strong>, qui fait defiler une liste de valeurs et la range dans
 *       un reglage ;</li>
 *   <li>un <strong>affichage</strong>, qui ne montre qu'une valeur.</li>
 * </ul>
 *
 * <p>Deux pages de l'original ne sont pas portees : le reglage des touches et celui des
 * controles, qui supposent une saisie de touche que ce portage n'offre pas — ses touches
 * sont fixes. Le filtre audio non plus : c'est un filtre passe-bas du materiel Amiga, qui
 * n'a pas d'equivalent ici.
 */
public final class ConfigMenu {

    /** Les pages du menu. */
    public enum Page { MAIN, WINDOW, SOUND, KEYBOARD, OPTIONS }

    /** Les valeurs qu'une ligne de choix fait defiler. */
    private static final String[] ON_OFF = {"OFF", "_ON"};

    private static final String[] WINDOW_SIZES = {
        "_96*60", "128*80", "160*100", "192*120",
        "224*140", "256*160", "288*180", "320*200",
    };

    private static final String[] PIXEL_SIZES = {"1*1", "2*1", "1*2", "2*2"};

    private static final String[] MUSIC_VOLUMES = {"1", "2", "3", "4"};

    /** Ce qu'une ligne fait quand on la valide. */
    private enum Kind { GO, CLOSE, QUIT, CYCLE, SHOW, CREDITS, SAVE, KEY }

    /**
     * Une ligne du menu.
     *
     * @param label  texte a gauche
     * @param y      ordonnee dans le moniteur
     * @param kind   nature de la ligne
     * @param target page a atteindre, pour une ligne {@link Kind#GO}
     * @param values valeurs a faire defiler, pour une ligne {@link Kind#CYCLE}
     */
    private record Line(String label, int y, Kind kind, Page target, String[] values) {
    }

    /** Abscisse des lignes, et des valeurs a droite. Reprises des donnees d'origine. */
    private static final int LABEL_X = 4;
    private static final int VALUE_X = 82;

    private static final Line[] MAIN_LINES = {
        new Line("RETURN TO GAME", 24, Kind.CLOSE, null, null),
        new Line("WINDOW", 34, Kind.GO, Page.WINDOW, null),
        new Line("SOUND", 44, Kind.GO, Page.SOUND, null),
        new Line("KEYBOARD", 54, Kind.GO, Page.KEYBOARD, null),
        new Line("GAME OPTIONS", 64, Kind.GO, Page.OPTIONS, null),
        new Line("QUIT GAME", 74, Kind.QUIT, null, null),
    };

    private static final Line[] WINDOW_LINES = {
        new Line("WINDOW_SIZE", 34, Kind.CYCLE, null, WINDOW_SIZES),
        new Line("PIXEL_SIZE", 44, Kind.CYCLE, null, PIXEL_SIZES),
        new Line("SIGHT", 54, Kind.CYCLE, null, ON_OFF),
        new Line("MAIN PAGE", 74, Kind.GO, Page.MAIN, null),
    };

    private static final Line[] SOUND_LINES = {
        new Line("MUSIC_VOLUME", 34, Kind.CYCLE, null, MUSIC_VOLUMES),
        new Line("MUSIC_STATE", 44, Kind.CYCLE, null, ON_OFF),
        new Line("SOUND_STATE", 54, Kind.CYCLE, null, ON_OFF),
        new Line("MAIN PAGE", 74, Kind.GO, Page.MAIN, null),
    };

    /** Une ligne par commande redefinissable, plus le retour. */
    private static final Line[] KEY_LINES = keyLines();

    private static Line[] keyLines() {
        Line[] lines = new Line[KeyBindings.ACTIONS.length + 1];
        for (int i = 0; i < KeyBindings.ACTIONS.length; i++) {
            lines[i] = new Line(KeyBindings.ACTIONS[i], 24 + i * 8, Kind.KEY, null, null);
        }
        lines[lines.length - 1] = new Line("MAIN PAGE", 112, Kind.GO, Page.MAIN, null);
        return lines;
    }

    private static final Line[] OPTION_LINES = {
        new Line("ACCESS CODE", 34, Kind.SHOW, null, null),
        new Line("SAVE CONFIG", 54, Kind.SAVE, null, null),
        new Line("CREDITS", 64, Kind.CREDITS, null, null),
        new Line("MAIN PAGE", 74, Kind.GO, Page.MAIN, null),
    };

    private final Settings settings;
    private boolean open;
    private boolean quitting;
    private boolean creditsRequested;

    /** Commande dont on attend la nouvelle touche, ou {@code null}. */
    private String capturing;
    private boolean saveRequested;
    private Page page = Page.MAIN;
    private int selection;
    private String accessCode = "";

    public ConfigMenu(Settings settings) {
        this.settings = settings;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * La commande dont le menu attend une touche, ou {@code null}.
     *
     * <p>Tant qu'elle n'est pas nulle, la couche graphique doit lui donner la prochaine
     * touche pressee plutot que de l'interpreter.
     */
    public String capturing() {
        return capturing;
    }

    /**
     * Affecte une touche a la commande en attente.
     *
     * <p>Si une autre commande employait deja cette touche, elle la perd : deux commandes
     * sur la meme touche rendraient le jeu injouable.
     *
     * @param label nom de la touche, tel qu'il s'affiche
     * @param code  code opaque de la couche graphique
     * @return la commande affectee, ou {@code null} si l'on n'attendait rien
     */
    public String captureKey(String label, int code) {
        String action = capturing;
        if (action == null) {
            return null;
        }
        capturing = null;
        String previous = settings.keys().userOf(code, action);
        if (previous != null) {
            settings.keys().set(previous, "-", 0);
        }
        settings.keys().set(action, label, code);
        settings.touch();
        return action;
    }

    /** Abandonne l'attente d'une touche. */
    public void cancelCapture() {
        capturing = null;
    }

    /**
     * Le joueur a-t-il demande les credits ?
     *
     * <p>L'appel efface la demande : elle ne vaut qu'une fois.
     */
    public boolean takeCreditsRequest() {
        boolean wanted = creditsRequested;
        creditsRequested = false;
        return wanted;
    }

    /** Le joueur a-t-il demande d'enregistrer la configuration ? L'appel efface la demande. */
    public boolean takeSaveRequest() {
        boolean wanted = saveRequested;
        saveRequested = false;
        return wanted;
    }

    /** Le joueur a-t-il demande a quitter ? */
    public boolean isQuitting() {
        return quitting;
    }

    public Page page() {
        return page;
    }

    public int selection() {
        return selection;
    }

    public int lineCount() {
        return lines().length;
    }

    /** Le code de la partie en cours, montre sur la page des options. */
    public void setAccessCode(String code) {
        this.accessCode = (code == null) ? "" : code;
    }

    public void open() {
        open = true;
        quitting = false;
        creditsRequested = false;
        saveRequested = false;
        capturing = null;
        page = Page.MAIN;
        selection = 0;
    }

    public void close() {
        open = false;
    }

    public void up() {
        if (selection > 0) {
            selection--;
        }
    }

    public void down() {
        if (selection < lines().length - 1) {
            selection++;
        }
    }

    /**
     * Valide la ligne choisie.
     *
     * <p>Une ligne de choix fait defiler sa liste d'un cran, comme dans l'original ou la
     * meme touche sert a avancer dans les valeurs.
     */
    public void select() {
        Line line = lines()[selection];
        switch (line.kind()) {
            case GO -> {
                page = line.target();
                selection = 0;
            }
            case CLOSE -> close();
            case QUIT -> {
                quitting = true;
                close();
            }
            case CYCLE -> cycle(line, +1);
            case CREDITS -> {
                creditsRequested = true;
                close();
            }
            case SAVE -> saveRequested = true;
            case KEY -> capturing = line.label();
            default -> {
                // Une ligne d'affichage ne fait rien.
            }
        }
    }

    /** Fait defiler la valeur de la ligne choisie, si c'en est une. */
    public void change(int steps) {
        Line line = lines()[selection];
        if (line.kind() == Kind.CYCLE) {
            cycle(line, steps);
        }
    }

    private void cycle(Line line, int steps) {
        int count = line.values().length;
        int next = Math.floorMod(valueOf(line) + steps, count);
        setValue(line, next);
    }

    /** Index de la valeur courante d'une ligne de choix. */
    private int valueOf(Line line) {
        return switch (line.label()) {
            case "WINDOW_SIZE" -> settings.viewSize().index();
            case "PIXEL_SIZE" -> settings.viewSize().pixelType();
            case "SIGHT" -> settings.sight() ? 1 : 0;
            case "MUSIC_VOLUME" -> settings.musicVolume() - 1;
            case "MUSIC_STATE" -> settings.music() ? 1 : 0;
            case "SOUND_STATE" -> settings.sound() ? 1 : 0;
            default -> 0;
        };
    }

    private void setValue(Line line, int index) {
        switch (line.label()) {
            case "WINDOW_SIZE" -> settings.viewSize().resize(index - settings.viewSize().index());
            case "PIXEL_SIZE" -> settings.viewSize().setPixelType(index);
            case "SIGHT" -> settings.setSight(index != 0);
            case "MUSIC_VOLUME" -> settings.setMusicVolume(index + 1);
            case "MUSIC_STATE" -> settings.setMusic(index != 0);
            case "SOUND_STATE" -> settings.setSound(index != 0);
            default -> {
                // Rien a ranger.
            }
        }
    }

    private Line[] lines() {
        return switch (page) {
            case MAIN -> MAIN_LINES;
            case WINDOW -> WINDOW_LINES;
            case SOUND -> SOUND_LINES;
            case KEYBOARD -> KEY_LINES;
            case OPTIONS -> OPTION_LINES;
        };
    }

    /** Texte de la ligne d'index donne, valeur comprise. Pour les tests. */
    public String line(int index) {
        Line line = lines()[index];
        if (line.kind() == Kind.KEY) {
            return KeyBindings.title(line.label()) + " " + settings.keys().label(line.label());
        }
        if (line.kind() == Kind.CYCLE) {
            return line.label() + " " + line.values()[valueOf(line)];
        }
        if (line.kind() == Kind.SHOW) {
            return line.label() + " " + accessCode;
        }
        return line.label();
    }

    /** Dessine le menu par-dessus la vue. */
    public void render(byte[] target, int width, int height) {
        Monitor.clear(target, width, height);

        String title = switch (page) {
            case WINDOW -> "WINDOW CONFIGURATION";
            case SOUND -> "SOUND CONFIGURATION";
            case KEYBOARD -> "KEYBOARD";
            case OPTIONS -> "GAME OPTIONS";
            default -> "CONFIGURATION MENU";
        };
        int titleX = Math.max(0, (Monitor.WIDTH - Monitor.textWidth(title)) / 2);
        Monitor.print(target, width, height, titleX, 12, title, Monitor.COLOUR_TEXT);

        Line[] lines = lines();
        for (int i = 0; i < lines.length; i++) {
            Line line = lines[i];
            int colour = (i == selection) ? Monitor.COLOUR_SELECTED : Monitor.COLOUR_TEXT;
            String label = (line.kind() == Kind.KEY)
                    ? KeyBindings.title(line.label()) : line.label();
            Monitor.print(target, width, height, LABEL_X, line.y(), label, colour);

            if (line.kind() == Kind.KEY) {
                String value = line.label().equals(capturing)
                        ? "?" : settings.keys().label(line.label());
                Monitor.print(target, width, height, VALUE_X, line.y(), value, colour);
            } else if (line.kind() == Kind.CYCLE) {
                Monitor.print(target, width, height, VALUE_X, line.y(),
                        line.values()[valueOf(line)], colour);
            } else if (line.kind() == Kind.SHOW && !accessCode.isEmpty()) {
                // Le code tient sur seize caracteres : il va a la ligne, sous son titre.
                Monitor.print(target, width, height, LABEL_X, line.y() + 10,
                        accessCode, Monitor.COLOUR_TEXT);
            }
        }
    }
}
