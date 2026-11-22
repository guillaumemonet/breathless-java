package fov.breathless.game;

import android.os.Bundle;

import com.jme3.app.AndroidHarness;

/**
 * L'activite du jeu : jMonkeyEngine sur une surface OpenGL ES, en plein ecran.
 *
 * <p>Les doigts arrivent comme evenements tactiles, et non comme une souris : ce sont les
 * commandes tactiles de la coquille qui les lisent. La touche Retour du telephone arrive
 * comme Echap et ouvre le menu.
 */
public final class GameActivity extends AndroidHarness {

    public GameActivity() {
        appClass = AndroidBreathless.class.getName();
        eglBitsPerPixel = 24;
        eglAlphaBits = 0;
        eglDepthBits = 16;
        eglSamples = 0;
        eglStencilBits = 0;
        frameRate = -1;
        mouseEventsEnabled = false;
        joystickEventsEnabled = false;
        keyEventsEnabled = true;
        handleExitHook = false;
        finishOnAppStop = true;
        screenFullScreen = true;
        screenShowTitle = false;
    }

    @Override
    public void onCreate(Bundle state) {
        LauncherActivity.configure(this);
        super.onCreate(state);
    }
}
