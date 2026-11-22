package fov.breathless.engine;

import fov.breathless.gld.gfx.LightingTable;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.object.ObjectDefinition;
import fov.breathless.gld.object.ObjectFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rasterisation des objets en sprites.
 *
 * <p>Portage de {@code DrawObjects} (Sorgenti/Objects.asm). Les objets sont transformes
 * dans le repere de la camera, tries du plus lointain au plus proche, puis dessines
 * colonne par colonne. Chaque colonne est decoupee contre les bornes que la passe des
 * murs a laissees a cette profondeur : un objet derriere un mur disparait naturellement,
 * sans tampon de profondeur.
 *
 * <p>Deux ecarts du moteur d'origine sont reproduits tels quels, parce qu'ils se voient :
 *
 * <ul>
 *   <li>l'echelle horizontale des sprites vaut {@code 128 * yRatio / 65536}, la meme que
 *       l'echelle verticale, alors que les murs sont traces avec une echelle horizontale
 *       de 256. Les sprites sont donc environ dix pour cent plus etroits que ne le
 *       voudrait une projection coherente avec le decor ;</li>
 *   <li>l'assombrissement par la distance est calcule sans le facteur deux applique aux
 *       murs : a distance egale, un sprite est plus clair que le mur derriere lui. Dans
 *       les sources, le doublement est present mais commente.</li>
 * </ul>
 */
public final class ObjectRenderer {

    /**
     * {@code ptmul} : exposant d'echelle horizontale des sprites. Quatre en pixels 1x1,
     * un de moins quand les colonnes sont doublees (on en calcule deux fois moins), un de
     * plus quand les lignes le sont (la distance, rapportee a un {@code yRatio} deux fois
     * plus petit, double).
     */
    private int scaleShift = 4;

    /** Regle l'echelle des sprites sur la grosseur des pixels, {@link ViewSize#PIXEL_1X1} et suivants. */
    public void setPixelType(int pixelType) {
        scaleShift = 4 - (pixelType & 1) + ((pixelType >> 1) & 1);
    }

    /** Les coordonnees laterales portent trois bits de precision supplementaires. */
    private static final int LATERAL_BITS = 3;

    private final World world;
    private final Viewport viewport;
    private int globalLight;
    private RayTable table;

    /** Reutilise d'une image a l'autre pour ne rien allouer pendant le rendu. */
    private final List<Projected> visible = new ArrayList<>();

    private static final class Projected {
        SceneObject object;
        Projectile projectile;
        int depth;
        int lateral;
        int distance;
        int rayDistance;
    }

    public ObjectRenderer(World world, Viewport viewport) {
        this.world = world;
        this.viewport = viewport;
    }

    public void setGlobalLight(int globalLight) {
        this.globalLight = globalLight;
    }

    /**
     * Dessine les objets et les tirs en vol.
     *
     * <p>Dans l'original un tir est un objet de la carte comme les autres : il passe par
     * le meme tri en profondeur, la meme lumiere, la meme animation. Un tir derriere un
     * ennemi est donc cache par lui.
     */
    public void render(Camera camera, RayTable table, Framebuffer target, List<SceneObject> objects,
                       List<Projectile> projectiles) {
        this.table = table;
        visible.clear();
        for (SceneObject object : objects) {
            // Un objet en attente de son declencheur est bel et bien la : le moteur
            // d'origine l'inscrit dans la carte des le chargement du niveau
            // ({@code InitObjects}, TMapMain.asm) et ne consulte {@code obj_inactive}
            // qu'au moment de faire bouger les ennemis. Il se voit, il arrete les tirs,
            // il encaisse : il ne fait que rester immobile jusqu'a son reveil.
            if (object.isCollected() || object.definition().frames().isEmpty()) {
                continue;
            }
            Projected projected = project(camera, object.x(), object.z());
            if (projected != null) {
                projected.object = object;
                visible.add(projected);
            }
        }
        for (Projectile projectile : projectiles) {
            if (projectile.definition().frames().isEmpty()) {
                continue;
            }
            Projected projected = project(camera, projectile.x(), projectile.z());
            if (projected != null) {
                projected.projectile = projectile;
                visible.add(projected);
            }
        }

        // Du plus lointain au plus proche : les sprites se recouvrent sans tampon de
        // profondeur, seul l'ordre de trace les departage.
        visible.sort(Comparator.comparingInt((Projected p) -> p.depth).reversed());

        for (Projected projected : visible) {
            draw(camera, table, target, projected);
        }
    }

