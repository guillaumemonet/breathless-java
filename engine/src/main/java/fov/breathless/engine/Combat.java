package fov.breathless.engine;

import fov.breathless.gld.level.Level;
import fov.breathless.gld.object.ObjectDefinition;

import java.util.Iterator;
import java.util.List;

/**
 * Tirs, impacts et explosions.
 *
 * <p>Portage de {@code PlayerShot} et de la boucle de deplacement des projectiles
 * (Sorgenti/Objects.asm). Un tir accelere jusqu'a sa vitesse maximale, s'arrete sur un
 * mur, sur une cible ou au bout de sa portee, et laisse une explosion s'il en declare une.
 *
 * <p>Un tir ne cumule pas des deplacements : il retient son point de depart et sa distance
 * parcourue. Une trajectoire longue ne derive donc pas.
 */
public final class Combat {

    private final World world;

    /** {@code PlayerSpeed} au moment du tir, que le projectile herite a moitie. */
    private int playerSpeed;

    /** Le joueur fournit son elan avant chaque pas. */
    public void setPlayerSpeed(int speed) {
        this.playerSpeed = speed;
    }
    private final Weapons weapons;
    private final PlayerState state;
    private final java.util.Map<Integer, ObjectDefinition> shotsByWeapon = new java.util.HashMap<>();
    /**
     * Tirs en attente de partir. {@code Fire} dans Objects.asm.
     *
     * <p>Chaque pression sur la gachette en met un en file ; le lance-flammes, lui, en met
     * un par image tant qu'on le tient. La file se vide d'un tir par image : il n'y a pas
     * d'autre delai de rechargement que celui-la.
     */
    private int pending;
    private SoundPlayer sound = SoundPlayer.SILENT;

    /** Branche le son. Sans cela les tirs partent en silence. */
    public void setSoundPlayer(SoundPlayer player) {
        this.sound = (player == null) ? SoundPlayer.SILENT : player;
    }

    /** Nom du bruitage d'un objet, ou {@code null} : 0 depart, 1 impact, 2 mort. */
    private static String soundOf(ObjectDefinition definition, int index) {
        java.util.List<String> names = definition.sounds();
        if (index < 0 || index >= names.size()) {
            return null;
        }
        String name = names.get(index);
        return (name == null || name.isBlank()) ? null : name;
    }

    public Combat(World world, Weapons weapons, PlayerState state,
                  fov.breathless.gld.object.ObjectBank bank) {
        this.world = world;
        this.weapons = weapons;
        this.state = state;
        // InitObjects remplit GunObj dans l'ordre du catalogue : pour un meme numero, le
        // dernier projectile l'emporte.
        for (ObjectDefinition definition : bank.objects().values()) {
            if (definition.objectType() == ObjectDefinition.TYPE_SHOT) {
                shotsByWeapon.put((int) (byte) definition.param(7), definition);
            }
        }
    }

    public Weapons weapons() {
        return weapons;
    }

    /** Avance d'un pas : tir eventuel, puis vol et impacts des projectiles. */
    public void update(Camera camera, PlayerInput input, int playerBlock) {
        if (input.nextWeapon) {
            weapons.next();
            input.nextWeapon = false;
        }

        boolean flamethrower = weapons.isFlamethrower();
        if (flamethrower) {
            // Le lance-flammes met un tir en file par image tant qu'il est tenu.
            input.firePresses = 0;
            if (input.fire) {
                pending++;
            } else if (pending <= 0) {
                stopFlame();
            }
        } else {
            pending += input.firePresses;
            input.firePresses = 0;
        }

        if (pending > 0 && !state.isDead()) {
            pending--;
            pullTrigger(camera, playerBlock);
        } else if (state.isDead()) {
            pending = 0;
        }

        currentAnimStep = animStep();
        advanceProjectiles(camera);
        advanceExplosions();
        flash();
    }

    /**
     * {@code GlobalLight} : tant qu'une explosion n'a pas joue la moitie de ses images, la
     * scene s'eclaire d'un coup (-5, plus clair) ; ensuite la lumiere revient d'un cran par
     * image jusqu'a la normale.
     */
    private int globalLight;

    public int globalLight() {
        return globalLight;
    }

