package fov.breathless.gld.cli;

import fov.breathless.engine.AutoMap;
import fov.breathless.engine.Camera;
import fov.breathless.engine.Trig;
import fov.breathless.engine.World;
import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.Palette;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.object.ObjectBank;
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
 * Exporte la carte automatique d'un niveau en PNG.
 *
 * <p>Deux images : ce que le joueur voit depuis son point de depart, puis la carte
 * complete, comme s'il avait tout parcouru. La seconde sert a verifier le trace des
 * passages sans avoir a jouer le niveau.
 */
final class MapExport {

    private static final int WIDTH = AutoMap.VIEW_COLUMNS * AutoMap.CELL;
    private static final int HEIGHT = AutoMap.VIEW_ROWS * AutoMap.CELL;

    private MapExport() {
    }

    static void run(Path dataDir, Path levelFile, Path outDir) throws IOException {
        GfxBank gfx = null;
        TextureBank textures = null;
        ObjectBank objects = null;
        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            switch (gld.payloadId()) {
                case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                case TextureBank.TGLD -> textures = TextureBank.read(gld);
                case ObjectBank.OGLD -> objects = ObjectBank.read(gld);
                default -> {
                    // Sans objet ici.
                }
            }
        }
        if (gfx == null || textures == null) {
            throw new IOException("Banque graphique ou de textures absente de " + dataDir);
        }

        Level level = LevelReader.read(GldContainer.read(levelFile));
        World world = new World(level, textures, gfx, objects);
        Camera camera = Camera.atPlayerStart(level);
        Files.createDirectories(outDir);
        Palette palette = gfx.palette();

        // Un tour sur place : ce que trois rayons relevent sans faire un pas.
        AutoMap fromStart = new AutoMap(world);
        int heading = camera.heading();
        for (int turn = 0; turn < Trig.STEPS; turn += 32) {
            camera.setHeading(turn);
            fromStart.survey(camera);
        }
        camera.setHeading(heading);
        write(render(fromStart, camera), palette, outDir.resolve("carte-depart.png"));
        System.out.printf("carte-depart.png     %d cases relevees depuis le point de depart%n",
                fromStart.seenCount());

        // Tout le niveau : le trace des passages, sans avoir a le parcourir.
        AutoMap whole = new AutoMap(world);
        surveyEverywhere(whole, world, level, camera);
        write(render(whole, camera), palette, outDir.resolve("carte-complete.png"));
        System.out.printf("carte-complete.png   %d cases relevees sur les %d praticables%n",
                whole.seenCount(), walkable(level));
    }

    /** Fait le tour de toutes les cases praticables, pour relever la carte entiere. */
    private static void surveyEverywhere(AutoMap map, World world, Level level, Camera camera) {
        int heading = camera.heading();
        int x = camera.x();
        int z = camera.z();
        for (int cellZ = 0; cellZ < Level.MAP_SIZE; cellZ++) {
            for (int cellX = 0; cellX < Level.MAP_SIZE; cellX++) {
                if (level.isVoid(cellX, cellZ) || level.isWall(cellX, cellZ)) {
                    continue;
                }
                camera.setPosition(
                        fov.breathless.engine.Fixed.of(cellX * Level.BLOCK_SIZE + 32),
                        fov.breathless.engine.Fixed.of(cellZ * Level.BLOCK_SIZE + 32));
                for (int turn = 0; turn < Trig.STEPS; turn += 256) {
                    camera.setHeading(turn);
                    map.survey(camera);
                }
            }
        }
        camera.setPosition(x, z);
        camera.setHeading(heading);
    }

    private static int walkable(Level level) {
        int count = 0;
        for (int z = 0; z < Level.MAP_SIZE; z++) {
            for (int x = 0; x < Level.MAP_SIZE; x++) {
                if (!level.isVoid(x, z) && !level.isWall(x, z)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static byte[] render(AutoMap map, Camera camera) {
        byte[] pixels = new byte[WIDTH * HEIGHT];
        map.render(pixels, WIDTH, HEIGHT, camera);
        return pixels;
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
