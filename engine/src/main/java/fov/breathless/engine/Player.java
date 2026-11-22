package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.Level;

/**
 * Deplacement du joueur et collisions avec le decor.
 *
 * <p>Portage de {@code DoMovement} (Sorgenti/movement.asm). Le mouvement a de l'inertie :
 * les commandes fixent une vitesse <em>visee</em>, la vitesse reelle s'en approche par
 * paliers. Il en va de meme pour la rotation, avec une acceleration legerement plus forte
 * quand on inverse le sens, pour que le demi-tour reste vif.
 *
 * <p>Les collisions traitent le joueur comme un carre de {@link #WIDTH} de demi-cote dans
 * une grille de 64. Selon les bords de case qu'il chevauche, une a trois cases voisines
 * sont testees ; celles qui le refusent provoquent un recalage de la coordonnee
 * correspondante contre le bord. C'est ce recalage par axe qui produit le glissement le
 * long des murs.
 */
public final class Player {

    /** Demi-cote du joueur, {@code PLAYER_WIDTH}. */
    public static final int WIDTH = 16;

    /** Hauteur du joueur, {@code PLAYER_HEIGHT}. */
    public static final int HEIGHT = 56;

    /** Denivele maximal franchissable, {@code PLAYER_MAX_RISE}. */
    public static final int MAX_RISE = 24;

    /** Hauteur libre exigee pour passer : le moteur ajoute huit unites a la taille. */
    public static final int CLEARANCE = HEIGHT + 8;

    // Valeurs par defaut reellement appliquees au demarrage (Sorgenti/TMapMain.asm).
    // Attention : les constantes PLAYER_WALK_SPEED et consorts de TMap.i sont des
    // vestiges, annotes « old= » dans le fichier, et ne sont utilisees nulle part.
    // Les vitesses de deplacement sont stockees multipliees par seize.
    private int walkSpeed = 4 << 4;
    private int runSpeed = 6 << 4;
    private int accel = 4;
    private int rotWalkSpeed = 8;
    private int rotRunSpeed = 20;
    private int rotAccelBase = 4;

    /**
     * Les reglages de la page des controles, tels que {@link Settings#movement()} les
     * traduit : marche, course, rotation, rotation en course, acceleration, acceleration
     * de rotation.
     */
    public void setMovement(int[] values) {
        walkSpeed = values[0];
        runSpeed = values[1];
        rotWalkSpeed = values[2];
        rotRunSpeed = values[3];
        accel = values[4];
        rotAccelBase = values[5];
    }

    private static final int BLOCK = Level.BLOCK_SIZE;
    private static final int GRID_MASK = ((Level.MAP_SIZE * BLOCK) - BLOCK) << 16;

    /** Codes de direction : bits pas-a-droite, pas-a-gauche, arriere, avant. */
    private static final int DIR_RIGHT = 1;
    private static final int DIR_LEFT = 2;
    private static final int DIR_BACK = 4;
    private static final int DIR_FORWARD = 8;

    /**
     * Pour chaque code de direction, l'angle a ajouter au cap et le signe de la vitesse.
     * Reprend {@code movingdirtable} : un pas de cote est un cap tourne d'un quart de
     * tour, une diagonale d'un huitieme.
     */
    private static final int[] DIR_ANGLE = new int[16];
    private static final int[] DIR_SIGN = new int[16];

    static {
        setDirection(1, 512, 1);      // pas a droite
        setDirection(2, 512, -1);     // pas a gauche
        setDirection(4, 0, -1);       // arriere
        setDirection(5, -256, -1);    // arriere-droite
        setDirection(6, 256, -1);     // arriere-gauche
        setDirection(8, 0, 1);        // avant
        setDirection(9, 256, 1);      // avant-droite
        setDirection(10, -256, 1);    // avant-gauche
    }

    private static void setDirection(int code, int angle, int sign) {
        DIR_ANGLE[code] = angle;
        DIR_SIGN[code] = sign;
    }

