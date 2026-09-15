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
 * dependant que de la colonne visee et du cap du joueur, sa ligne que de la ligne visee.
 * Il ne tourne donc pas avec le regard vertical et ne s'eloigne jamais.
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
            int denominator = y - horizon;
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
                if (Surfaces.kindOf(value) == Surfaces.SKY) {
                    drawSky(camera, target, y, x, end, value);
                } else if (denominator != 0) {
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
        Texture sky = world.texture(block.ceilTextureIndex());
        if (sky == null) {
            return;
        }
        int rotation = camera.heading() / 8;   // 2048 pas de cap pour 256 pixels de ciel
        int row = ((y * skyYRatio) >> 16) % sky.height();
        byte[] frame = sky.frame(0);

        for (int x = from; x <= to; x++) {
            int column = (((x * skyXRatio) >> 12) + rotation) % sky.width();
            if (column < 0) {
                column += sky.width();
            }
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
