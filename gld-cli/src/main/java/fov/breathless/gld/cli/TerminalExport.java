package fov.breathless.gld.cli;

import fov.breathless.engine.PlayerState;
import fov.breathless.engine.Terminal;
import fov.breathless.engine.Weapons;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.Palette;
import fov.breathless.gld.object.ObjectBank;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exporte les quatre pages du terminal en PNG, pour les relire sans jouer. */
final class TerminalExport {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;

    private TerminalExport() {
    }

    static void run(Path dataDir, Path outDir) throws IOException {
        GfxBank gfx = null;
        ObjectBank objects = null;
        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            switch (gld.payloadId()) {
                case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                case ObjectBank.OGLD -> objects = ObjectBank.read(gld);
                default -> {
                    // Sans objet ici.
                }
            }
        }
        if (gfx == null || objects == null) {
            throw new IOException("Banque graphique ou banque d'objets absente de " + dataDir);
        }
        Files.createDirectories(outDir);

        PlayerState state = new PlayerState();
        state.collect(PlayerState.PICKUP_CREDITS, 25000);
        Weapons weapons = new Weapons(objects);
        Terminal terminal = new Terminal(state, weapons);
        terminal.open(7);

        // Et les pages du menu de configuration, sur le meme moniteur.
        fov.breathless.engine.Settings settings = new fov.breathless.engine.Settings();
        fov.breathless.engine.ConfigMenu config = new fov.breathless.engine.ConfigMenu(settings);
        config.setAccessCode(fov.breathless.engine.LevelCode.FIRST_LEVEL);
        String[] configPages = {"reglages", "reglages-fenetre", "reglages-son", "reglages-options"};
        for (int i = 0; i < configPages.length; i++) {
            config.open();
            for (int step = 0; step < i; step++) {
                config.down();
            }
            if (i > 0) {
                config.select();
            }
            byte[] menu = new byte[WIDTH * HEIGHT];
            config.render(menu, WIDTH, HEIGHT);
            write(menu, gfx.palette(), outDir.resolve("terminal-" + configPages[i] + ".png"));
            System.out.println("terminal-" + configPages[i] + ".png   " + config.lineCount()
                    + " lignes, premiere : " + config.line(0));
        }

        String[] names = {"accueil", "armes", "ameliorations", "accessoires"};
        Terminal.Page[] pages = Terminal.Page.values();
        for (int i = 0; i < pages.length; i++) {
            terminal.open(7);
            navigateTo(terminal, pages[i]);
            byte[] pixels = new byte[WIDTH * HEIGHT];
            terminal.render(pixels, WIDTH, HEIGHT);
            write(pixels, gfx.palette(), outDir.resolve("terminal-" + names[i] + ".png"));
            System.out.println("terminal-" + names[i] + ".png   "
                    + terminal.lineCount() + " lignes, premiere : " + terminal.line(0));
        }
    }

    /** Enchaine les choix de l'accueil jusqu'a la page voulue. */
    private static void navigateTo(Terminal terminal, Terminal.Page page) {
        int target = switch (page) {
            case WEAPONS -> 0;
            case BOOSTS -> 1;
            case ACCESSORIES -> 2;
            case MAIN -> -1;
        };
        if (target < 0) {
            return;
        }
        for (int step = 0; step < target; step++) {
            terminal.down();
        }
        terminal.select();
    }

    private static void write(byte[] pixels, Palette palette, Path file) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] argb = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            argb[i] = palette.argb(pixels[i] & 0xFF);
        }
        image.setRGB(0, 0, WIDTH, HEIGHT, argb, 0, WIDTH);
        ImageIO.write(image, "png", file.toFile());
    }

    private static List<Path> gldFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Repertoire introuvable : " + dir);
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }
}
