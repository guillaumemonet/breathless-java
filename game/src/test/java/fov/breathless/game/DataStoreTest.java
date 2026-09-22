package fov.breathless.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Depliage de l'archive des donnees d'origine.
 *
 * <p>Sans reseau : une archive est fabriquee a la forme de celles que sert GitHub, avec
 * son repertoire de tete.
 */
class DataStoreTest {

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    zip.write(name.getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("Le repertoire de tete de l'archive est ote")
    void theLeadingDirectoryIsStripped() {
        assertEquals("Sorgenti/Graphic/Panel04.raw",
                DataStore.stripLeadingDirectory("Breathless-master/Sorgenti/Graphic/Panel04.raw"));
        assertEquals("BREATHLESS.GLD",
                DataStore.stripLeadingDirectory("Breathless-master/BREATHLESS.GLD"));
        // La racine de l'archive elle-meme ne donne rien a ecrire.
        assertEquals("", DataStore.stripLeadingDirectory("Breathless-master/"));
        assertEquals("", DataStore.stripLeadingDirectory("sans-barre-oblique"));
    }

    @Test
    @DisplayName("Les fichiers du jeu se retrouvent a la racine du repertoire")
    void gameFilesLandAtTheRoot(@TempDir Path target) throws IOException {
        byte[] archive = zip(
                "Breathless-master/",
                "Breathless-master/BREATHLESS.GLD",
                "Breathless-master/BLES0006.GLD",
                "Breathless-master/Sorgenti/Graphic/Panel04.raw");

        int written = DataStore.extract(new ByteArrayInputStream(archive), target);

        assertEquals(3, written);
        assertTrue(Files.isRegularFile(target.resolve("BREATHLESS.GLD")));
        assertTrue(Files.isRegularFile(target.resolve("BLES0006.GLD")));
        assertTrue(Files.isRegularFile(target.resolve("Sorgenti/Graphic/Panel04.raw")));
        // C'est ce fichier qui signe un repertoire de donnees valable.
        assertTrue(DataStore.holdsGameData(target));
    }

    @Test
    @DisplayName("Une entree qui remonte hors du repertoire n'est pas ecrite")
    void entriesEscapingTheDirectoryAreRefused(@TempDir Path target) throws IOException {
        byte[] archive = zip(
                "Breathless-master/BREATHLESS.GLD",
                "Breathless-master/../evade.txt",
                "Breathless-master/../../evade-plus-loin.txt");

        int written = DataStore.extract(new ByteArrayInputStream(archive), target);

        assertEquals(1, written, "seul le fichier du jeu devait etre ecrit");
        assertFalse(Files.exists(target.resolveSibling("evade.txt")));
        assertFalse(Files.exists(target.getParent().resolveSibling("evade-plus-loin.txt")));
    }

    @Test
    @DisplayName("Un repertoire sans le fichier maitre n'est pas pris pour des donnees")
    void anIncompleteDirectoryIsNotMistakenForData(@TempDir Path target) throws IOException {
        assertFalse(DataStore.holdsGameData(target));
        Files.writeString(target.resolve("BLES0006.GLD"), "x");
        assertFalse(DataStore.holdsGameData(target));
        Files.writeString(target.resolve("BREATHLESS.GLD"), "x");
        assertTrue(DataStore.holdsGameData(target));
    }

    @Test
    @DisplayName("Le rangement des donnees depend du systeme")
    void theDataDirectoryFollowsTheSystem() {
        Path local = DataStore.localDirectory();
        assertTrue(local.isAbsolute(), "le rangement doit etre un chemin absolu");
        assertEquals("data", local.getFileName().toString());
    }
}