    /**
     * Effet d'un changement de direction sur la vitesse acquise, d'apres
     * {@code updspeedtable} : 1 remet la vitesse a zero, -1 l'inverse, 0 la conserve.
     * Lignes indexees par la nouvelle direction, colonnes par l'ancienne, de 1 a 10.
     */
    private static final byte[][] SPEED_TRANSITION = {
            {0, 0, 0, 1, -1, 1, 0, 1, 0, 1},
            {0, 0, 0, 1, 1, 0, 0, 1, 1, -1},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {1, 1, 0, 0, 0, 0, 0, 0, 1, 1},
            {-1, 1, 0, 0, 0, 0, 0, 1, 0, 0},
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {1, 1, 0, 0, 1, 1, 0, 0, 0, 0},
            {0, 1, 0, 1, 0, 0, 0, 0, 0, 0},
            {1, -1, 0, 1, 0, 0, 0, 0, 0, 0},
    };

    /** Chute : un denivele d'au moins {@code PLAYER_MAX_RISE} vers le bas. */
    private static final int FALL_THRESHOLD = MAX_RISE;

    /** Vitesse verticale maximale et acceleration, en 16.16. */
    private static final int MAX_SPEED_Y = 0xA0000;
    private static final int ACCEL_Y = 0x8000;

    /** Borne du regard vertical, en crans. */
    private static final int MAX_LOOK = 72;

    /** Hauteur des yeux une fois le joueur a terre, et duree de l'attente qui suit. */
    private static final int DEAD_EYES_HEIGHT = 12;
    private static final int POST_DEATH_WAIT = 60;

    /**
     * {@code OscSpeedTrans} : la vitesse divisee par 1,333, indexee de -114 a +113. Elle
     * fait avancer la phase du balancement.
     */
    private static final int[] OSC_SPEED = buildOscSpeed();

    /** {@code OscillationData} : la forme d'onde de la marche, a amplitude maximale. */
    private static final int[] OSC_WAVE = {
        0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 6, 7,
        7, 7, 7, 6, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1,
        0, 0, -1, -1, -2, -2, -3, -3, -4, -4, -5, -5, -6, -6, -6, -7,
        -7, -7, -7, -6, -6, -6, -5, -5, -4, -4, -3, -3, -2, -2, -1, -1,
    };

    /**
     * {@code OscillationAmp} : l'amplitude reelle selon la vitesse, une ligne par tranche
     * de huit, une colonne par valeur de la forme d'onde (-8 a +7).
     */
    private static final int[] OSC_AMP = {
        -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1,
        -2, -2, -2, -1, -1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2,
        -2, -2, -2, -1, -1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2,
        -3, -3, -2, -2, -2, -1, -1, 0, 0, 0, 1, 1, 2, 2, 2, 3,
        -3, -3, -2, -2, -2, -1, -1, 0, 0, 0, 1, 1, 2, 2, 2, 3,
        -4, -4, -3, -3, -2, -1, -1, 0, 0, 0, 1, 1, 2, 3, 3, 4,
        -4, -4, -3, -3, -2, -1, -1, 0, 0, 0, 1, 1, 2, 3, 3, 4,
        -5, -5, -4, -3, -3, -2, -1, -1, 0, 1, 1, 2, 3, 3, 4, 5,
        -5, -5, -4, -3, -3, -2, -1, -1, 0, 1, 1, 2, 3, 3, 4, 5,
        -6, -5, -4, -4, -3, -2, -2, -1, 0, 1, 2, 2, 3, 4, 4, 5,
        -6, -5, -4, -4, -3, -2, -2, -1, 0, 1, 2, 2, 3, 4, 4, 5,
        -7, -6, -5, -4, -4, -3, -2, -1, 0, 1, 2, 3, 4, 4, 5, 6,
        -7, -6, -5, -4, -4, -3, -2, -1, 0, 1, 2, 3, 4, 4, 5, 6,
        -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7,
        -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7,
        -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7,
    };

