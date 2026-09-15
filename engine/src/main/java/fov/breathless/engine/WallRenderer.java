package fov.breathless.engine;

import fov.breathless.gld.gfx.LightingTable;
import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Edge;
import fov.breathless.gld.texture.Texture;

/**
 * Rasterisation des murs a partir de la table de rayons.
 *
 * <p>Portage de {@code MakeFrame} (Sorgenti/DrawScreen.asm). Pour chaque colonne, on
 * parcourt les transitions de bloc du plus proche au plus lointain, en maintenant deux
 * bornes de decoupe qui se resserrent : tout ce qui est deja peint plus pres masque ce
 * qui vient derriere.
 *
 * <p>Chaque transition donne jusqu'a trois pans :
 * <ul>
 *   <li>une <em>texture haute</em> si le plafond descend ;</li>
 *   <li>une <em>texture basse</em> si le sol monte ;</li>
 *   <li>une <em>texture principale</em> si l'arete est un mur plein, ce qui termine
 *       la colonne.</li>
 * </ul>
 *
 * <p>Les pixels que cette passe ne peint pas montrent le sol ou le plafond d'un bloc.
 * Elle les consigne dans une {@link Surfaces}, que {@link FlatRenderer} texture ensuite
 * en perspective. Le moteur d'origine procede de meme avec sa table {@code OTable}.
 */
public final class WallRenderer {

    /** Projection maximale, pour eviter un debordement sur une distance minuscule. */
    private static final int MAX_PROJECTION = 1 << 20;

    private final World world;
    private final Viewport viewport;
    private int globalLight;

    public WallRenderer(World world, Viewport viewport) {
        this.world = world;
        this.viewport = viewport;
    }

    /** Correction d'eclairage appliquee a tout le niveau. Equivalent de {@code GlobalLight}. */
    public void setGlobalLight(int globalLight) {
        this.globalLight = globalLight;
    }

    public void render(Camera camera, RayTable table, Framebuffer target, Surfaces surfaces) {
        target.clear(0);
        surfaces.clear();
        int playerBlock = Math.abs(world.cell(camera.cellX(), camera.cellZ()));
        for (int column = 0; column < viewport.width(); column++) {
            renderColumn(camera, table, target, surfaces, column, playerBlock);
        }
    }

    private void renderColumn(Camera camera, RayTable table, Framebuffer target, Surfaces surfaces,
                              int column, int playerBlock) {
        int clipMin = -1;
        int clipMax = viewport.height();
        int previousBlock = playerBlock;
        int processed = 0;

        for (int hit = 0; hit < table.count(column) && clipMin + 1 < clipMax; hit++) {
            int distance = table.distance(column, hit);
            if (distance == 0) {
                continue;   // le moteur d'origine ecarte aussi la division par zero
            }

            // Consigne les bornes en vigueur avant ce pan : le rendu des sprites en a
            // besoin pour decouper un objet situe a cette profondeur.
            table.setClip(column, hit, clipMin, clipMax);
            processed = hit + 1;

            int currentBlock = table.block(column, hit);
            Block current = world.block(currentBlock);
            Block previous = world.block(previousBlock);
            // La table donne le numero de l'arete traversee (1 a 4) ; le bloc traverse
            // dit quelle arete du niveau cela designe.
            int edgeIndex = current.edge(table.edge(column, hit));
            Edge edge = world.edge(edgeIndex);
            int brushColumn = table.brushColumn(column, hit);
            int light = lightLevel(distance, previous, previousBlock);
            LightingTable shading = previous.hasFog() ? world.fogLighting() : world.lighting();

            int ceilCurrent = world.ceilHeight(currentBlock);
            int ceilPrevious = world.ceilHeight(previousBlock);
            int floorCurrent = world.floorHeight(currentBlock);
            int floorPrevious = world.floorHeight(previousBlock);

            int yCeilCurrent = project(camera, ceilCurrent, distance);
            int yCeilPrevious = project(camera, ceilPrevious, distance);
            int yFloorCurrent = project(camera, floorCurrent, distance);
            int yFloorPrevious = project(camera, floorPrevious, distance);

            // Plafond du bloc precedent, au-dessus du pan de mur haut.
            int ceilingBottom = Math.min(yCeilPrevious, clipMax);
            if (ceilingBottom > clipMin + 1) {
                surfaces.mark(column, clipMin + 1, ceilingBottom - 1, previousBlock,
                        previous.hasSkyCeiling() ? Surfaces.SKY : Surfaces.CEILING);
            }
            clipMin = Math.max(clipMin, ceilingBottom - 1);

            // Texture haute : le plafond descend en passant dans le bloc suivant.
            if (yCeilCurrent > yCeilPrevious) {
                drawSpan(target, column, yCeilPrevious, yCeilCurrent - yCeilPrevious,
                        world.edgeTexture(edgeIndex, edge.upTexture()),
                        world.textureFrame(edge.upTexture()),
                        ceilPrevious - ceilCurrent,
                        brushColumn, edge.isUpperUnpegged(), shading, light, clipMin, clipMax);
                clipMin = Math.max(clipMin, Math.min(yCeilCurrent, clipMax) - 1);
            }

            // Sol du bloc precedent, sous le pan de mur bas.
            int floorTop = Math.max(yFloorPrevious, clipMin + 1);
            if (floorTop < clipMax) {
                surfaces.mark(column, floorTop, clipMax - 1, previousBlock, Surfaces.FLOOR);
            }
            clipMax = Math.min(clipMax, floorTop);

            // Texture basse : le sol monte en passant dans le bloc suivant.
            if (yFloorPrevious > yFloorCurrent) {
                drawSpan(target, column, yFloorCurrent, yFloorPrevious - yFloorCurrent,
                        world.edgeTexture(edgeIndex, edge.lowTexture()),
                        world.textureFrame(edge.lowTexture()),
                        floorCurrent - floorPrevious,
                        brushColumn, edge.isLowerUnpegged(), shading, light, clipMin, clipMax);
                clipMax = Math.min(clipMax, Math.max(yFloorCurrent, clipMin + 1));
            }

            // Texture principale : un mur plein ferme la colonne.
            Texture normal = world.edgeTexture(edgeIndex, edge.normTexture());
            if (normal != null) {
                drawSpan(target, column, yCeilCurrent, yFloorCurrent - yCeilCurrent, normal,
                        world.textureFrame(edge.normTexture()), ceilCurrent - floorCurrent,
                        brushColumn, false, shading, light, clipMin, clipMax);
                table.setVisibleCount(column, processed);
                return;
            }

            previousBlock = currentBlock;
        }
        table.setVisibleCount(column, processed);
    }

