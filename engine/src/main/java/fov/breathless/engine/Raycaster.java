package fov.breathless.engine;

import fov.breathless.gld.level.Level;

/**
 * Lancer de rayons, un rayon par colonne d'ecran.
 *
 * <p>Portage de {@code Render3d} et {@code RayCasting} (Sorgenti/3d.asm). Le principe
 * est une DDA classique en 16.16 : on avance de frontiere de case en frontiere de case,
 * en prenant a chaque fois l'axe dont la prochaine intersection est la plus proche.
 *
 * <p>Deux points meritent attention.
 *
 * <p><strong>Distance sans effet fisheye.</strong> Le rayon de la colonne {@code c} vaut
 * {@code direction * 128 + perpendiculaire * X}, ou {@code X} balaye [-80, +80[. La
 * composante le long du regard est donc constante ; le parametre {@code t} accumule par
 * la DDA est proportionnel a la distance perpendiculaire, et non a la distance
 * euclidienne. La correction du fisheye est ainsi gratuite.
 *
 * <p><strong>Seuls les changements sont enregistres.</strong> Deux cases voisines
 * partageant le meme bloc ne produisent aucune entree : le moteur ne s'interesse qu'aux
 * transitions, qui seules donnent lieu a un pan de mur.
 *
 * <p>Le moteur d'origine decline huit variantes de la boucle selon le quadrant et l'axe
 * dominant, pour economiser des registres. Cette version n'en ecrit qu'une, qui choisit
 * l'axe a chaque iteration ; le parcours produit est le meme.
 */
public final class Raycaster {

    /** Masque isolant la case dans une coordonnee 16.16, avec bouclage sur la carte. */
    private static final int GRID_MASK = ((Level.MAP_SIZE * Level.BLOCK_SIZE) - Level.BLOCK_SIZE) << 16;

    /** Masque ramenant une coordonnee 16.16 dans une case de 64 unites. */
    private static final int BRUSH_MASK = ((Level.BLOCK_SIZE - 1) << 16) | 0xFFFF;

    private static final int BLOCK = Level.BLOCK_SIZE;

    /** Aretes, telles que numerotees par le format de niveau. */
    private static final int EDGE_EAST = 1;
    private static final int EDGE_SOUTH = 2;
    private static final int EDGE_WEST = 3;
    private static final int EDGE_NORTH = 4;

    private Raycaster() {
    }

    /** Lance un rayon par colonne et remplit la table. */
    public static void cast(World world, Camera camera, Viewport viewport, RayTable table) {
        int viewDirX = camera.viewDirX();
        int viewDirZ = camera.viewDirZ();

        table.forgetReachedCells();
        table.reach(camera.cellX(), camera.cellZ());

        // Une colonne de plus que la fenetre : le rendu des sols lit la direction
        // suivante pour en deduire son pas horizontal.
        for (int column = 0; column <= viewport.width(); column++) {
            int offset = viewport.rayOffset(column);

            // RayDir = regard * 128 + perpendiculaire * offset
            int rayDirX = (viewDirX << 7) - Fixed.mul(offset, viewDirZ);
            int rayDirZ = (viewDirZ << 7) + Fixed.mul(offset, viewDirX);

            // Mise a l'echelle verticale : allonger le rayon rapproche l'horizon.
            rayDirX = Fixed.mul(viewport.yRatio(), rayDirX);
            rayDirZ = Fixed.mul(viewport.yRatio(), rayDirZ);

            table.setDirection(column, rayDirX, rayDirZ);
            if (column == viewport.width()) {
                break;
            }

            table.reset(column);
            castColumn(world, camera, table, column, rayDirX, rayDirZ);
        }
    }

