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

    /** Ecran fixe affiche, ou {@code null} quand on joue. */
    private fov.breathless.gld.gfx.Picture screenPicture;
    private Screens screens;

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
        show(Screens.TITLE, () -> introduceWorld(start));
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
        if (pressed && viewSize.setPixelType(type)) {
            applyViewSize();
        }
    }

    /**
     * Prend en compte un reglage qui vient de changer.
     *
     * <p>Refaire la fenetre de rendu coute assez pour ne le faire qu'au vrai changement :
     * le numero de revision dit s'il y a lieu.
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
        fov.breathless.gld.gfx.Picture picture = screens.get(name);
        if (picture == null) {
            if (next != null) {
                next.run();
            }
            return;
        }
        screenPicture = picture;
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

    private void bindKeys() {
        bind("avant", KeyInput.KEY_UP, KeyInput.KEY_W, KeyInput.KEY_Z);
        bind("arriere", KeyInput.KEY_DOWN, KeyInput.KEY_S);
        bind("gauche", KeyInput.KEY_LEFT, KeyInput.KEY_A, KeyInput.KEY_Q);
        bind("droite", KeyInput.KEY_RIGHT, KeyInput.KEY_D);
        bind("courir", KeyInput.KEY_LSHIFT, KeyInput.KEY_RSHIFT);
        bind("pas-de-cote", KeyInput.KEY_LMENU, KeyInput.KEY_RMENU);
        // L'espace est la touche d'action du jeu d'origine : ouvrir, actionner.
        bind("tirer", KeyInput.KEY_LCONTROL, KeyInput.KEY_RCONTROL);
        bind("actionner", KeyInput.KEY_SPACE, KeyInput.KEY_E, KeyInput.KEY_RETURN);
        // Tabulation montre la carte, comme dans l'original, qui met alors le jeu en
        // pause. Les armes s'y choisissent au clavier numerique, de 1 a 6.
        bind("carte", KeyInput.KEY_TAB);
        // Echap ouvre le menu de configuration, qui porte la commande de sortie.
        bind("menu", KeyInput.KEY_ESCAPE);
        bind("arme-suivante", KeyInput.KEY_X);
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

        List<String> names = new ArrayList<>(List.of("avant", "arriere", "gauche", "droite",
                "courir", "pas-de-cote", "tirer", "actionner", "carte", "menu",
                "arme-suivante",
                "niveau-suivant", "niveau-precedent",
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

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
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
            case "tirer" -> input.fire = pressed;
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
                if (pressed && viewSize.resize(+1)) {
                    applyViewSize();
                }
            }
            case "fenetre-moins" -> {
                if (pressed && viewSize.resize(-1)) {
                    applyViewSize();
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
                if (pressed && name.startsWith("arme-") && simulation.combat() != null) {
                    simulation.combat().weapons()
                            .select(Integer.parseInt(name.substring("arme-".length())));
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
        if (screenPicture != null) {
            accumulator = 0;
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
            applySettings();
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
        } else {
            accumulator += Math.min(tpf, MAX_CATCH_UP);
            while (accumulator >= TICK) {
                simulation.tick(input);
                accumulator -= TICK;
                if (simulation.state().isDead()) {
                    // Mort du joueur : la banniere, puis on reprend le niveau au debut.
                    int retry = levelIndex;
                    show(Screens.GAME_OVER, () -> {
                        loadLevel(retry);
                        introducedWorld = 0;
                    });
                    accumulator = 0;
                    break;
                }
                if (simulation.takeLevelFinished()) {
                    announceCode();
                    int next = levelIndex + 1;
                    loadLevel(next);
                    accumulator = 0;
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
