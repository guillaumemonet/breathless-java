package fov.breathless.gld.cli;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.LightingTable;
import fov.breathless.gld.gfx.Palette;
import fov.breathless.gld.texture.Texture;
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
 * Export des textures d'origine en PNG.
 *
 * <p>C'est la verification la plus directe de la lecture : si les images sorties
 * ressemblent a des murs, c'est que la disposition par colonnes, la palette et les
 * offsets d'enregistrement sont tous corrects.
 */
final class TextureExport {

    private TextureExport() {
    }

    static void run(Path dataDir, Path outDir) throws IOException {
        GfxBank gfx = null;
        TextureBank textures = null;

        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            switch (gld.payloadId()) {
                case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                case TextureBank.TGLD -> textures = TextureBank.read(gld);
                default -> {
                    // Les autres banques ne concernent pas cet export.
                }
            }
        }

        if (gfx == null) {
            throw new IOException("Aucune banque graphique (GGLD) dans " + dataDir);
        }
        if (textures == null) {
            throw new IOException("Aucune banque de textures (TGLD) dans " + dataDir);
        }

        Files.createDirectories(outDir);
        Palette palette = gfx.palette();

        System.out.println("Palettes            : " + gfx.palettes().size());
        System.out.println("Tables d'eclairage  : " + gfx.lightingTables().size());
        System.out.println("Images plein ecran  : " + gfx.pictures().size());
        System.out.println("Textures            : " + textures.size());
        System.out.println();

        writePalette(palette, outDir.resolve("palette.png"));
        writeLightingRamp(palette, gfx.lightingTable(), outDir.resolve("lighting-ramp.png"));

        int files = 0;
        int animated = 0;
        int switches = 0;
        for (Texture texture : textures.textures().values()) {
            files += writeTexture(texture, palette, outDir);
            if (texture.isAnimated()) {
                animated++;
            }
            if (texture.isSwitch()) {
                switches++;
            }
        }

        System.out.println(files + " PNG ecrits dans " + outDir);
        System.out.println("  dont " + animated + " textures animees et " + switches + " a variante d'interrupteur");
    }

    /** Ecrit une texture et, le cas echeant, ses images d'animation et sa variante. */
    private static int writeTexture(Texture texture, Palette palette, Path outDir) throws IOException {
        int written = 0;
        for (int frame = 0; frame < texture.frameCount(); frame++) {
            String suffix = texture.frameCount() > 1 ? String.format("_f%02d", frame) : "";
            Path file = outDir.resolve(sanitize(texture.name()) + suffix + ".png");
            ImageIO.write(toImage(texture, frame, palette), "png", file.toFile());
            written++;
        }
        if (texture.isSwitch()) {
            written += writeTexture(texture.switched(), palette, outDir);
        }
        return written;
    }

    /** Convertit une image de texture, rangee par colonnes, en image RVB. */
    private static BufferedImage toImage(Texture texture, int frame, Palette palette) {
        BufferedImage image = new BufferedImage(texture.width(), texture.height(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < texture.width(); x++) {
            for (int y = 0; y < texture.height(); y++) {
                image.setRGB(x, y, palette.argb(texture.pixel(frame, x, y)));
            }
        }
        return image;
    }

    /** Nuancier 16 x 16 de la palette, une case de 16 pixels par couleur. */
    private static void writePalette(Palette palette, Path file) throws IOException {
        int cell = 16;
        BufferedImage image = new BufferedImage(16 * cell, 16 * cell, BufferedImage.TYPE_INT_RGB);
        for (int index = 0; index < Palette.COLORS; index++) {
            int color = palette.argb(index);
            int ox = (index % 16) * cell;
            int oy = (index / 16) * cell;
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    image.setRGB(ox + x, oy + y, color);
                }
            }
        }
        ImageIO.write(image, "png", file.toFile());
        System.out.println("Palette      -> " + file.getFileName());
    }

    /**
     * Rampe d'eclairage : une ligne par niveau de lumiere, la palette entiere en
     * colonnes. Permet de verifier d'un coup d'oeil que la table assombrit bien de
     * facon monotone.
     */
    private static void writeLightingRamp(Palette palette, LightingTable lighting, Path file) throws IOException {
        int cell = 4;
        BufferedImage image = new BufferedImage(Palette.COLORS * cell, LightingTable.LEVELS * cell,
                BufferedImage.TYPE_INT_RGB);
        for (int level = 0; level < LightingTable.LEVELS; level++) {
            for (int index = 0; index < Palette.COLORS; index++) {
                int color = palette.argb(lighting.shade(level, index));
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) {
                        image.setRGB(index * cell + x, level * cell + y, color);
                    }
                }
            }
        }
        ImageIO.write(image, "png", file.toFile());
        System.out.println("Eclairage    -> " + file.getFileName());
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
