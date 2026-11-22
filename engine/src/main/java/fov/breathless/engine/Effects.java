package fov.breathless.engine;

import fov.breathless.gld.level.Block;
import fov.breathless.gld.level.EffectEntry;
import fov.breathless.gld.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Portes, ascenseurs, sols mobiles et jeux de lumiere.
 *
 * <p>Portage de la seconde moitie de {@code Animations.asm}. Le mecanisme tient en trois
 * pieces :
 *
 * <ul>
 *   <li>chaque bloc porte un <em>numero de trigger</em> ; tous ceux qui partagent le meme
 *       numero bougent ensemble. C'est ainsi qu'une porte large de trois cases s'ouvre
 *       d'un seul tenant ;</li>
 *   <li>marcher sur un bloc dont le champ « liste d'effets » est non nul declenche cette
 *       liste, chaque entree visant un trigger ;</li>
 *   <li>un effet occupe un emplacement parmi {@value #MAX_ACTIVE} et avance d'un pas par
 *       cinquantieme de seconde, jusqu'a se retirer de lui-meme.</li>
 * </ul>
 *
 * <p>Une porte refuse de se refermer tant que le joueur se tient dessous : le moteur
 * restaure alors les hauteurs deja abaissees plutot que de l'ecraser.
 */
public final class Effects {

    /** Nombre d'effets simultanes, {@code MAX_EFFECT} de TMap.i. */
    public static final int MAX_ACTIVE = 42;

    /** Numeros de trigger possibles, de 1 a 255. */
    private static final int TRIGGERS = 256;

    // Codes du catalogue de l'editeur (MapEditor/FxList.h).
    private static final int CEIL_UP = 1;
    private static final int FLOOR_UP = 2;
    private static final int CEIL_DOWN = 3;
    private static final int FLOOR_DOWN = 4;
    private static final int DOOR = 5;
    private static final int DOOR2 = 6;
    private static final int LIFT_UP = 7;
    private static final int LIFT_DOWN = 8;
    private static final int LIGHT_UP = 9;
    private static final int LIGHT_DOWN = 10;
    private static final int TERMINAL = 11;
    private static final int DOOR_DOWN = 12;
    private static final int LINKED_LIGHT = 13;
    private static final int END_LEVEL = 14;
    private static final int TELEPORT = 15;
    private static final int BLINKING_LIGHT = 16;
    private static final int ACTIVE_ENEMY = 17;

    /** Etats d'un cycle aller/pause/retour. */
    private static final int STATE_NEW = 0;
    private static final int STATE_OUT = 1;
    private static final int STATE_PAUSE = 2;
    private static final int STATE_BACK = 3;

    /**
     * Effet termine, a retirer au passage suivant, et dont le trigger peut repartir.
     * {@code StopEffect1}.
     */
    private static final int STATE_ENDED = -1;

    /**
     * Effet termine pour de bon : son trigger ne pourra plus le rejouer.
     * {@code StopEffect2} — un plafond qui descend ne redescend pas a chaque passage.
     */
    private static final int STATE_ENDED_FOR_GOOD = -2;

    private static final class Slot {
        int effect;
        int trigger;
        int status;
        int param1;
        int param2;
        int counter;
        int fraction;
        /** Eclairage d'origine, memorise pour pouvoir y revenir. */
        int restoreLight;
        /** Effet suivi par une lumiere liee, ou {@code null}. */
        Slot linked;
    }

    private final World world;
    private final Level level;
    /** Le generateur partage du moteur : les neons tirent dans la meme suite que les ennemis. */
    private Rnd rnd = new Rnd();

    /** Partage le generateur du niveau. */
    public void setRnd(Rnd rnd) {
        this.rnd = rnd;
    }
    private final int[][] blocksByTrigger = new int[TRIGGERS][];
    private final int[] runningByTrigger = new int[TRIGGERS];
    private final Slot[] slots = new Slot[MAX_ACTIVE];

    /**
     * Un ennemi se tient sur un bloc du trigger. Bit 15 du mot de commande de
     * {@code TriggerBlockListPun} : une porte qui se ferme se rouvre, une porte en pause
     * attend.
     */
    private final boolean[] enemyOnTrigger = new boolean[TRIGGERS];

    /** Etat du joueur, pour les cles. {@code null} : aucune cle n'est exigee. */
    private PlayerState player;

    /** Message demande par l'effet, indice de la table {@code Messages}, ou -1. */
    private int messageRequest = -1;

    /** Le message d'une cle manquante : {@code addq.w #3,d1}, cle comptee de 1 a 4. */
    public static final int MESSAGE_KEY_BASE = 3;

    /** Effets rencontres mais pas encore portes, pour ne les signaler qu'une fois. */
    private final boolean[] reported = new boolean[32];

    public Effects(World world) {
        this.world = world;
        this.level = world.level();
        for (int i = 0; i < MAX_ACTIVE; i++) {
            slots[i] = new Slot();
        }
        buildTriggerLists();
    }

    /**
     * Un bloc appartient a un trigger par son champ principal <em>ou</em> par son champ
     * secondaire : les deux designent le meme groupe.
     */
    private void buildTriggerLists() {
        List<List<Integer>> lists = new ArrayList<>(TRIGGERS);
        for (int i = 0; i < TRIGGERS; i++) {
            lists.add(null);
        }
        for (int index = 0; index < level.blocks().size(); index++) {
            Block block = level.blocks().get(index);
            add(lists, block.trigger(), index);
            if (block.trigger2() != block.trigger()) {
                add(lists, block.trigger2(), index);
            }
        }
        for (int trigger = 0; trigger < TRIGGERS; trigger++) {
            List<Integer> list = lists.get(trigger);
            if (list == null) {
                continue;
            }
            int[] blocks = new int[list.size()];
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = list.get(i);
            }
            blocksByTrigger[trigger] = blocks;
        }
    }

    private static void add(List<List<Integer>> lists, int trigger, int blockIndex) {
        if (trigger <= 0 || trigger >= TRIGGERS) {
            return;
        }
        if (lists.get(trigger) == null) {
            lists.set(trigger, new ArrayList<>());
        }
        lists.get(trigger).add(blockIndex);
    }

    /**
     * Declenche la liste d'effets du bloc ou le joueur vient d'entrer.
     *
     * <p>Un bloc dont les attributs portent un interrupteur est ignore : son effet
     * s'active a la barre d'espace, face a l'arete concernee, et non en marchant dessus.
     */
    public void enterBlock(int blockIndex) {
        Block block = level.blocks().get(blockIndex);
        if (block.effectList() == 0 || (block.attributes() & 0xF0) != 0) {
            return;
        }
        trigger(block.effectList());
    }

    /** Duree de la teleportation, en cinquantiemes. {@code Teleport} dans Animations.asm. */
    private static final int TELEPORT_TICKS = 32;

    /** Numero du terminal a ouvrir, ou -1. Releve puis efface par la simulation. */
    private int terminalRequest = -1;
    private SoundPlayer sound = SoundPlayer.SILENT;

    /** Branche le son. Sans cela les effets se jouent en silence. */
    public void setSoundPlayer(SoundPlayer player) {
        this.sound = (player == null) ? SoundPlayer.SILENT : player;
    }

    /** Branche l'etat du joueur : sans lui, les portes a cle s'ouvrent sans cle. */
    public void setPlayerState(PlayerState state) {
        this.player = state;
    }

    /**
     * Le message que l'effet demande d'afficher — une cle manquante —, ou -1.
     *
     * <p>L'appel efface la demande.
     */
    public int takeMessageRequest() {
        int request = messageRequest;
        messageRequest = -1;
        return request;
    }

    /** {@code StartDoorSoundFX} : le bruit de porte, sur le canal du trigger. */
    private void startDoorSound(Slot slot) {
        sound.playGlobalOn(fov.breathless.gld.sound.Sound.GLOBAL_DOOR_START, slot.trigger);
    }

    /** {@code StopDoorSoundFX} : le choc de fin de course, sur le meme canal. */
    private void stopDoorSound(Slot slot) {
        sound.playGlobalOn(fov.breathless.gld.sound.Sound.GLOBAL_DOOR_STOP, slot.trigger);
    }

    /** Case visee par une teleportation en attente, ou {@code null}. */
    private int[] teleportRequest;

    /** Le niveau vient-il d'etre acheve ? */
    private boolean levelFinished;

    /**
     * La case ou une teleportation vient d'aboutir, ou {@code null}.
     *
     * <p>L'appel efface la demande : elle ne vaut qu'une fois.
     */
    public int[] takeTeleportRequest() {
        int[] request = teleportRequest;
        teleportRequest = null;
        return request;
    }

    /**
     * Le niveau est-il termine ?
     *
     * <p>{@code EndLevel} ne fait qu'une chose : lever le drapeau de sortie. L'appel
     * efface la reponse.
     */
    public boolean takeLevelFinished() {
        boolean finished = levelFinished;
        levelFinished = false;
        return finished;
    }

    /**
     * Teleporte le joueur apres un temps mort.
     *
     * <p>{@code Teleport} : trente-deux cinquantiemes d'attente — le temps du fondu dans
     * l'original — puis le joueur se retrouve au centre de la case donnee par les deux
     * parametres de l'effet.
     */
    private void teleport(Slot slot, int ticks) {
        if (slot.status == STATE_NEW) {
            slot.status = STATE_OUT;
            slot.counter = TELEPORT_TICKS;
            sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_TELEPORT);
            fade.teleport();
        }
        slot.counter -= ticks;
        if (slot.counter > 0) {
            return;
        }
        teleportRequest = new int[]{slot.param1, slot.param2};
        end(slot, STATE_ENDED);
    }

    /** Le fondu de l'image, partage avec la simulation. */
    private Fade fade = new Fade();

    /** Partage le fondu de la simulation : la teleportation le declenche. */
    public void setFade(Fade fade) {
        this.fade = fade;
    }

    /**
     * Une teleportation gele-t-elle le jeu ?
     *
     * <p>{@code Teleport} met {@code ProgramState} a -1, et seule la fin du fondu le
     * remet : le joueur et les ennemis restent figes tout le temps du fondu, aller et
     * retour, et pas seulement jusqu'au deplacement.
     */
    public boolean isTeleporting() {
        return fade.freezesGame();
    }

    /**
     * Lie l'eclairage de blocs a l'avancement d'un autre effet.
     *
     * <p>{@code LinkedLight} : le deuxieme parametre designe le trigger de l'effet a
     * suivre, qui doit appartenir a la meme liste et etre deja lance ; le premier donne
     * l'ecart de luminosite correspondant a la course complete de cet effet. Une porte qui
     * s'ouvre eclaire ainsi la piece a mesure qu'elle monte.
     */
    private void linkedLight(Slot slot, int[] blocks) {
        if (slot.status == STATE_NEW) {
            Slot target = running(slot.param2);
            if (target == null || target.status == STATE_NEW) {
                // L'effet suivi n'est pas encore parti : on attend le pas suivant, comme
                // l'original qui repasse par l'initialisation.
                return;
            }
            if (target.param1 == 0) {
                // Course nulle : rien a suivre, et l'original s'arrete pour de bon plutot
                // que de diviser par zero.
                end(slot, STATE_ENDED_FOR_GOOD);
                return;
            }
            slot.linked = target;
            slot.restoreLight = world.lightOffset(blocks[0]);
            slot.status = STATE_OUT;
        }

        Slot target = slot.linked;
        // Un effet termine reste un passage avec son statut negatif avant d'etre retire :
        // c'est la qu'on lit comment il a fini. L'emplacement repris par un autre effet ne
        // devrait pas arriver, mais on le traite comme une fin ordinaire.
        boolean gone = target == null || target.effect == 0 || target.trigger != slot.param2;
        if (gone || target.status < 0) {
            if (!gone && target.status == STATE_ENDED_FOR_GOOD) {
                // LLend : l'effet suivi est bloque definitivement — une porte qui reste
                // ouverte. La piece garde la lumiere qu'elle a, et l'on s'arrete aussi pour
                // de bon.
                end(slot, STATE_ENDED_FOR_GOOD);
                return;
            }
            for (int block : blocks) {
                world.setLight(block, slot.restoreLight);
            }
            end(slot, STATE_ENDED);
            return;
        }

        int travelled = switch (target.status) {
            case STATE_OUT -> target.param1 - target.counter;
            case STATE_BACK -> target.counter;
            default -> -1;      // en pause : la lumiere ne bouge pas
        };
        if (travelled < 0) {
            return;
        }
        int change = travelled * slot.param1 / target.param1;
        for (int block : blocks) {
            world.setLight(block, slot.restoreLight - change);
        }
    }

    /** L'effet en cours portant ce trigger, ou {@code null}. */
    private Slot running(int trigger) {
        for (Slot slot : slots) {
            if (slot.effect != 0 && slot.trigger == trigger) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Le numero du terminal que l'effet vient de demander, ou -1.
     *
     * <p>L'appel efface la demande : elle ne vaut qu'une fois.
     */
    public int takeTerminalRequest() {
        int request = terminalRequest;
        terminalRequest = -1;
        return request;
    }

    /** Ecart maximal au cardinal pour qu'un interrupteur soit considere comme vise. */
    private static final int AIM_TOLERANCE = 224;

    /**
     * Actionne l'interrupteur que le joueur a devant lui.
     *
     * <p>Portage de {@code SwitchManagement} (Sorgenti/movement.asm). Le joueur doit faire
     * face a l'un des quatre cardinaux, a moins de {@value #AIM_TOLERANCE} pas pres — soit
     * environ trente-neuf degres. On regarde alors la case adjacente dans cette direction :
     * si le bloc qui l'occupe porte le bit d'interrupteur de l'arete tournee vers nous, sa
     * texture bascule et <strong>sa</strong> liste d'effets se declenche.
     *
     * <p>Deux tiers des declencheurs des vingt niveaux fonctionnent ainsi : sans cela,
     * l'essentiel des portes reste ferme.
     *
     * @return {@code true} si un interrupteur a repondu
     */
    public boolean useSwitch(Camera camera) {
        int heading = camera.heading();

        int stepX;
        int stepZ;
        int bit;
        int edgeNumber;

        if (aimsAt(heading, 1024)) {            // vers l'ouest : face est du bloc d'en face
            stepX = -1;
            stepZ = 0;
            bit = 0x10;
            edgeNumber = 1;
        } else if (aimsAt(heading, 512)) {
            stepX = 0;
            stepZ = 1;
            bit = 0x80;
            edgeNumber = 4;
        } else if (aimsAt(heading, 1536)) {
            stepX = 0;
            stepZ = -1;
            bit = 0x20;
            edgeNumber = 2;
        } else if (aimsAt(heading, 0)) {
            stepX = 1;
            stepZ = 0;
            bit = 0x40;
            edgeNumber = 3;
        } else {
            return false;                       // regard trop oblique
        }

        int index = Math.abs(world.cell(camera.cellX() + stepX, camera.cellZ() + stepZ));
        if (index == 0) {
            return false;
        }
        Block block = level.blocks().get(index);
        if ((block.attributes() & bit) == 0) {
            return false;                       // ce cote-la ne porte pas d'interrupteur
        }

        world.toggleEdge(block.edge(edgeNumber));
        trigger(block.effectList(), true);
        return true;
    }

    /** Le cap vise-t-il ce cardinal a la tolerance pres ? */
    private static boolean aimsAt(int heading, int cardinal) {
        int difference = ((heading - cardinal + Trig.STEPS + Trig.STEPS / 2) & Trig.MASK)
                - Trig.STEPS / 2;
        return Math.abs(difference) <= AIM_TOLERANCE;
    }

    /** Nombre de fois qu'un effet non porte s'est presente. */
    private int unimplemented;

    /**
     * Combien de fois un effet non porte s'est presente depuis le chargement.
     *
     * <p>Le moteur d'origine en decrit dix-sept, et ils le sont tous desormais : un test
     * joue toutes les listes des vingt niveaux et verifie que ce compte reste nul.
     */
    public int unimplementedCount() {
        return unimplemented;
    }

    /** Declenche une liste d'effets par son numero, 1-based, comme le fait le sol. */
    public void trigger(int effectList) {
        trigger(effectList, false);
    }

    /** Declenche une liste comme le fait un interrupteur : cles exigees. */
    void triggerFromSwitch(int effectList) {
        trigger(effectList, true);
    }

    /**
     * Declenche une liste d'effets.
     *
     * <p>Deux chemins dans l'original, qui ne different que sur deux points. Celui de
     * l'interrupteur ({@code SwitchManagement}) exige la cle que porte l'entree — sans
     * elle, l'effet est annule, le message « cle requise » s'affiche et le reste de la
     * liste est abandonne ; avec elle, la cle est consommee — et joue le clic pour chaque
     * effet reellement lance. Celui du sol ({@code DoMovement}) ignore la cle.
     *
     * <p>Un trigger ne peut pas rejouer l'effet qu'il est en train de jouer, ni celui
     * qu'il a fini pour de bon : {@code StopEffect2} ne lui rend jamais la main.
     */
    private void trigger(int effectList, boolean bySwitch) {
        if (effectList <= 0 || effectList > level.effectLists().size()) {
            return;
        }
        for (EffectEntry entry : level.effectLists().get(effectList - 1)) {
            if (entry.trigger() <= 0 || entry.trigger() >= TRIGGERS) {
                continue;
            }
            if (runningByTrigger[entry.trigger()] == entry.effect()) {
                continue;
            }
            if (bySwitch && entry.key() >= 1 && entry.key() <= 4 && player != null) {
                int key = PlayerState.PICKUP_GREEN_KEY + entry.key() - 1;
                if (!player.hasKey(key)) {
                    messageRequest = MESSAGE_KEY_BASE + entry.key();
                    return;
                }
                usedKeys[entry.key() - 1] = true;
            }
            Slot slot = freeSlot();
            if (slot == null) {
                return;   // les quarante-deux emplacements sont pris
            }
            slot.effect = entry.effect();
            slot.trigger = entry.trigger();
            slot.status = STATE_NEW;
            slot.param1 = entry.param1();
            slot.param2 = entry.param2();
            slot.counter = 0;
            slot.fraction = 0;
            slot.linked = null;
            runningByTrigger[entry.trigger()] = entry.effect();
            if (bySwitch) {
                sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SWITCH);
            }
        }
        if (!bySwitch || player == null) {
            return;
        }
        // SMtstkloop : les cles marquees ne sont retirees qu'une fois la liste parcourue
        // jusqu'au bout. Une liste abandonnee faute de cle les laisse marquees : elles
        // partiront a la prochaine liste menee a terme.
        for (int key = 0; key < usedKeys.length; key++) {
            if (usedKeys[key]) {
                usedKeys[key] = false;
                player.dropKey(PlayerState.PICKUP_GREEN_KEY + key);
            }
        }
    }

    /** {@code GreenKeyFL} et suivants : cles employees, en attente d'etre retirees. */
    private final boolean[] usedKeys = new boolean[4];

    private Slot freeSlot() {
        for (Slot slot : slots) {
            if (slot.effect == 0) {
                return slot;
            }
        }
        return null;
    }

    /** Nombre d'effets en cours. */
    public int activeCount() {
        int count = 0;
        for (Slot slot : slots) {
            if (slot.effect != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Avance tous les effets.
     *
     * @param ticks       cinquantiemes de seconde ecoules, normalement 1
     * @param playerBlock bloc occupe par le joueur, qui empeche une porte de se refermer
     * @param objects     objets du niveau, pour le reveil des ennemis
     */
    public void update(int ticks, int playerBlock, List<SceneObject> objects) {
        markObjectsOnTriggers(objects);
        for (Slot slot : slots) {
            if (slot.effect == 0) {
                continue;
            }
            if (slot.status < 0) {
                remove(slot);
                continue;
            }
            run(slot, ticks, playerBlock, objects);
        }
    }

    /**
     * Releve les ennemis debout et les restes poses sur des blocs a trigger.
     *
     * <p>{@code MNnext} pose le bit 15 du mot de commande pour chaque ennemi qui se tient
     * sur un tel bloc ; {@code AWnext} l'efface apres chaque passe. Les restes d'un ennemi
     * mort y inscrivent leur numero, pour que la porte les fasse disparaitre en se
     * fermant ({@code RemoveRemains}). Le releve se refait donc a chaque pas.
     */
    private void markObjectsOnTriggers(List<SceneObject> objects) {
        java.util.Arrays.fill(enemyOnTrigger, false);
        if (objects == null) {
            return;
        }
        for (SceneObject object : objects) {
            if (object.isCollected() || object.isDormant()) {
                continue;
            }
            boolean enemy = !object.isRemains() && object.definition().objectType()
                    == fov.breathless.gld.object.ObjectDefinition.TYPE_ENEMY;
            if (!enemy && !object.isRemains()) {
                continue;
            }
            int code = world.cell(Math.floorDiv(object.x(), Level.BLOCK_SIZE),
                    Math.floorDiv(object.z(), Level.BLOCK_SIZE));
            if (code == 0) {
                continue;
            }
            int trigger = level.blocks().get(Math.abs(code)).trigger();
            if (trigger <= 0 || trigger >= TRIGGERS) {
                continue;
            }
            if (enemy) {
                enemyOnTrigger[trigger] = true;
            }
        }
    }

    /**
     * {@code RemoveRemains} : une porte qui vient de se fermer fait disparaitre les restes
     * restes pris sous elle.
     */
    private void removeRemains(int trigger, List<SceneObject> objects) {
        if (objects == null) {
            return;
        }
        for (SceneObject object : objects) {
            if (!object.isRemains() || object.isCollected()) {
                continue;
            }
            int code = world.cell(Math.floorDiv(object.x(), Level.BLOCK_SIZE),
                    Math.floorDiv(object.z(), Level.BLOCK_SIZE));
            if (code != 0 && level.blocks().get(Math.abs(code)).trigger() == trigger) {
                object.collect();
            }
        }
    }

    /** Liste des objets du pas en cours, pour les portes qui se ferment. */
    private List<SceneObject> currentObjects;

    private void run(Slot slot, int ticks, int playerBlock, List<SceneObject> objects) {
        currentObjects = objects;
        // Le reveil d'ennemi vise des objets, pas des blocs : il se passe de liste.
        if (slot.effect == ACTIVE_ENEMY) {
            activateEnemies(slot, ticks, objects);
            return;
        }
        // Le terminal ne touche pas au decor : il demande une ouverture et s'arrete.
        if (slot.effect == TERMINAL) {
            terminalRequest = slot.param1;
            end(slot, STATE_ENDED);
            return;
        }

        int[] blocks = blocksByTrigger[slot.trigger];
        if (blocks == null) {
            end(slot, STATE_ENDED);
            return;
        }

        switch (slot.effect) {
            case CEIL_UP -> oneWay(slot, ticks, blocks, true, +1);
            case FLOOR_UP -> oneWay(slot, ticks, blocks, false, +1);
            case CEIL_DOWN -> oneWay(slot, ticks, blocks, true, -1);
            case FLOOR_DOWN -> oneWay(slot, ticks, blocks, false, -1);
            case DOOR -> cycle(slot, ticks, blocks, Motion.DOOR_OPENS, playerBlock, true);
            case DOOR2 -> cycle(slot, ticks, blocks, Motion.DOUBLE_DOOR_OPENS, playerBlock, true);
            case DOOR_DOWN -> cycle(slot, ticks, blocks, Motion.FLOOR_SINKS, playerBlock, true);
            case LIFT_UP -> cycle(slot, ticks, blocks, Motion.FLOOR_RISES, playerBlock, false);
            case LIFT_DOWN -> cycle(slot, ticks, blocks, Motion.FLOOR_SINKS, playerBlock, false);
            case BLINKING_LIGHT -> blink(slot, ticks, blocks);
            case LIGHT_UP -> light(slot, ticks, blocks, +1);
            case LIGHT_DOWN -> light(slot, ticks, blocks, -1);
            case LINKED_LIGHT -> linkedLight(slot, blocks);
            case TELEPORT -> teleport(slot, ticks);
            case END_LEVEL -> {
                levelFinished = true;
                end(slot, STATE_ENDED_FOR_GOOD);
            }
            default -> {
                reportUnimplemented(slot.effect);
                end(slot, STATE_ENDED);
            }
        }
    }

    /**
     * Deplacement simple : le sol ou le plafond bouge de param1 unites, et c'est tout.
     *
     * <p>Definitif : {@code StopEffect2}. Repasser sur la dalle ne le rejoue pas.
     */
    private void oneWay(Slot slot, int ticks, int[] blocks, boolean ceiling, int direction) {
        if (slot.status == STATE_NEW) {
            slot.status = STATE_OUT;
            slot.counter = slot.param1;
        }
        int step = Math.min(ticks, slot.counter);
        move(blocks, ceiling, direction * step);
        slot.counter -= step;
        if (slot.counter <= 0) {
            end(slot, STATE_ENDED_FOR_GOOD);
        }
    }

    /**
     * Cycle aller / pause / retour : portes et ascenseurs.
     *
     * <p>Une pause nulle laisse l'effet ouvert definitivement.
     *
     * <p>Les deux familles ne se comportent pas pareil devant le joueur. Une porte ne se
     * referme pas sur lui : {@code DUdown} et ses jumelles comparent chaque bloc au sien et
     * remettent tout en place s'il est dessous. Un ascenseur, lui, n'a <em>aucun</em> test
     * de ce genre — {@code FloorUpDown} et {@code FloorDownUp} bougent leur plancher quoi
     * qu'il arrive, ce qui est bien le moindre pour un ascenseur : lui faire refuser de
     * bouger sous le joueur le bloque des qu'on monte dessus.
     *
     * @param yieldsToPlayer vrai pour une porte, faux pour un ascenseur
     */
    private void cycle(Slot slot, int ticks, int[] blocks, Motion motion, int playerBlock,
                       boolean yieldsToPlayer) {
        switch (slot.status) {
            case STATE_NEW -> {
                slot.status = STATE_OUT;
                slot.counter = slot.param1;
                startDoorSound(slot);
                cycleOut(slot, ticks, blocks, motion);
            }
            case STATE_OUT -> cycleOut(slot, ticks, blocks, motion);
            case STATE_PAUSE -> {
                // Le joueur ou un ennemi sous la porte suspend le decompte. Un ascenseur
                // n'a pas ce test : FUDpause decompte quoi qu'il arrive.
                if (yieldsToPlayer
                        && (contains(blocks, playerBlock) || enemyOnTrigger[slot.trigger])) {
                    return;
                }
                slot.counter -= ticks;
                if (slot.counter <= 0) {
                    slot.counter = slot.param1;
                    slot.status = STATE_BACK;
                    startDoorSound(slot);
                }
            }
            case STATE_BACK -> {
                if (yieldsToPlayer && enemyOnTrigger[slot.trigger]) {
                    // DUenemystop : un ennemi passe dessous, la porte repart vers le haut.
                    reopen(slot);
                    return;
                }
                int step = Math.min(ticks, slot.counter);
                move(blocks, motion, -step);
                if (yieldsToPlayer && contains(blocks, playerBlock)) {
                    // DUplayerstop : le joueur s'est glisse dessous. Le pas est annule et
                    // la porte se rouvre entierement, puis refait sa pause.
                    move(blocks, motion, step);
                    reopen(slot);
                    return;
                }
                slot.counter -= step;
                if (slot.counter <= 0) {
                    stopDoorSound(slot);
                    if (yieldsToPlayer) {
                        removeRemains(slot.trigger, currentObjects);
                    }
                    end(slot, STATE_ENDED);
                }
            }
            default -> end(slot, STATE_ENDED);
        }
    }

    /**
     * La porte repart dans l'autre sens pour la course deja refaite.
     *
     * <p>{@code move.w #1,ef_status / ef_var1 = param1 - ef_var1}.
     */
    private static void reopen(Slot slot) {
        slot.status = STATE_OUT;
        slot.counter = slot.param1 - slot.counter;
    }

    private void cycleOut(Slot slot, int ticks, int[] blocks, Motion motion) {
        int step = Math.min(ticks, slot.counter);
        move(blocks, motion, step);
        slot.counter -= step;
        if (slot.counter <= 0) {
            stopDoorSound(slot);
            if (slot.param2 == 0) {
                // Pause nulle : la porte reste ouverte, et pour de bon.
                end(slot, STATE_ENDED_FOR_GOOD);
                return;
            }
            slot.counter = slot.param2;
            slot.status = STATE_PAUSE;
        }
    }

    /**
     * Variation d'eclairage de param1 niveaux etalee sur param2 cinquantiemes.
     * La vitesse est en 8.8, d'ou la partie fractionnaire conservee d'un pas a l'autre.
     */
    private void light(Slot slot, int ticks, int[] blocks, int direction) {
        if (slot.status == STATE_NEW) {
            slot.status = STATE_OUT;
            slot.counter = (slot.param1 << 8) & 0xFFFF;
            slot.param2 = (slot.param2 == 0) ? slot.counter : (slot.counter / slot.param2);
            slot.fraction = 0;
        }
        int change = Math.min(Math.min(slot.param2 * ticks, 0x7FFF), slot.counter);
        int consumed = change;

        // sub.b d2,ef_var2 / bcc / add.w #$100,d2 : la retenue de la partie decimale
        // ajoute une unite a la partie entiere.
        int low = change & 0xFF;
        if ((slot.fraction & 0xFF) < low) {
            change += 0x100;
        }
        slot.fraction = (slot.fraction - low) & 0xFF;
        int whole = (change & 0xFFFF) >>> 8;

        // Dans le moteur, une valeur d'eclairage plus haute est plus sombre : LightUp
        // soustrait, LightDown ajoute. L'octet est lu sans signe, et un debordement dans
        // un sens comme dans l'autre le ramene a zero.
        for (int block : blocks) {
            int value = world.lightOffset(block) & 0xFF;
            value = (direction > 0) ? value - whole : value + whole;
            if (value < 0 || value > 0xFF) {
                value = 0;
            }
            world.setLight(block, (byte) value);
        }
        slot.counter -= consumed;
        if (slot.counter <= 0) {
            end(slot, STATE_ENDED_FOR_GOOD);
        }
    }

    /**
     * Lumiere clignotante.
     *
     * <p>Alterne entre l'eclairage d'origine et celui de param1. La phase allumee dure dix
     * cinquantiemes ; la phase eteinte dure param2, ou une duree tiree au hasard entre zero
     * et trois secondes lorsque param2 vaut zero — c'est ce qui donne aux neons defectueux
     * leur battement irregulier.
     */
    private void blink(Slot slot, int ticks, int[] blocks) {
        if (slot.status == STATE_NEW) {
            slot.status = STATE_OUT;
            slot.counter = 50;
            slot.restoreLight = world.lightOffset(blocks[0]);
        }
        slot.counter -= ticks;
        if (slot.counter > 0) {
            return;
        }

        int value;
        if (slot.status == STATE_OUT) {
            value = slot.param1;
            slot.status = STATE_PAUSE;
            slot.counter = 10;
        } else {
            value = slot.restoreLight;
            slot.status = STATE_OUT;
            slot.counter = (slot.param2 != 0) ? slot.param2 : rnd.next(3 * 50);
        }
        for (int block : blocks) {
            world.setLight(block, value);
        }
    }

    /** Reveille les ennemis qui attendaient ce numero de trigger. */
    private void activateEnemies(Slot slot, int ticks, List<SceneObject> objects) {
        if (slot.status == STATE_NEW) {
            slot.status = STATE_OUT;
            slot.counter = slot.param1;
        }
        slot.counter -= ticks;
        if (slot.counter > 0) {
            return;
        }
        // ActiveEnemy ne parcourt que la liste des ennemis : un ramassable porteur d'un
        // trigger n'a jamais ete en attente, voir Simulation.collectPickups.
        for (SceneObject object : objects) {
            if (object.dormantTrigger() == slot.trigger && object.isDormant()
                    && object.definition().objectType()
                    == fov.breathless.gld.object.ObjectDefinition.TYPE_ENEMY) {
                object.awaken();
                // ObjBufferedPlaySoundFX : le premier son de l'objet, depuis sa position.
                java.util.List<String> names = object.definition().sounds();
                if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                    sound.playAt(names.get(0), object.x(), object.z());
                }
            }
        }
        end(slot, STATE_ENDED_FOR_GOOD);
    }

    private void move(int[] blocks, boolean ceiling, int delta) {
        if (delta == 0) {
            return;
        }
        for (int block : blocks) {
            if (ceiling) {
                world.moveCeiling(block, delta);
            } else {
                world.moveFloor(block, delta);
            }
        }
    }

    /**
     * Deplace le plafond et le sol d'un meme pas.
     *
     * <p>Les deux ne sont pas exclusifs : une porte a deux battants ecarte le plafond et le
     * sol en meme temps.
     */
    private void move(int[] blocks, Motion motion, int step) {
        move(blocks, true, motion.ceiling() * step);
        move(blocks, false, motion.floor() * step);
    }

    /**
     * Ce qu'un effet fait bouger, et dans quel sens, par pas d'ouverture.
     *
     * <p>Le moteur d'origine ne donne pas a chaque effet une surface et un sens, mais une
     * paire de deplacements : {@code Door2} — la porte « a due ante » — leve le plafond
     * <em>et</em> abaisse le sol, et {@code DoorDown} touche le sol seul la ou une porte
     * ordinaire touche le plafond seul.
     *
     * @param ceiling deplacement du plafond a l'ouverture, en pas
     * @param floor   deplacement du sol a l'ouverture, en pas
     */
    private record Motion(int ceiling, int floor) {

        /** Porte ordinaire, {@code DoorUp} : le plafond se leve. */
        static final Motion DOOR_OPENS = new Motion(+1, 0);

        /** Porte a deux battants, {@code Door2} : le plafond se leve et le sol s'abaisse. */
        static final Motion DOUBLE_DOOR_OPENS = new Motion(+1, -1);

        /** {@code DoorDown} et {@code FloorDownUp} : le sol s'abaisse. */
        static final Motion FLOOR_SINKS = new Motion(0, -1);

        /** {@code FloorUpDown} : le sol se leve. */
        static final Motion FLOOR_RISES = new Motion(0, +1);
    }

    private static boolean contains(int[] blocks, int blockIndex) {
        for (int block : blocks) {
            if (block == blockIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Termine un effet. Il reste un passage avec son statut negatif — c'est la que les
     * lumieres liees lisent comment il a fini — puis {@link #remove} le retire.
     *
     * @param how {@link #STATE_ENDED} ou {@link #STATE_ENDED_FOR_GOOD}
     */
    private static void end(Slot slot, int how) {
        slot.status = how;
    }

    /**
     * {@code RemoveEffect} : libere l'emplacement, et ne rend la main au trigger que si
     * l'effet n'a pas fini pour de bon.
     */
    private void remove(Slot slot) {
        if (slot.status != STATE_ENDED_FOR_GOOD) {
            runningByTrigger[slot.trigger] = 0;
        }
        slot.effect = 0;
        slot.status = STATE_NEW;
        slot.linked = null;
    }

    /**
     * Les effets de logique de jeu — terminal, fin de niveau, teleportation — et les
     * lumieres liees ou clignotantes ne sont pas encore portes. On le signale une fois par
     * code plutot qu'a chaque declenchement.
     */
    private void reportUnimplemented(int effect) {
        unimplemented++;
        if (effect < 0 || effect >= reported.length || reported[effect]) {
            return;
        }
        reported[effect] = true;
        System.out.println("Effet non encore porte : " + effect + " (" + effectName(effect) + ")");
    }

    private static String effectName(int effect) {
        return switch (effect) {
            case TERMINAL -> "terminal";
            case LINKED_LIGHT -> "lumiere liee";
            case END_LEVEL -> "fin de niveau";
            case TELEPORT -> "teleportation";
            default -> "inconnu";
        };
    }
}
