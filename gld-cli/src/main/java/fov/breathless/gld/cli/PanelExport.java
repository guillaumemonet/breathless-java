package fov.breathless.gld.cli;

import fov.breathless.engine.Hud;
import fov.breathless.engine.ViewSize;
import fov.breathless.gld.texture.TextureBank;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.level.Level;
import fov.breathless.engine.World;
import fov.breathless.engine.Viewport;
import fov.breathless.engine.Renderer;
import fov.breathless.engine.Framebuffer;
import fov.breathless.engine.Camera;
import fov.breathless.engine.PlayerState;
import fov.breathless.engine.Weapons;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.Palette;
import fov.breathless.gld.gfx.RawPictures;
import fov.breathless.gld.gfx.PlanarImage;
import fov.breathless.gld.object.ObjectBank;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exporte le panneau de bord en PNG, vide puis rempli.
 *
 * <p>Sert a verifier hors du jeu que l'image de fond se deplie bien, que les chiffres
 * tombent aux bons endroits et que les voyants s'allument.
 */
final class PanelExport {

    private PanelExport() {
    }

    static void run(Path dataDir, Path outDir) throws IOException {
        GfxBank gfx = null;
        ObjectBank objects = null;
        TextureBank textures = null;
        Level first = null;
        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            switch (gld.payloadId()) {
                case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                case ObjectBank.OGLD -> objects = ObjectBank.read(gld);
                case TextureBank.TGLD -> textures = TextureBank.read(gld);
                case LevelReader.LGLD -> {
                    if (first == null) {
                        first = LevelReader.read(gld);
                    }
                }
                default -> {
                    // Sans objet ici.
                }
            }
        }
        if (gfx == null || objects == null) {
            throw new IOException("Banque graphique ou banque d'objets absente de " + dataDir);
        }

        Path raw = RawPictures.locate(dataDir);
        if (raw == null) {
            throw new IOException("Image du panneau introuvable sous " + dataDir
                    + " : " + RawPictures.RELATIVE_PATH + " est attendu");
        }
        PlanarImage panel = RawPictures.read(raw);
        Files.createDirectories(outDir);
        Palette palette = gfx.palette();

        write(panel.pixels(), panel.width(), panel.height(), palette,
                outDir.resolve("panneau-fond.png"));
        System.out.println("panneau-fond.png     " + panel.width() + "x" + panel.height()
                + ", " + distinct(panel.pixels()) + " couleurs");

        // Un etat de jeu plausible : deux cles, trois armes, la deuxieme en main.
        Hud hud = new Hud(panel);
        PlayerState state = new PlayerState();
        state.addScore(123456);
        state.collect(PlayerState.PICKUP_CREDITS, 4200);
        state.collect(PlayerState.PICKUP_GREEN_KEY, 1);
        state.collect(PlayerState.PICKUP_RED_KEY, 1);
        state.hit(21);
        state.spendEnergy(1337);

        Weapons weapons = new Weapons(objects);
        weapons.give(1);
        weapons.give(3);
        weapons.select(1);

        byte[] pixels = hud.render(state, weapons);
        write(pixels, panel.width(), panel.height(), palette, outDir.resolve("panneau-rempli.png"));
        if (textures != null && first != null) {
            compose(first, textures, gfx, objects, pixels, palette, null, null,
                    outDir.resolve("ecran.png"));
            System.out.println("ecran.png            " + panel.width() + "x"
                    + (VIEW_HEIGHT + Hud.HEIGHT) + ", la vue et le panneau");

            // Et la meme chose fenetre reduite, gros pixels, tenture autour.
            Path backdropFile = RawPictures.locateBackdrop(dataDir);
            if (backdropFile != null) {
                ViewSize small = new ViewSize();
                small.resize(-2);
                small.setPixelType(ViewSize.PIXEL_2X2);
                compose(first, textures, gfx, objects, pixels, palette, small,
                        RawPictures.readBackdrop(backdropFile),
                        outDir.resolve("ecran-reduit.png"));
                System.out.println("ecran-reduit.png     fenetre " + small);
            }
        }
        System.out.println("panneau-rempli.png   sante " + state.health()
                + ", boucliers " + state.shields() + ", energie " + state.energy()
                + ", credits " + state.credits() + ", score " + state.score());
    }

    /** Hauteur de la vue en trois dimensions, au-dessus du panneau. */
    private static final int VIEW_HEIGHT = 200;

    /** Assemble une vue de jeu et le panneau, comme a l'ecran. */
    private static void compose(Level level, TextureBank textures, GfxBank gfx,
                                ObjectBank objects, byte[] panel, Palette palette,
                                ViewSize size, PlanarImage backdrop, Path file)
            throws IOException {
        World world = new World(level, textures, gfx, objects);
        int renderWidth = (size == null) ? RawPictures.WIDTH : size.renderWidth();
        int renderHeight = (size == null) ? VIEW_HEIGHT : size.renderHeight();
        Renderer renderer = new Renderer(world, new Viewport(renderWidth, renderHeight));
        Framebuffer frame = renderer.render(Camera.atPlayerStart(level));

        int height = VIEW_HEIGHT + Hud.HEIGHT;
        byte[] screen = new byte[RawPictures.WIDTH * height];

        if (size == null) {
            System.arraycopy(frame.pixels(), 0, screen, 0, RawPictures.WIDTH * VIEW_HEIGHT);
        } else {
            tile(screen, backdrop);
            int left = (RawPictures.WIDTH - size.width()) / 2;
            int top = (VIEW_HEIGHT - size.height()) / 2;
            for (int y = 0; y < renderHeight; y++) {
                for (int repeatY = 0; repeatY < size.scaleY(); repeatY++) {
                    int target = (top + y * size.scaleY() + repeatY) * RawPictures.WIDTH + left;
                    for (int x = 0; x < renderWidth; x++) {
                        byte value = frame.pixels()[y * renderWidth + x];
                        for (int repeatX = 0; repeatX < size.scaleX(); repeatX++) {
                            screen[target + x * size.scaleX() + repeatX] = value;
                        }
                    }
                }
            }
        }
        fov.breathless.engine.Crosshair.draw(screen, RawPictures.WIDTH, VIEW_HEIGHT);
        System.arraycopy(panel, 0, screen, RawPictures.WIDTH * VIEW_HEIGHT, panel.length);
        write(screen, RawPictures.WIDTH, height, palette, file);
    }

    /** Repete la tenture sur toute la vue. */
    private static void tile(byte[] screen, PlanarImage backdrop) {
        byte[] pixels = backdrop.pixels();
        int size = backdrop.width();
        for (int y = 0; y < VIEW_HEIGHT; y++) {
            int source = (y % size) * size;
            for (int x = 0; x < RawPictures.WIDTH; x++) {
                screen[y * RawPictures.WIDTH + x] = pixels[source + (x % size)];
            }
        }
    }

    private static int distinct(byte[] pixels) {
        boolean[] seen = new boolean[256];
        int count = 0;
        for (byte pixel : pixels) {
            int index = pixel & 0xFF;
            if (!seen[index]) {
                seen[index] = true;
                count++;
            }
        }
        return count;
    }

    private static void write(byte[] pixels, int width, int height, Palette palette, Path file)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] argb = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            argb[i] = palette.argb(pixels[i] & 0xFF);
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
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
