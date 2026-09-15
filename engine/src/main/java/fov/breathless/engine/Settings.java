package fov.breathless.engine;

/**
 * Les reglages que le joueur peut changer en cours de partie.
 *
 * <p>Ils sont rassembles ici pour que le menu de configuration les modifie sans rien
 * savoir du reste, et que la couche graphique n'ait qu'un objet a consulter.
 *
 * <p>Un compteur de revisions evite de refaire la fenetre de rendu a chaque image :
 * l'appelant ne s'en occupe que lorsqu'il a change.
 */
public final class Settings {

    /** Volume de musique le plus fort, comme les quatre crans de l'original. */
    public static final int MAX_MUSIC_VOLUME = 4;

    private final ViewSize viewSize = new ViewSize();
    private final KeyBindings keys = new KeyBindings();
    private boolean sight = true;
    private boolean music = true;
    private boolean sound = true;
    private int musicVolume = 3;
    private int revision;

    /** Les touches du jeu. */
    public KeyBindings keys() {
        return keys;
    }

    /** Signale un changement que le compteur de revisions ne verrait pas seul. */
    public void touch() {
        revision++;
    }

    /** Taille de la fenetre et grosseur des pixels. */
    public ViewSize viewSize() {
        return viewSize;
    }

    /** Le viseur est-il affiche ? {@code SightState}. */
    public boolean sight() {
        return sight;
    }

    public void setSight(boolean shown) {
        if (sight != shown) {
            sight = shown;
            revision++;
        }
    }

    /** La musique joue-t-elle ? {@code MusicOnOff}. */
    public boolean music() {
        return music;
    }

    public void setMusic(boolean playing) {
        if (music != playing) {
            music = playing;
            revision++;
        }
    }

    /** Les bruitages sont-ils joues ? */
    public boolean sound() {
        return sound;
    }

    public void setSound(boolean playing) {
        if (sound != playing) {
            sound = playing;
            revision++;
        }
    }

    /** Volume de la musique, de 1 a {@value #MAX_MUSIC_VOLUME}. {@code MusicVolume}. */
    public int musicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(int volume) {
        int wanted = Math.max(1, Math.min(MAX_MUSIC_VOLUME, volume));
        if (musicVolume != wanted) {
            musicVolume = wanted;
            revision++;
        }
    }

    /**
     * Numero de revision, qui change des qu'un reglage change.
     *
     * <p>La taille de fenetre y compte aussi : elle est relevee a part, puisque
     * {@link ViewSize} se modifie directement.
     */
    public int revision() {
        return revision + viewSize.index() * 16 + viewSize.pixelType();
    }
}
