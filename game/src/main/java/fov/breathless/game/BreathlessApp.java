package fov.breathless.game;

import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.Texture;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;
import fov.breathless.engine.Camera;
import fov.breathless.engine.Crosshair;
import fov.breathless.engine.Framebuffer;
import fov.breathless.engine.LevelCode;
import fov.breathless.engine.Hud;
import fov.breathless.engine.PlayerInput;
import fov.breathless.engine.PlayerState;
import fov.breathless.engine.Simulation;
import fov.breathless.engine.Terminal;
import fov.breathless.engine.Renderer;
import fov.breathless.engine.ConfigMenu;
import fov.breathless.engine.Settings;
import fov.breathless.engine.ViewSize;
import fov.breathless.engine.Viewport;
import fov.breathless.engine.World;
import fov.breathless.gld.level.Level;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * La coquille : une fenetre, un quad plein ecran, et le tampon du moteur televerse a
 * chaque image.
 *
 * <p>jMonkeyEngine ne fait ici aucun rendu 3d. Tout le travail est accompli par le
 * rasteriseur logiciel ; le moteur graphique se contente d'afficher son resultat et de
 * fournir la fenetre et le clavier.
 *
 * <p>Le jeu est simule a <strong>pas fixe</strong>, cinquante fois par seconde. Les
 * constantes de deplacement du moteur d'origine sont exprimees par image sur une machine
 * PAL : les lier a la frequence de l'ecran rendrait le jeu plus ou moins rapide selon le
 * moniteur.
 */
public final class BreathlessApp extends SimpleApplication implements ActionListener {

    /** Resolution interne, celle du jeu d'origine. */
    private static final int WIDTH = 320;

    /** Hauteur de la vue en trois dimensions. Le panneau de bord vient dessous. */
    private static final int VIEW_HEIGHT = 200;

    /** Niveaux par monde : les vingt niveaux du jeu se rangent en quatre mondes de cinq. */
    private static final int LEVELS_PER_GAME = 5;

    /** Tentatives accordees au depart. {@code move.b #3,Retries(a5)}. */
    private static final int RETRIES = 3;

    /** Frequence de simulation du jeu d'origine, en hertz. */
    private static final float TICK = 1f / 50f;

    /** Au-dela, on renonce a rattraper : mieux vaut ralentir que spiraler. */
    private static final float MAX_CATCH_UP = 0.25f;

    private final GameData data;
    private final PlayerInput input = new PlayerInput();
    private final Settings settings = new Settings();
    private final ViewSize viewSize = settings.viewSize();
    private final ConfigMenu configMenu = new ConfigMenu(settings);
    private int settingsRevision = -1;
    private Viewport viewport = new Viewport(WIDTH, VIEW_HEIGHT);
    private final Hud hud;

    /** Hauteur totale affichee : la vue, et le panneau de bord s'il est disponible. */
    private final int screenHeight;

    /** Vue et panneau composes, tels qu'ils partent vers la carte graphique. */
    private final byte[] screenPixels;

    /** Tampon de la carte, de la taille de la vue. */
    private final byte[] mapPixels = new byte[WIDTH * VIEW_HEIGHT];

    private ByteBuffer pixels;
    private Image image;
    private Picture screen;

    private World world;
    private Renderer renderer;
    private Simulation simulation;
    private Camera camera;

    /** Dernier etat affiche, pour ne signaler que les changements. */
    private String lastReport = "";
    private int levelIndex;
    private float accumulator;

    /** La carte est-elle affichee ? Elle remplace la vue et suspend le jeu, comme l'original. */
    private boolean showMap;
    private boolean restored;
    private boolean paused;

    /**
     * Tentatives restantes. {@code Retries} : trois au depart, une de moins a chaque
     * mort, et la partie s'arrete quand il n'en reste plus.
     */
    private int retries = RETRIES;

    /** Ecran fixe affiche, ou {@code null} quand on joue. */
    private fov.breathless.gld.gfx.Picture screenPicture;
    private Screens screens;

