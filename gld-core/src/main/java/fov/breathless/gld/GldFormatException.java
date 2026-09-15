package fov.breathless.gld;

/** Signale un fichier de donnees Breathless illisible ou incoherent. */
public class GldFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GldFormatException(String message) {
        super(message);
    }

    public GldFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
