package fov.breathless.engine;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Le menu de configuration, sur le meme moniteur que le terminal.
 *
 * <p>Portage des pages {@code conf_page0} a {@code conf_page5} et de {@code DSconfig}
 * (Sorgenti/Terminal.asm), positions et libelles compris. Deux pages d'accueil : celle du
 * jeu, ouverte par Echap, et celle de la presentation, ouverte depuis le titre, qui porte
 * le depart de la partie, les options de jeu et les credits.
 *
 * <p>Sont portes a l'identique : la confirmation « REALLY QUIT ? (Y/N) », la saisie du
 * code d'acces avec son « BAD ACCESS CODE ! », la remise du code de depart, le
 * bouclage de la selection d'un bout a l'autre de la page.
 *
 * <p>Deux ecarts : la page des controles ne propose pas la souris, que ce portage
 * n'emploie pas, et le filtre audio — un passe-bas du materiel Amiga, sans equivalent —
 * cede sa ligne a l'interrupteur des bruitages.
 */
public final class ConfigMenu {

    /** Les pages du menu. */
    public enum Page { MAIN, WINDOW, SOUND, KEYBOARD, CONTROLS, OPTIONS }

    /** Le code de depart, que « RESET ACCESS CODE » remet. */
    public static final String START_CODE = "181CEIGGLJRJSE2T";

    private static final String[] ON_OFF = {"OFF", "_ON"};

    private static final String[] WINDOW_SIZES = {
        "_96*60", "128*80", "160*100", "192*120",
        "224*140", "256*160", "288*180", "320*200",
    };

    private static final String[] PIXEL_SIZES = {"1*1", "2*1", "1*2", "2*2"};
    private static final String[] MUSIC_VOLUMES = {"1", "2", "3", "4"};
    private static final String[] CONTROLS = {"KEYBOARD"};
    private static final String[] WALK_SPEEDS = {"1", "2", "3", "4"};
    private static final String[] ROT_SPEEDS = {"1", "2", "3", "4", "5", "6"};
    private static final String[] INERTIAS = {"HIGH", "_MID", "_LOW"};

    /** Ce qu'une ligne fait quand on la valide. */
    private enum Kind { TITLE, GO, CLOSE, START, QUIT, CYCLE, CREDITS, SAVE, KEY, CODE, RESET_CODE, TEXT }

    /**
     * Une ligne du menu, telle que la decrit {@code cpN_sM} : abscisse et ordonnee du
     * libelle, puis abscisse de la valeur pour une ligne de choix.
     */
    private record Line(String label, int x, int y, Kind kind, Page target, String[] values,
                        int valueX) {

        static Line title(String label, int x, int y) {
            return new Line(label, x, y, Kind.TITLE, null, null, 0);
        }

        static Line text(String label, int x, int y) {
            return new Line(label, x, y, Kind.TEXT, null, null, 0);
        }

        static Line go(String label, int x, int y, Page target) {
            return new Line(label, x, y, Kind.GO, target, null, 0);
        }

        static Line act(String label, int x, int y, Kind kind) {
            return new Line(label, x, y, kind, null, null, 0);
        }

        static Line choice(String label, int y, String[] values, int valueX) {
            return new Line(label, 4, y, Kind.CYCLE, null, values, valueX);
        }

        boolean selectable() {
            return kind != Kind.TITLE && kind != Kind.TEXT;
        }
    }

    private static final Line[] MAIN_GAME = {
        Line.title("CONFIGURATION MENU", 11, 12),
        Line.act("RETURN TO GAME", 24, 24, Kind.CLOSE),
        Line.go("WINDOW", 46, 34, Page.WINDOW),
        Line.go("SOUND", 49, 44, Page.SOUND),
        Line.go("KEYBOARD", 40, 54, Page.KEYBOARD),
        Line.go("CONTROLS", 40, 64, Page.CONTROLS),
        Line.act("QUIT GAME", 38, 74, Kind.QUIT),
    };