    /**
     * Ordonnee ecran d'une hauteur monde.
     *
     * <p>{@code halfHeight + lookHeight + ((oeil - hauteur) << 16) / distance}, ou la
     * distance est le parametre accumule par la DDA. La constante de projection est donc
     * portee par l'echelle de ce parametre, et non ecrite en dur.
     */
    private int project(Camera camera, int height, int distance) {
        long numerator = ((long) (camera.y() - height)) << 16;
        long y = numerator / distance;
        if (y > MAX_PROJECTION) {
            y = MAX_PROJECTION;
        } else if (y < -MAX_PROJECTION) {
            y = -MAX_PROJECTION;
        }
        return (int) y + viewport.halfHeight() + camera.lookHeight();
    }

    /**
     * Niveau d'assombrissement d'un pan : une base proportionnelle a la distance, plus
     * la correction du bloc, plus la correction globale, borne sur la table.
     */
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

    /**
     * Trace un pan de mur vertical.
     *
     * @param top        ordonnee ecran du sommet du pan, avant decoupe
     * @param pixels     hauteur du pan en lignes, avant decoupe
     * @param wallHeight hauteur du pan en unites monde
     * @param unpegged   aligner la texture sur le bas du pan plutot que sur le haut
     */
    private void drawSpan(Framebuffer target, int column, int top, int pixels, Texture texture,
                          int frame, int wallHeight, int brushColumn, boolean unpegged,
                          LightingTable shading, int light, int clipMin, int clipMax) {
        if (texture == null || pixels <= 0 || wallHeight <= 0) {
            return;
        }

        int brushHeight = texture.height();
        // La texture est posee a raison d'une ligne de brosse par unite monde ; un pan
        // plus haut que la brosse la repete verticalement.
        int step = (int) ((((long) wallHeight) << 16) / pixels);
        int accumulator = unpegged ? Math.max(0, brushHeight - wallHeight) << 16 : 0;

        int first = Math.max(top, clipMin + 1);
        int last = Math.min(top + pixels - 1, clipMax - 1);
        if (first > last) {
            return;
        }
        accumulator += (first - top) * step;

        int mask = brushHeight - 1;
        byte[] pixelsOfFrame = texture.frame(Math.min(frame, texture.frameCount() - 1));
        int columnBase = brushColumn * brushHeight;

        for (int y = first; y <= last; y++) {
            int row = (accumulator >> 16) & mask;
            int index = pixelsOfFrame[columnBase + row] & 0xFF;
            target.set(column, y, shading.shade(light, index));
            accumulator += step;
        }
    }
}