    private static int[] buildOscSpeed() {
        int[] positive = {
            0, 0, 1, 2, 3, 3, 4, 5, 6, 6, 7, 8, 9, 9, 10, 11,
            12, 12, 13, 14, 15, 15, 16, 17, 18, 18, 19, 20, 21, 21, 22, 23,
            24, 24, 25, 26, 27, 27, 28, 29, 30, 30, 31, 32, 33, 33, 34, 35,
            36, 36, 36, 36, 37, 37, 37, 37, 38, 38, 38, 38, 39, 39, 39, 39,
            40, 40, 40, 40, 41, 41, 41, 41, 41, 42, 42, 42, 42, 42, 43, 43,
            43, 43, 43, 44, 44, 44, 44, 44, 44, 45, 45, 45, 45, 45, 45, 45,
            46, 46, 46, 46, 46, 46, 46, 46, 47, 47, 47, 47, 47, 47, 47, 47,
            48, 48,
        };
        int[] negative = {
            -48, -48,
            -47, -47, -47, -47, -47, -47, -47, -47, -46, -46, -46, -46, -46, -46, -46, -46,
            -45, -45, -45, -45, -45, -45, -45, -44, -44, -44, -44, -44, -44, -43, -43, -43,
            -43, -43, -42, -42, -42, -42, -42, -41, -41, -41, -41, -41, -40, -40, -40, -40,
            -39, -39, -39, -39, -38, -38, -38, -38, -37, -37, -37, -37, -36, -36, -36, -36,
            -36, -35, -34, -33, -33, -32, -31, -30, -30, -29, -28, -27, -27, -26, -25, -24,
            -24, -23, -22, -21, -21, -20, -19, -18, -18, -17, -16, -15, -15, -14, -13, -12,
            -12, -11, -10, -9, -9, -8, -7, -6, -6, -5, -4, -3, -3, -2, -1, 0,
        };
        int[] table = new int[negative.length + positive.length];
        System.arraycopy(negative, 0, table, 0, negative.length);
        System.arraycopy(positive, 0, table, negative.length, positive.length);
        return table;
    }

    private static int oscSpeed(int speed) {
        int index = speed + 114;
        return (index >= 0 && index < OSC_SPEED.length) ? OSC_SPEED[index] : 0;
    }

    private final World world;
    private int speed;
    private int rotSpeed;
    private int movingDir;
    private int previousDir;

    /** {@code WalkMaxSpeed} : la vitesse visee, gardee pendant une chute. */
    private int walkMax;

    /**
     * {@code AMovingDir} : direction ajoutee apres avoir heurte un objet, qui fait
     * contourner l'obstacle et s'eteint de 64 pas par image.
     */
    private int aMovingDir;

    /** {@code CPlayerY} en 16.16, et la hauteur qu'il rejoint, {@code NewPlayerY}. */
    private int yFixed;
    private int newY;
    private int speedY;

    /** {@code PlayerFalling} : vrai le temps d'une chute, commandes ignorees. */
    private boolean falling;
    private int fallingHeight;

    /** Degats d'une chute qui vient de s'achever, a la charge de la simulation. */
    private int pendingFallDamage;

    private int eyesHeight = Camera.EYES_HEIGHT;

    /** {@code LUDSpeed} et {@code CLookHeightNum}. */
    private int lookSpeed;

    /** {@code OscCont} et {@code PlayerYOsc}. */
    private int oscCont;
    private int oscY;
    private boolean stepPlayed;
    private boolean stepSound;

    /** {@code CPlayerBlock}, et le fait d'en avoir change a ce pas. */
    private int currentBlock = -1;
    private boolean changedBlock;

    /** {@code PlayerDeath} et {@code PostDeathWait}. */
    private boolean dead;
    private int postDeathWait = -1;
    private boolean deathFinished;

    public Player(World world) {
        this.world = world;
    }

    /** Vitesse acquise, dans l'echelle du moteur (seizieme d'unite par image). */
    public int speed() {
        return speed;
    }

    /** Le joueur est-il en train de tomber ? {@code PlayerFalling}. */
    public boolean isFalling() {
        return falling;
    }

    /** Le joueur vient-il de changer de bloc ? {@code PlayerMoved}. */
    public boolean changedBlock() {
        return changedBlock;
    }

    /** Degats de la chute qui vient de finir, une seule fois. */
    public int takeFallDamage() {
        int damage = pendingFallDamage;
        pendingFallDamage = 0;
        return damage;
    }

    /** Un pas vient-il de sonner ? Une seule fois. {@code GlobalSound5}. */
    public boolean takeStepSound() {
        boolean step = stepSound;
        stepSound = false;
        return step;
    }