    /** Images restant a afficher avant de passer, ou 0 si l'on attend une touche. */
    private int screenTicks;

    /** Ce qu'il faudra faire quand le joueur aura valide l'ecran fixe. */
    private Runnable afterScreen;

    /** Lecteur de bruitages, ou {@code null} si la banque sonore manque. */
    private JmeSoundPlayer soundPlayer;
    private final fov.breathless.gld.gfx.PlanarImage backdrop;

    /** Partie reprise par son code, ou {@code null} pour un depart du debut. */
    private final LevelCode.State saved;

    /** Dernier monde presente, pour ne pas le repeter a chaque niveau. */
    private int introducedWorld;

    BreathlessApp(GameData data, LevelCode.State saved) {
        this.data = data;
        this.saved = saved;
        this.backdrop = data.backdrop();
        this.hud = (data.panel() == null) ? null : new Hud(data.panel());
        this.screenHeight = VIEW_HEIGHT + (hud == null ? 0 : Hud.HEIGHT);
        this.screenPixels = new byte[WIDTH * screenHeight];
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        // La souris ne sert pas encore : inutile de masquer le curseur.
        setDisplayStatView(false);
        setDisplayFps(false);
        // jMonkeyEngine relie Echap a sa propre commande de sortie. Ici Echap ouvre le
        // menu de configuration, qui porte la sortie : sans ce retrait, le jeu quitterait
        // avant d'avoir montre le menu.
        if (inputManager.hasMapping(INPUT_MAPPING_EXIT)) {
            inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        }

        pixels = BufferUtils.createByteBuffer(
                WIDTH * screenHeight * PixelUpload.BYTES_PER_PIXEL);
        image = new Image(Image.Format.RGBA8, WIDTH, screenHeight, pixels, ColorSpace.Linear);

        Texture2D texture = new Texture2D(image);
        // Le jeu est fait de gros pixels : les lisser trahirait son aspect.
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);

        screen = new Picture("ecran");
        screen.setTexture(assetManager, texture, false);
        guiNode.attachChild(screen);
        resizeScreen(cam.getWidth(), cam.getHeight());