    /** Compatibilite : les objets seuls. */
    public void render(Camera camera, RayTable table, Framebuffer target, List<SceneObject> objects) {
        render(camera, table, target, objects, List.of());
    }

    /**
     * Place un point dans le repere de la camera, ou {@code null} s'il n'est pas visible.
     *
     * <p>{@code DOloop2} : la coordonnee laterale est multipliee par 9/8 avant la
     * projection — sans quoi un objet serait place a 8/9 de son ecart au centre, et
     * glisserait sur le sol en tournant.
     */
    private Projected project(Camera camera, int x, int z) {
        // Seuls les objets dont un rayon a traverse la case sont visibles.
        if (!table.reached(Math.floorDiv(x, fov.breathless.gld.level.Level.BLOCK_SIZE),
                Math.floorDiv(z, fov.breathless.gld.level.Level.BLOCK_SIZE))) {
            return null;
        }
        int cos = Trig.cos(camera.heading()) >> 2;   // 2.14, comme dans l'original
        int sin = Trig.sin(camera.heading()) >> 2;
        int dx = x - (camera.x() >> 16);
        int dz = z - (camera.z() >> 16);

        int depth = (dx * cos + dz * sin) >> 14;
        if (depth <= 0) {
            return null;   // derriere la camera
        }
        int distance = (int) ((((long) depth) << 16) / viewport.yRatio());
        if (distance <= 0) {
            return null;
        }
        Projected projected = new Projected();
        projected.depth = depth;
        int lateral = (dz * cos - dx * sin) >> (14 - LATERAL_BITS);
        projected.lateral = ((lateral << 3) + lateral) >> 3;
        projected.distance = distance;
        projected.rayDistance = distance << 9;
        return projected;
    }

    private void draw(Camera camera, RayTable table, Framebuffer target, Projected projected) {
        SceneObject object = projected.object;
        Projectile projectile = projected.projectile;
        ObjectFrame frame;
        int baseY;
        if (projectile != null) {
            List<ObjectFrame> frames = projectile.definition().frames();
            frame = frames.get(Math.floorMod(projectile.animCounter(), frames.size()));
            baseY = projectile.y();
        } else {
            frame = selectFrame(camera, object);
            baseY = object.y();
        }
        if (frame == null) {
            return;
        }

        int distance = projected.distance;

        int screenWidth = (frame.width() << (scaleShift + LATERAL_BITS)) / distance;
        int screenHeight = (frame.height() << 7) / distance;
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        int left = ((projected.lateral - (frame.xOffset() << LATERAL_BITS)) << scaleShift) / distance
                + viewport.halfWidth();
        int bottom = (((camera.y() - baseY - frame.yOffset()) << 7) / distance)
                + viewport.halfHeight();
        int top = bottom - screenHeight + camera.lookHeight();

        int columnStep = (frame.width() << 16) / screenWidth;
        int rowStep = (frame.height() << 16) / screenHeight;

        // Un tir est eclaire comme tout objet : par la distance et par son bloc.
        int blockIndex = (projectile != null)
                ? blockAt(projectile.x(), projectile.z()) : blockOf(object);
        Block block = world.block(blockIndex);
        int light = lightLevel(projected.rayDistance, block, blockIndex);
        LightingTable shading = block.hasFog() ? world.fogLighting() : world.lighting();

        int firstColumn = Math.max(left, 0);
        int lastColumn = Math.min(left + screenWidth - 1, viewport.width() - 1);

        for (int x = firstColumn; x <= lastColumn; x++) {
            int source = ((x - left) * columnStep) >> 16;
            if (source < 0 || source >= frame.width()) {
                continue;
            }
            int hit = clipFor(table, x, projected.rayDistance);
            if (hit < 0) {
                continue;   // l'objet est derriere tout ce que cette colonne montre
            }
            drawColumn(target, frame, source, x, top, rowStep,
                    table.clipMin(x, hit), table.clipMax(x, hit), shading, light);
        }
    }

