package fov.breathless.game;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Trouve les donnees du jeu, et va les chercher si elles manquent.
 *
 * <p>Ce depot ne contient pas les donnees de <em>Breathless</em> : il ne redistribue rien.
 * Elles vivent dans l'archive des sources publiee par ses auteurs, que le jeu telecharge
 * au premier lancement et range une fois pour toutes dans le repertoire de l'utilisateur.
 * Les lancements suivants la retrouvent sans rien demander.
 *
 * <p>Une copie locale l'emporte toujours : un repertoire donne en argument, la propriete
 * {@code breathless.data}, ou l'archive posee a cote du jeu.
 */
final class DataStore {

    /** L'archive des sources d'origine, telle que GitHub la sert en zip. */
    private static final String ARCHIVE =
            "https://codeload.github.com/mheyer32/Breathless/zip/refs/heads/master";

    /** D'ou viennent les donnees, pour le dire a qui lance le jeu. */
    static final String ORIGIN = "https://github.com/mheyer32/Breathless";

    /** Fichier maitre : sa presence signe un repertoire de donnees valable. */
    private static final String MASTER = "breathless.gld";

    /** Au-dela, on renonce : le serveur ne repond pas. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private DataStore() {
    }

    /**
     * Le repertoire ou le jeu range les donnees telechargees.
     *
     * <p>Chaque systeme a le sien ; a defaut, un repertoire cache sous le compte.
     */
    static Path localDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home", "."));

        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            Path base = (appData == null || appData.isBlank()) ? home : Path.of(appData);
            return base.resolve("Breathless").resolve("data");
        }
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support")
                    .resolve("Breathless").resolve("data");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = (xdg == null || xdg.isBlank())
                ? home.resolve(".local").resolve("share") : Path.of(xdg);
        return base.resolve("breathless").resolve("data");
    }

    /** Un repertoire de donnees contient le fichier maitre du jeu. */
    static boolean holdsGameData(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        try (var stream = Files.list(directory)) {
            return stream.anyMatch(file ->
                    file.getFileName().toString().equalsIgnoreCase(MASTER));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Telecharge l'archive d'origine et la depose dans {@link #localDirectory()}.
     *
     * <p>L'extraction se fait a cote, puis bascule d'un coup : un telechargement
     * interrompu ne laisse pas un repertoire a moitie rempli, que le lancement suivant
     * croirait bon.
     *
     * @param progress recoit les etapes, pour qui veut les montrer
     * @return le repertoire des donnees
     */
    static Path download(java.util.function.Consumer<String> progress) throws IOException {
        Path target = localDirectory();
        Path staging = target.resolveSibling(target.getFileName() + ".partiel");

        deleteTree(staging);
        Files.createDirectories(staging);

        progress.accept("Telechargement de l'archive d'origine...");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ARCHIVE))
                .timeout(TIMEOUT)
                .header("Accept", "application/zip")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Telechargement interrompu", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("L'archive d'origine a repondu " + response.statusCode()
                    + " (" + ARCHIVE + ")");
        }

        progress.accept("Extraction...");
        int files;
        try (InputStream body = response.body()) {
            files = extract(body, staging);
        }
        if (files == 0) {
            deleteTree(staging);
            throw new IOException("L'archive telechargee est vide");
        }
        if (!holdsGameData(staging)) {
            deleteTree(staging);
            throw new IOException("L'archive telechargee ne contient pas " + MASTER);
        }

        deleteTree(target);
        Files.createDirectories(target.getParent());
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        progress.accept("Donnees installees dans " + target);
        return target;
    }

    /**
     * Deplie le zip, en otant le repertoire de tete que GitHub ajoute.
     *
     * @return le nombre de fichiers ecrits
     */
    static int extract(InputStream body, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        int written = 0;

        try (ZipInputStream zip = new ZipInputStream(body)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = stripLeadingDirectory(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                Path file = root.resolve(name).normalize();
                // Une entree qui remonte hors du repertoire n'est pas ecrite.
                if (!file.startsWith(root)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                    continue;
                }
                Files.createDirectories(file.getParent());
                Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
                written++;
            }
        }
        return written;
    }

    /** {@code Breathless-master/Sorgenti/x} devient {@code Sorgenti/x}. */
    static String stripLeadingDirectory(String name) {
        String clean = name.replace('\\', '/');
        int slash = clean.indexOf('/');
        return (slash < 0) ? "" : clean.substring(slash + 1);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
