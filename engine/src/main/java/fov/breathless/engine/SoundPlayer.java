package fov.breathless.engine;

/**
 * Ce que le moteur demande au son, sans rien savoir de la facon de le produire.
 *
 * <p>Les bruitages sont designes par leur nom de quatre caracteres dans la banque SGLD,
 * ou par le code d'un son global. Le moteur reste ainsi sans dependance : c'est la couche
 * jMonkeyEngine qui sait ouvrir un canal.
 */
public interface SoundPlayer {

    /** Un lecteur qui ne joue rien : celui des tests et des outils en ligne de commande. */
    SoundPlayer SILENT = new SoundPlayer() {
        @Override
        public void play(String name) {
            // Silence.
        }

        @Override
        public void playGlobal(int code) {
            // Silence.
        }

        @Override
        public void stop(String name) {
            // Silence.
        }

        @Override
        public void playMusic(String name) {
            // Silence.
        }

        @Override
        public void stopMusic() {
            // Silence.
        }
    };

    /** Joue un bruitage par son nom dans la banque. Un nom inconnu ne fait rien. */
    void play(String name);

    /**
     * Joue un son global par son code.
     *
     * @param code {@code Sound.GLOBAL_DOOR_START} et suivants
     */
    void playGlobal(int code);

    /** Arrete un son qui boucle, comme celui du lance-flammes. */
    void stop(String name);

    /**
     * Lance le morceau du niveau, en boucle, et arrete le precedent.
     *
     * @param name nom du morceau dans la banque
     */
    void playMusic(String name);

    /** Arrete la musique. */
    void stopMusic();
}