    private static final Line[] MAIN_PRESENTATION = {
        Line.title("CONFIGURATION MENU", 11, 12),
        Line.act("START GAME", 35, 24, Kind.START),
        Line.go("WINDOW", 46, 34, Page.WINDOW),
        Line.go("SOUND", 49, 44, Page.SOUND),
        Line.go("KEYBOARD", 40, 54, Page.KEYBOARD),
        Line.go("CONTROLS", 40, 64, Page.CONTROLS),
        Line.go("GAME OPTIONS", 29, 74, Page.OPTIONS),
        Line.act("CREDITS", 43, 84, Kind.CREDITS),
        Line.act("QUIT GAME", 38, 94, Kind.QUIT),
    };

    private static final Line[] WINDOW_LINES = {
        Line.title("WINDOW CONFIGURATION", 5, 12),
        Line.choice("WINDOW_SIZE", 34, WINDOW_SIZES, 82),
        Line.choice("PIXEL_SIZE", 44, PIXEL_SIZES, 94),
        Line.choice("SIGHT", 54, ON_OFF, 94),
        Line.go("MAIN PAGE", 38, 74, Page.MAIN),
    };

    private static final Line[] SOUND_LINES = {
        Line.title("SOUND CONFIGURATION", 8, 12),
        Line.choice("MUSIC_VOLUME", 24, MUSIC_VOLUMES, 118),
        Line.choice("MUSIC_STATE", 34, ON_OFF, 106),
        Line.choice("SOUND_STATE", 44, ON_OFF, 106),
        Line.go("MAIN PAGE", 38, 54, Page.MAIN),
    };

    private static final Line[] KEY_LINES = keyLines();

    private static Line[] keyLines() {
        Line[] lines = new Line[KeyBindings.ACTIONS.length + 2];
        lines[0] = Line.title("KEYS CONFIGURATION", 11, 0);
        for (int i = 0; i < KeyBindings.ACTIONS.length; i++) {
            lines[i + 1] = new Line(KeyBindings.ACTIONS[i], 4, 12 + i * 8, Kind.KEY, null, null, 106);
        }
        lines[lines.length - 1] = Line.go("MAIN PAGE", 38, 120, Page.MAIN);
        return lines;
    }

    private static final Line[] CONTROL_LINES = {
        Line.title("CONTROL CONFIGURATION", 2, 2),
        Line.choice("CONTROL", 14, CONTROLS, 76),
        Line.choice("WALKING INERTIA", 34, INERTIAS, 100),
        Line.choice("ROTATING INERTIA", 42, INERTIAS, 100),
        Line.text("ACCEL. KEY NOT PRESSED", 1, 54),
        Line.choice("WALKING SPEED", 64, WALK_SPEEDS, 118),
        Line.choice("ROTATING SPEED", 72, ROT_SPEEDS, 118),
        Line.text("ACCEL. KEY PRESSED", 12, 84),
        Line.choice("WALKING SPEED ", 94, WALK_SPEEDS, 118),
        Line.choice("ROTATING SPEED ", 102, ROT_SPEEDS, 118),
        Line.go("MAIN PAGE", 38, 120, Page.MAIN),
    };

    private static final Line[] OPTION_LINES = {
        Line.title("GAME OPTIONS", 29, 12),
        Line.text("ACCESS CODE", 32, 24),
        Line.act("", 16, 34, Kind.CODE),
        Line.act("RESET ACCESS CODE", 15, 46, Kind.RESET_CODE),
        Line.act("SAVE CONFIGURATION", 11, 56, Kind.SAVE),
        Line.go("MAIN PAGE", 38, 74, Page.MAIN),
    };

    /** {@code badcode} : affiche cent vingt-sept cinquantiemes en (16, 90). */
    private static final int BAD_CODE_TICKS = 127;

    private final Settings settings;
    private boolean open;
    private boolean inGame = true;
    private boolean quitting;
    private boolean askingQuit;
    private boolean startRequested;
    private boolean creditsRequested;
    private boolean saveRequested;

