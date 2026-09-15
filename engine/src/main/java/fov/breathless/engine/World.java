package fov.breathless.engine;

import fov.breathless.gld.gfx.GfxBank;
import fov.breathless.gld.gfx.LightingTable;
import fov.breathless.gld.gfx.Palette;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Edge;
import fov.breathless.gld.level.Level;
import fov.breathless.gld.object.ObjectBank;
import fov.breathless.gld.object.ObjectDefinition;
import fov.breathless.gld.texture.Texture;
import fov.breathless.gld.texture.TextureBank;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un niveau pret a etre rendu : carte, blocs, aretes et textures deja resolues.
 *
 * <p>Le fichier de niveau ne porte que des <em>noms</em> de textures ; les resoudre
 * une fois pour toutes evite une recherche par pixel.
 *
 * <p>Les hauteurs de sol et de plafond et la correction d'eclairage sont <em>mutables</em> :
 * ce sont elles que font varier les portes, les ascenseurs et les jeux de lumiere. Elles
 * vivent donc ici, dans des tableaux indexes par numero de bloc, plutot que dans les
 * {@link Block} issus du fichier, qui restent la description d'origine intacte.
 */
public final class World {

    private final Level level;
    private final Palette palette;
    private final Palette damagePalette;
    private final LightingTable lighting;
    private final LightingTable fogLighting;
    private final Texture[] texturesByIndex;
    private final List<SceneObject> objects;
    private final List<Projectile> projectiles = new ArrayList<>();
    private final Map<Integer, ObjectDefinition> explosions = new java.util.HashMap<>();
    private final short[] floorHeights;
    private final short[] ceilHeights;
    private final short[] lightOffsets;
    private final int[] textureFrames;
    private final boolean[] edgeToggled;
    private final Map<ObjectDefinition, int[]> objectFrames = new IdentityHashMap<>();
    private int textureCarry;
    private int objectCarry;
    private int objectTick;

    public World(Level level, TextureBank textures, GfxBank gfx) {
        this(level, textures, gfx, null);
    }

    public World(Level level, TextureBank textures, GfxBank gfx, ObjectBank objectBank) {
        this.level = level;
        this.palette = gfx.palette();
        // Le fichier graphique livre deux palettes : la normale, et celle du voile rouge.
        this.damagePalette = (gfx.palettes().size() > 1) ? gfx.palettes().get(1) : this.palette;
        this.lighting = gfx.lightingTable();
        this.fogLighting = gfx.fogLightingTable();

        // Index 0 signifie « aucune texture », d'ou le decalage d'une case.
        this.texturesByIndex = new Texture[level.textureNames().size() + 1];
        for (int i = 0; i < level.textureNames().size(); i++) {
            texturesByIndex[i + 1] = textures.get(level.textureNames().get(i));
        }

        this.objects = new ArrayList<>();
        if (objectBank != null) {
            this.objects.addAll(placeObjects(level, objectBank));
            for (ObjectDefinition definition : objectBank.objects().values()) {
                if (definition.objectType() == ObjectDefinition.TYPE_EXPLOSION) {
                    explosions.put(definition.param(1), definition);
                }
            }
        }

        this.textureFrames = new int[texturesByIndex.length];
        this.edgeToggled = new boolean[level.edges().size()];

        int blocks = level.blocks().size();
        this.floorHeights = new short[blocks];
        this.ceilHeights = new short[blocks];
        this.lightOffsets = new short[blocks];
        for (int i = 0; i < blocks; i++) {
            Block block = level.blocks().get(i);
            floorHeights[i] = (short) block.floorHeight();
            ceilHeights[i] = (short) block.ceilHeight();
            lightOffsets[i] = (short) block.lightOffset();
        }
    }

    /**
     * Cadence des textures animees : une image toutes les huit, soit 6,25 par seconde.
     * Le moteur d'origine divise par huit le nombre de cinquantiemes ecoules.
     */
    private static final int TEXTURE_PERIOD = 8;