    /**
     * Transition de la colonne au-dela de laquelle l'objet se trouve, ou -1 s'il est
     * plus loin que tout ce que cette colonne montre.
     */
    private static int clipFor(RayTable table, int column, int rayDistance) {
        // On ne cherche que parmi les transitions dont la passe des murs a laisse des
        // bornes valables : au-dela, la colonne est bouchee et rien n'est visible.
        int count = table.visibleCount(column);
        for (int hit = 0; hit < count; hit++) {
            if (table.distance(column, hit) > rayDistance) {
                return hit;
            }
        }
        return -1;
    }

    private void drawColumn(Framebuffer target, ObjectFrame frame, int source, int x, int top,
                            int rowStep, int clipMin, int clipMax, LightingTable shading, int light) {
        for (ObjectFrame.Segment segment : frame.column(source)) {
            int segmentTop = top + ((segment.top() << 16) / rowStep);
            int segmentHeight = (segment.length() << 16) / rowStep;

            int first = Math.max(segmentTop, Math.max(clipMin + 1, 0));
            int last = Math.min(segmentTop + segmentHeight - 1, Math.min(clipMax - 1, viewport.height() - 1));

            for (int y = first; y <= last; y++) {
                int row = ((y - segmentTop) * rowStep) >> 16;
                if (row < 0 || row >= segment.length()) {
                    continue;
                }
                target.set(x, y, shading.shade(light, segment.pixel(row)));
            }
        }
    }

    /**
     * Choisit l'image a afficher.
     *
     * <p>Un ennemi a une animation <em>directionnelle</em> : seize poses pour chacune des
     * huit orientations, choisies selon l'angle entre son cap et celui du joueur. C'est ce
     * qui permet de le voir de dos quand il s'eloigne.
     */
    private ObjectFrame selectFrame(Camera camera, SceneObject object) {
        ObjectDefinition definition = object.definition();
        List<ObjectFrame> frames = definition.frames();

        int index;
        if (definition.isDirectional() && object.status() < 0) {
            // DOAnimDirezionale : un statut negatif — l'ennemi tire, tombe, ou gît —
            // designe l'image par son index absolu, sans passer par l'octant.
            index = object.animCounter();
        } else if (definition.isDirectional()) {
            // DOadnostop : un ennemi dessine bougera meme au-dela de vingt blocs.
            object.markInView();
            int objectOctant = ((object.heading() + 128) >> 8) & 7;
            int cameraOctant = ((camera.heading() + 128) >> 8) & 7;
            index = (((objectOctant + 6 - cameraOctant) & 7) << 4) + object.animCounter();
        } else if (definition.objectType() >= ObjectDefinition.TYPE_SHOT) {
            index = object.animCounter();
        } else {
            index = object.animCounter() % Math.max(1, frames.size());
        }

        if (index < 0 || index >= frames.size()) {
            return frames.isEmpty() ? null : frames.get(0);
        }
        return frames.get(index);
    }

    private int blockOf(SceneObject object) {
        return blockAt(object.x(), object.z());
    }

    private int blockAt(int x, int z) {
        int cellX = Math.floorDiv(x, fov.breathless.gld.level.Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(z, fov.breathless.gld.level.Level.BLOCK_SIZE);
        return Math.abs(world.cell(cellX, cellZ));
    }

    /**
     * Comme pour les murs, mais sans le facteur deux : l'original a laisse cette ligne
     * commentee, les sprites sont donc plus clairs que le decor a distance egale.
     */
    private int lightLevel(int rayDistance, Block block, int blockIndex) {
        long base = (((long) viewport.yRatio()) * ((long) rayDistance)) >>> 32;
        long level = base + world.lightOffset(blockIndex);
        if (!block.hasFog()) {
            level += globalLight;
        }
        if (level < 0) {
            return 0;
        }
        return (int) Math.min(level, LightingTable.LEVELS - 1);
    }
}