    /** Le joueur est mort : il s'effondre, puis l'attente commence. */
    public void die() {
        dead = true;
    }

    /** La sequence de mort est-elle finie ? {@code st Escape}. */
    public boolean isDeathFinished() {
        return deathFinished;
    }

    /**
     * Pose le joueur a l'endroit ou se trouve la camera, sans elan ni chute.
     *
     * <p>{@code InitPlayerPos2} : appele au chargement du niveau et apres une
     * teleportation.
     */
    public void place(Camera camera) {
        currentBlock = Math.abs(world.cell(camera.cellX(), camera.cellZ()));
        newY = world.floorHeight(currentBlock) + eyesHeight;
        yFixed = newY << 16;
        speedY = 0;
        falling = false;
        fallingHeight = 0;
        camera.setY(newY + oscY);
    }

    /**
     * Coupe net l'elan acquis.
     *
     * <p>{@code ResetMoveVars} : une teleportation ne fait pas glisser le joueur sur sa
     * lancee a l'arrivee.
     */
    public void stop() {
        speed = 0;
        rotSpeed = 0;
        movingDir = 0;
        previousDir = 0;
        walkMax = 0;
        aMovingDir = 0;
    }

    /** Avance la simulation d'une image et deplace la camera. */
    public void update(Camera camera, PlayerInput input) {
        if (currentBlock < 0) {
            place(camera);
        }
        changedBlock = false;
        int maxWalk = input.run ? runSpeed : walkSpeed;
        int maxRot = input.run ? rotRunSpeed : rotWalkSpeed;

        // RotMaxSpeed s'efface toujours ; WalkMaxSpeed seulement hors chute : celui qui
        // tombe garde son elan, mais ne commande plus rien.
        int targetRot = 0;
        if (!falling) {
            walkMax = 0;
        }
        if (!falling && !dead) {
            int direction = 0;
            if (input.sideRight) {
                direction |= DIR_RIGHT;
            } else if (input.sideLeft) {
                direction |= DIR_LEFT;
            } else if (input.strafe) {
                if (input.right) {
                    direction |= DIR_RIGHT;
                } else if (input.left) {
                    direction |= DIR_LEFT;
                }
            }
            if (!input.strafe) {
                // Gauche est testee apres droite : si les deux sont tenues, elle l'emporte.
                if (input.right) {
                    targetRot = maxRot;
                }
                if (input.left) {
                    targetRot = -maxRot;
                }
            }
            if (input.forward) {
                direction |= DIR_FORWARD;
            }
            if (input.backward) {
                direction |= DIR_BACK;
            }
            if (direction != 0 && DIR_SIGN[direction] != 0) {
                movingDir = DIR_ANGLE[direction];
                walkMax = DIR_SIGN[direction] < 0 ? -maxWalk : maxWalk;
                applySpeedTransition(direction);
                previousDir = direction;
            }
        }

        speed = approach(speed, walkMax, accel);
        verticalSpeed();
        look(camera, input);

        // L'acceleration augmente d'un cran quand on inverse le sens de rotation ou
        // quand on relache : sans cela le demi-tour tarderait trop.
        int rotAccel = rotAccelBase;
        if (targetRot == 0 || ((targetRot ^ rotSpeed) < 0)) {
            rotAccel++;
        }
        rotSpeed = approach(rotSpeed, targetRot, rotAccel);

        if (dead) {
            dying();
        }
        camera.setHeading(camera.heading() + rotSpeed);

        int previousX = camera.x();
        int previousZ = camera.z();
        int previousYFixed = yFixed;
        int previousBlock = currentBlock;

        int moveDir = camera.heading() + movingDir + aMovingDir;
        decayExtraDirection();
        moveAndCollide(camera, moveDir);

        int block = Math.abs(world.cell(camera.cellX(), camera.cellZ()));
        changedBlock = block != currentBlock;
        currentBlock = block;

        // Chute : le sol vient de se derober d'au moins une marche.
        int drop = (short) (world.floorHeight(previousBlock) - world.floorHeight(block));
        if (drop >= FALL_THRESHOLD) {
            oscCont = 24;
            falling = true;
            fallingHeight = drop;
            // bra DMout : ni morsure de sol, ni effet du bloc, ni nouvelle hauteur ce
            // pas-ci. PlayerMoved est efface : le bloc ou l'on tombe ne declenche rien.
            changedBlock = false;
        } else {
            settle();
        }

        // CtrlCollPlayerObj : heurter un objet ramene a la position precedente, et fait
        // contourner l'obstacle.
        if (hitsObject(camera, moveDir)) {
            camera.setPosition(previousX, previousZ);
            yFixed = previousYFixed;
            currentBlock = previousBlock;
            changedBlock = false;
        }
        camera.setY((yFixed >> 16) + oscY);
    }