    /**
     * Cadence des objets a animation simple : une image toutes les quatre, soit 12,5 par
     * seconde. L'original accumule {@code ticks << 14} et retient la partie entiere.
     */
    private static final int OBJECT_PERIOD = 4;

    /**
     * Avance les animations de textures et d'objets.
     *
     * <p>Les deux cadences sont independantes et plus lentes que l'affichage : une texture
     * de lave ne clignote pas a cinquante images par seconde.
     *
     * <p>L'animation d'un objet est portee par son <em>type</em>, pas par ses instances :
     * tous les bidons d'acide d'un niveau bouillonnent a l'unisson, comme dans l'original.
     */
    public void animate(int ticks) {
        textureCarry += ticks;
        int textureSteps = textureCarry / TEXTURE_PERIOD;
        textureCarry %= TEXTURE_PERIOD;
        if (textureSteps > 0) {
            for (int i = 0; i < texturesByIndex.length; i++) {
                Texture texture = texturesByIndex[i];
                if (texture != null && texture.isAnimated()) {
                    textureFrames[i] = (textureFrames[i] + textureSteps) % texture.frameCount();
                }
            }
        }

        objectCarry += ticks;
        int objectSteps = objectCarry / OBJECT_PERIOD;
        objectCarry %= OBJECT_PERIOD;
        if (objectSteps == 0) {
            return;
        }
        // Deux passes : le compteur appartient au type, il ne doit avancer qu'une fois,
        // quel que soit le nombre d'exemplaires poses dans le niveau.
        for (SceneObject object : objects) {
            ObjectDefinition definition = object.definition();
            if (!animatesOnItsOwn(definition)) {
                continue;
            }
            int[] counter = objectFrames.computeIfAbsent(definition, d -> new int[]{0, -1});
            if (counter[1] != objectTick) {
                counter[1] = objectTick;
                counter[0] = (counter[0] + objectSteps) % definition.frames().size();
            }
        }
        for (SceneObject object : objects) {
            int[] counter = objectFrames.get(object.definition());
            if (counter != null) {
                object.setAnimCounter(counter[0]);
            }
        }
        objectTick++;
    }

    /**
     * Seule l'animation « simple » tourne toute seule : une animation directionnelle suit
     * la pose de l'ennemi, qui releve de son comportement et non de l'horloge.
     */
    private static boolean animatesOnItsOwn(ObjectDefinition definition) {
        return definition.animType() > ObjectDefinition.ANIM_NONE
                && definition.objectType() < ObjectDefinition.TYPE_SHOT
                && definition.frames().size() >= 2;
    }

    /**
     * Bascule l'apparence d'une arete portant un interrupteur.
     *
     * <p>Une texture d'interrupteur embarque sa propre variante actionnee ; l'arete
     * l'adopte definitivement, comme dans le moteur d'origine qui y remplace le pointeur.
     */
    public void toggleEdge(int edgeIndex) {
        if (edgeIndex >= 0 && edgeIndex < edgeToggled.length) {
            edgeToggled[edgeIndex] = true;
        }
    }

    /**
     * Texture d'une arete, variante actionnee comprise.
     *
     * @param edgeIndex    arete concernee
     * @param textureIndex index de texture porte par cette arete
     */
    public Texture edgeTexture(int edgeIndex, int textureIndex) {
        Texture texture = texture(textureIndex);
        if (texture == null || edgeIndex < 0 || edgeIndex >= edgeToggled.length
                || !edgeToggled[edgeIndex] || !texture.isSwitch()) {
            return texture;
        }
        return texture.switched();
    }

    /** Image courante d'une texture, 0 si elle est fixe. */
    public int textureFrame(int textureIndex) {
        int i = Math.abs(textureIndex);
        return (i <= 0 || i >= textureFrames.length) ? 0 : textureFrames[i];
    }

    /** Hauteur courante du sol d'un bloc, effets compris. */
    public int floorHeight(int blockIndex) {
        return floorHeights[blockIndex];
    }

    /** Hauteur courante du plafond d'un bloc, effets compris. */
    public int ceilHeight(int blockIndex) {
        return ceilHeights[blockIndex];
    }

