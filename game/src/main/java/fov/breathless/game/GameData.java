package fov.breathless.game;

import fov.breathless.gld.GldContainer;
import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.RawPictures;
import fov.breathless.gld.gfx.PlanarImage;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.level.LevelReader;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.sound.SoundBank;
import fov.breathless.gld.texture.TextureBank;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chargement des donnees d'origine : banques partagees et liste des niveaux.
 *
 * <p>Les niveaux sont ouverts et analyses des le demarrage. Ils sont assez petits pour
 * que cela reste instantane, et cela evite un chargement en cours de partie.
 */
final class GameData {

    private final GfxBank gfx;
    private final TextureBank textures;
    private final ObjectBank objects;
    private final List<Level> levels;
    private final PlanarImage panel;
    private final SoundBank sounds;
    private final PlanarImage backdrop;
    private final fov.breathless.gld.MasterFile master;

    private GameData(GfxBank gfx, TextureBank textures, ObjectBank objects, List<Level> levels,
                     PlanarImage panel, SoundBank sounds,
                     PlanarImage backdrop, fov.breathless.gld.MasterFile master) {
        this.gfx = gfx;
        this.textures = textures;
        this.objects = objects;
        this.levels = levels;
        this.panel = panel;
        this.sounds = sounds;
        this.backdrop = backdrop;
        this.master = master;
    }

    /** Fichier maitre : noms des mondes et des niveaux, ou {@code null} s'il manque. */
    fov.breathless.gld.MasterFile master() {
        return master;
    }

    /** Tenture de fond, derriere une fenetre reduite, ou {@code null}. */
    PlanarImage backdrop() {
        return backdrop;
    }

    /** Banque sonore, ou {@code null} si le fichier SGLD manque. */
    SoundBank sounds() {
        return sounds;
    }

    /**
     * Image de fond du panneau de bord, ou {@code null} si elle est absente.
     *
     * <p>Elle ne vit pas dans un {@code .GLD} mais dans l'archive des sources ; une
     * installation qui ne contient que les donnees se joue alors sans panneau.
     */
    PlanarImage panel() {
        return panel;
    }

    static GameData load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Repertoire de donnees introuvable : " + directory.toAbsolutePath());
        }

        GfxBank gfx = null;
        TextureBank textures = null;
        ObjectBank objects = null;
        SoundBank sounds = null;
        List<Level> levels = new ArrayList<>();
        fov.breathless.gld.MasterFile master = null;

        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gld"))
                    .sorted()
                    .toList()) {
                GldContainer gld = GldContainer.read(file);
                switch (gld.payloadId()) {
                    case GfxBank.GGLD -> gfx = GfxBank.read(gld);
                    case TextureBank.TGLD -> textures = TextureBank.read(gld);
                    case ObjectBank.OGLD -> objects = ObjectBank.read(gld);
                    case LevelReader.LGLD -> levels.add(LevelReader.read(gld));
                    case SoundBank.SGLD -> sounds = SoundBank.read(gld);
                    case fov.breathless.gld.MasterFile.MGLD -> master = fov.breathless.gld.MasterFile.read(gld);
                    default -> {
                        // Rien d'autre a lire.
                    }
                }
            }
        }

        if (gfx == null) {
            throw new IOException("Aucune banque graphique (GGLD) dans " + directory);
        }
        if (textures == null) {
            throw new IOException("Aucune banque de textures (TGLD) dans " + directory);
        }
        if (levels.isEmpty()) {
            throw new IOException("Aucun niveau (LGLD) dans " + directory);
        }
        if (objects == null) {
            throw new IOException("Aucune banque d'objets (OGLD) dans " + directory);
        }
        Path raw = RawPictures.locate(directory);
        PlanarImage panel = (raw == null) ? null : RawPictures.read(raw);
        if (panel == null) {
            System.out.println("Panneau de bord absent (" + RawPictures.RELATIVE_PATH
                    + " introuvable) : la vue occupera tout l'ecran");
        }
        if (sounds == null) {
            System.out.println("Banque sonore (SGLD) absente : le jeu sera muet");
        }
        Path backdropFile = RawPictures.locateBackdrop(directory);
        PlanarImage backdrop = (backdropFile == null) ? null
                : RawPictures.readBackdrop(backdropFile);

        return new GameData(gfx, textures, objects, levels, panel, sounds, backdrop, master);
    }

    GfxBank gfx() {
        return gfx;
    }

    TextureBank textures() {
        return textures;
    }

    ObjectBank objects() {
        return objects;
    }

    List<Level> levels() {
        return levels;
    }
}