    /**
     * {@code DMYok} : la hauteur de l'oeil rejoint {@code NewPlayerY} avec une vitesse
     * qui accelere de {@code $8000} par image jusqu'a {@code $A0000}.
     */
    private void verticalSpeed() {
        int target = newY << 16;
        int maxSpeed = 0;
        if (yFixed < target) {
            maxSpeed = MAX_SPEED_Y;
        } else if (yFixed > target) {
            maxSpeed = -MAX_SPEED_Y;
        }
        if (speedY < maxSpeed) {
            speedY += ACCEL_Y;
        } else if (speedY > maxSpeed) {
            speedY -= ACCEL_Y;
        }
        if (yFixed < target) {
            yFixed += speedY;
            if (yFixed > target) {
                speedY = 0;
                yFixed = target;
            }
        } else if (yFixed > target) {
            yFixed += speedY;
            if (yFixed < target) {
                speedY = 0;
                yFixed = target;
            }
        }
    }

    /**
     * Regard vertical : la vitesse approche quatre crans par image dans le sens tenu, et
     * le regard reste ou on le laisse. {@code CLookHeight} se deduit ensuite des crans
     * selon la hauteur de la fenetre, au rendu.
     */
    private void look(Camera camera, PlayerInput input) {
        if (input.lookReset) {
            input.lookReset = false;
            camera.setLookStep(0);
        }
        int wanted = (input.lookUp ? 1 : input.lookDown ? -1 : 0) << 2;
        if (lookSpeed < wanted) {
            lookSpeed = Math.min(lookSpeed + 1, wanted);
        } else if (lookSpeed > wanted) {
            lookSpeed = Math.max(lookSpeed - 1, wanted);
        }
        int step = camera.lookStep() + lookSpeed;
        camera.setLookStep(Math.max(-MAX_LOOK, Math.min(MAX_LOOK, step)));
    }

    /** Le joueur s'effondre : les yeux descendent de deux par image jusqu'a douze. */
    private void dying() {
        if (postDeathWait <= 0) {
            postDeathWait = POST_DEATH_WAIT;
        }
        oscCont = 0;
        if (falling) {
            return;
        }
        speed = 0;
        walkMax = 0;
        rotSpeed = 0;
        eyesHeight -= 2;
        if (eyesHeight <= DEAD_EYES_HEIGHT) {
            eyesHeight = DEAD_EYES_HEIGHT;
            postDeathWait--;
            if (postDeathWait == 0) {
                deathFinished = true;
            }
        }
    }

    private void decayExtraDirection() {
        if (aMovingDir > 0) {
            aMovingDir = Math.max(0, aMovingDir - 64);
        } else if (aMovingDir < 0) {
            aMovingDir = Math.min(0, aMovingDir + 64);
        }
    }

    /**
     * Fin du pas hors chute : nouvelle hauteur visee, fin de chute eventuelle, et
     * balancement de la marche.
     */
    private void settle() {
        newY = world.floorHeight(currentBlock) + eyesHeight;
        if (falling) {
            oscY = 0;
            if ((short) (newY - (yFixed >> 16)) >= 0) {
                falling = false;
                // Quatre points par tranche de 128 unites, au-dela de 256.
                if (fallingHeight > 256) {
                    pendingFallDamage = (fallingHeight >> 7) << 2;
                }
            }
            return;
        }
        if (speed == 0) {
            oscCont = 0;
            oscY = 0;
            return;
        }
        oscCont = (oscCont + oscSpeed(speed)) & 0x3FF;
        int phase = oscCont >> 4;
        int sample = OSC_WAVE[phase];

        // Le bruit de pas, une fois par cycle, au creux de la vague.
        boolean stepping = (speed > 0) ? phase >= 50 : phase <= 46;
        if (!stepping) {
            stepPlayed = false;
        } else if (!stepPlayed) {
            stepPlayed = true;
            stepSound = true;
        }

        int row = Math.abs(speed) & 0xFFF8;
        int index = row * 2 + 8 + sample;
        oscY = (index >= 0 && index < OSC_AMP.length) ? OSC_AMP[index] : 0;
    }