    /** Correction d'eclairage courante d'un bloc, effets compris. */
    public int lightOffset(int blockIndex) {
        return lightOffsets[blockIndex];
    }

    /** Deplace le sol d'un bloc. Utilise par les ascenseurs et les sols mobiles. */
    public void moveFloor(int blockIndex, int delta) {
        floorHeights[blockIndex] += (short) delta;
    }

    /** Deplace le plafond d'un bloc. Utilise par les portes. */
    public void moveCeiling(int blockIndex, int delta) {
        ceilHeights[blockIndex] += (short) delta;
    }

    /** Fixe l'eclairage d'un bloc. Utilise par les lumieres clignotantes. */
    public void setLight(int blockIndex, int value) {
        lightOffsets[blockIndex] = (short) Math.max(-128, Math.min(127, value));
    }

    /** Modifie l'eclairage d'un bloc, en restant dans les bornes d'un octet signe. */
    public void changeLight(int blockIndex, int delta) {
        int value = lightOffsets[blockIndex] + delta;
        lightOffsets[blockIndex] = (short) Math.max(-128, Math.min(127, value));
    }

    /**
     * Instancie les objets poses dans le niveau.
     *
     * <p>Un objet est ancre sur le sol du bloc qu'il occupe, comme dans le moteur
     * d'origine, qui recalcule cette altitude a chaque image pour les types autres que
     * projectiles et explosions.
     */
    private static List<SceneObject> placeObjects(Level level, ObjectBank bank) {
        List<SceneObject> placed = new ArrayList<>();
        for (fov.breathless.gld.level.MapObject entry : level.objects()) {
            ObjectDefinition definition = bank.get(level.objectName(entry.objectIndex()));
            if (definition == null) {
                continue;
            }
            int cellX = Math.floorDiv(entry.x(), Level.BLOCK_SIZE);
            int cellZ = Math.floorDiv(entry.z(), Level.BLOCK_SIZE);
            Block block = level.blockAt(cellX, cellZ);
            int floor = (block == null) ? 0 : block.floorHeight();

            SceneObject object = new SceneObject(definition, entry.x(), entry.z(), floor,
                    entry.heading(), entry.trigger());
            if (definition.objectType() == ObjectDefinition.TYPE_ENEMY) {
                object.setStrength(definition.param(3));
            }
            placed.add(object);
        }
        return placed;
    }

    /** Objets presents dans le niveau, poses au chargement ou apparus depuis. */
    public List<SceneObject> objects() {
        return objects;
    }

    /** Tirs en vol. */
    public List<Projectile> projectiles() {
        return projectiles;
    }

    /** Definition d'explosion par son index, ou {@code null}. */
    public ObjectDefinition explosion(int index) {
        return explosions.get(index);
    }

    public Level level() {
        return level;
    }

    public Palette palette() {
        return palette;
    }

    /** Palette a employer selon que le joueur vient d'encaisser ou non. */
    public Palette palette(boolean damaged) {
        return damaged ? damagePalette : palette;
    }

    public LightingTable lighting() {
        return lighting;
    }

    public LightingTable fogLighting() {
        return fogLighting;
    }

    /** Texture designee par un index de bloc ou d'arete, ou {@code null} si aucune. */
    public Texture texture(int index) {
        int i = Math.abs(index);
        return (i <= 0 || i >= texturesByIndex.length) ? null : texturesByIndex[i];
    }

    /**
     * Code brut de la case : 0 pour le vide, positif pour un bloc traversable,
     * negatif pour un mur plein. Renvoie -1 hors de la carte, ce qui arrete le rayon.
     */
    public int cell(int x, int z) {
        if (x < 0 || x >= Level.MAP_SIZE || z < 0 || z >= Level.MAP_SIZE) {
            return -1;
        }
        return level.rawCell(x, z);
    }

    /** Bloc d'index donne. */
    public Block block(int index) {
        return level.blocks().get(index);
    }

    /** Arete d'index donne. */
    public Edge edge(int index) {
        return level.edges().get(index);
    }
}