    private void flash() {
        boolean bright = false;
        for (SceneObject object : world.objects()) {
            if (object.isSelfAnimated()
                    && object.animCounter() * 2 < object.definition().frames().size()) {
                bright = true;
                break;
            }
        }
        if (bright) {
            globalLight = -5;
        } else if (globalLight < 0) {
            globalLight++;
        }
    }

    /** Arrete le bruit du lance-flammes, qui boucle tant qu'il crache. */
    private void stopFlame() {
        ObjectDefinition shot = weapons.projectile();
        if (shot == null) {
            return;
        }
        String name = soundOf(shot, 0);
        if (name != null) {
            sound.stop(name);
        }
    }

    /** Amplitude de l'oscillation du lance-flammes, en pas d'angle. {@code PFflame}. */
    private static final int FLAME_SWING = 40;

    /** Ecart des deux tirs d'une arme amelioree. {@code PlayerFire}. */
    private static final int BOOST_SPREAD = 8;

    private int flameOscillation;
    private int flameDirection = 6;

    /**
     * Appuie sur la gachette.
     *
     * <p>Portage de {@code PlayerFire}. Trois cas : le lance-flammes, dont la visee
     * balaie de part et d'autre de l'axe ; une arme amelioree, qui part deux fois en
     * eventail, le second tir gratuit ; et le cas ordinaire, un tir dans l'axe.
     *
     * @return {@code true} si au moins un projectile est parti
     */
    private boolean pullTrigger(Camera camera, int playerBlock) {
        if (weapons.isFlamethrower()) {
            flameOscillation += flameDirection;
            if (flameOscillation >= FLAME_SWING || flameOscillation <= -FLAME_SWING) {
                flameDirection = -flameDirection;
            }
            // PFflstop : faute d'energie, le bruit du lance-flammes s'arrete.
            if (!fire(camera, playerBlock, flameOscillation, false)) {
                stopFlame();
                return false;
            }
            return true;
        }
        if (weapons.isBoosted(weapons.active())) {
            // bsr PlayerShot / bne PFnofire : le second tir ne part que si le premier a pu
            // partir. Il ne coute alors rien — l'original passe a6 non nul pour l'en
            // dispenser.
            if (!fire(camera, playerBlock, -BOOST_SPREAD, false)) {
                return false;
            }
            fire(camera, playerBlock, BOOST_SPREAD, true);
            return true;
        }
        return fire(camera, playerBlock, 0, false);
    }

    /**
     * Tire un projectile.
     *
     * @param aimOffset ecart de visee, en pas d'angle
     * @param free      si vrai, le tir ne consomme pas d'energie
     * @return {@code false} si l'energie manque ou si l'arme n'a pas de projectile decrit
     */
    private boolean fire(Camera camera, int playerBlock, int aimOffset, boolean free) {
        ObjectDefinition shot = weapons.projectile();
        if (shot == null) {
            return false;
        }
        if (!free) {
            int cost = weapons.energyCost();
            if (state.energy() < cost) {
                // PFesci2 : l'arme claque a vide quand l'energie manque.
                sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_FAULT);
                return false;
            }
            state.spendEnergy(cost);
        }

        // La hauteur du canon est donnee par le douzieme parametre du projectile, un octet
        // sans signe ajoute au sol du joueur.
        int startY = world.floorHeight(playerBlock) + (shot.param(12) & 0xFF);

        // PFnomipsp : le tir part avec la moitie de l'elan du joueur en plus, jamais moins.
        int speed = (Math.max(0, playerSpeed) >> 1) + shot.param(2);

