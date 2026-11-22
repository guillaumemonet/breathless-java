package fov.breathless.game;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.event.TouchEvent;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les commandes d'un ecran tactile.
 *
 * <p>L'original se joue au clavier ; un telephone n'en a pas. L'image garde ses
 * proportions au centre, et les bandes laterales recoivent :
 *
 * <ul>
 *   <li>a gauche, un <strong>joystick</strong> : vers le haut on avance, vers le bas on
 *       recule, sur les cotes on tourne ; pousse a fond, on court ;</li>
 *   <li>a droite, les <strong>boutons</strong> : tir, action, arme suivante, carte,
 *       menu.</li>
 * </ul>
 *
 * <p>Sur un ecran fixe ou dans un menu, le joystick deplace le choix et une touche
 * n'importe ou dans l'image valide. Tout passe par les memes commandes que le clavier :
 * le jeu ne sait pas d'ou elles viennent.
 */
final class TouchControls {

    /** Un bouton : sa commande, son libelle, son rectangle. */
    private static final class Button {
        final String action;
        final String label;
        float x;
        float y;
        float w;
        float h;
        Geometry quad;
        BitmapText text;

        Button(String action, String label) {
            this.action = action;
            this.label = label;
        }

        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private final BreathlessApp app;
    private final AssetManager assets;
    private final BitmapFont font;
    private final Node root = new Node("commandes-tactiles");
    private final List<Button> buttons = new ArrayList<>();

    /** Doigt pose sur chaque bouton, par identifiant de pointeur. */
    private final Map<Integer, Button> pressed = new HashMap<>();

    // Le joystick : son centre, son rayon, le doigt qui le tient.
    private float stickX;
    private float stickY;
    private float stickRadius;
    private int stickPointer = -1;
    private Geometry stickBase;
    private Geometry stickKnob;

    /** Directions actuellement tenues par le joystick. */
    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean run;

    /** Doigts poses dans l'image, qui valideront en se levant. */
    private final Map<Integer, Boolean> taps = new HashMap<>();

    TouchControls(BreathlessApp app, AssetManager assets, BitmapFont font, Node gui) {
        this.app = app;
        this.assets = assets;
        this.font = font;
        buttons.add(new Button("tirer", "TIR"));
        buttons.add(new Button("actionner", "ACTION"));
        buttons.add(new Button("arme-suivante", "ARME"));
        buttons.add(new Button("carte", "CARTE"));
        buttons.add(new Button("menu", "MENU"));
        gui.attachChild(root);
    }

    private Geometry quad(String name, float w, float h, ColorRGBA colour) {
        Geometry geometry = new Geometry(name, new Quad(w, h));
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", colour);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        geometry.setMaterial(material);
        geometry.setQueueBucket(RenderQueue.Bucket.Gui);
        return geometry;
    }

    /**
     * Place les commandes.
     *
     * @param side largeur des bandes laterales ; trop etroites, les commandes se posent
     *             en transparence sur l'image
     */
    void layout(int width, int height, float side) {
        root.detachAllChildren();
        float band = Math.max(side, width * 0.2f);

        // Le joystick, au centre de la bande gauche, un peu bas.
        stickRadius = Math.min(band * 0.4f, height * 0.25f);
        stickX = band / 2f;
        stickY = height * 0.35f;
        stickBase = quad("joystick", stickRadius * 2, stickRadius * 2, new ColorRGBA(1, 1, 1, 0.12f));
        stickBase.setLocalTranslation(stickX - stickRadius, stickY - stickRadius, 2);
        root.attachChild(stickBase);
        float knob = stickRadius * 0.7f;
        stickKnob = quad("manette", knob, knob, new ColorRGBA(1, 1, 1, 0.35f));
        root.attachChild(stickKnob);
        placeKnob(stickX, stickY);

        // Les boutons, empiles dans la bande droite ; le tir, en bas, plus grand.
        float margin = height * 0.02f;
        float buttonW = band - 2 * margin;
        float small = (height - 7 * margin) * 0.17f;
        float fire = height - 6 * margin - 4 * small;
        float x = width - band + margin;
        float y = margin;
        for (Button button : buttons) {
            float h = button.action.equals("tirer") ? fire : small;
            button.x = x;
            button.y = y;
            button.w = buttonW;
            button.h = h;
            button.quad = quad(button.label, buttonW, h, new ColorRGBA(1, 1, 1, 0.15f));
            button.quad.setLocalTranslation(x, y, 2);
            root.attachChild(button.quad);
            button.text = new BitmapText(font);
            button.text.setSize(Math.min(small * 0.45f, buttonW / (button.label.length() + 1)));
            button.text.setColor(new ColorRGBA(1, 1, 1, 0.8f));
            button.text.setText(button.label);
            button.text.setLocalTranslation(x + (buttonW - button.text.getLineWidth()) / 2f,
                    y + (h + button.text.getLineHeight()) / 2f, 3);
            root.attachChild(button.text);
            y += h + margin;
        }
    }

