package fov.breathless.engine;

import fov.breathless.gld.gfx.LightingTable;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.texture.Texture;

/**
 * Rasterisation des sols, des plafonds et du ciel.
 *
 * <p>Portage de {@code MakeTextCeiling} et de son pendant pour le sol
 * (Sorgenti/DrawScreen.asm). Le principe tient en une remarque : sur une ligne d'ecran
 * donnee, tous les points d'une surface horizontale sont a la meme profondeur. Le
 * placage de texture y est donc affine, et se reduit a un pas constant le long de la
 * ligne, la ou un mur demande un pas par colonne.
 *
 * <p>La profondeur d'une ligne se deduit de la meme relation que la projection des murs,
 * renversee : {@code T = (hauteur - oeil) / (y - horizon)}.
 *
 * <p>Le ciel echappe a tout cela : il est trace en coordonnees ecran, sa colonne ne
 * dependant que de la colonne visee et du cap du joueur, sa ligne que de la ligne visee
 * decalee du regard vertical. Il ne s'eloigne jamais.
 */
public final class FlatRenderer {

    /** Largeur d'une brosse de ciel, {@code SKY_BRUSH_WIDTH} de TMap.i. */
    private static final int SKY_WIDTH = 256;

    /** Hauteur de reference du ciel, {@code SKY_STANDARD_HEIGHT} de TMap.i. */
    private static final int SKY_HEIGHT = 200;

    private final World world;
    private final Viewport viewport;
    private final int skyXRatio;
    private final int skyYRatio;
    private int globalLight;

    public FlatRenderer(World world, Viewport viewport) {
        this.world = world;
        this.viewport = viewport;
        this.skyXRatio = (SKY_WIDTH << 12) / viewport.width();
        this.skyYRatio = (SKY_HEIGHT << 16) / viewport.height();
    }

    public void setGlobalLight(int globalLight) {
        this.globalLight = globalLight;
    }

    public void render(Camera camera, RayTable table, Surfaces surfaces, Framebuffer target) {
        int horizon = viewport.halfHeight() + camera.lookHeight();

        for (int y = 0; y < viewport.height(); y++) {
            // Les plafonds comptent leurs lignes depuis le haut : la ligne 0 est a
            // -horizon. Les sols les comptent depuis le bas, la derniere ligne etant a
            // h/2 - LookHeight : une ligne de plus. MakeTextFloor ne trace donc pas la
            // ligne de l'horizon, et toute la profondeur des sols est decalee d'autant.
            int ceilingDenominator = y - horizon;
            int floorDenominator = y - horizon + 1;
            int x = 0;
            while (x < viewport.width()) {
                int value = surfaces.at(x, y);
                if (value == Surfaces.NONE) {
                    x++;
                    continue;
                }
                int end = x;
                while (end + 1 < viewport.width() && surfaces.at(end + 1, y) == value) {
                    end++;
                }
                int kind = Surfaces.kindOf(value);
                int denominator = (kind == Surfaces.FLOOR) ? floorDenominator : ceilingDenominator;
                if (kind == Surfaces.SKY) {
                    drawSky(camera, target, y, x, end, value);
                } else if (kind == Surfaces.FLOOR ? denominator > 0 : denominator < 0) {
                    drawSpan(camera, table, target, y, x, end, value, denominator);
                }
                x = end + 1;
            }
        }
    }

    /**
     * Trace un segment de sol ou de plafond.
     *
     * @param denominator ecart en lignes entre la ligne tracee et l'horizon ; c'est lui
     *                    qui porte la profondeur
     */
    private void drawSpan(Camera camera, RayTable table, Framebuffer target,
                          int y, int from, int to, int value, int denominator) {
        int blockIndex = Surfaces.blockOf(value);
        Block block = world.block(blockIndex);
        boolean ceiling = Surfaces.kindOf(value) == Surfaces.CEILING;

        Texture texture = ceiling
                ? world.texture(block.ceilTextureIndex())
                : world.texture(block.floorTexture());
        if (texture == null) {
            return;
        }

        int height = ceiling ? world.ceilHeight(blockIndex) : world.floorHeight(blockIndex);

        // Profondeur de la ligne. Le moteur d'origine calcule cette quantite negative
        // et la propage telle quelle jusqu'aux coordonnees de texture ; le motif etant
        // repete, cela ne change que l'orientation d'une dalle.
        long depth = ((((long) height) << 16) - (((long) camera.y()) << 16)) / denominator;
        int t = (int) depth;

        int u = Fixed.mul(t, table.dirX(from)) - camera.x();
        int v = Fixed.mul(t, table.dirZ(from)) - camera.z();
        int du = Fixed.mul(t, table.dirX(from + 1) - table.dirX(from));
        int dv = Fixed.mul(t, table.dirZ(from + 1) - table.dirZ(from));

        int light = lightLevel(-t, block, blockIndex);
        LightingTable shading = block.hasFog() ? world.fogLighting() : world.lighting();

        int rowMask = texture.height() - 1;
        int columnMask = texture.width() - 1;
        int textureIndex = ceiling ? block.ceilTextureIndex() : block.floorTexture();
        byte[] frame = texture.frame(
                Math.min(world.textureFrame(textureIndex), texture.frameCount() - 1));
        int textureHeight = texture.height();

        for (int x = from; x <= to; x++) {
            int row = (u >> 16) & rowMask;
            int column = (v >> 16) & columnMask;
            int index = frame[column * textureHeight + row] & 0xFF;
            target.set(x, y, shading.shade(light, index));
            u += du;
            v += dv;
        }
    }

    /**
     * Trace un segment de ciel.
     *
     * <p>La colonne de brosse avance avec la colonne d'ecran et se decale avec le cap :
     * un tour complet du joueur fait defiler toute la largeur de la brosse.
     */
    private void drawSky(Camera camera, Framebuffer target, int y, int from, int to, int value) {
        Block block = world.block(Surfaces.blockOf(value));
        int textureIndex = block.ceilTextureIndex();
        Texture sky = world.texture(textureIndex);
        if (sky == null) {
            return;
        }
        // CSkyRotation = (cap >> 1) << 8, et SkyRotation = cap >> 1 : un demi-pixel de
        // brosse par pas de cap, soit quatre tours de brosse par tour du joueur.
        int rotation = camera.heading() >> 1;
        // La brosse courante, tx_Brush : un ciel anime l'est aussi a l'ecran.
        byte[] frame = sky.frame(Math.min(world.textureFrame(textureIndex), sky.frameCount() - 1));

        // Le ciel suit le regard vertical : les lignes au-dessus du haut de la brosse
        // prennent une couleur unie, le premier texel.
        int line = y - camera.lookHeight();
        if (line <= 0) {
            int color = frame[0] & 0xFF;
            for (int x = from; x <= to; x++) {
                target.set(x, y, color);
            }
            return;
        }
        int row = ((line * skyYRatio) >> 16) % sky.height();

        for (int x = from; x <= to; x++) {
            int column = (((x * skyXRatio) + (rotation << 12)) >> 12) & (SKY_WIDTH - 1);
            column %= sky.width();
            target.set(x, y, frame[column * sky.height() + row] & 0xFF);
        }
    }

    /** Meme calcul que pour les murs : base liee a la profondeur, plus les corrections. */
    private int lightLevel(int distance, Block block, int blockIndex) {
        long base = (((long) viewport.yRatio()) * (((long) distance) << 1)) >>> 32;
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
