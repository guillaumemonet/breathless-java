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
    /** Graine fixe : deux parties identiques doivent clignoter pareil. */
    private final java.util.Random random = new java.util.Random(0x8115);
    private final int[][] blocksByTrigger = new int[TRIGGERS][];
    private final int[] runningByTrigger = new int[TRIGGERS];
    private final Slot[] slots = new Slot[MAX_ACTIVE];

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
        }
        slot.counter -= ticks;
        if (slot.counter > 0) {
            return;
        }
        teleportRequest = new int[]{slot.param1, slot.param2};
        stop(slot);
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
            if (target == null || target.status == STATE_NEW || target.param1 == 0) {
                // L'effet suivi n'est pas encore parti, ou n'a pas de course : on attend
                // le pas suivant, comme l'original qui repasse par l'initialisation.
                return;
            }
            slot.linked = target;
            slot.restoreLight = world.lightOffset(blocks[0]);
            slot.status = STATE_OUT;
        }

        Slot target = slot.linked;
        // L'emplacement peut avoir ete repris par un autre effet depuis : on verifie que
        // c'est toujours celui qu'on suit.
        if (target == null || target.effect == 0 || target.trigger != slot.param2) {
            // L'effet suivi s'est arrete : on rend l'eclairage d'origine.
            for (int block : blocks) {
                world.setLight(block, slot.restoreLight);
            }
            stop(slot);
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
        sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_SWITCH);
        trigger(block.effectList());
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

    /** Declenche une liste d'effets par son numero, 1-based. */
    public void trigger(int effectList) {
        if (effectList <= 0 || effectList > level.effectLists().size()) {
            return;
        }
        for (EffectEntry entry : level.effectLists().get(effectList - 1)) {
            if (entry.trigger() <= 0 || entry.trigger() >= TRIGGERS) {
                continue;
            }
            // Un trigger ne peut pas rejouer l'effet qu'il est deja en train de jouer.
            if (runningByTrigger[entry.trigger()] == entry.effect()) {
                continue;
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
        }
    }

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
        for (Slot slot : slots) {
            if (slot.effect != 0) {
                run(slot, ticks, playerBlock, objects);
            }
        }
    }

    private void run(Slot slot, int ticks, int playerBlock, List<SceneObject> objects) {
        // Le reveil d'ennemi vise des objets, pas des blocs : il se passe de liste.
        if (slot.effect == ACTIVE_ENEMY) {
            activateEnemies(slot, ticks, objects);
            return;
        }
        // Le terminal ne touche pas au decor : il demande une ouverture et s'arrete.
        if (slot.effect == TERMINAL) {
            terminalRequest = slot.param1;
            stop(slot);
            return;
        }

        int[] blocks = blocksByTrigger[slot.trigger];
        if (blocks == null) {
            stop(slot);
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
                stop(slot);
            }
            default -> {
                reportUnimplemented(slot.effect);
                stop(slot);
            }
        }
    }

    /** Deplacement simple : le sol ou le plafond bouge de param1 unites, et c'est tout. */
    private void oneWay(Slot slot, int ticks, int[] blocks, boolean ceiling, int direction) {
        if (slot.status == STATE_NEW) {
            slot.status = STATE_OUT;
            slot.counter = slot.param1;
        }
        int step = Math.min(ticks, slot.counter);
        move(blocks, ceiling, direction * step);
        slot.counter -= step;
        if (slot.counter <= 0) {
            stop(slot);
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
                // StartDoorSoundFX : le moteur d'origine lance ici le bruit de porte.
                sound.playGlobal(fov.breathless.gld.sound.Sound.GLOBAL_DOOR_START);
                cycleOut(slot, ticks, blocks, motion);
            }
            case STATE_OUT -> cycleOut(slot, ticks, blocks, motion);
            case STATE_PAUSE -> {
                // Le joueur present sous la porte suspend le decompte.
                if (yieldsToPlayer && contains(blocks, playerBlock)) {
                    return;
                }
                slot.counter -= ticks;
                if (slot.counter <= 0) {
                    slot.counter = slot.param1;
                    slot.status = STATE_BACK;
                }
            }
            case STATE_BACK -> {
                int step = Math.min(ticks, slot.counter);
                move(blocks, motion, -step);
                if (yieldsToPlayer && contains(blocks, playerBlock)) {
                    // Le joueur s'est glisse dessous : on remet tout en place et on attend.
                    move(blocks, motion, step);
                    return;
                }
                slot.counter -= step;
                if (slot.counter <= 0) {
                    stop(slot);
                }
            }
            default -> stop(slot);
        }
    }

    private void cycleOut(Slot slot, int ticks, int[] blocks, Motion motion) {
        int step = Math.min(ticks, slot.counter);
        move(blocks, motion, step);
        slot.counter -= step;
        if (slot.counter <= 0) {
            if (slot.param2 == 0) {
                stop(slot);   // pause nulle : reste ouvert
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
            slot.counter = slot.param1 << 8;
            slot.param2 = (slot.param2 == 0) ? slot.counter : (slot.counter / slot.param2);
            slot.fraction = 0;
        }
        int change = Math.min(slot.param2 * ticks, slot.counter);
        slot.counter -= change;

        slot.fraction += change & 0xFF;
        int whole = (change >> 8) + (slot.fraction >> 8);
        slot.fraction &= 0xFF;

        if (whole != 0) {
            for (int block : blocks) {
                world.changeLight(block, direction * whole);
            }
        }
        if (slot.counter <= 0) {
            stop(slot);
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
            slot.counter = (slot.param2 != 0) ? slot.param2 : random.nextInt(3 * 50);
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
        for (SceneObject object : objects) {
            if (object.dormantTrigger() == slot.trigger) {
                object.awaken();
                // ActiveEnemy joue le premier son de l'objet en le reveillant.
                java.util.List<String> names = object.definition().sounds();
                if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                    sound.play(names.get(0));
                }
            }
        }
        stop(slot);
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

    private void stop(Slot slot) {
        runningByTrigger[slot.trigger] = 0;
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
