package fov.breathless.game;

import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Node;

import java.util.List;

/**
 * La verification de la carte graphique, propre au poste de travail.
 *
 * <p>Elle emploie Swing pour poser sa question, que n'a pas Android : la coquille
 * l'appelle par son nom, et la version Android, qui ne la compile pas, s'en passe.
 *
 * @see GpuCheck
 */
final class DesktopGpu {

    private DesktopGpu() {
    }

    /**
     * Dit sur quelle carte la fenetre s'est ouverte et, si c'est la carte integree d'une
     * machine qui en a une autre, demande avant de continuer.
     */
    static void check(Node gui, BitmapFont font, int screenHeight, Runnable stop) {
        String gl = GpuCheck.renderer();
        List<String> cards = GpuCheck.adapters();
        String warn = GpuCheck.report(gl, cards);
        if (warn == null) {
            return;
        }
        // L'avertissement reste a l'ecran, par-dessus l'image du jeu.
        BitmapText red = new BitmapText(font);
        red.setSize(font.getCharSet().getRenderedSize());
        red.setColor(ColorRGBA.Red);
        red.setText(warn + '\n' + GpuCheck.FIX);
        red.setLocalTranslation(6, screenHeight - 6, 1);
        gui.attachChild(red);
        // -Dbreathless.gpu=ok : l'avertissement reste, la question n'est pas posee.
        if ("ok".equals(System.getProperty("breathless.gpu")) || !GpuCheck.hasOtherCard(cards)) {
            return;
        }
        // Demande sur le fil de Swing, pas sur celui du rendu : bloquer le fil OpenGL sur
        // une boite modale empeche la fenetre du jeu de repondre a Windows, qui la tient
        // alors pour figee.
        String text = String.join("\n", warn, "", GpuCheck.FIX, "",
                "Continuer quand meme sur la carte integree ?");
        javax.swing.SwingUtilities.invokeLater(() -> {
            int choice = javax.swing.JOptionPane.showConfirmDialog(null, text,
                    "Carte graphique", javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (choice != javax.swing.JOptionPane.YES_OPTION) {
                System.err.println("Arret a la demande : " + GpuCheck.FIX);
                stop.run();
            }
        });
    }
}
