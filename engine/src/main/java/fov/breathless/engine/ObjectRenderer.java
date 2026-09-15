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
     * Exposant d'echelle horizontale des sprites, pour des pixels 1x1. Le moteur le
     * reduit d'un cran par doublement de pixel.
     */
    private static final int SCALE_SHIFT = 4;

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

    public void render(Camera camera, RayTable table, Framebuffer target, List<SceneObject> objects) {
        this.table = table;
        int cos = Trig.cos(camera.heading()) >> 2;   // 2.14, comme dans l'original
        int sin = Trig.sin(camera.heading()) >> 2;
        int playerX = camera.x() >> 16;
        int playerZ = camera.z() >> 16;

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
            // Seuls les objets dont un rayon a traverse la case sont visibles.
            if (!table.reached(Math.floorDiv(object.x(), fov.breathless.gld.level.Level.BLOCK_SIZE),
                    Math.floorDiv(object.z(), fov.breathless.gld.level.Level.BLOCK_SIZE))) {
                continue;
            }
            int dx = object.x() - playerX;
            int dz = object.z() - playerZ;

            int depth = (dx * cos + dz * sin) >> 14;
            if (depth <= 0) {
                continue;   // derriere la camera
            }
            int distance = (int) ((((long) depth) << 16) / viewport.yRatio());
            if (distance <= 0) {
                continue;
            }

            Projected projected = new Projected();
            projected.object = object;
            projected.depth = depth;
            projected.lateral = (dz * cos - dx * sin) >> (14 - LATERAL_BITS);
            projected.distance = distance;
            projected.rayDistance = distance << 9;
            visible.add(projected);
        }

        // Du plus lointain au plus proche : les sprites se recouvrent sans tampon de
        // profondeur, seul l'ordre de trace les departage.
        visible.sort(Comparator.comparingInt((Projected p) -> p.depth).reversed());

        for (Projected projected : visible) {
            draw(camera, table, target, projected);
        }
    }

    /**
     * Dessine les tirs en vol.
     *
     * <p>Ils ne figurent pas dans la liste des objets du niveau : ils vont et viennent
     * trop vite pour valoir une instance permanente. Le trace est le meme, seule la source
     * des coordonnees change.
     */
    public void renderProjectiles(Camera camera, RayTable table, Framebuffer target,
                                  List<Projectile> projectiles) {
        if (projectiles.isEmpty()) {
            return;
        }
        this.table = table;
        int cos = Trig.cos(camera.heading()) >> 2;
        int sin = Trig.sin(camera.heading()) >> 2;
        int playerX = camera.x() >> 16;
        int playerZ = camera.z() >> 16;

        for (Projectile projectile : projectiles) {
            if (projectile.definition().frames().isEmpty()) {
                continue;
            }
            if (!table.reached(Math.floorDiv(projectile.x(), fov.breathless.gld.level.Level.BLOCK_SIZE),
                    Math.floorDiv(projectile.z(), fov.breathless.gld.level.Level.BLOCK_SIZE))) {
                continue;
            }
            int dx = projectile.x() - playerX;
            int dz = projectile.z() - playerZ;

            int depth = (dx * cos + dz * sin) >> 14;
            if (depth <= 0) {
                continue;
            }
            int distance = (int) ((((long) depth) << 16) / viewport.yRatio());
            if (distance <= 0) {
                continue;
            }

            Projected projected = new Projected();
            projected.object = null;
            projected.depth = depth;
            projected.lateral = (dz * cos - dx * sin) >> (14 - LATERAL_BITS);
            projected.distance = distance;
            projected.rayDistance = distance << 9;
            drawProjectile(camera, table, target, projected, projectile);
        }
    }

    private void drawProjectile(Camera camera, RayTable table, Framebuffer target,
                                Projected projected, Projectile projectile) {
        ObjectFrame frame = projectile.definition().frames().get(0);
        int distance = projected.distance;

        int screenWidth = (frame.width() << (SCALE_SHIFT + LATERAL_BITS)) / distance;
        int screenHeight = (frame.height() << 7) / distance;
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        int left = ((projected.lateral - (frame.xOffset() << LATERAL_BITS)) << SCALE_SHIFT) / distance
                + viewport.halfWidth();
        int bottom = (((camera.y() - projectile.y() - frame.yOffset()) << 7) / distance)
                + viewport.halfHeight();
        int top = bottom - screenHeight + camera.lookHeight();

        int columnStep = (frame.width() << 16) / screenWidth;
        int rowStep = (frame.height() << 16) / screenHeight;

        // Un tir brille : il est trace en pleine lumiere, sans assombrissement.
        LightingTable shading = world.lighting();

        int firstColumn = Math.max(left, 0);
        int lastColumn = Math.min(left + screenWidth - 1, viewport.width() - 1);
        for (int x = firstColumn; x <= lastColumn; x++) {
            int source = ((x - left) * columnStep) >> 16;
            if (source < 0 || source >= frame.width()) {
                continue;
            }
            int hit = clipFor(table, x, projected.rayDistance);
            if (hit < 0) {
                continue;
            }
            drawColumn(target, frame, source, x, top, rowStep,
                    table.clipMin(x, hit), table.clipMax(x, hit), shading, 0);
        }
    }

    private void draw(Camera camera, RayTable table, Framebuffer target, Projected projected) {
        SceneObject object = projected.object;
        ObjectFrame frame = selectFrame(camera, object);
        if (frame == null) {
            return;
        }

        int distance = projected.distance;

        int screenWidth = (frame.width() << (SCALE_SHIFT + LATERAL_BITS)) / distance;
        int screenHeight = (frame.height() << 7) / distance;
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        int left = ((projected.lateral - (frame.xOffset() << LATERAL_BITS)) << SCALE_SHIFT) / distance
                + viewport.halfWidth();
        int bottom = (((camera.y() - object.y() - frame.yOffset()) << 7) / distance)
                + viewport.halfHeight();
        int top = bottom - screenHeight + camera.lookHeight();

        int columnStep = (frame.width() << 16) / screenWidth;
        int rowStep = (frame.height() << 16) / screenHeight;

        int blockIndex = blockOf(object);
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
        int cellX = Math.floorDiv(object.x(), fov.breathless.gld.level.Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(object.z(), fov.breathless.gld.level.Level.BLOCK_SIZE);
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
