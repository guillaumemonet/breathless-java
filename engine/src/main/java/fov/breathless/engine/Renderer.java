package fov.breathless.engine;

/**
 * Enchaine les trois passes d'une image et detient les tampons qui les relient.
 *
 * <p>L'ordre reprend celui du moteur d'origine : les rayons, les murs, les surfaces
 * horizontales, puis les objets. Les murs passent en premier parce que ce sont eux qui
 * determinent, pour chaque pixel qu'ils laissent libre, quel sol ou quel plafond s'y
 * trouve, et parce qu'ils laissent au passage les bornes de decoupe dont les sprites ont
 * besoin.
 *
 * <p>Un {@code Renderer} alloue ses tampons une fois pour toutes et les reutilise a
 * chaque image : rien n'est alloue pendant le rendu.
 */
public final class Renderer {

    private final Viewport viewport;
    private final RayTable table;
    private final Surfaces surfaces;
    private final WallRenderer walls;
    private final FlatRenderer flats;
    private final ObjectRenderer objects;
    private final Framebuffer frame;
    private final World world;

    public Renderer(World world, Viewport viewport) {
        this.world = world;
        this.viewport = viewport;
        this.table = new RayTable(viewport.width());
        this.surfaces = new Surfaces(viewport.width(), viewport.height());
        this.walls = new WallRenderer(world, viewport);
        this.flats = new FlatRenderer(world, viewport);
        this.objects = new ObjectRenderer(world, viewport);
        this.frame = new Framebuffer(viewport.width(), viewport.height());
    }

    /** Correction d'eclairage appliquee a tout le niveau. */
    public void setGlobalLight(int globalLight) {
        walls.setGlobalLight(globalLight);
        flats.setGlobalLight(globalLight);
        objects.setGlobalLight(globalLight);
    }

    /** Calcule une image et renvoie le tampon, reutilise d'un appel a l'autre. */
    public Framebuffer render(Camera camera) {
        Raycaster.cast(world, camera, viewport, table);
        walls.render(camera, table, frame, surfaces);
        flats.render(camera, table, surfaces, frame);
        objects.render(camera, table, frame, world.objects());
        objects.renderProjectiles(camera, table, frame, world.projectiles());
        return frame;
    }

    /** Table de rayons de la derniere image, pour inspection ou diagnostic. */
    public RayTable rayTable() {
        return table;
    }

    public World world() {
        return world;
    }

    public Viewport viewport() {
        return viewport;
    }
}
