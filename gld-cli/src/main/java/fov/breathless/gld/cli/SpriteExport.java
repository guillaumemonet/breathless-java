package fov.breathless.gld.cli;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.Palette;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.object.ObjectDefinition;
import fov.breathless.gld.object.ObjectFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Export des sprites d'objets en PNG, transparence comprise.
 *
 * <p>Meme role que l'export des textures : c'est la facon la plus directe de verifier
 * que les segments verticaux, les offsets de colonnes et la palette sont correctement
 * interpretes.
 */
final class SpriteExport {

    private SpriteExport() {
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
                    // Les autres banques ne concernent pas cet export.
                }
            }
        }
        if (gfx == null) {
            throw new IOException("Aucune banque graphique (GGLD) dans " + dataDir);
        }
        if (objects == null) {
            throw new IOException("Aucune banque d'objets (OGLD) dans " + dataDir);
        }

        Files.createDirectories(outDir);
        Palette palette = gfx.palette();

        System.out.println("Objets : " + objects.size());
        System.out.println();
        System.out.printf("%-6s %-14s %6s %8s  %s%n", "NOM", "TYPE", "IMAGES", "TAILLE", "ANIMATION");
        System.out.println("-".repeat(60));

        Map<String, Integer> byType = new TreeMap<>();
        int written = 0;

        for (ObjectDefinition object : objects.objects().values()) {
            byType.merge(typeName(object.objectType()), 1, Integer::sum);

            String size = object.frames().isEmpty() ? "-"
                    : object.frames().get(0).width() + "x" + object.frames().get(0).height();
            System.out.printf("%-6s %-14s %6d %8s  %s%n",
                    object.name(), typeName(object.objectType()), object.frames().size(),
                    size, animName(object.animType()));

            for (int i = 0; i < object.frames().size(); i++) {
                String suffix = object.frames().size() > 1 ? String.format("_%03d", i) : "";
                Path file = outDir.resolve(sanitize(object.name()) + suffix + ".png");
                ImageIO.write(toImage(object.frames().get(i), palette), "png", file.toFile());
                written++;
            }
        }

        System.out.println("-".repeat(60));
        byType.forEach((type, count) -> System.out.printf("  %-14s %d%n", type, count));
        System.out.println();
        System.out.println(written + " PNG ecrits dans " + outDir);
    }

    /** Les pixels hors segment restent transparents. */
    private static BufferedImage toImage(ObjectFrame frame, Palette palette) {
        BufferedImage image = new BufferedImage(frame.width(), frame.pixelHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < frame.width(); x++) {
            for (ObjectFrame.Segment segment : frame.column(x)) {
                for (int row = 0; row < segment.length(); row++) {
                    image.setRGB(x, segment.top() + row, palette.argb(segment.pixel(row)));
                }
            }
        }
        return image;
    }

    private static String typeName(int type) {
        return switch (type) {
            case ObjectDefinition.TYPE_THING -> "decor";
            case ObjectDefinition.TYPE_PLAYER -> "joueur";
            case ObjectDefinition.TYPE_ENEMY -> "ennemi";
            case ObjectDefinition.TYPE_PICKUP -> "ramassable";
            case ObjectDefinition.TYPE_SHOT -> "projectile";
            case ObjectDefinition.TYPE_EXPLOSION -> "explosion";
            default -> "type " + type;
        };
    }

    private static String animName(int animType) {
        return switch (animType) {
            case ObjectDefinition.ANIM_DIRECTIONAL -> "directionnelle";
            case ObjectDefinition.ANIM_NONE -> "aucune";
            case ObjectDefinition.ANIM_SIMPLE -> "simple";
            default -> "type " + animType;
        };
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
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