    /**
     * {@code CtrlCollObj} : un decor ou un ennemi — y compris en attente — sur la case du
     * joueur ou l'une des huit voisines, plus pres que la somme de leurs largeurs.
     *
     * <p>Le sens du contournement se lit au signe du produit vectoriel entre la direction
     * de marche et la direction de l'objet.
     */
    private boolean hitsObject(Camera camera, int moveDir) {
        int playerX = camera.x() >> 16;
        int playerZ = camera.z() >> 16;
        int cellX = Math.floorDiv(playerX, BLOCK);
        int cellZ = Math.floorDiv(playerZ, BLOCK);
        int dirX = Trig.cos(moveDir) >> 8;
        int dirZ = Trig.sin(moveDir) >> 8;
        boolean hit = false;
        for (SceneObject object : world.objects()) {
            if (object.isCollected() || object.isRemains() || object.isSelfAnimated()
                    || object.definition().objectType() >= fov.breathless.gld.object.ObjectDefinition.TYPE_PICKUP) {
                continue;
            }
            int width = object.definition().radius();
            if (width == 0) {
                continue;
            }
            if (Math.abs(Math.floorDiv(object.x(), BLOCK) - cellX) > 1
                    || Math.abs(Math.floorDiv(object.z(), BLOCK) - cellZ) > 1) {
                continue;
            }
            int dx = (short) (object.x() - playerX);
            int dz = (short) (object.z() - playerZ);
            long distance = (long) dx * dx + (long) dz * dz;
            if (distance >= (long) width * width + 2L * WIDTH * WIDTH) {
                continue;
            }
            hit = true;
            long cross = (long) dz * dirX - (long) dx * dirZ;
            aMovingDir = (cross >= 0) ? -512 : 512;
        }
        return hit;
    }

    /** Une direction opposee a la precedente annule ou inverse la vitesse acquise. */
    private void applySpeedTransition(int direction) {
        if (previousDir < 1 || previousDir > 10 || direction < 1 || direction > 10) {
            return;
        }
        int effect = SPEED_TRANSITION[direction - 1][previousDir - 1];
        if (effect > 0) {
            speed = 0;
        } else if (effect < 0) {
            speed = -speed;
        }
    }

    private static int approach(int current, int target, int step) {
        if (current < target) {
            return Math.min(current + step, target);
        }
        if (current > target) {
            return Math.max(current - step, target);
        }
        return current;
    }

    /**
     * Applique le deplacement puis recale la position contre les cases refusees.
     *
     * <p>La vitesse est exprimee en seiziemes d'unite monde, d'ou le decalage de quatre
     * bits apres multiplication par la direction en 16.16.
     */
    private void moveAndCollide(Camera camera, int angle) {
        if (speed == 0) {
            return;
        }
        int dirX = Trig.cos(angle);
        int dirZ = Trig.sin(angle);

        int x = camera.x() + (int) (((long) dirX * speed) >> 4);
        int z = camera.z() + (int) (((long) dirZ * speed) >> 4);

        int currentIndex = Math.abs(world.cell(camera.cellX(), camera.cellZ()));
        int cellX = (x >> 16) / BLOCK;
        int cellZ = (z >> 16) / BLOCK;

        int inX = (x >> 16) & (BLOCK - 1);
        int inZ = (z >> 16) & (BLOCK - 1);

        // Quels bords de case le joueur chevauche-t-il ?
        int borders = 0;
        if (inX < WIDTH) {
            borders |= 4;
        } else if (inX >= BLOCK - WIDTH) {
            borders |= 1;
        }
        if (inZ < WIDTH) {
            borders |= 8;
        } else if (inZ >= BLOCK - WIDTH) {
            borders |= 2;
        }

        int blocked = testBorders(borders, cellX, cellZ, inX, inZ, currentIndex);

        if ((blocked & 1) != 0) {
            x = (x & GRID_MASK) + ((BLOCK - WIDTH) << 16);
        } else if ((blocked & 4) != 0) {
            x = (x & GRID_MASK) + (WIDTH << 16);
        }
        if ((blocked & 2) != 0) {
            z = (z & GRID_MASK) + ((BLOCK - WIDTH) << 16);
        } else if ((blocked & 8) != 0) {
            z = (z & GRID_MASK) + (WIDTH << 16);
        }

        camera.setPosition(x, z);
    }

