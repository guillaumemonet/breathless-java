package fov.breathless.gld.gfx;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.texture.Texture;
import fov.breathless.gld.texture.TextureBank;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lecture de la banque graphique et de la banque de textures d'origine.
 *
 * <p>Ignore si les donnees d'origine sont absentes. Les localiser avec
 * {@code -Dbreathless.data=<chemin>}.
 */
class GraphicsBankIT {

    private static Path dataDir() {
        String configured = System.getProperty("breathless.data");
        Path dir = (configured != null) ? Path.of(configured) : Path.of("..", "..", "Breathless");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Donnees d'origine absentes (" + dir.toAbsolutePath() + ") : test ignore");
        return dir;
    }

    private static GldContainer find(String payloadId) throws IOException {
        try (var stream = Files.list(dataDir())) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                if (payloadId.equals(gld.payloadId())) {
                    return gld;
                }
            }
        }
        throw new AssertionError("Aucune banque " + payloadId + " dans les donnees d'origine");
    }

    @Test
    @DisplayName("La banque graphique livre deux palettes et deux tables d'eclairage")
    void gfxBankHasPalettesAndLighting() throws IOException {
        GfxBank gfx = GfxBank.read(find(GfxBank.GGLD));

        assertEquals(2, gfx.palettes().size(), "palette normale et palette de degats");
        assertEquals(2, gfx.lightingTables().size(), "eclairage normal et brouillard");
        assertFalse(gfx.pictures().isEmpty(), "aucune image plein ecran");
    }

    @Test
    @DisplayName("L'eclairage va de la pleine lumiere au noir, sans remontee")
    void lightingDarkensMonotonically() throws IOException {
        GfxBank gfx = GfxBank.read(find(GfxBank.GGLD));
        Palette palette = gfx.palette();
        LightingTable lighting = gfx.lightingTable();

        long previous = Long.MAX_VALUE;
        for (int level = 0; level < LightingTable.LEVELS; level++) {
            long luminance = 0;
            for (int index = 0; index < Palette.COLORS; index++) {
                int shaded = lighting.shade(level, index);
                luminance += palette.red(shaded) + palette.green(shaded) + palette.blue(shaded);
            }
            assertTrue(luminance <= previous,
                    "le niveau " + level + " est plus clair que le precedent");
            previous = luminance;
        }

        // Le dernier niveau doit etre effectivement noir ou presque.
        long darkest = 0;
        for (int index = 0; index < Palette.COLORS; index++) {
            int shaded = lighting.shade(LightingTable.LEVELS - 1, index);
            darkest += palette.red(shaded) + palette.green(shaded) + palette.blue(shaded);
        }
        assertTrue(darkest / (Palette.COLORS * 3) < 10, "le niveau le plus sombre n'est pas noir");
    }

    @Test
    @DisplayName("Toutes les textures se lisent et ont des dimensions de brush valides")
    void everyTextureIsReadable() throws IOException {
        TextureBank bank = TextureBank.read(find(TextureBank.TGLD));

        assertFalse(bank.textures().isEmpty(), "banque de textures vide");

        // Distribution observee dans les donnees d'origine : 64x64 (65), 64x128 (19),
        // 64x32 (8), 64x16 (1) et 256x128 (4). Les brosses de 256 de large sont les
        // ciels, conformement a SKY_BRUSH_WIDTH dans Sorgenti/TMap.i.
        for (Texture texture : bank.textures().values()) {
            String id = texture.name();

            assertTrue(texture.width() == 64 || texture.width() == 256,
                    id + " : largeur inattendue (" + texture.width() + ")");

            // Le moteur multiplie par la hauteur avec un decalage : elle est donc
            // necessairement une puissance de deux, et l'exposant doit la retrouver.
            assertEquals(texture.height(), 1 << texture.heightShift(),
                    id + " : exposant " + texture.heightShift()
                            + " incoherent avec la hauteur " + texture.height());
            assertTrue(texture.height() >= 1 && texture.height() <= 256,
                    id + " : hauteur hors bornes (" + texture.height() + ")");

            for (int frame = 0; frame < texture.frameCount(); frame++) {
                assertEquals(texture.width() * texture.height(), texture.frame(frame).length,
                        id + " : taille d'image " + frame + " incoherente");
            }
        }
    }

    @Test
    @DisplayName("Une texture d'interrupteur porte bien sa variante actionnee")
    void switchTextureCarriesItsVariant() throws IOException {
        TextureBank bank = TextureBank.read(find(TextureBank.TGLD));

        Texture aSwitch = bank.textures().values().stream()
                .filter(Texture::isSwitch)
                .findFirst()
                .orElseThrow(() -> new AssertionError("aucune texture a variante d'interrupteur"));

        Texture variant = aSwitch.switched();
        assertNotNull(variant);
        assertEquals(aSwitch.width(), variant.width(), "la variante change de largeur");
        assertEquals(aSwitch.height(), variant.height(), "la variante change de hauteur");
    }

    @Test
    @DisplayName("Les textures nommees par les niveaux existent toutes dans la banque")
    void levelsOnlyReferenceExistingTextures() throws IOException {
        TextureBank bank = TextureBank.read(find(TextureBank.TGLD));

        int checked = 0;
        try (var stream = Files.list(dataDir())) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                if (!"LGLD".equals(gld.payloadId())) {
                    continue;
                }
                var level = fov.breathless.gld.level.LevelReader.read(gld);
                for (String name : level.textureNames()) {
                    assertNotNull(bank.get(name),
                            level.name() + " reclame la texture absente " + name);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "aucune reference de texture verifiee");
    }
}
