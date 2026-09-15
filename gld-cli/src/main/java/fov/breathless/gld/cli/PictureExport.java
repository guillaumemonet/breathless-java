package fov.breathless.gld.cli;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.Picture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exporte en PNG les images plein ecran de la banque graphique. */
final class PictureExport {

    private PictureExport() {
    }

    static void run(Path dataDir, Path outDir) throws IOException {
        GfxBank gfx = null;
        for (Path file : gldFiles(dataDir)) {
            GldContainer gld = GldContainer.read(file);
            if (GfxBank.GGLD.equals(gld.payloadId())) {
                gfx = GfxBank.read(gld);
            }
        }
        if (gfx == null) {
            throw new IOException("Aucune banque graphique (GGLD) dans " + dataDir);
        }
        Files.createDirectories(outDir);

        System.out.printf("%-5s %10s %9s %9s %s%n", "NOM", "TAILLE", "POSITION", "OCTETS", "ETAT");
        System.out.println("-".repeat(56));

        for (String name : gfx.pictures().keySet()) {
            Picture picture = Picture.read(gfx.pictureData(name), name);

            BufferedImage image = new BufferedImage(picture.width(), picture.height(),
                    BufferedImage.TYPE_INT_RGB);
            int[] argb = new int[picture.pixels().length];
            for (int i = 0; i < argb.length; i++) {
                argb[i] = picture.palette().argb(picture.pixels()[i] & 0xFF);
            }
            image.setRGB(0, 0, picture.width(), picture.height(), argb, 0, picture.width());
            ImageIO.write(image, "png", outDir.resolve(name + ".png").toFile());

            System.out.printf("%-5s %10s %9s %9d %s%n", name,
                    picture.width() + "x" + picture.height(),
                    picture.x() + "," + picture.y(),
                    gfx.pictures().get(name).length(),
                    picture.isCompressed() ? "VDCO" : "en clair");
        }
        System.out.println();
        System.out.println(gfx.pictures().size() + " image(s) exportee(s)");
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