        if (data.sounds() != null) {
            soundPlayer = new JmeSoundPlayer(data.sounds(), rootNode,
                    task -> enqueue(task));
        }
        screens = new Screens(data.gfx());
        // Un code de niveau dit ou reprendre : cinq niveaux par monde.
        int start = (saved == null) ? 0
                : (saved.game() - 1) * LEVELS_PER_GAME + (saved.level() - 1);
        loadLevel(start);
        bindKeys();
        // Les deux logos, puis le titre : la sequence d'ouverture de l'original.
        show(Screens.LOGO_1, Screens.LOGO_1_TICKS,
                () -> show(Screens.LOGO_2, Screens.LOGO_2_TICKS,
                        () -> show(Screens.TITLE, () -> introduceWorld(start))));
    }

    /**
     * Le quad occupe toute la fenetre, quelle que soit sa taille.
     *
     * <p>jMonkeyEngine appelle {@link #reshape} pendant la creation du contexte, donc
     * avant {@code simpleInitApp} : ni la camera ni le quad n'existent encore a ce
     * moment-la. La taille sera reprise a l'initialisation.
     */
    private void resizeScreen(int width, int height) {
        if (screen == null) {
            return;
        }
        screen.setWidth(width);
        screen.setHeight(height);
        screen.setPosition(0, 0);
    }

    @Override
    public void reshape(int width, int height) {
        super.reshape(width, height);
        resizeScreen(width, height);
    }

    private void loadLevel(int index) {
        levelIndex = Math.floorMod(index, data.levels().size());
        Level level = data.levels().get(levelIndex);

        world = new World(level, data.textures(), data.gfx(), data.objects());
        viewport = new Viewport(viewSize.renderWidth(), viewSize.renderHeight());
        renderer = new Renderer(world, viewport);
        camera = Camera.atPlayerStart(level);
        simulation = new Simulation(world, camera, data.objects());
        if (saved != null && !restored) {
            restored = true;
            simulation.restore(saved);
        }
        if (soundPlayer != null) {
            simulation.setSoundPlayer(soundPlayer);
            startLevelMusic(level);
        }
        accumulator = 0;
        lastReport = "";

        System.out.println("Niveau " + (levelIndex + 1) + "/" + data.levels().size()
                + " : " + level.name() + " (" + world.objects().size() + " objets)");
    }

    /**
     * Lance le morceau du niveau.
     *
     * <p>Chaque niveau declare les sons qu'il emploie ; celui de type module est sa
     * musique. L'original y prend son {@code PTModule} de la meme facon.
     */
    private void startLevelMusic(Level level) {
        for (String name : level.soundNames()) {
            fov.breathless.gld.sound.Sound sound = data.sounds().get(name);
            if (sound != null && sound.type() == fov.breathless.gld.sound.Sound.TYPE_MODULE) {
                System.out.println("Musique : " + name);
                soundPlayer.playMusic(name);
                return;
            }
        }
        soundPlayer.stopMusic();
    }

    /**
     * Le joueur vient de mourir.
     *
     * <p>Une tentative de moins ; s'il en reste, on reprend le niveau au debut, sinon la
     * partie s'arrete et l'on revient au titre avec trois tentatives neuves.
     */
    private void playerDied() {
        retries--;
        int retry = levelIndex;
        if (retries > 0) {
            System.out.println("Mort. Tentatives restantes : " + retries);
            show(Screens.GAME_OVER, () -> {
                loadLevel(retry);
                introducedWorld = 0;
            });
            return;
        }
        System.out.println("Partie terminee.");
        show(Screens.GAME_OVER, () -> show(Screens.TITLE, () -> {
            retries = RETRIES;
            introducedWorld = 0;
            loadLevel(0);
            introduceWorld(0);
        }));
    }

    /** Affiche le code du niveau suivant, pour pouvoir y revenir. */
    private void announceCode() {
        int next = levelIndex + 1;
        String code = LevelCode.encode(simulation.state(),
                simulation.combat().weapons(),
                next / LEVELS_PER_GAME + 1, next % LEVELS_PER_GAME + 1);
        System.out.println("Niveau termine. Code pour la suite : " + code);
    }

    /**
     * Pose l'image calculee au centre de la vue, entouree de la tenture.
     *
     * <p>Chaque pixel calcule est repete autant de fois que la grosseur reglee le demande :
     * c'est ainsi que l'original gagnait de la vitesse, en gardant l'image de meme taille.
     */
    private void blitView(Framebuffer frame) {
        int left = (WIDTH - viewSize.width()) / 2;
        int top = (VIEW_HEIGHT - viewSize.height()) / 2;
        if (viewSize.width() < WIDTH || viewSize.height() < VIEW_HEIGHT) {
            drawBackdrop();
        }

        byte[] pixels = frame.pixels();
        int scaleX = viewSize.scaleX();
        int scaleY = viewSize.scaleY();
        for (int y = 0; y < frame.height(); y++) {
            int row = y * frame.width();
            for (int repeatY = 0; repeatY < scaleY; repeatY++) {
                int target = (top + y * scaleY + repeatY) * WIDTH + left;
                for (int x = 0; x < frame.width(); x++) {
                    byte value = pixels[row + x];
                    for (int repeatX = 0; repeatX < scaleX; repeatX++) {
                        screenPixels[target + x * scaleX + repeatX] = value;
                    }
                }
            }
        }
    }

    /** Repete la tenture d'origine sur toute la vue. */
    private void drawBackdrop() {
        if (backdrop == null) {
            java.util.Arrays.fill(screenPixels, 0, WIDTH * VIEW_HEIGHT, (byte) 0);
            return;
        }
        byte[] tile = backdrop.pixels();
        int size = backdrop.width();
        for (int y = 0; y < VIEW_HEIGHT; y++) {
            int source = (y % size) * size;
            int target = y * WIDTH;
            for (int x = 0; x < WIDTH; x++) {
                screenPixels[target + x] = tile[source + (x % size)];
            }
        }
    }

    private void changePixels(boolean pressed, int type) {
        if (pressed) {
            viewSize.setPixelType(type);
        }
    }

    /**
     * Prend en compte un reglage qui vient de changer.
     *
     * <p>Refaire la fenetre de rendu coute assez pour ne le faire qu'au vrai changement :
     * le numero de revision dit s'il y a lieu.
     *
     * <p>C'est le <em>seul</em> endroit qui refait la fenetre de rendu, et il est appele
     * en tete d'image, avant tout dessin : la taille reglee et le tampon rendu ne peuvent
     * ainsi jamais se contredire. Les touches, elles, se contentent de changer le reglage.
     */
    private void applySettings() {
        if (settingsRevision == settings.revision()) {
            return;
        }
        settingsRevision = settings.revision();
        applyViewSize();
        if (soundPlayer != null) {
            soundPlayer.setMusicEnabled(settings.music());
            soundPlayer.setSoundEnabled(settings.sound());
            soundPlayer.setMusicVolume(settings.musicVolume());
        }
    }

    /** Refait la fenetre de rendu apres un changement de taille ou de pixel. */
    private void applyViewSize() {
        viewport = new Viewport(viewSize.renderWidth(), viewSize.renderHeight());
        renderer = new Renderer(world, viewport);
        System.out.println("Fenetre : " + viewSize);
    }

    /**
     * Affiche un ecran fixe et suspend le jeu jusqu'a ce que le joueur valide.
     *
     * <p>Si l'image manque, on passe directement a la suite : mieux vaut jouer sans
     * presentation que ne pas jouer.
     */
    private void show(String name, Runnable next) {
        show(name, 0, next);
    }

    /**
     * Affiche un ecran fixe.
     *
     * @param ticks nombre d'images avant de passer tout seul, ou 0 pour attendre une touche
     */
    private void show(String name, int ticks, Runnable next) {
        fov.breathless.gld.gfx.Picture picture = screens.get(name);
        if (picture == null) {
            if (next != null) {
                next.run();
            }
            return;
        }
        screenPicture = picture;
        screenTicks = ticks;
        afterScreen = next;
    }

    /** Presente le monde auquel appartient un niveau, une fois par monde. */
    private void introduceWorld(int index) {
        int world = index / LEVELS_PER_GAME + 1;
        if (world == introducedWorld) {
            return;
        }
        introducedWorld = world;
        show(Screens.world(world), null);
    }

    /** Touches d'origine des commandes redefinissables. */
    private static final Object[][] DEFAULT_KEYS = {
        {"avant", "UP", KeyInput.KEY_UP},
        {"arriere", "DOWN", KeyInput.KEY_DOWN},
        {"gauche", "LEFT", KeyInput.KEY_LEFT},
        {"droite", "RIGHT", KeyInput.KEY_RIGHT},
        {"courir", "SHIFT", KeyInput.KEY_LSHIFT},
        {"pas-de-cote", "ALT", KeyInput.KEY_LMENU},
        {"tirer", "CTRL", KeyInput.KEY_LCONTROL},
        {"actionner", "SPACE", KeyInput.KEY_SPACE},
        {"carte", "TAB", KeyInput.KEY_TAB},
        {"arme-suivante", "X", KeyInput.KEY_X},
    };

    /** Emplacement du fichier de reglages, a cote du jeu. */
    private static java.nio.file.Path configFile() {
        return java.nio.file.Path.of(Configuration.FILE);
    }

    /** Declare les touches par defaut, avant toute reprise de reglages. */
    private void defaultKeys() {
        for (Object[] entry : DEFAULT_KEYS) {
            settings.keys().set((String) entry[0], (String) entry[1], (Integer) entry[2]);
        }
    }

    /**
     * Relie les commandes redefinissables a leurs touches.
     *
     * <p>Rappelee apres chaque changement : les anciennes liaisons sont defaites d'abord.
     */
    private void bindConfigurableKeys() {
        for (String action : fov.breathless.engine.KeyBindings.ACTIONS) {
            if (inputManager.hasMapping(action)) {
                inputManager.deleteMapping(action);
            }
            var binding = settings.keys().get(action);
            if (binding == null || binding.code() == 0) {
                continue;
            }
            inputManager.addMapping(action, new KeyTrigger(binding.code()));
            inputManager.addListener(this, action);
        }
    }

    /**
     * Ecoute les touches brutes, pour la redefinition.
     *
     * <p>Une commande en attente prend la prochaine touche pressee telle quelle : il faut
     * donc l'evenement brut, et non une liaison, puisque la touche voulue n'en a pas
     * encore.
     */
    private void listenForKeyCapture() {
        inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
            private final com.jme3.input.KeyNames names = new com.jme3.input.KeyNames();

            @Override
            public void onKeyEvent(com.jme3.input.event.KeyInputEvent event) {
                if (!event.isPressed() || configMenu.capturing() == null) {
                    return;
                }
                if (event.getKeyCode() == KeyInput.KEY_ESCAPE) {
                    configMenu.cancelCapture();
                    return;
                }
                String label = names.getName(event.getKeyCode());
                configMenu.captureKey(label == null ? "?" : label.toUpperCase(java.util.Locale.ROOT),
                        event.getKeyCode());
                bindConfigurableKeys();
            }

            @Override
            public void beginInput() {
                // Sans objet.
            }

            @Override
            public void endInput() {
                // Sans objet.
            }

            @Override
            public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent event) {
                // Sans objet.
            }

            @Override
            public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent event) {
                // Sans objet.
            }

            @Override
            public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent event) {
                // Sans objet.
            }

            @Override
            public void onMouseButtonEvent(com.jme3.input.event.MouseButtonEvent event) {
                // Sans objet.
            }

            @Override
            public void onTouchEvent(com.jme3.input.event.TouchEvent event) {
                // Sans objet.
            }
        });
    }

    private void bindKeys() {
        defaultKeys();
        Configuration.load(configFile(), settings);
        bindConfigurableKeys();
        listenForKeyCapture();

        // Les secondes touches, qui ne se redefinissent pas : elles doublent les
        // premieres pour qui prefere ZQSD ou WASD.
        bind("avant2", KeyInput.KEY_W, KeyInput.KEY_Z);
        bind("arriere2", KeyInput.KEY_S);
        bind("gauche2", KeyInput.KEY_A, KeyInput.KEY_Q);
        bind("droite2", KeyInput.KEY_D);
        bind("courir2", KeyInput.KEY_RSHIFT);
        bind("pas-de-cote2", KeyInput.KEY_RMENU);
        bind("tirer2", KeyInput.KEY_RCONTROL);
        bind("actionner2", KeyInput.KEY_E, KeyInput.KEY_RETURN);
        // Echap ouvre le menu de configuration, qui porte la commande de sortie.
        bind("menu", KeyInput.KEY_ESCAPE);
        bind("pause", KeyInput.KEY_P);
        // Reglages d'affichage, comme dans l'original : +/- la taille, le pave la grosseur.
        bind("fenetre-plus", KeyInput.KEY_ADD, KeyInput.KEY_EQUALS);
        bind("fenetre-moins", KeyInput.KEY_SUBTRACT, KeyInput.KEY_MINUS);
        bind("pixel-11", KeyInput.KEY_NUMPAD7);
        bind("pixel-21", KeyInput.KEY_NUMPAD8);
        bind("pixel-12", KeyInput.KEY_NUMPAD9);
        bind("pixel-22", KeyInput.KEY_NUMPAD0);
        bind("niveau-suivant", KeyInput.KEY_PGUP);
        bind("niveau-precedent", KeyInput.KEY_PGDN);

        int[] weaponKeys = {KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3,
            KeyInput.KEY_4, KeyInput.KEY_5, KeyInput.KEY_6};
        for (int weapon = 0; weapon < weaponKeys.length; weapon++) {
            bind("arme-" + weapon, weaponKeys[weapon]);
        }

        List<String> names = new ArrayList<>(List.of(
                "avant2", "arriere2", "gauche2", "droite2", "courir2", "pas-de-cote2",
                "tirer2", "actionner2",
                "menu", "pause", "niveau-suivant", "niveau-precedent",
                "fenetre-plus", "fenetre-moins",
                "pixel-11", "pixel-21", "pixel-12", "pixel-22"));
        for (int weapon = 0; weapon < weaponKeys.length; weapon++) {
            names.add("arme-" + weapon);
        }
        for (String name : names) {
            inputManager.addListener(this, name);
        }
    }

    private void bind(String name, int... keys) {
        List<KeyTrigger> triggers = new ArrayList<>();
        for (int key : keys) {
            triggers.add(new KeyTrigger(key));
        }
        inputManager.addMapping(name, triggers.toArray(new KeyTrigger[0]));
    }

    /**
     * Les touches secondaires : le meme nom que la commande, suffixe de « 2 ».
     *
     * <p>La liste est explicite, et non deduite du suffixe : d'autres commandes finissent
     * par un chiffre sans etre des doublons — {@code arme-2} est la troisieme arme, et la
     * retailler en {@code arme-} la rendait illisible.
     */
    private static final java.util.Set<String> SECONDARY_ACTIONS = java.util.Set.of(
            "avant2", "arriere2", "gauche2", "droite2",
            "courir2", "pas-de-cote2", "tirer2", "actionner2");

    /** Ramene une touche secondaire au nom de la commande qu'elle double. */
    static String resolveAction(String action) {
        return SECONDARY_ACTIONS.contains(action)
                ? action.substring(0, action.length() - 1) : action;
    }

    @Override
    public void onAction(String action, boolean pressed, float tpf) {
        String name = resolveAction(action);
        switch (name) {
            case "avant" -> {
                if (pressed && (routeToMenu("avant") || routeToTerminal("avant"))) {
                    return;
                }
                input.forward = pressed;
            }
            case "arriere" -> {
                if (pressed && (routeToMenu("arriere") || routeToTerminal("arriere"))) {
                    return;
                }
                input.backward = pressed;
            }
            case "gauche" -> {
                if (pressed && routeToMenu("gauche")) {
                    return;
                }
                input.left = pressed;
            }
            case "droite" -> {
                if (pressed && routeToMenu("droite")) {
                    return;
                }
                input.right = pressed;
            }
            case "courir" -> input.run = pressed;
            case "pas-de-cote" -> input.strafe = pressed;
            case "tirer" -> {
                input.fire = pressed;
                if (pressed) {
                    // C'est la pression qui compte, pas la duree : une arme ordinaire
                    // tire une fois par appui.
                    input.firePresses++;
                }
            }
            case "actionner" -> {
                if (pressed) {
                    if (dismissScreen() || routeToMenu("actionner")
                            || routeToTerminal("actionner")) {
                        return;
                    }
                    input.action = true;
                }
            }
            case "arme-suivante" -> {
                if (pressed) {
                    input.nextWeapon = true;
                }
            }
            case "carte" -> {
                if (pressed) {
                    showMap = !showMap;
                }
            }
            case "pause" -> {
                if (pressed) {
                    paused = !paused;
                    System.out.println(paused ? "Pause." : "Reprise.");
                }
            }
            case "menu" -> {
                if (pressed) {
                    if (configMenu.isOpen()) {
                        configMenu.close();
                    } else {
                        configMenu.setAccessCode(currentCode());
                        configMenu.open();
                    }
                }
            }
            case "fenetre-plus" -> {
                if (pressed) {
                    viewSize.resize(+1);
                }
            }
            case "fenetre-moins" -> {
                if (pressed) {
                    viewSize.resize(-1);
                }
            }
            case "pixel-11" -> changePixels(pressed, ViewSize.PIXEL_1X1);
            case "pixel-21" -> changePixels(pressed, ViewSize.PIXEL_2X1);
            case "pixel-12" -> changePixels(pressed, ViewSize.PIXEL_1X2);
            case "pixel-22" -> changePixels(pressed, ViewSize.PIXEL_2X2);
            case "niveau-suivant" -> {
                if (pressed) {
                    loadLevel(levelIndex + 1);
                }
            }
            case "niveau-precedent" -> {
                if (pressed) {
                    loadLevel(levelIndex - 1);
                }
            }
            default -> {
                // Un nom mal forme ne doit pas remonter : une exception ici tuerait le
                // fil de rendu, et le jeu avec.
                String index = name.startsWith("arme-")
                        ? name.substring("arme-".length()) : "";
                if (pressed && index.length() == 1 && Character.isDigit(index.charAt(0))
                        && simulation.combat() != null) {
                    simulation.combat().weapons().select(index.charAt(0) - '0');
                }
            }
        }
    }

    /**
     * Dirige une touche vers le terminal quand il est ouvert.
     *
     * <p>L'original y navigue avec les fleches et valide a Entree ou a l'espace ; ici les
     * memes touches que pour avancer, reculer et actionner.
     *
     * @return {@code true} si le terminal a pris la touche
     */
    /**
     * Le joueur valide l'ecran fixe.
     *
     * @return {@code true} si la touche a servi a cela
     */
    private boolean dismissScreen() {
        if (screenPicture == null) {
            return false;
        }
        screenPicture = null;
        screenTicks = 0;
        Runnable next = afterScreen;
        afterScreen = null;
        if (next != null) {
            next.run();
        }
        return true;
    }

    /**
     * Dirige une touche vers le menu de configuration quand il est ouvert.
     *
     * @return {@code true} si le menu a pris la touche
     */
    private boolean routeToMenu(String action) {
        if (!configMenu.isOpen()) {
            return false;
        }
        if (configMenu.capturing() != null) {
            return true;   // on attend une touche, pas une commande
        }
        switch (action) {
            case "avant" -> configMenu.up();
            case "arriere" -> configMenu.down();
            case "gauche" -> configMenu.change(-1);
            case "droite" -> configMenu.change(+1);
            case "actionner" -> {
                configMenu.select();
                if (configMenu.isQuitting()) {
                    stop();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Le code de la partie en cours, tel que le menu le montre. */
    private String currentCode() {
        if (simulation.combat() == null) {
            return "";
        }
        return LevelCode.encode(simulation.state(), simulation.combat().weapons(),
                levelIndex / LEVELS_PER_GAME + 1, levelIndex % LEVELS_PER_GAME + 1);
    }

    private boolean routeToTerminal(String action) {
        Terminal terminal = simulation.terminal();
        if (terminal == null || !terminal.isOpen()) {
            return false;
        }
        switch (action) {
            case "avant" -> terminal.up();
            case "arriere" -> terminal.down();
            case "actionner" -> terminal.select();
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void simpleUpdate(float tpf) {
        // Avant tout dessin : le menu a pu changer la taille de la vue depuis la derniere
        // image, et {@code blitView} lit la taille reglee en meme temps que le tampon
        // rendu. Les reconstruire apres coup laisserait une image ou les deux ne
        // s'accordent pas — et un tampon plus grand que l'ecran deborde.
        applySettings();

        if (screenPicture != null) {
            accumulator = 0;
            if (screenTicks > 0 && --screenTicks == 0) {
                dismissScreen();
                return;
            }
            Screens.draw(screenPicture, screenPixels, WIDTH, screenHeight);
            PixelUpload.fill(pixels, screenPixels, WIDTH, screenHeight,
                    screenPicture.palette());
            image.setUpdateNeeded();
            return;
        }

        if (configMenu.isOpen()) {
            accumulator = 0;
            Framebuffer frame = renderer.render(camera);
            blitView(frame);
            configMenu.render(screenPixels, WIDTH, VIEW_HEIGHT);
            if (hud != null) {
                System.arraycopy(hud.render(simulation.state(),
                                simulation.combat().weapons()), 0,
                        screenPixels, WIDTH * VIEW_HEIGHT, WIDTH * Hud.HEIGHT);
            }
            PixelUpload.fill(pixels, screenPixels, WIDTH, screenHeight,
                    world.palette(false));
            image.setUpdateNeeded();
            if (configMenu.takeSaveRequest()) {
                Configuration.save(configFile(), settings);
            }
            return;
        }

        if (configMenu.takeCreditsRequest()) {
            show(Screens.CREDITS, null);
            return;
        }

        Terminal terminal = simulation.terminal();
        if (terminal != null && terminal.isOpen()) {
            // Le terminal suspend le jeu : la vue reste figee derriere le moniteur.
            accumulator = 0;
            Framebuffer frame = renderer.render(camera);
            System.arraycopy(frame.pixels(), 0, screenPixels, 0, WIDTH * VIEW_HEIGHT);
            terminal.render(screenPixels, WIDTH, VIEW_HEIGHT);
        } else if (showMap) {
            // Le jeu est suspendu tant que la carte est la : c'est ce que fait l'original,
            // qui bascule le drapeau de pause en meme temps que celui d'affichage.
            accumulator = 0;
            simulation.autoMap().render(mapPixels, WIDTH, VIEW_HEIGHT, camera);
            System.arraycopy(mapPixels, 0, screenPixels, 0, WIDTH * VIEW_HEIGHT);
        } else if (paused) {
            accumulator = 0;
            Framebuffer frame = renderer.render(camera);
            blitView(frame);
            if (settings.sight()) {
                Crosshair.draw(screenPixels, WIDTH, VIEW_HEIGHT);
            }
        } else {
            accumulator += Math.min(tpf, MAX_CATCH_UP);
            while (accumulator >= TICK) {
                simulation.tick(input);
                accumulator -= TICK;
                if (simulation.state().isDead()) {
                    accumulator = 0;
                    playerDied();
                    break;
                }
                if (simulation.takeLevelFinished()) {
                    announceCode();
                    int next = levelIndex + 1;
                    accumulator = 0;
                    if (next >= data.levels().size()) {
                        // Le dernier niveau est fini : l'ecran de fin, puis le titre.
                        show(Screens.END, () -> show(Screens.TITLE, () -> {
                            retries = RETRIES;
                            introducedWorld = 0;
                            loadLevel(0);
                            introduceWorld(0);
                        }));
                        break;
                    }
                    loadLevel(next);
                    introduceWorld(next);
                    break;
                }
            }

            Framebuffer frame = renderer.render(camera);
            blitView(frame);
            if (settings.sight()) {
                Crosshair.draw(screenPixels, WIDTH, VIEW_HEIGHT);
            }
        }
        if (hud != null) {
            System.arraycopy(hud.render(simulation.state(), simulation.combat().weapons()), 0,
                    screenPixels, WIDTH * VIEW_HEIGHT, WIDTH * Hud.HEIGHT);
        }
        PixelUpload.fill(pixels, screenPixels, WIDTH, screenHeight,
                world.palette(simulation.state().isFlashing()));
        image.setUpdateNeeded();

        if (hud == null) {
            reportState();
        }
    }

    /**
     * Affiche l'etat du joueur en console a chaque changement.
     *
     * <p>Ne sert que faute de panneau de bord : sans l'un ni l'autre, un ramassage ou une
     * morsure de sol nocif passerait inapercu.
     */
    private void reportState() {
        PlayerState state = simulation.state();
        StringBuilder report = new StringBuilder()
                .append("sante ").append(state.health())
                .append(" | boucliers ").append(state.shields())
                .append(" | energie ").append(state.energy())
                .append(" | credits ").append(state.credits());
        for (int key = PlayerState.PICKUP_GREEN_KEY; key <= PlayerState.PICKUP_BLUE_KEY; key++) {
            if (state.hasKey(key)) {
                report.append(" | cle ").append(KEY_NAMES[key - PlayerState.PICKUP_GREEN_KEY]);
            }
        }
        if (state.isDead()) {
            report.append(" | MORT");
        }

        String line = report.toString();
        if (!line.equals(lastReport)) {
            lastReport = line;
            System.out.println(line);
        }
    }

    private static final String[] KEY_NAMES = {"verte", "jaune", "rouge", "bleue"};
}