    /** Commande dont on attend la nouvelle touche, ou {@code null}. */
    private String capturing;
    private Page page = Page.MAIN;
    private int selection;

    /** Le code d'acces courant, {@code LevelCodeASC}, et sa saisie en cours. */
    private String accessCode = START_CODE;
    private StringBuilder codeInput;
    private int badCodeTicks;
    private Predicate<String> codeChecker = code -> true;

    /** Touches que la page du clavier refuse d'affecter : {@code keylist} y met zero. */
    private final Set<Integer> reserved = new HashSet<>();

    public ConfigMenu(Settings settings) {
        this.settings = settings;
    }

    public boolean isOpen() {
        return open;
    }

    /** Le menu est-il celui de la presentation, avant la partie ? */
    public boolean isPresentation() {
        return !inGame;
    }

    /** {@code CheckAccessCode} : dit si un code saisi est valable. */
    public void setCodeChecker(Predicate<String> checker) {
        this.codeChecker = (checker == null) ? code -> true : checker;
    }

    /** Declare les touches que le joueur ne peut pas affecter a une commande. */
    public void reserveKeys(Set<Integer> codes) {
        reserved.clear();
        reserved.addAll(codes);
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

    /** Le menu attend-il une saisie au clavier — touche, code, ou reponse Y/N ? */
    public boolean wantsRawKeys() {
        return open && (capturing != null || codeInput != null || askingQuit);
    }

    /**
     * Affecte une touche a la commande en attente.
     *
     * <p>Une touche reservee est refusee : la commande garde la sienne. Si une autre
     * commande employait deja la touche, elle la perd — deux commandes sur la meme touche
     * rendraient le jeu injouable.
     *
     * @return la commande affectee, ou {@code null} si l'on n'attendait rien ou si la
     *         touche est refusee
     */
    public String captureKey(String label, int code) {
        String action = capturing;
        if (action == null) {
            return null;
        }
        capturing = null;
        if (reserved.contains(code)) {
            return null;
        }
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
     * Saisie d'un caractere du code, d'un effacement, d'une validation ou d'un abandon.
     *
     * <p>{@code DSCMgoloop2} : le tampon part de seize traits bas, que les caracteres
     * tapes remplacent un a un ; Retour valide, Echap abandonne, Effacement recule.
     */
    public void typeCode(char character) {
        if (codeInput == null) {
            return;
        }
        int cursor = codeInput.indexOf("_");
        if (cursor < 0) {
            return;
        }
        char upper = Character.toUpperCase(character);
        if ((upper >= '0' && upper <= '9') || (upper >= 'A' && upper <= 'Z')) {
            codeInput.setCharAt(cursor, upper);
        }
    }

    public void eraseCode() {
        if (codeInput == null) {
            return;
        }
        int cursor = codeInput.indexOf("_");
        int last = (cursor < 0) ? codeInput.length() - 1 : cursor - 1;
        if (last >= 0) {
            codeInput.setCharAt(last, '_');
        }
    }

    public void confirmCode() {
        if (codeInput == null) {
            return;
        }
        String typed = codeInput.toString();
        codeInput = null;
        accessCode = typed;
        if (!codeChecker.test(typed)) {
            badCodeTicks = BAD_CODE_TICKS;
        }
    }

    public void cancelCode() {
        codeInput = null;
    }

    /** Reponse a « REALLY QUIT ? (Y/N) ». Toute autre touche est ignoree. */
    public void answerQuit(boolean yes) {
        if (!askingQuit) {
            return;
        }
        askingQuit = false;
        if (yes) {
            quitting = true;
        }
        // DSCMrtg : oui ou non, le menu se ferme.
        close();
    }

    public boolean isAskingQuit() {
        return askingQuit;
    }

    /** Fait s'ecouler le temps du menu : le message de code errone s'efface. */
    public void advance(int ticks) {
        if (badCodeTicks > 0) {
            badCodeTicks = Math.max(0, badCodeTicks - ticks);
        }
    }

    /** Le joueur a-t-il demande les credits ? L'appel efface la demande. */
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

    /** Le joueur a-t-il lance la partie depuis la presentation ? L'appel efface la demande. */
    public boolean takeStartRequest() {
        boolean wanted = startRequested;
        startRequested = false;
        return wanted;
    }

    /**
     * Le joueur a-t-il confirme vouloir quitter ? En jeu, cela ramene a la presentation ;
     * depuis la presentation, cela ferme le programme. L'appel efface la reponse.
     */
    public boolean takeQuitRequest() {
        boolean wanted = quitting;
        quitting = false;
        return wanted;
    }

    public Page page() {
        return page;
    }

    /** Index de la ligne choisie parmi les lignes selectionnables. */
    public int selection() {
        return selectableIndex(selection);
    }

    public int lineCount() {
        int count = 0;
        for (Line line : lines()) {
            if (line.selectable()) {
                count++;
            }
        }
        return count;
    }

    /** Le code d'acces, {@code LevelCodeASC}. */
    public String accessCode() {
        return accessCode;
    }

    /** Le code d'acces courant : celui calcule a l'entree du niveau, ou celui saisi. */
    public void setAccessCode(String code) {
        this.accessCode = (code == null || code.isEmpty()) ? START_CODE : code;
    }

    /** Ouvre le menu du jeu. */
    public void open() {
        open(true);
    }

    /**
     * Ouvre le menu.
     *
     * @param duringGame vrai pour le menu du jeu, faux pour celui de la presentation
     */
    public void open(boolean duringGame) {
        inGame = duringGame;
        open = true;
        quitting = false;
        askingQuit = false;
        startRequested = false;
        creditsRequested = false;
        saveRequested = false;
        capturing = null;
        codeInput = null;
        badCodeTicks = 0;
        page = Page.MAIN;
        selection = firstSelectable();
    }

    public void close() {
        open = false;
        capturing = null;
        codeInput = null;
    }

    /** {@code Tkeyup} : au-dessus de la premiere ligne, on passe a la derniere. */
    public void up() {
        Line[] lines = lines();
        int next = selection;
        do {
            next = Math.floorMod(next - 1, lines.length);
        } while (!lines[next].selectable());
        selection = next;
    }

    /** {@code Tkeydown} : au-dessous de la derniere ligne, on revient a la premiere. */
    public void down() {
        Line[] lines = lines();
        int next = selection;
        do {
            next = Math.floorMod(next + 1, lines.length);
        } while (!lines[next].selectable());
        selection = next;
    }

    /**
     * Valide la ligne choisie.
     *
     * <p>Une ligne de choix fait defiler sa liste d'un cran, comme dans l'original ou la
     * meme touche sert a avancer dans les valeurs.
     */
    public void select() {
        if (askingQuit || codeInput != null) {
            return;
        }
        Line line = lines()[selection];
        switch (line.kind()) {
            case GO -> {
                page = line.target();
                selection = firstSelectable();
            }
            case CLOSE -> close();
            case START -> {
                startRequested = true;
                close();
            }
            case QUIT -> askingQuit = true;
            case CYCLE -> cycle(line, +1);
            case CREDITS -> {
                creditsRequested = true;
                close();
            }
            case SAVE -> saveRequested = true;
            case KEY -> capturing = line.label();
            case CODE -> codeInput = new StringBuilder("_".repeat(START_CODE.length()));
            case RESET_CODE -> accessCode = START_CODE;
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
            case "WALKING INERTIA" -> settings.walkInertia();
            case "ROTATING INERTIA" -> settings.rotInertia();
            case "WALKING SPEED" -> settings.walkSpeed();
            case "ROTATING SPEED" -> settings.rotSpeed();
            case "WALKING SPEED " -> settings.runSpeed();
            case "ROTATING SPEED " -> settings.runRotSpeed();
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
            case "WALKING INERTIA" -> settings.setControl("WALK_INERTIA", index);
            case "ROTATING INERTIA" -> settings.setControl("ROT_INERTIA", index);
            case "WALKING SPEED" -> settings.setControl("WALK", index);
            case "ROTATING SPEED" -> settings.setControl("ROT", index);
            case "WALKING SPEED " -> settings.setControl("RUN", index);
            case "ROTATING SPEED " -> settings.setControl("RUN_ROT", index);
            default -> {
                // Rien a ranger.
            }
        }
    }

    private Line[] lines() {
        return switch (page) {
            case MAIN -> inGame ? MAIN_GAME : MAIN_PRESENTATION;
            case WINDOW -> WINDOW_LINES;
            case SOUND -> SOUND_LINES;
            case KEYBOARD -> KEY_LINES;
            case CONTROLS -> CONTROL_LINES;
            case OPTIONS -> OPTION_LINES;
        };
    }

    private int firstSelectable() {
        Line[] lines = lines();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].selectable()) {
                return i;
            }
        }
        return 0;
    }

    private int selectableIndex(int lineIndex) {
        Line[] lines = lines();
        int count = 0;
        for (int i = 0; i < lineIndex && i < lines.length; i++) {
            if (lines[i].selectable()) {
                count++;
            }
        }
        return count;
    }

    /** Texte de la n-ieme ligne selectionnable, valeur comprise. Pour les tests. */
    public String line(int index) {
        Line[] lines = lines();
        int count = -1;
        for (Line line : lines) {
            if (!line.selectable()) {
                continue;
            }
            count++;
            if (count == index) {
                return describe(line);
            }
        }
        throw new IndexOutOfBoundsException(index);
    }

    private String describe(Line line) {
        return switch (line.kind()) {
            case KEY -> KeyBindings.title(line.label()) + " " + settings.keys().label(line.label());
            case CYCLE -> line.label().strip() + " " + line.values()[valueOf(line)];
            case CODE -> "ACCESS CODE " + accessCode;
            default -> line.label();
        };
    }

    /** Dessine le menu par-dessus la vue. */
    public void render(byte[] target, int width, int height) {
        Monitor.clear(target, width, height);
        // askquit commence par -1 : le moniteur est efface, la question reste seule.
        if (askingQuit) {
            Monitor.print(target, width, height, 10, 20, "REALLY QUIT ? (Y/N)",
                    Monitor.COLOUR_SELECTED);
            return;
        }

        Line[] lines = lines();
        for (int i = 0; i < lines.length; i++) {
            Line line = lines[i];
            int colour = (i == selection) ? Monitor.COLOUR_SELECTED : Monitor.COLOUR_TEXT;
            switch (line.kind()) {
                case KEY -> {
                    Monitor.print(target, width, height, line.x(), line.y(),
                            KeyBindings.title(line.label()), colour);
                    String value = line.label().equals(capturing)
                            ? "___" : settings.keys().label(line.label());
                    Monitor.print(target, width, height, line.valueX(), line.y(), value,
                            Monitor.COLOUR_SELECTED);
                }
                case CYCLE -> {
                    Monitor.print(target, width, height, line.x(), line.y(), line.label(), colour);
                    Monitor.print(target, width, height, line.valueX(), line.y(),
                            line.values()[valueOf(line)], Monitor.COLOUR_SELECTED);
                }
                case CODE -> {
                    String shown = (codeInput != null) ? codeInput.toString() : accessCode;
                    int codeColour = (codeInput != null) ? Monitor.COLOUR_SELECTED : colour;
                    Monitor.print(target, width, height, line.x(), line.y(), shown, codeColour);
                }
                case TITLE, TEXT -> Monitor.print(target, width, height, line.x(), line.y(),
                        line.label(), Monitor.COLOUR_TEXT);
                default -> Monitor.print(target, width, height, line.x(), line.y(),
                        line.label(), colour);
            }
        }
        if (badCodeTicks > 0) {
            Monitor.print(target, width, height, 16, 90, "BAD ACCESS CODE !",
                    Monitor.COLOUR_REFUSED);
        }
    }
}
