package fov.breathless.gld.cli;

import fov.breathless.engine.Camera;
import fov.breathless.engine.Framebuffer;
import fov.breathless.engine.RayTable;
import fov.breathless.engine.Renderer;
import fov.breathless.engine.Viewport;
import fov.breathless.engine.World;
import fov.breathless.engine.Fixed;
import fov.breathless.engine.SceneObject;
import fov.breathless.engine.Trig;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.texture.TextureBank;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rendu d'images fixes du moteur, sans boucle temps reel ni jMonkeyEngine.
 *
 * <p>Sert de banc d'essai au raycaster : une image vaut mieux qu'un test unitaire pour
 * juger d'une projection.
 */
final class FrameExport {

    /** Taille de la fenetre standard du jeu. */
    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;

    private FrameExport() {
    }

    static void run(Path dataDir, Path levelFile, Path outDir) throws IOException {
        GfxBank gfx = null;
        TextureBank textures = null;
        ObjectBank objectBank = null;
        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            switch (gld.payloadId()) {
                case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                case TextureBank.TGLD -> textures = TextureBank.read(gld);
                case ObjectBank.OGLD -> objectBank = ObjectBank.read(gld);
                default -> {
                    // Les sons ne servent pas au rendu.
                }
            }
        }
        if (gfx == null || textures == null) {
            throw new IOException("Banque graphique ou banque de textures absente de " + dataDir);
        }

        Level level = LevelReader.read(GldContainer.read(levelFile));
        World world = new World(level, textures, gfx, objectBank);
        Viewport viewport = new Viewport(WIDTH, HEIGHT);
        Renderer renderer = new Renderer(world, viewport);

        Files.createDirectories(outDir);

        Camera camera = Camera.atPlayerStart(level);
        System.out.println("Niveau " + level.name()
                + " : depart " + (camera.x() >> 16) + "," + (camera.z() >> 16)
                + " cap " + camera.heading()
                + " oeil a " + camera.y());
        System.out.println("  " + world.objects().size() + " objets instancies");

        // Vues depuis le point de depart : le cap du niveau, une diagonale, puis des
        // quarts de tour. La diagonale est la vue qui revele une erreur de projection.
        int[] turns = {0, 256, 512, 1024, 1536};
        for (int turn : turns) {
            camera.setHeading(level.playerStart().heading() + turn);
            Framebuffer frame = renderer.render(camera);

            String name = String.format("%s-cap%04d.png",
                    level.name().replaceAll("\\.[Gg][Ll][Dd]$", ""), camera.heading());
            Path file = outDir.resolve(name);
            write(frame, world, file);
            System.out.println("  " + name + "  " + describe(renderer.rayTable()));
        }

        renderSkyView(level, world, renderer, outDir);
        renderObjectView(level, world, renderer, outDir);
    }

    /**
     * Vue cadree sur le premier objet eveille du niveau, depuis une case praticable
     * voisine. Sans cela les objets n'apparaissent pratiquement jamais dans les vues
     * prises au point de depart.
     */
    private static void renderObjectView(Level level, World world, Renderer renderer, Path outDir)
            throws IOException {
        for (SceneObject object : world.objects()) {
            if (object.isDormant()) {
                continue;
            }
            int cellX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
            int cellZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);

            // Cherche une case libre a trois blocs de la, dans les quatre directions.
            int[][] around = {{-3, 0}, {3, 0}, {0, -3}, {0, 3}};
            for (int[] offset : around) {
                int atX = cellX + offset[0];
                int atZ = cellZ + offset[1];
                if (atX < 0 || atX >= Level.MAP_SIZE || atZ < 0 || atZ >= Level.MAP_SIZE) {
                    continue;
                }
                if (level.isVoid(atX, atZ) || level.isWall(atX, atZ)) {
                    continue;
                }
                Block block = level.blockAt(atX, atZ);
                int eyeX = atX * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2;
                int eyeZ = atZ * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2;

                // Cap vers l'objet, dans l'echelle 2048 pas par tour du moteur.
                double angle = Math.atan2(object.z() - eyeZ, object.x() - eyeX);
                int heading = (int) Math.round(angle * Trig.STEPS / (2 * Math.PI)) & (Trig.STEPS - 1);

                Camera camera = new Camera(Fixed.of(eyeX), Fixed.of(eyeZ),
                        block.floorHeight() + Camera.EYES_HEIGHT, heading);
                Framebuffer frame = renderer.render(camera);

                String name = level.name().replaceAll("\\.[Gg][Ll][Dd]$", "") + "-objet.png";
                write(frame, world, outDir.resolve(name));
                System.out.println("  " + name + "  " + object.definition().name()
                        + " vu depuis " + atX + "," + atZ);
                return;
            }
        }
    }

    /**
     * Vue supplementaire depuis une case a ciel ouvert, si le niveau en comporte une.
     * C'est le seul moyen commode de verifier le trace du ciel, que le point de depart
     * ne montre pratiquement jamais.
     */
    private static void renderSkyView(Level level, World world, Renderer renderer, Path outDir)
            throws IOException {
        for (int z = 0; z < Level.MAP_SIZE; z++) {
            for (int x = 0; x < Level.MAP_SIZE; x++) {
                Block block = level.blockAt(x, z);
                if (block == null || level.isWall(x, z) || !block.hasSkyCeiling()) {
                    continue;
                }
                Camera camera = new Camera(
                        Fixed.of(x * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                        Fixed.of(z * Level.BLOCK_SIZE + Level.BLOCK_SIZE / 2),
                        block.floorHeight() + Camera.EYES_HEIGHT,
                        level.playerStart().heading());
                Framebuffer frame = renderer.render(camera);

                String name = level.name().replaceAll("\\.[Gg][Ll][Dd]$", "") + "-ciel.png";
                write(frame, world, outDir.resolve(name));
                System.out.println("  " + name + "  case a ciel ouvert en " + x + "," + z);
                return;
            }
        }
    }

    /** Statistiques de la passe de rayons, utiles quand l'image parait fausse. */
    private static String describe(RayTable table) {
        int total = 0;
        int empty = 0;
        int max = 0;
        for (int column = 0; column < table.columns(); column++) {
            int n = table.count(column);
            total += n;
            max = Math.max(max, n);
            if (n == 0) {
                empty++;
            }
        }
        return String.format("%d transitions, %.1f par colonne, max %d, %d colonne(s) vide(s)",
                total, (double) total / table.columns(), max, empty);
    }

    private static void write(Framebuffer frame, World world, Path file) throws IOException {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, frame.width(), frame.height(), frame.toArgb(world.palette()), 0, frame.width());
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
