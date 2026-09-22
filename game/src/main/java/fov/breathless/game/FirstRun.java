package fov.breathless.game;

import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Le premier lancement, quand les donnees du jeu manquent encore.
 *
 * <p>Le jeu demande son accord au joueur, va chercher l'archive des sources d'origine,
 * puis la range une fois pour toutes. Ce qui se passe est montre a l'ecran : lance depuis
 * une icone, le jeu n'a pas de console ou l'on verrait quoi que ce soit, et une fenetre
 * qui ne s'ouvre pas pendant dix secondes ressemble a une panne.
 *
 * <p>Sans ecran — en ligne de commande, sur un serveur — tout se passe en console et le
 * telechargement se fait sans rien demander : on ne peut pas repondre a une question qui
 * ne s'affiche pas.
 */
final class FirstRun {

    private FirstRun() {
    }

    private static boolean hasScreen() {
        return !GraphicsEnvironment.isHeadless();
    }

    /**
     * Obtient les donnees, en les telechargeant si le joueur l'accepte.
     *
     * @return le repertoire des donnees, ou {@code null} si le joueur a refuse ou si le
     *     telechargement a echoue
     */
    static Path obtain() {
        System.out.println("Donnees du jeu absentes.");
        System.out.println("Elles seront prises dans l'archive des sources d'origine, "
                + DataStore.ORIGIN);
        System.out.println("et rangees dans " + DataStore.localDirectory());

        if (hasScreen() && !askPermission()) {
            System.out.println("Telechargement refuse.");
            return null;
        }

        JDialog progress = hasScreen() ? progressDialog() : null;
        try {
            if (progress != null) {
                SwingUtilities.invokeLater(() -> progress.setVisible(true));
            }
            return DataStore.download(System.out::println);
        } catch (IOException e) {
            System.err.println("Telechargement impossible : " + e.getMessage());
            if (hasScreen()) {
                closeDialog(progress);
                JOptionPane.showMessageDialog(null,
                        "Les donnees du jeu n'ont pas pu etre telechargees.\n\n"
                                + e.getMessage() + "\n\n"
                                + "Vous pouvez aussi recuperer l'archive vous-meme,\n"
                                + DataStore.ORIGIN + "\n"
                                + "et lancer le jeu en lui donnant le repertoire en argument.",
                        "Breathless", JOptionPane.ERROR_MESSAGE);
            }
            return null;
        } finally {
            closeDialog(progress);
        }
    }

    private static boolean askPermission() {
        int answer = JOptionPane.showConfirmDialog(null,
                "Breathless a besoin des donnees du jeu d'origine,\n"
                        + "qui ne sont pas fournies avec ce portage.\n\n"
                        + "Les telecharger maintenant depuis l'archive des sources ?\n"
                        + DataStore.ORIGIN + "\n\n"
                        + "Environ 2,5 Mo, une seule fois. Elles seront rangees dans\n"
                        + DataStore.localDirectory(),
                "Breathless — premier lancement",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
    }

    private static JDialog progressDialog() {
        JDialog dialog = new JDialog((Dialog) null, "Breathless", false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(new EmptyBorder(20, 24, 20, 24));

        JPanel lines = new JPanel();
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.add(new JLabel("Telechargement des donnees du jeu..."));
        lines.add(new JLabel(DataStore.ORIGIN));
        panel.add(lines, BorderLayout.NORTH);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        return dialog;
    }

    private static void closeDialog(JDialog dialog) {
        if (dialog != null) {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }
}
