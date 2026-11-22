package fov.breathless.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import java.io.IOException;

/**
 * Le premier ecran : s'assure que les donnees du jeu sont la, puis lance la partie.
 *
 * <p>Comme sur le poste de travail, l'application ne contient aucune donnee de
 * <em>Breathless</em>. Elle propose d'aller chercher l'archive des sources d'origine
 * — environ 2,5 Mo — et la range dans son propre stockage ; les lancements suivants la
 * retrouvent sans rien demander.
 */
public final class LauncherActivity extends Activity {

    private TextView status;

    /** Les reglages communs aux deux activites : ou ranger, et quel ecran. */
    static void configure(Activity activity) {
        java.io.File home = activity.getExternalFilesDir(null);
        if (home == null) {
            home = activity.getFilesDir();
        }
        System.setProperty(DataStore.HOME_PROPERTY, home.getAbsolutePath());
        System.setProperty("breathless.touch", "true");
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configure(this);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(18);
        status.setPadding(48, 48, 48, 48);
        setContentView(status);

        if (DataStore.holdsGameData(DataStore.localDirectory())) {
            startGame();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Breathless")
                .setMessage("Les donnees du jeu ne sont pas fournies avec l'application.\n\n"
                        + "Aller chercher l'archive des sources d'origine (environ 2,5 Mo) sur "
                        + DataStore.ORIGIN + " ?")
                .setPositiveButton("Telecharger", (dialog, which) -> download())
                .setNegativeButton("Quitter", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void download() {
        status.setText("Telechargement...");
        Thread worker = new Thread(() -> {
            try {
                DataStore.download(step -> runOnUiThread(() -> status.setText(step)));
                runOnUiThread(this::startGame);
            } catch (IOException failure) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Breathless")
                        .setMessage("Le telechargement a echoue :\n" + failure.getMessage())
                        .setPositiveButton("Quitter", (dialog, which) -> finish())
                        .setCancelable(false)
                        .show());
            }
        }, "telechargement");
        worker.start();
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }
}
