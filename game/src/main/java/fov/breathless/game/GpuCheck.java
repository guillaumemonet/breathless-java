package fov.breathless.game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * La carte graphique sur laquelle la fenetre s'est ouverte, dite a voix haute.
 *
 * <p>Sur un portable a deux cartes, le pilote donne a un {@code java.exe} la carte
 * integree, faute de le reconnaitre comme un jeu — et rien ne le signale. Un processus
 * Java ne peut pas choisir lui-meme sous Windows : les symboles
 * {@code NvOptimusEnablement} et {@code AmdPowerXpressRequestHighPerformance} doivent
 * etre exportes par l'executable natif, ce que {@code java.exe} ne fait pas. Le mieux
 * qu'on puisse faire est de lire {@code GL_RENDERER}, de l'afficher, et, quand il
 * ressemble a une puce integree, de le dire franchement avec le correctif.
 *
 * <p>Ce portage rasterise en logiciel et ne confie a la carte qu'une texture par image :
 * elle lui coute peu. L'avertissement compte pour toute mesure de temps, fausse sur la
 * puce lente sans que rien ne le montre.
 */
public final class GpuCheck {

    /** Noms de rendu des puces integrees et du rendu logiciel de Windows. */
    private static final Pattern INTEGRATED = Pattern.compile(
            "Intel.*(HD|UHD|Iris)|AMD Radeon\\(TM\\) Graphics|Microsoft Basic Render",
            Pattern.CASE_INSENSITIVE);

    /** {@code GL_RENDERER}, tel que le definit OpenGL. */
    private static final int GL_RENDERER = 0x1F01;

    /** Le correctif exact, dans les termes de Windows. */
    public static final String FIX =
            "Parametres Windows > Systeme > Affichage > Graphiques : ajouter java.exe"
            + " et javaw.exe, choisir << Hautes performances >>, puis relancer.";

    private GpuCheck() {
    }

    public static boolean looksIntegrated(String renderer) {
        return renderer != null && INTEGRATED.matcher(renderer).find();
    }

    /**
     * Le nom du rendu OpenGL courant. A appeler sur le fil du contexte.
     *
     * <p>LWJGL n'est qu'une dependance d'execution de la coquille : on passe par la
     * reflexion plutot que de lier la compilation a une version de LWJGL.
     */
    public static String renderer() {
        try {
            Object name = Class.forName("org.lwjgl.opengl.GL11")
                    .getMethod("glGetString", int.class)
                    .invoke(null, GL_RENDERER);
            return (name == null) ? "inconnu" : name.toString();
        } catch (ReflectiveOperationException | LinkageError e) {
            return "inconnu (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Les cartes que Windows connait, ou une liste vide ailleurs ou s'il ne repond pas.
     * Trois secondes au plus : c'est une politesse, pas de quoi bloquer le demarrage.
     */
    public static List<String> adapters() {
        List<String> out = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return out;
        }
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_VideoController | "
                    + "Select-Object -ExpandProperty Name")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        out.add(line.trim());
                    }
                }
            }
        } catch (Exception e) {
            // Pas de PowerShell, ou pas le droit de le lancer : on ne dit rien des autres.
        }
        return out;
    }

    /**
     * Affiche le rendu et, s'il ressemble a une carte integree, l'avertissement.
     *
     * @return l'avertissement a garder a l'ecran, ou {@code null} s'il n'y en a pas
     */
    public static String report(String renderer, List<String> adapters) {
        System.out.println("Carte graphique utilisee (GL_RENDERER) : " + renderer);
        if (!adapters.isEmpty()) {
            System.out.println("Cartes presentes : " + String.join(", ", adapters));
        }
        if (!looksIntegrated(renderer)) {
            return null;
        }
        String warn = "ATTENTION : le jeu tourne sur la carte integree (" + renderer + ")"
                + (hasOtherCard(adapters) ? " alors que la machine en a une autre." : ".");
        System.err.println();
        System.err.println("************************************************************");
        System.err.println(warn);
        System.err.println(FIX);
        System.err.println("Toute mesure de performance faite ainsi est celle de l'iGPU.");
        System.err.println("************************************************************");
        System.err.println();
        return warn;
    }

    /** La machine a-t-elle une carte qui n'est pas integree ? */
    public static boolean hasOtherCard(List<String> adapters) {
        return adapters.stream().anyMatch(a -> !looksIntegrated(a));
    }
}