    private static void castColumn(World world, Camera camera, RayTable table, int column,
                                   int rayDirX, int rayDirZ) {
        int playerX = camera.x();
        int playerZ = camera.z();

        boolean eastward = rayDirX > 0;
        boolean southward = rayDirZ > 0;

        int edgeForX = eastward ? EDGE_WEST : EDGE_EAST;
        int edgeForZ = southward ? EDGE_NORTH : EDGE_SOUTH;

        int stepCellX = eastward ? 1 : -1;
        int stepCellZ = southward ? 1 : -1;

        // Frontieres de case suivantes, en 16.16.
        int boundaryX = playerX & GRID_MASK;
        int boundaryZ = playerZ & GRID_MASK;
        if (eastward) {
            boundaryX += BLOCK << 16;
        }
        if (southward) {
            boundaryZ += BLOCK << 16;
        }

        // Pas de parametre entre deux frontieres, et parametre de la premiere.
        //
        // Un rayon parallele a un axe ne franchit jamais les frontieres de cet axe :
        // son pas est alors infini, et la DDA n'avance plus que sur l'autre axe. Le
        // moteur d'origine met ces valeurs a zero et se retrouve a suivre un pointeur
        // de carte non initialise ; le cas se produit pourtant reellement, la colonne
        // centrale d'un cap de 0 ou 512 y tombe exactement.
        long stepDistX = axisStep(eastward ? BLOCK : -BLOCK, rayDirX);
        long stepDistZ = axisStep(southward ? BLOCK : -BLOCK, rayDirZ);
        long distX = axisEntry(boundaryX - playerX, rayDirX);
        long distZ = axisEntry(boundaryZ - playerZ, rayDirZ);

        // Coordonnee le long de la face traversee, et son increment par pas.
        int coordZ = playerZ + Fixed.mul(saturate(distX), rayDirZ);
        int deltaZ = Fixed.mul(saturate(stepDistX), rayDirZ);
        int coordX = playerX + Fixed.mul(saturate(distZ), rayDirX);
        int deltaX = Fixed.mul(saturate(stepDistZ), rayDirX);

        // Les faces opposees doivent voir leur texture defiler dans le meme sens :
        // le moteur inverse la coordonnee pour l'ouest et pour le sud.
        if (edgeForX == EDGE_WEST) {
            coordZ = ~coordZ;
            deltaZ = -deltaZ;
        }
        if (edgeForZ == EDGE_SOUTH) {
            coordX = ~coordX;
            deltaX = -deltaX;
        }
        coordZ &= BRUSH_MASK;
        coordX &= BRUSH_MASK;

        int cellX = camera.cellX();
        int cellZ = camera.cellZ();
        int lastBlock = world.cell(cellX, cellZ);

        while (true) {
            boolean advanceX = distX <= distZ;
            if (advanceX) {
                cellX += stepCellX;
            } else {
                cellZ += stepCellZ;
            }

            int code = world.cell(cellX, cellZ);
            table.reach(cellX, cellZ);
            int dist = saturate(advanceX ? distX : distZ);
            int coord = advanceX ? coordZ : coordX;
            int edge = advanceX ? edgeForX : edgeForZ;

            if (code < 0) {
                table.add(column, dist, -code, coord, edge, true);
                // RCXOloop : un rayon sur quatre poursuit au-dela du mur, onze cases de
                // plus, pour ne relever que les objets. Un ennemi a l'angle d'un mur
                // depasse ainsi progressivement, au lieu d'apparaitre d'un coup quand
                // un rayon atteint sa case ; la decoupe en profondeur cache le reste.
                if ((column & 3) == 0) {
                    if (advanceX) {
                        distX = add(distX, stepDistX);
                    } else {
                        distZ = add(distZ, stepDistZ);
                    }
                    for (int extra = 0; extra <= 10; extra++) {
                        boolean alongX = distX <= distZ;
                        if (alongX) {
                            cellX += stepCellX;
                            distX = add(distX, stepDistX);
                        } else {
                            cellZ += stepCellZ;
                            distZ = add(distZ, stepDistZ);
                        }
                        if (cellX < 0 || cellZ < 0
                                || cellX >= Level.MAP_SIZE || cellZ >= Level.MAP_SIZE) {
                            break;
                        }
                        table.reach(cellX, cellZ);
                    }
                }
                return;
            }
            if (code != lastBlock) {
                if (!table.add(column, dist, code, coord, edge, false)) {
                    return;
                }
                lastBlock = code;
            }

            if (advanceX) {
                coordZ += deltaZ;
                distX = add(distX, stepDistX);
            } else {
                coordX += deltaX;
                distZ = add(distZ, stepDistZ);
            }
        }
    }

    /** Parametre parcouru entre deux frontieres de case sur un axe. */
    private static long axisStep(int blockSpan, int rayDir) {
        if (rayDir == 0) {
            return Long.MAX_VALUE;
        }
        return (((long) blockSpan) << 32) / rayDir;
    }

    /** Parametre de la premiere frontiere de case rencontree sur un axe. */
    private static long axisEntry(int toBoundary, int rayDir) {
        if (rayDir == 0) {
            return Long.MAX_VALUE;
        }
        return (((long) toBoundary) << 16) / rayDir;
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return (sum < a) ? Long.MAX_VALUE : sum;   // saturation plutot que bouclage
    }

    private static int saturate(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }
}
