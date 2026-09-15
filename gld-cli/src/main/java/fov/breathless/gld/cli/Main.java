package fov.breathless.gld.cli;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.GldFormatException;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.EffectEntry;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.level.MapObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Outils en ligne de commande sur les donnees Breathless d'origine. */
public final class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }

        String command = args[0];
        Path target = Path.of(args[1]);

        switch (command) {
            case "probe" -> probe(target);
            case "levels" -> levels(target);
            case "level" -> level(target);
            case "render" -> {
                if (args.length < 4) {
                    usage();
                    System.exit(2);
                }
                FrameExport.run(target, Path.of(args[2]), Path.of(args[3]));
            }
            case "sprites" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                SpriteExport.run(target, Path.of(args[2]));
            }
            case "map" -> {
                if (args.length < 4) {
                    usage();
                    System.exit(2);
                }
                MapExport.run(target, Path.of(args[2]), Path.of(args[3]));
            }
            case "pictures" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                PictureExport.run(target, Path.of(args[2]));
            }
            case "modules" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                ModuleExport.run(target, Path.of(args[2]));
            }
            case "sounds" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                SoundExport.run(target, Path.of(args[2]));
            }
            case "terminal" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                TerminalExport.run(target, Path.of(args[2]));
            }
            case "panel" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                PanelExport.run(target, Path.of(args[2]));
            }
            case "textures" -> {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                TextureExport.run(target, Path.of(args[2]));
            }
            default -> {
                System.err.println("Commande inconnue : " + command);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.err.println("""
                Usage :
                  probe  <repertoire>   Inventorie les fichiers .GLD : compression, ID, tailles
                  levels <repertoire>   Analyse tous les niveaux et resume leur contenu
                  level  <fichier>      Detaille un niveau : blocs, effets, objets, carte
                  textures <repertoire> <sortie>
                                        Exporte palette, rampe d'eclairage et textures en PNG
                  sprites <repertoire> <sortie>
                                        Exporte les sprites d'objets en PNG transparents
                  map <repertoire> <niveau.gld> <sortie>
                                        Exporte la carte automatique d'un niveau
                  pictures <repertoire> <sortie>
                                        Exporte les images plein ecran en PNG
                  modules <repertoire> <sortie>
                                        Convertit les morceaux P61A en modules ProTracker
                  sounds <repertoire> <sortie>
                                        Inventorie la banque sonore et exporte les bruitages
                  terminal <repertoire> <sortie>
                                        Exporte les quatre pages du terminal
                  panel <repertoire> <sortie>
                                        Exporte le panneau de bord, vide puis rempli
                  render <repertoire> <niveau.gld> <sortie>
                                        Rend quatre vues fixes depuis le point de depart
                """);
    }

    // ---------------------------------------------------------------- probe

    /** Ouvre tous les .GLD d'un repertoire et resume ce qu'on arrive a en lire. */
    private static void probe(Path dir) throws IOException {
        List<Path> files = gldFiles(dir);
        if (files.isEmpty()) {
            System.out.println("Aucun fichier .GLD dans " + dir);
            return;
        }

        System.out.printf("%-18s %-9s %10s %10s  %s%n", "FICHIER", "CONTENU", "DISQUE", "EN CLAIR", "ETAT");
        System.out.println("-".repeat(72));

        int ok = 0;
        int failed = 0;
        for (Path file : files) {
            long onDisk = Files.size(file);
            try {
                GldContainer gld = GldContainer.read(file);
                String state = gld.isCompressed()
                        ? String.format("SLZ, ratio %.2f", (double) gld.size() / gld.packedSize())
                        : "non compresse";
                System.out.printf("%-18s %-9s %10d %10d  %s%n",
                        file.getFileName(), gld.payloadId(), onDisk, gld.size(), state);
                ok++;
            } catch (GldFormatException e) {
                System.out.printf("%-18s %-9s %10d %10s  ECHEC : %s%n",
                        file.getFileName(), "?", onDisk, "-", e.getMessage());
                failed++;
            }
        }

        System.out.println("-".repeat(72));
        System.out.printf("%d fichier(s) lu(s), %d echec(s)%n", ok, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --------------------------------------------------------------- levels

    /** Analyse tous les niveaux d'un repertoire et les resume en une ligne chacun. */
    private static void levels(Path dir) throws IOException {
        List<Path> files = gldFiles(dir);

        System.out.printf("%-16s %6s %6s %7s %7s %7s %7s %7s  %s%n",
                "NIVEAU", "BLOCS", "ARETES", "EFFETS", "TEXT.", "OBJETS", "SONS", "CASES", "DEPART");
        System.out.println("-".repeat(94));

        int parsed = 0;
        int failed = 0;
        int totalObjects = 0;
        for (Path file : files) {
            GldContainer gld;
            try {
                gld = GldContainer.read(file);
            } catch (GldFormatException e) {
                System.out.printf("%-16s ECHEC ouverture : %s%n", file.getFileName(), e.getMessage());
                failed++;
                continue;
            }
            if (!LevelReader.LGLD.equals(gld.payloadId())) {
                continue;
            }

            try {
                Level level = LevelReader.read(gld);
                int used = countUsedCells(level);
                System.out.printf("%-16s %6d %6d %7d %7d %7d %7d %7d  %d,%d cap %d%n",
                        file.getFileName(),
                        level.blocks().size(),
                        level.edges().size(),
                        level.effectLists().size(),
                        level.textureNames().size(),
                        level.objects().size(),
                        level.soundNames().size(),
                        used,
                        level.playerStart().x(), level.playerStart().z(), level.playerStart().heading());
                totalObjects += level.objects().size();
                parsed++;
            } catch (GldFormatException e) {
                System.out.printf("%-16s ECHEC analyse : %s%n", file.getFileName(), e.getMessage());
                failed++;
            }
        }

        System.out.println("-".repeat(94));
        System.out.printf("%d niveau(x) analyse(s), %d echec(s), %d objets places au total%n",
                parsed, failed, totalObjects);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- level

    /** Detaille un niveau : c'est la vue qui sert a verifier une lecture a la main. */
    private static void level(Path file) throws IOException {
        Level level = LevelReader.read(GldContainer.read(file));

        System.out.println("Niveau " + level.name());
        System.out.println("  image de chargement : " + level.loadingPicture());
        System.out.println("  depart joueur       : x=" + level.playerStart().x()
                + " z=" + level.playerStart().z()
                + " cap=" + level.playerStart().heading());
        System.out.println("  blocs               : " + level.blocks().size());
        System.out.println("  aretes              : " + level.edges().size());
        System.out.println("  cases occupees      : " + countUsedCells(level) + " / " + (128 * 128));

        System.out.println();
        System.out.println("Textures (" + level.textureNames().size() + ") :");
        printWrapped(level.textureNames());

        System.out.println();
        System.out.println("Sons (" + level.soundNames().size() + ") :");
        printWrapped(level.soundNames());

        System.out.println();
        System.out.println("Objets places (" + level.objects().size() + ") :");
        Map<String, Integer> byName = new LinkedHashMap<>();
        int dormant = 0;
        for (MapObject o : level.objects()) {
            byName.merge(level.objectName(o.objectIndex()), 1, Integer::sum);
            if (o.isDormant()) {
                dormant++;
            }
        }
        byName.forEach((n, c) -> System.out.printf("  %-8s x%d%n", n, c));
        System.out.println("  dont " + dormant + " en attente d'un declenchement");

        System.out.println();
        System.out.println("Listes d'effets (" + level.effectLists().size() + ") :");
        for (int i = 0; i < level.effectLists().size(); i++) {
            List<EffectEntry> list = level.effectLists().get(i);
            StringBuilder sb = new StringBuilder();
            for (EffectEntry e : list) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append("trigger ").append(e.trigger())
                        .append(" -> effet ").append(e.effect())
                        .append(" (").append(e.param1()).append(',').append(e.param2()).append(')');
                if (e.key() != 0) {
                    sb.append(" cle ").append(e.key());
                }
            }
            System.out.printf("  %3d : %s%n", i + 1, sb.length() == 0 ? "(vide)" : sb);
        }

        System.out.println();
        System.out.println("Apercu de la carte autour du depart :");
        printMapExcerpt(level);
    }

    /** Vue ASCII centree sur le depart : '#' mur, '.' sol, ' ' vide, '@' joueur. */
    private static void printMapExcerpt(Level level) {
        int cx = Math.floorDiv(level.playerStart().x(), Level.BLOCK_SIZE);
        int cz = Math.floorDiv(level.playerStart().z(), Level.BLOCK_SIZE);
        int radius = 24;

        for (int y = cz - radius / 2; y <= cz + radius / 2; y++) {
            StringBuilder line = new StringBuilder("  ");
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x < 0 || x >= Level.MAP_SIZE || y < 0 || y >= Level.MAP_SIZE) {
                    line.append(' ');
                } else if (x == cx && y == cz) {
                    line.append('@');
                } else if (level.isVoid(x, y)) {
                    line.append(' ');
                } else if (level.isWall(x, y)) {
                    line.append('#');
                } else {
                    line.append('.');
                }
            }
            System.out.println(line);
        }
    }

    // --------------------------------------------------------------- outils

    private static int countUsedCells(Level level) {
        int used = 0;
        for (int y = 0; y < Level.MAP_SIZE; y++) {
            for (int x = 0; x < Level.MAP_SIZE; x++) {
                if (!level.isVoid(x, y)) {
                    used++;
                }
            }
        }
        return used;
    }

    private static void printWrapped(List<String> names) {
        StringBuilder line = new StringBuilder("  ");
        for (String n : names) {
            if (line.length() + n.length() + 1 > 78) {
                System.out.println(line);
                line = new StringBuilder("  ");
            }
            line.append(n).append(' ');
        }
        if (line.length() > 2) {
            System.out.println(line);
        }
    }

    private static List<Path> gldFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            System.err.println("Repertoire introuvable : " + dir);
            System.exit(1);
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

    private Main() {
    }
}