        world.projectiles().add(new Projectile(shot, true,
                camera.x() >> 16, camera.z() >> 16, startY,
                (camera.heading() + aimOffset) & Trig.MASK,
                (short) (camera.lookStep() << 8), speed));
        // Le second tir d'une arme amelioree est muet : l'original passe d7 non nul.
        if (!free) {
            String fired = soundOf(shot, 0);
            if (fired != null) {
                sound.play(fired);
            }
        }
        return true;
    }

    /**
     * {@code animstep} : un quart d'image par cinquantieme, avec retenue. Tirs et
     * explosions s'animent ainsi a 12,5 images par seconde.
     */
    private int animCarry;

    private int animStep() {
        animCarry += 0x4000;
        int step = animCarry >>> 16;
        animCarry &= 0xFFFF;
        return step;
    }

    /** Pas d'animation du cinquantieme en cours. */
    private int currentAnimStep;

    /**
     * {@code MSloop} : la portee, puis les coins avec l'altitude precedente, puis les
     * cibles, puis la case d'arrivee — et seulement alors la nouvelle position.
     */
    private void advanceProjectiles(Camera camera) {
        Iterator<Projectile> iterator = world.projectiles().iterator();
        while (iterator.hasNext()) {
            Projectile projectile = iterator.next();

            boolean stopped = !projectile.advance()
                    || hitsCorners(projectile)
                    || hitsTarget(projectile, camera)
                    || hitsArrivalCell(projectile);
            if (stopped) {
                explode(projectile);
                iterator.remove();
                continue;
            }
            projectile.commit();
            projectile.animate(currentAnimStep);
        }
    }

    /**
     * Une case est-elle infranchissable a cette altitude ? Mur plein, ou
     * {@code y < sol}, ou {@code y >= plafond - hauteur du tir}.
     */
    private boolean blocks(int code, int y, int height) {
        if (code < 0) {
            return true;
        }
        return y < world.floorHeight(code) || y >= world.ceilHeight(code) - height;
    }

    /**
     * {@code CTS0} a {@code CTS7} : trois points a une largeur du centre, du cote ou va
     * le tir, avec l'altitude qu'il avait au pas precedent. La case du centre n'est pas
     * testee ici.
     */
    private boolean hitsCorners(Projectile projectile) {
        int x = projectile.nextX();
        int z = projectile.nextZ();
        int w = projectile.radius();
        int cellX = Math.floorDiv(x, Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(z, Level.BLOCK_SIZE);
        int[][] points = switch ((projectile.heading() >> 8) & 7) {
            case 0 -> new int[][]{{x + w, z - w}, {x + w, z}, {x + w, z + w}};
            case 1 -> new int[][]{{x + w, z}, {x, z + w}, {x + w, z + w}};
            case 2 -> new int[][]{{x + w, z + w}, {x, z + w}, {x - w, z + w}};
            case 3 -> new int[][]{{x, z + w}, {x - w, z}, {x - w, z + w}};
            case 4 -> new int[][]{{x - w, z + w}, {x - w, z}, {x - w, z - w}};
            case 5 -> new int[][]{{x - w, z}, {x, z - w}, {x - w, z - w}};
            case 6 -> new int[][]{{x - w, z - w}, {x, z - w}, {x + w, z - w}};
            default -> new int[][]{{x, z - w}, {x + w, z}, {x + w, z - w}};
        };
        for (int[] point : points) {
            int px = Math.floorDiv((short) point[0], Level.BLOCK_SIZE);
            int pz = Math.floorDiv((short) point[1], Level.BLOCK_SIZE);
            if (px == cellX && pz == cellZ) {
                continue;
            }
            if (blocks(world.cell(px, pz), projectile.y(), projectile.height())) {
                return true;
            }
        }
        return false;
    }

    /**
     * La case d'arrivee, testee seulement si le tir change de case : {@code MSnochgbl}
     * saute le test quand il reste sur la meme.
     */
    private boolean hitsArrivalCell(Projectile projectile) {
        int cellX = Math.floorDiv(projectile.nextX(), Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(projectile.nextZ(), Level.BLOCK_SIZE);
        if (cellX == Math.floorDiv(projectile.x(), Level.BLOCK_SIZE)
                && cellZ == Math.floorDiv(projectile.z(), Level.BLOCK_SIZE)) {
            return false;
        }
        return blocks(world.cell(cellX, cellZ), projectile.y(), projectile.height());
    }

    /**
     * Voisinage teste par un tir du joueur : sa propre case, plus les trois cases de
     * l'octant vers lequel il va.
     *
     * <p>Portage de {@code CollTestOffsetTable} (Sorgenti/Objects.asm). Le moteur d'origine
     * range les objets par case et ne parcourt que ces quatre listes ; sans cette
     * restriction, un tir peut toucher une cible situee ailleurs dans le niveau.
     */
    private static final int[][] NEIGHBOURS = {
        {1, -1, 1, 0, 1, 1},
        {1, 0, 1, 1, 0, 1},
        {1, 1, 0, 1, -1, 1},
        {0, 1, -1, 1, -1, 0},
        {-1, 1, -1, 0, -1, -1},
        {-1, 0, -1, -1, 0, -1},
        {-1, -1, 0, -1, 1, -1},
        {0, -1, 1, -1, 1, 0},
    };

    /**
     * Le tir a-t-il atteint une cible ?
     *
     * <p>Un tir du joueur cherche les objets solides ; un tir ennemi cherche le joueur.
     *
     * <p>Cote joueur, trois regles reprises de {@code MSctrlobjcoll} :
     *
     * <ul>
     *   <li>seuls comptent les objets <em>dans le voisinage immediat</em> du tir, cf.
     *       {@link #NEIGHBOURS} ;</li>
     *   <li>la portee est le carre de la <em>somme</em> des deux rayons, pas la somme de
     *       leurs carres — l'original ne fait la somme des carres que pour le joueur ;</li>
     *   <li>tout objet de type inferieur ou egal a un ennemi arrete le tir, decors compris.
     *       Un pilier protege donc ce qu'il y a derriere.</li>
     * </ul>
     *
     * <p>Entre plusieurs candidats, c'est le plus proche qui encaisse. L'original prend le
     * premier de sa liste de case, un ordre de placement : deux ennemis cote a cote se
     * volaient alors les coups.
     *
     * <p>Aucun test de hauteur : le moteur d'origine n'en fait pas pour les objets. Viser
     * au-dessus d'un ennemi ne le sauve pas.
     *
     * <p>Un ennemi abattu rapporte les points que son type declare.
     */
    private boolean hitsTarget(Projectile projectile, Camera camera) {
        int radius = projectile.radius();

        if (!projectile.isFromPlayer()) {
            int reach = radius * radius + Player.WIDTH * Player.WIDTH;
            int dx = (short) ((camera.x() >> 16) - projectile.nextX());
            int dz = (short) ((camera.z() >> 16) - projectile.nextZ());
            if (dx * dx + dz * dz > reach) {
                return false;
            }
            // Un tir qui passe bien au-dessus de la tete ne touche pas.
            if (camera.y() + radius + 3 < projectile.nextY()) {
                return false;
            }
            // PlayerHit crie a chaque coup encaisse, pas seulement au dernier.
            int before = state.health() + state.shields();
            state.hit(projectile.power());
            if (state.health() + state.shields() < before) {
                sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SCREAM);
            }
            return true;
        }

        SceneObject nearest = nearestTarget(projectile, radius);
        if (nearest == null) {
            return false;
        }
        if (nearest.definition().objectType() == ObjectDefinition.TYPE_ENEMY) {
            // Un corps qui s'abat n'encaisse plus rien : le tir s'arrete sur lui, mais ne
            // le blesse ni ne le tue une seconde fois. Sans cela il reprendrait un statut
            // vivant et se figerait debout au milieu de sa chute.
            if (EnemyAi.isFalling(nearest)) {
                return true;
            }
            // MSctrlobjcoll1 : l'arret sous le choc vient avant la perte de resistance,
            // meme si le coup est fatal.
            EnemyAi.wound(nearest);
            if (nearest.damage(projectile.power())) {
                // DestroyEnemy credite le deuxieme parametre du type abattu : de mille
                // points pour le plus faible a vingt-cinq mille pour le plus coriace.
                state.addScore(nearest.definition().param(2));
                // Un tir qui explose fait exploser sa cible ; sinon le corps tombe dans la
                // direction du tir, s'il a une chute, et crie en tombant.
                boolean explodes = projectile.explosionIndex() >= 0;
                if (EnemyAi.kill(nearest, projectile.heading(), explodes)) {
                    playAt(soundOf(nearest.definition(), 2), nearest);
                } else {
                    explodeAt(nearest);
                }
            } else {
                playAt(soundOf(nearest.definition(), 1), nearest);
            }
        }
        return true;
    }

    /** {@code ObjBufferedPlaySoundFX} : un son emis depuis l'objet. */
    private void playAt(String name, SceneObject source) {
        if (name != null) {
            sound.playAt(name, source.x(), source.z());
        }
    }

    /** Objet solide le plus proche du tir dans son voisinage, ou {@code null}. */
    private SceneObject nearestTarget(Projectile projectile, int radius) {
        // Le voisinage est celui de la case visee, mais la distance se mesure depuis la
        // position encore en vigueur : obj_x n'est mis a jour qu'apres les collisions.
        int cellX = Math.floorDiv(projectile.nextX(), Level.BLOCK_SIZE);
        int cellZ = Math.floorDiv(projectile.nextZ(), Level.BLOCK_SIZE);
        int[] octant = NEIGHBOURS[(projectile.heading() >> 8) & 7];

        SceneObject nearest = null;
        long nearestDistance = Long.MAX_VALUE;

        for (SceneObject object : world.objects()) {
            if (object.definition().objectType() > ObjectDefinition.TYPE_ENEMY
                    || object.isCollected() || object.isRemains()) {
                continue;   // un cadavre ne fait plus obstacle
            }
            int objectRadius = object.definition().radius();
            if (objectRadius == 0) {
                continue;   // un objet sans largeur ne fait pas obstacle
            }
            if (!inNeighbourhood(object, cellX, cellZ, octant)) {
                continue;
            }
            int dx = object.x() - projectile.x();
            int dz = object.z() - projectile.z();
            long distance = (long) dx * dx + (long) dz * dz;
            long reach = (long) (radius + objectRadius) * (radius + objectRadius);
            if (distance > reach || distance >= nearestDistance) {
                continue;
            }
            nearestDistance = distance;
            nearest = object;
        }
        return nearest;
    }

    /** L'objet occupe-t-il la case du tir ou l'une des trois de son octant ? */
    private static boolean inNeighbourhood(SceneObject object, int cellX, int cellZ,
                                           int[] octant) {
        int objectX = Math.floorDiv(object.x(), Level.BLOCK_SIZE);
        int objectZ = Math.floorDiv(object.z(), Level.BLOCK_SIZE);
        if (objectX == cellX && objectZ == cellZ) {
            return true;
        }
        for (int i = 0; i < octant.length; i += 2) {
            if (objectX == cellX + octant[i] && objectZ == cellZ + octant[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lance un projectile ennemi, vise sur le joueur.
     *
     * <p>Le numero d'arme d'un ennemi renvoie a un projectile de la banque, dont les
     * numeros dix a quatorze leur sont reserves.
     *
     * <p>Le tir ne part <strong>pas</strong> dans l'axe de l'ennemi : le moteur d'origine
     * calcule l'angle exact vers le joueur, par racine carree puis arc-sinus affine dans la
     * table des sinus ({@code EnemiesFire} dans Objects.asm). Un ennemi ne tournant que par
     * huitiemes de tour, tirer dans son axe le ferait manquer presque a tous les coups.
     */
    void enemyShot(SceneObject enemy, int gun, int playerX, int playerZ, int playerY) {
        if (gun == 0) {
            return;
        }
        ObjectDefinition shot = shotsByWeapon.get(gun);
        if (shot == null) {
            return;
        }
        int dx = (short) (playerX - enemy.x());
        int dz = (short) (playerZ - enemy.z());
        int distance = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        if (distance == 0) {
            return;
        }
        int heading = (int) Math.round(Math.atan2(dz, dx) * Trig.STEPS / (2 * Math.PI))
                & Trig.MASK;

        // Le tir part du sol de l'ennemi, rehausse du douzieme parametre de son type.
        int startY = world.floorHeight(Math.abs(world.cell(
                Math.floorDiv(enemy.x(), Level.BLOCK_SIZE),
                Math.floorDiv(enemy.z(), Level.BLOCK_SIZE))))
                + (enemy.definition().param(12) & 0xFF);

        // Le tir nait seize unites plus loin : s'il nait dans un mur, un sol ou un
        // plafond, il ne part pas.
        int firstX = (short) (enemy.x() + ((Trig.cos(heading) * 16) >> 16));
        int firstZ = (short) (enemy.z() + ((Trig.sin(heading) * 16) >> 16));
        int code = world.cell(Math.floorDiv(firstX, Level.BLOCK_SIZE),
                Math.floorDiv(firstZ, Level.BLOCK_SIZE));
        if (blocks(code, startY, shot.height())) {
            return;
        }

        // Pente vers les yeux du joueur : ((PlayerY - obj_y - 54) << 15) / distance, sur
        // seize bits. Un divs qui deborde sature a 32767, du signe de l'ecart.
        int rise = (short) (playerY - enemy.y() - Camera.EYES_HEIGHT);
        long quotient = ((long) rise << 15) / distance;
        int pitch = (quotient > Short.MAX_VALUE || quotient < Short.MIN_VALUE)
                ? (rise >= 0 ? 32767 : -32767) : (int) quotient;

        // La vitesse du tir ajoute la moitie de celle de l'ennemi.
        int speed = (enemy.speed() >> 1) + shot.param(2);

        world.projectiles().add(new Projectile(shot, false,
                enemy.x(), enemy.z(), startY, heading, pitch, speed));
        playAtPoint(soundOf(shot, 0), enemy.x(), enemy.z());
    }

    private void playAtPoint(String name, int x, int z) {
        if (name != null) {
            sound.playAt(name, x, z);
        }
    }

    /**
     * Fait exploser un ennemi qui n'a pas d'animation de chute.
     *
     * <p>{@code DEexpl} : l'objet n'est pas retire. Il prend l'explosion que declare son
     * quatrieme parametre, recentree sur sa hauteur, et garde memoire de ce qu'il etait —
     * c'est ainsi qu'il pourra laisser un cadavre quand l'explosion sera finie.
     */
    private void explodeAt(SceneObject enemy) {
        ObjectDefinition definition = world.explosion(enemy.definition().param(4));
        if (definition == null || definition.frames().isEmpty()) {
            enemy.collect();
            return;
        }
        enemy.setY(enemy.y() + ((enemy.definition().height() - definition.height()) >> 1));
        enemy.setSpeed(0);
        enemy.becomeExplosion(definition);
        playAt(soundOf(definition, 0), enemy);
    }

    /**
     * Laisse une explosion a l'endroit de l'impact, si le tir en declare une.
     *
     * <p>{@code MSSexpl} : le tir devient l'explosion, recentree sur sa hauteur, et fait
     * entendre le premier son de celle-ci.
     */
    private void explode(Projectile projectile) {
        int index = projectile.explosionIndex();
        if (index < 0) {
            return;
        }
        ObjectDefinition definition = world.explosion(index);
        if (definition == null || definition.frames().isEmpty()) {
            return;
        }
        int y = projectile.y() + ((short) (projectile.height() - definition.height()) >> 1);
        SceneObject explosion = new SceneObject(definition,
                projectile.x(), projectile.z(), y, 0, 0);
        explosion.setSelfAnimated(true);
        world.objects().add(explosion);
        playAt(soundOf(definition, 0), explosion);
    }

    /** Image du cadavre d'un ennemi que l'explosion laisse au sol. {@code AOESrestien}. */
    private static final int EXPLODED_REMAINS_FRAME = 128;

    /**
     * Fait vivre les explosions et decide de ce qu'elles laissent.
     *
     * <p>{@code AOEloop} : une explosion finie ne disparait pas toujours. Le deuxieme
     * parametre de son type tranche, et seulement pour celle qui a remplace un ennemi —
     * l'explosion d'un simple impact ne laisse jamais rien :
     *
     * <ul>
     *   <li>negatif, {@code AOESrestien} : l'ennemi reprend son type et son image 128, le
     *       cadavre au sol ;</li>
     *   <li>positif, {@code AOESrestiexp} : la derniere image de l'explosion reste ;</li>
     *   <li>nul : l'objet disparait.</li>
     * </ul>
     */
    private void advanceExplosions() {
        List<SceneObject> objects = world.objects();
        for (int i = objects.size() - 1; i >= 0; i--) {
            SceneObject object = objects.get(i);
            if (!object.isSelfAnimated()) {
                continue;
            }
            // AOEloop : une explosion avance de animstep, soit 12,5 images par seconde.
            int next = object.animCounter() + currentAnimStep;
            if (next < object.definition().frames().size()) {
                object.setAnimCounter(next);
                continue;
            }

            int leaves = object.hasPreviousDefinition() ? object.definition().param(2) : 0;
            if (leaves == 0) {
                objects.remove(i);
                continue;
            }
            object.setSelfAnimated(false);
            object.setRemains(true);
            if (leaves < 0) {
                object.restorePreviousDefinition();
                object.setAnimCounter(EXPLODED_REMAINS_FRAME);
            } else {
                object.setAnimCounter(object.definition().frames().size() - 1);
            }
        }
    }
}