    private void placeKnob(float x, float y) {
        if (stickKnob == null) {
            return;
        }
        float half = stickRadius * 0.35f;
        stickKnob.setLocalTranslation(x - half, y - half, 3);
    }

    /** Un evenement tactile : doigt pose, deplace ou leve. */
    void onTouch(TouchEvent event) {
        int pointer = event.getPointerId();
        float x = event.getX();
        float y = event.getY();
        switch (event.getType()) {
            case DOWN -> down(pointer, x, y);
            case MOVE -> {
                if (pointer == stickPointer) {
                    moveStick(x, y);
                }
            }
            case UP -> up(pointer);
            default -> {
                // Les gestes composes ne servent pas.
            }
        }
        event.setConsumed();
    }

    private void down(int pointer, float x, float y) {
        for (Button button : buttons) {
            if (button.contains(x, y)) {
                pressed.put(pointer, button);
                app.touchAction(button.action, true);
                return;
            }
        }
        float dx = x - stickX;
        float dy = y - stickY;
        if (dx * dx + dy * dy <= stickRadius * stickRadius * 2.25f) {
            stickPointer = pointer;
            moveStick(x, y);
            return;
        }
        // Ailleurs : une touche dans l'image, qui valide en se levant.
        taps.put(pointer, Boolean.TRUE);
    }

    private void up(int pointer) {
        Button button = pressed.remove(pointer);
        if (button != null) {
            app.touchAction(button.action, false);
            return;
        }
        if (pointer == stickPointer) {
            stickPointer = -1;
            placeKnob(stickX, stickY);
            setDirections(false, false, false, false, false);
            return;
        }
        if (taps.remove(pointer) != null) {
            app.touchAction("actionner", true);
            app.touchAction("actionner", false);
        }
    }

    private void moveStick(float x, float y) {
        float dx = x - stickX;
        float dy = y - stickY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > stickRadius) {
            dx = dx / length * stickRadius;
            dy = dy / length * stickRadius;
        }
        placeKnob(stickX + dx, stickY + dy);
        float threshold = stickRadius * 0.35f;
        setDirections(dy > threshold, dy < -threshold, dx < -threshold, dx > threshold,
                length > stickRadius * 0.9f);
    }

    /**
     * Envoie les changements de direction comme des appuis et des relachements de touche :
     * dans un menu, chaque passage du seuil deplace le choix d'une ligne.
     */
    private void setDirections(boolean up, boolean down, boolean toLeft, boolean toRight,
                               boolean fast) {
        if (up != forward) {
            forward = up;
            app.touchAction("avant", up);
        }
        if (down != backward) {
            backward = down;
            app.touchAction("arriere", down);
        }
        if (toLeft != left) {
            left = toLeft;
            app.touchAction("gauche", toLeft);
        }
        if (toRight != right) {
            right = toRight;
            app.touchAction("droite", toRight);
        }
        if (fast != run) {
            run = fast;
            app.touchAction("courir", fast);
        }
    }
}
