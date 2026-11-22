package fov.breathless.game;

import java.io.IOException;

/**
 * Le jeu sur Android : la meme coquille, les donnees prises dans le stockage de
 * l'application.
 *
 * <p>{@code AndroidHarness} instancie la classe par son nom, sans argument : c'est ici
 * que se chargent les donnees que {@link LauncherActivity} a verifiees.
 */
public final class AndroidBreathless extends BreathlessApp {

    public AndroidBreathless() {
        super(load(), null);
    }

    private static GameData load() {
        try {
            return GameData.load(DataStore.localDirectory());
        } catch (IOException failure) {
            throw new IllegalStateException("Donnees du jeu illisibles : " + failure.getMessage(),
                    failure);
        }
    }
}
