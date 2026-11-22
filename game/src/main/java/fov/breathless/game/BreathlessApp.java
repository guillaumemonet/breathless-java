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
import fov.breathless.engine.Career;
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
import fov.breathless.engine.Messages;
import fov.breathless.engine.Monitor;
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

    /**
     * La partie en cours : ce que le joueur emporte d'un niveau au suivant.
     *
     * <p>Refaite a neuf quand la partie recommence, et seulement la : le moteur d'origine
     * n'appelle {@code InitScores} qu'au depart.
     */
    private Career career;

    /** Ecran fixe affiche, ou {@code null} quand on joue. */
    private fov.breathless.gld.gfx.Picture screenPicture;
    private Screens screens;

    /** Images restant a afficher avant de passer, ou 0 si l'on attend une touche. */
    private int screenTicks;

    /** Images ecoulees depuis l'affichage de l'ecran fixe. */
    private int screenAge;

    /** Images pendant lesquelles un ecran fixe ignore les touches. */
    private static final int SCREEN_GRACE = 10;

    /** Ce qu'il faudra faire quand le joueur aura valide l'ecran fixe. */
    private Runnable afterScreen;

    /** Lecteur de bruitages, ou {@code null} si la banque sonore manque. */
    private JmeSoundPlayer soundPlayer;
    private final fov.breathless.gld.gfx.PlanarImage backdrop;

    /** Partie reprise par son code, ou {@code null} pour un depart du debut. */
    private LevelCode.State saved;

    /** Un code donne au lancement : la partie part de lui, sans passer par le menu. */
    private boolean startFromArgument;

    /**
     * Sommes-nous dans la presentation ? Titre, credits, menu d'avant-partie : rien ne
     * se joue, et le menu qui s'ouvre est celui de {@code conf_page0p}.
     */
    private boolean presentation = true;

    /** Textes poses par-dessus l'ecran fixe : l'ecran de chargement. */
    private final List<Overlay> overlay = new ArrayList<>();

    private record Overlay(int x, int y, String text, int colour) {
    }

    /** Message en jeu, {@code SprDelayPrintMessage}, ou {@code null}. */
    private String message;
    private int messageTicks;

    /** Arme en main au pas precedent : un changement s'annonce par son nom. */
    private int lastWeapon = -1;

    BreathlessApp(GameData data, LevelCode.State saved) {
        this.data = data;
        this.career = new Career(data.objects());
        this.saved = saved;
        this.startFromArgument = saved != null;
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
        checkGpu();

        if (data.sounds() != null) {
            soundPlayer = new JmeSoundPlayer(data.sounds(), rootNode,
                    task -> enqueue(task));
        }
        screens = new Screens(data.gfx());
        configMenu.setCodeChecker(code -> LevelCode.decode(code) != null);
        // Un monde vide pour que tout soit pret : la partie ne se charge qu'au depart.
        loadLevel(0, false, false);
        bindKeys();
        // Les deux logos, puis le titre, sur la musique des titres : la sequence
        // d'ouverture de l'original.
        playPresentationMusic();
        show(Screens.LOGO_1, Screens.LOGO_1_TICKS,
                () -> show(Screens.LOGO_2, Screens.LOGO_2_TICKS, this::showTitle));
    }

    /**
     * Dit sur quelle carte la fenetre s'est ouverte et, si c'est la carte integree d'une
     * machine qui en a une autre, demande avant de continuer — voir {@link GpuCheck}.
     */
    private void checkGpu() {
        String gl = GpuCheck.renderer();
        List<String> cards = GpuCheck.adapters();
        String warn = GpuCheck.report(gl, cards);
        if (warn == null) {
            return;
        }
        // L'avertissement reste a l'ecran, par-dessus l'image du jeu.
        com.jme3.font.BitmapText red = new com.jme3.font.BitmapText(guiFont);
        red.setSize(guiFont.getCharSet().getRenderedSize());
        red.setColor(com.jme3.math.ColorRGBA.Red);
        red.setText(warn + '\n' + GpuCheck.FIX);
        red.setLocalTranslation(6, cam.getHeight() - 6, 1);
        guiNode.attachChild(red);
        // -Dbreathless.gpu=ok : l'avertissement reste, la question n'est pas posee.
        if ("ok".equals(System.getProperty("breathless.gpu")) || !GpuCheck.hasOtherCard(cards)) {
            return;
        }
        // Demande sur le fil de Swing, pas sur celui du rendu : bloquer le fil OpenGL sur
        // une boite modale empeche la fenetre du jeu de repondre a Windows, qui la tient
        // alors pour figee.
        String text = String.join("\n", warn, "", GpuCheck.FIX, "",
                "Continuer quand meme sur la carte integree ?");
        javax.swing.SwingUtilities.invokeLater(() -> {
            int choice = javax.swing.JOptionPane.showConfirmDialog(null, text,
                    "Carte graphique", javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (choice != javax.swing.JOptionPane.YES_OPTION) {
                System.err.println("Arret a la demande : " + GpuCheck.FIX);
                stop();
            }
        });
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
        loadLevel(index, false, true);
    }

    /**
     * Charge un niveau, en gardant la partie en cours.
     *
     * <p>{@code TMapStart} : le joueur traverse les niveaux avec ce qu'il a. Seul un
     * nouveau depart le remet a neuf, et une reprise apres la mort le ramene a l'etat ou
     * il est entre dans le niveau.
     *
     * @param playAgain vrai si l'on reprend un niveau deja joue
     */
    private void loadLevel(int index, boolean playAgain, boolean withMusic) {
        levelIndex = Math.floorMod(index, data.levels().size());
        Level level = data.levels().get(levelIndex);

        world = new World(level, data.textures(), data.gfx(), data.objects());
        viewport = new Viewport(viewSize.renderWidth(), viewSize.renderHeight());
        renderer = new Renderer(world, viewport);
        renderer.setPixelType(viewSize.pixelType());
        camera = Camera.atPlayerStart(level);
        simulation = new Simulation(world, camera, data.objects(), career);
        if (saved != null && !restored) {
            restored = true;
            simulation.restore(saved);
        }
        // InitScores2, apres la reprise eventuelle du code de niveau : c'est l'etat
        // d'entree qui est mis de cote, code compris.
        career.enterLevel(playAgain);
        simulation.player().setMovement(settings.movement());
        lastWeapon = (simulation.combat() == null) ? -1 : simulation.combat().weapons().active();
        message = null;
        if (soundPlayer != null) {
            simulation.setSoundPlayer(soundPlayer);
            if (withMusic) {
                startLevelMusic(level);
            }
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

    /** {@code PresModName} : la musique des titres, des logos, des credits et de la fin. */
    private void playPresentationMusic() {
        if (soundPlayer == null) {
            return;
        }
        String name = (data.master() == null) ? "MUST" : data.master().presentationMusic();
        soundPlayer.playMusic(name);
    }

    /** La musique de l'ecran de chargement et de la fin de partie. */
    private void playLoadingMusic() {
        if (soundPlayer != null) {
            soundPlayer.playMusic("MUSL");
        }
    }

    /**
     * Le titre. Une touche ouvre le menu de la presentation, d'ou part la partie ; un code
     * donne au lancement fait partir directement.
     */
    private void showTitle() {
        presentation = true;
        show(Screens.TITLE, () -> {
            if (startFromArgument) {
                startFromArgument = false;
                beginGame();
            } else {
                configMenu.open(false);
                // L'ecran fixe reste derriere le menu.
                show(Screens.TITLE, null);
            }
        });
    }

    /** Retour a la presentation : apres la fin de partie, la fin du jeu, ou « QUIT GAME ». */
    private void goToPresentation() {
        presentation = true;
        paused = false;
        showMap = false;
        message = null;
        playPresentationMusic();
        showTitle();
    }

    /**
     * « START GAME » : une partie neuve, au niveau que dit le code d'acces.
     *
     * <p>{@code LevelCodeIn} : le code de depart mene au premier niveau, un autre code au
     * niveau qu'il porte, dans l'etat qu'il porte.
     */
    private void beginGame() {
        presentation = false;
        screenPicture = null;
        afterScreen = null;
        if (saved == null) {
            String code = configMenu.accessCode();
            LevelCode.State state = LevelCode.decode(code);
            saved = (state == null || LevelCode.FIRST_LEVEL.equals(code)) ? null : state;
        }
        newGame();
        restored = false;
        int start = (saved == null) ? 0
                : (saved.game() - 1) * LEVELS_PER_GAME + (saved.level() - 1);
        prepareLevel(start, false);
        saved = null;
    }

    /**
     * Charge un niveau et montre son ecran de chargement ; le jeu part a la premiere
     * touche, sur la musique du niveau.
     *
     * <p>{@code LoadingScreen} (Presentation.asm) : l'image du niveau, le nom du monde et
     * celui de l'arene en haut, les tentatives restantes, le code d'acces — sauf au tout
     * premier niveau —, et « PRESS ANY KEY TO START » avec son ombre, sur la musique
     * {@code MUSL}.
     */
    private void prepareLevel(int index, boolean playAgain) {
        loadLevel(index, playAgain, false);
        Level level = data.levels().get(levelIndex);
        int game = levelIndex / LEVELS_PER_GAME + 1;
        int arena = levelIndex % LEVELS_PER_GAME + 1;

        overlay.clear();
        String title = (data.master() == null) ? level.name() : data.master().title(game, arena);
        int titleX = (WIDTH - title.length() * SystemFont.SIZE) / 2;
        overlay.add(new Overlay(titleX, 7, title, 254));
        overlay.add(new Overlay(100, 17, "RETRIES LEFT  " + retries, 254));
        if (game != 1 || arena != 1) {
            // LevelCodeOut : le code d'acces devient celui de ce niveau.
            String code = currentCode();
            configMenu.setAccessCode(code);
            overlay.add(new Overlay(116, 220, "ACCESS CODE", 254));
            overlay.add(new Overlay(96, 230, code, 254));
        }
        overlay.add(new Overlay(73, 117, "PRESS ANY KEY TO START", 0));
        overlay.add(new Overlay(72, 116, "PRESS ANY KEY TO START", 255));

        playLoadingMusic();
        String picture = (screens.get(level.loadingPicture()) != null)
                ? level.loadingPicture() : Screens.world(game);
        show(picture, () -> {
            overlay.clear();
            startLevelMusic(level);
        });
    }

    /**
     * La sequence de mort est finie : une tentative de moins. S'il en reste, on reprend
     * le niveau dans l'etat ou on y est entre ({@code TMretr}) ; sinon la banniere de fin
     * de partie, puis la presentation — le code d'acces reste celui du niveau atteint.
     */
    private void playerDied() {
        retries--;
        if (retries > 0) {
            System.out.println("Mort. Tentatives restantes : " + retries);
            prepareLevel(levelIndex, true);
            return;
        }
        System.out.println("Partie terminee.");
        playLoadingMusic();
        show(Screens.GAME_OVER, Screens.GAME_OVER_TICKS, this::goToPresentation);
    }

    /**
     * Repart de zero : {@code InitScores} et les trois tentatives.
     *
     * <p>C'est le seul endroit qui remet l'etat du joueur a neuf. Le faire a chaque niveau
     * lui ferait tout perdre en chemin.
     */
    private void newGame() {
        career = new Career(data.objects());
        retries = RETRIES;
    }

    /** Le niveau est fini : le suivant, ou l'ecran final. */
    private void levelFinished() {
        int next = levelIndex + 1;
        if (next >= data.levels().size()) {
            playPresentationMusic();
            show(Screens.END, Screens.END_TICKS, this::goToPresentation);
            return;
        }
        prepareLevel(next, false);
    }

    /** {@code SprDelayPrintMessage} : un message en haut de la vue. */
    private void showMessage(String text) {
        if (text == null) {
            return;
        }
        message = text;
        messageTicks = 0;
    }

    /**
     * Le message s'ecrit un caractere par vingt-cinquieme de seconde, reste soixante
     * cinquantiemes, puis s'efface ({@code MessWait}).
     */
    private void drawMessage() {
        if (message == null) {
            return;
        }
        Monitor.printMessage(screenPixels, WIDTH, VIEW_HEIGHT, message, messageTicks / 2);
    }

    private void advanceMessage(int ticks) {
        if (message == null) {
            return;
        }
        messageTicks += ticks;
        if (messageTicks > message.length() * 2 + Messages.DURATION) {
            message = null;
        }
    }

    /** Le clic de validation, {@code GlobalSound2}. */
    private void click() {
        if (soundPlayer != null) {
            soundPlayer.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SWITCH);
        }
    }

    /**
     * Apres un pas : messages demandes, arme changee, terminal referme.
     */
    private void afterTick() {
        int request = simulation.takeMessageRequest();
        if (request >= 0) {
            showMessage(Messages.text(request));
        }
        Terminal terminal = simulation.terminal();
        if (terminal != null && !terminal.isOpen() && simulation.combat() != null) {
            // TMterm : l'arme achetee passe en main a la sortie du terminal.
            int bought = terminal.takeBoughtWeapon();
            if (bought >= 0) {
                simulation.combat().weapons().select(bought);
            }
        }
        if (simulation.combat() != null) {
            int active = simulation.combat().weapons().active();
            if (active != lastWeapon) {
                if (lastWeapon >= 0) {
                    // ChangeWeapon : le nom de l'arme, et le clic.
                    showMessage(Messages.text(Messages.forWeapon(active)));
                    click();
                }
                lastWeapon = active;
            }
        }
        advanceMessage(1);
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
        renderer.setPixelType(viewSize.pixelType());
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
        screenAge = 0;
        afterScreen = next;
    }

    /**
     * Touches par defaut des commandes redefinissables.
     *
     * <p>Les commandes sont celles de {@code conf_page3}. Les touches, elles, suivent les
     * habitudes d'aujourd'hui plutot que celles de l'original — Alt gauche pour tirer, Ctrl
     * pour courir, Maj pour le pas de cote force : la page du clavier permet d'y revenir.
     */
    private static final Object[][] DEFAULT_KEYS = {
        {"avant", "_UP", KeyInput.KEY_UP},
        {"arriere", "DWN", KeyInput.KEY_DOWN},
        {"gauche", "LFT", KeyInput.KEY_LEFT},
        {"droite", "RGT", KeyInput.KEY_RIGHT},
        {"pas-gauche", "_,_", KeyInput.KEY_COMMA},
        {"pas-droite", "_._", KeyInput.KEY_PERIOD},
        {"tirer", "CTR", KeyInput.KEY_LCONTROL},
        {"courir", "LSH", KeyInput.KEY_LSHIFT},
        {"pas-de-cote", "LAL", KeyInput.KEY_LMENU},
        {"regard-haut", "HOM", KeyInput.KEY_HOME},
        {"regard-centre", "INS", KeyInput.KEY_INSERT},
        {"regard-bas", "END", KeyInput.KEY_END},
        {"actionner", "SPC", KeyInput.KEY_SPACE},
    };

    /**
     * Touches que la page du clavier refuse : {@code keylist} y met zero pour Tab, Echap,
     * les chiffres des armes, P et les touches de fonction. S'y ajoutent les touches fixes
     * de ce portage.
     */
    private static final java.util.Set<Integer> RESERVED_KEYS = java.util.Set.of(
            KeyInput.KEY_TAB, KeyInput.KEY_ESCAPE, KeyInput.KEY_P,
            KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3,
            KeyInput.KEY_4, KeyInput.KEY_5, KeyInput.KEY_6,
            KeyInput.KEY_F1, KeyInput.KEY_F2, KeyInput.KEY_F3,
            KeyInput.KEY_F4, KeyInput.KEY_F5, KeyInput.KEY_F6,
            KeyInput.KEY_X, KeyInput.KEY_ADD, KeyInput.KEY_SUBTRACT,
            KeyInput.KEY_EQUALS, KeyInput.KEY_MINUS,
            KeyInput.KEY_NUMPAD7, KeyInput.KEY_NUMPAD8, KeyInput.KEY_NUMPAD9,
            KeyInput.KEY_NUMPAD0, KeyInput.KEY_PGUP, KeyInput.KEY_PGDN);

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
     * Le nom d'une touche en trois caracteres, comme {@code keylist} : les lettres et les
     * chiffres entre deux traits bas, les autres abreges.
     */
    static String shortKeyName(int code) {
        String name = com.jme3.input.KeyNames.getName(code);
        if (name == null) {
            return "???";
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.length() == 1) {
            return "_" + upper + "_";
        }
        return switch (code) {
            case KeyInput.KEY_SPACE -> "SPC";
            case KeyInput.KEY_RETURN -> "RET";
            case KeyInput.KEY_NUMPADENTER -> "ENT";
            case KeyInput.KEY_DELETE -> "DEL";
            case KeyInput.KEY_UP -> "_UP";
            case KeyInput.KEY_DOWN -> "DWN";
            case KeyInput.KEY_LEFT -> "LFT";
            case KeyInput.KEY_RIGHT -> "RGT";
            case KeyInput.KEY_LSHIFT -> "LSH";
            case KeyInput.KEY_RSHIFT -> "RSH";
            case KeyInput.KEY_LCONTROL, KeyInput.KEY_RCONTROL -> "CTR";
            case KeyInput.KEY_LMENU -> "LAL";
            case KeyInput.KEY_RMENU -> "RAL";
            case KeyInput.KEY_BACK -> "BSP";
            default -> upper.substring(0, Math.min(3, upper.length()));
        };
    }

    /**
     * Ecoute les touches brutes : redefinition d'une commande, saisie du code d'acces,
     * reponse a « REALLY QUIT ? (Y/N) ».
     *
     * <p>Ces trois saisies prennent la prochaine touche telle quelle, et non une liaison.
     */
    private void listenForKeyCapture() {
        inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
            @Override
            public void onKeyEvent(com.jme3.input.event.KeyInputEvent event) {
                if (!event.isPressed() || !configMenu.wantsRawKeys()) {
                    return;
                }
                int key = event.getKeyCode();
                if (configMenu.isAskingQuit()) {
                    if (key == KeyInput.KEY_Y || key == KeyInput.KEY_N) {
                        configMenu.answerQuit(key == KeyInput.KEY_Y);
                    }
                    event.setConsumed();
                    return;
                }
                if (configMenu.capturing() != null) {
                    if (key == KeyInput.KEY_ESCAPE) {
                        configMenu.cancelCapture();
                    } else {
                        configMenu.captureKey(shortKeyName(key), key);
                        bindConfigurableKeys();
                    }
                    event.setConsumed();
                    return;
                }
                // Saisie du code d'acces.
                switch (key) {
                    case KeyInput.KEY_RETURN, KeyInput.KEY_NUMPADENTER -> configMenu.confirmCode();
                    case KeyInput.KEY_ESCAPE -> configMenu.cancelCode();
                    case KeyInput.KEY_BACK -> configMenu.eraseCode();
                    default -> {
                        char c = event.getKeyChar();
                        if (c != 0) {
                            configMenu.typeCode(c);
                        }
                    }
                }
                event.setConsumed();
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
        configMenu.reserveKeys(RESERVED_KEYS);
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
        // Touches fixes : Echap ouvre le menu, Tab la carte, P la pause, X passe a l'arme
        // suivante, 1 a 6 choisissent une arme.
        bind("menu", KeyInput.KEY_ESCAPE);
        bind("carte", KeyInput.KEY_TAB);
        bind("arme-suivante", KeyInput.KEY_X);
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
                "avant2", "arriere2", "gauche2", "droite2",
                "courir2", "pas-de-cote2", "tirer2", "actionner2",
                "menu", "carte", "arme-suivante", "pause",
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
        if (configMenu.wantsRawKeys()) {
            return;   // la saisie en cours prend les touches brutes
        }
        String name = resolveAction(action);
        // Sur un ecran fixe, une touche passe a la suite ; le titre ouvre le menu.
        if (pressed && screenPicture != null && !configMenu.isOpen()
                && !name.startsWith("fenetre") && !name.startsWith("pixel")) {
            // Une touche tout juste pressee pour quitter le menu ne doit pas emporter
            // aussi l'ecran qui le suit : il ne se ferme qu'apres quelques images.
            if (screenAge >= SCREEN_GRACE) {
                dismissScreen();
            }
            return;
        }
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
            case "pas-gauche" -> input.sideLeft = pressed;
            case "pas-droite" -> input.sideRight = pressed;
            case "courir" -> input.run = pressed;
            case "pas-de-cote" -> input.strafe = pressed;
            case "regard-haut" -> input.lookUp = pressed;
            case "regard-bas" -> input.lookDown = pressed;
            case "regard-centre" -> {
                if (pressed) {
                    input.lookReset = true;
                }
            }
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
                    if (routeToMenu("actionner") || routeToTerminal("actionner")) {
                        return;
                    }
                    input.action = true;
                }
            }
            case "arme-suivante" -> {
                if (pressed && !presentation) {
                    input.nextWeapon = true;
                }
            }
            case "carte" -> {
                if (pressed && !presentation) {
                    showMap = !showMap;
                    click();
                }
            }
            case "pause" -> {
                if (pressed && !presentation && !configMenu.isOpen()) {
                    paused = !paused;
                    showMessage(paused ? "PAUSE ON" : Messages.text(Messages.PAUSE_OFF));
                }
            }
            case "menu" -> {
                if (pressed) {
                    click();
                    if (configMenu.isOpen()) {
                        configMenu.close();
                    } else {
                        configMenu.open(!presentation);
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
                if (pressed && !presentation) {
                    prepareLevel(levelIndex + 1, false);
                }
            }
            case "niveau-precedent" -> {
                if (pressed && !presentation) {
                    prepareLevel(levelIndex - 1, false);
                }
            }
            default -> {
                // Un nom mal forme ne doit pas remonter : une exception ici tuerait le
                // fil de rendu, et le jeu avec.
                String index = name.startsWith("arme-")
                        ? name.substring("arme-".length()) : "";
                if (pressed && !presentation && index.length() == 1
                        && Character.isDigit(index.charAt(0))
                        && simulation.combat() != null) {
                    simulation.combat().weapons().select(index.charAt(0) - '0');
                }
            }
        }
    }

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
        switch (action) {
            case "avant" -> configMenu.up();
            case "arriere" -> configMenu.down();
            case "gauche" -> configMenu.change(-1);
            case "droite" -> configMenu.change(+1);
            case "actionner" -> {
                configMenu.select();
                click();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Ce que le menu a demande en se refermant : partir, quitter, les credits,
     * l'enregistrement.
     */
    private void handleMenuRequests() {
        if (configMenu.takeSaveRequest()) {
            Configuration.save(configFile(), settings);
        }
        if (simulation != null) {
            simulation.player().setMovement(settings.movement());
        }
        if (configMenu.takeStartRequest()) {
            beginGame();
            return;
        }
        if (configMenu.takeCreditsRequest()) {
            playPresentationMusic();
            show(Screens.CREDITS, Screens.CREDITS_TICKS, this::showTitle);
            return;
        }
        if (configMenu.takeQuitRequest()) {
            if (presentation) {
                stop();
            } else {
                // En jeu, Escape et EscKey ramenent a la presentation.
                goToPresentation();
            }
            return;
        }
        // Le menu de la presentation referme par Echap laisse le titre, qui le rouvre.
        if (presentation && !configMenu.isOpen() && screenPicture == null) {
            showTitle();
        }
    }

    /** Le code de la partie en cours, tel que le niveau l'a calcule a son entree. */
    private String currentCode() {
        if (simulation == null || simulation.combat() == null) {
            return ConfigMenu.START_CODE;
        }
        return LevelCode.encode(simulation.state(), simulation.combat().weapons(),
                levelIndex / LEVELS_PER_GAME + 1, levelIndex % LEVELS_PER_GAME + 1);
    }

    /**
     * Dirige une touche vers le terminal quand il est ouvert.
     *
     * <p>L'original y navigue avec les fleches et valide a Entree ou a l'espace ; ici les
     * memes touches que pour avancer, reculer et actionner.
     *
     * @return {@code true} si le terminal a pris la touche
     */
    private boolean routeToTerminal(String action) {
        Terminal terminal = simulation.terminal();
        if (terminal == null || !terminal.isOpen()) {
            return false;
        }
        switch (action) {
            case "avant" -> terminal.up();
            case "arriere" -> terminal.down();
            case "actionner" -> {
                terminal.select();
                // Tselect : le clic, sauf si la selection est refusee.
                if (!terminal.refused()) {
                    click();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Passe l'image calculee par le fondu en cours. */
    private void applyFade(Framebuffer frame) {
        simulation.fade().apply(frame.pixels(), frame.width() * frame.height(),
                world.lighting(), world.fogLighting());
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
            screenAge++;
            if (configMenu.isOpen()) {
                configMenu.advance(1);
            } else if (screenTicks > 0 && --screenTicks == 0) {
                dismissScreen();
                return;
            }
            Screens.draw(screenPicture, screenPixels, WIDTH, screenHeight);
            for (Overlay text : overlay) {
                SystemFont.text(screenPixels, WIDTH, screenHeight, text.x(), text.y(),
                        text.text(), text.colour());
            }
            if (configMenu.isOpen()) {
                configMenu.render(screenPixels, WIDTH, VIEW_HEIGHT);
            } else {
                handleMenuRequests();
            }
            if (screenPicture != null) {
                PixelUpload.fill(pixels, screenPixels, WIDTH, screenHeight,
                        screenPicture.palette());
                image.setUpdateNeeded();
            }
            return;
        }

        if (configMenu.isOpen()) {
            accumulator = 0;
            configMenu.advance(1);
            Framebuffer frame = renderer.render(camera);
            applyFade(frame);
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
            return;
        }
        handleMenuRequests();
        if (screenPicture != null || presentation) {
            return;
        }

        Terminal terminal = simulation.terminal();
        if (terminal != null && terminal.isOpen()) {
            // Le terminal suspend le jeu : la vue reste figee derriere le moniteur.
            accumulator = 0;
            Framebuffer frame = renderer.render(camera);
            applyFade(frame);
            blitView(frame);
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
            applyFade(frame);
            blitView(frame);
            if (settings.sight()) {
                Crosshair.draw(screenPixels, WIDTH, VIEW_HEIGHT);
            }
            drawMessage();
        } else {
            accumulator += Math.min(tpf, MAX_CATCH_UP);
            while (accumulator >= TICK) {
                simulation.tick(input);
                afterTick();
                accumulator -= TICK;
                if (simulation.isDeathFinished()) {
                    accumulator = 0;
                    playerDied();
                    return;
                }
                if (simulation.takeLevelFinished()) {
                    accumulator = 0;
                    levelFinished();
                    return;
                }
                if (simulation.terminal() != null && simulation.terminal().isOpen()) {
                    break;
                }
            }

            // Le flash des explosions eclaire toute la scene.
            if (simulation.combat() != null) {
                renderer.setGlobalLight(simulation.combat().globalLight());
            }
            Framebuffer frame = renderer.render(camera);
            applyFade(frame);
            blitView(frame);
            if (settings.sight()) {
                Crosshair.draw(screenPixels, WIDTH, VIEW_HEIGHT);
            }
            drawMessage();
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