    /**
     * Determine quels bords refusent le passage.
     *
     * <p>Quand le joueur vise un coin, les deux cases orthogonales peuvent etre libres et
     * la diagonale non : il faut alors choisir l'axe a bloquer. Le moteur compare la
     * penetration sur chacun des deux axes et bloque celui ou elle est la plus faible.
     *
     * @return combinaison des bits 1 (est), 2 (sud), 4 (ouest) et 8 (nord)
     */
    private int testBorders(int borders, int cellX, int cellZ, int inX, int inZ, int current) {
        int blocked = 0;
        switch (borders) {
            case 1 -> blocked = test(cellX + 1, cellZ, current, 1);
            case 2 -> blocked = test(cellX, cellZ + 1, current, 2);
            case 4 -> blocked = test(cellX - 1, cellZ, current, 4);
            case 8 -> blocked = test(cellX, cellZ - 1, current, 8);
            case 3 -> {
                blocked = test(cellX + 1, cellZ, current, 1) | test(cellX, cellZ + 1, current, 2);
                if (blocked == 0 && test(cellX + 1, cellZ + 1, current, 1) != 0) {
                    blocked = (inX <= inZ) ? 1 : 2;
                }
            }
            case 6 -> {
                blocked = test(cellX - 1, cellZ, current, 4) | test(cellX, cellZ + 1, current, 2);
                if (blocked == 0 && test(cellX - 1, cellZ + 1, current, 1) != 0) {
                    blocked = ((BLOCK - 1 - inZ) > inX) ? 2 : 4;
                }
            }
            case 9 -> {
                blocked = test(cellX + 1, cellZ, current, 1) | test(cellX, cellZ - 1, current, 8);
                if (blocked == 0 && test(cellX + 1, cellZ - 1, current, 1) != 0) {
                    blocked = (inZ > (BLOCK - 1 - inX)) ? 8 : 1;
                }
            }
            case 12 -> {
                blocked = test(cellX - 1, cellZ, current, 4) | test(cellX, cellZ - 1, current, 8);
                if (blocked == 0 && test(cellX - 1, cellZ - 1, current, 1) != 0) {
                    blocked = (inZ > inX) ? 8 : 4;
                }
            }
            default -> {
                // Aucun bord chevauche, ou combinaison impossible : rien a tester.
            }
        }
        return blocked;
    }

    /**
     * Le joueur peut-il occuper cette case ?
     *
     * <p>Portage de la macro {@code TESTBLOCK}. Trois refus possibles : un mur plein, une
     * marche trop haute, ou un plafond trop bas. En descente, seule la hauteur libre de la
     * case visee compte ; en montee, c'est le plus bas des deux plafonds qui limite.
     *
     * @return {@code bit} si le passage est refuse, 0 sinon
     */
    private int test(int cellX, int cellZ, int currentIndex, int bit) {
        int code = world.cell(cellX, cellZ);
        if (code < 0) {
            return bit;
        }
        int currentFloor = world.floorHeight(currentIndex);
        int targetFloor = world.floorHeight(code);
        int rise = targetFloor - currentFloor;

        if (rise < 0) {
            return (world.ceilHeight(code) - currentFloor > CLEARANCE) ? 0 : bit;
        }
        if (rise > MAX_RISE) {
            return bit;
        }
        int ceiling = Math.min(world.ceilHeight(currentIndex), world.ceilHeight(code));
        return (ceiling - targetFloor > CLEARANCE) ? 0 : bit;
    }

}
