package fr.exo.fond3d;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Écran de réglages : importer des fichiers .cockpit3d, choisir la scène, régler le relief, appliquer le fond. */
public class SettingsActivity extends Activity {
    static final String PREFS = "fond3d";
    private static final int PICK = 41;
    private SharedPreferences prefs;
    private RadioGroup list;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pad = dp(18);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(6, 18, 26));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root);

        TextView t = text("mobiGlas Fond 3D", 24, Color.rgb(255, 179, 71));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
        root.addView(text("Un fond d'écran en relief qui bouge quand tu inclines le téléphone. Tes cabines cockpit3d et tes fonds en relief marchent ici.", 14, Color.rgb(200, 220, 230)));

        root.addView(section("Scène"));
        list = new RadioGroup(this);
        root.addView(list);
        Button imp = button("Importer un fichier .cockpit3d");
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK);
        });
        root.addView(imp);
        Button del = button("Supprimer la scène choisie");
        del.setOnClickListener(v -> {
            String id = prefs.getString("scene", null);
            if (id == null) return;
            Ck3d.deleteRec(new File(Ck3d.scenesDir(this), id));
            prefs.edit().remove("scene").apply();
            refresh();
        });
        root.addView(del);

        root.addView(section("Relief"));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(prefs.getInt("intensity", 60));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { prefs.edit().putInt("intensity", p).apply(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);
        CheckBox inv = check("Inverser le sens du mouvement", "invert", false);
        root.addView(inv);
        CheckBox anim = check("Animations (étoiles, nuages, écrans)", "anim", true);
        root.addView(anim);
        CheckBox pages = check("Défiler dans la scène en changeant de page d'accueil", "pages", true);
        root.addView(pages);

        Button apply = button("Appliquer comme fond d'écran");
        apply.setOnClickListener(v -> {
            try {
                Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, WallService.class));
                startActivity(i);
            } catch (Exception e) {
                try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); }
                catch (Exception e2) { toast("Ouvre Fond d'écran > Fonds animés et choisis mobiGlas Fond 3D"); }
            }
        });
        root.addView(apply);
        status = text("", 13, Color.rgb(140, 170, 185));
        root.addView(status);
        setContentView(sv);
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        File[] dirs = Ck3d.scenesDir(this).listFiles(File::isDirectory);
        String cur = prefs.getString("scene", null);
        if (dirs == null || dirs.length == 0) {
            status.setText("Aucune scène pour l'instant : importe un fichier .cockpit3d (tes cabines, ou fond-ecran.cockpit3d).");
            return;
        }
        Arrays.sort(dirs);
        boolean any = false;
        for (File d : dirs) {
            String label = d.getName();
            try {
                JSONObject m = new JSONObject(Ck3d.readText(new File(d, "meta.json")));
                label = m.optString("label", label) + "  ·  " + m.optString("source", "");
            } catch (Exception ignored) {}
            RadioButton r = new RadioButton(this);
            r.setText(label);
            r.setTextColor(Color.WHITE);
            r.setTextSize(16);
            r.setId(View.generateViewId());
            final String id = d.getName();
            r.setOnClickListener(v -> { prefs.edit().putString("scene", id).apply(); status.setText("Scène choisie : " + id); });
            list.addView(r);
            if (id.equals(cur)) { r.setChecked(true); any = true; }
        }
        if (!any) {
            ((RadioButton) list.getChildAt(0)).setChecked(true);
            prefs.edit().putString("scene", dirs[0].getName()).apply();
        }
        status.setText(dirs.length + " scène(s). Choisis-en une puis « Appliquer comme fond d'écran ».");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = "cabines";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i); }
        } catch (Exception ignored) {}
        final String fname = name;
        status.setText("Import en cours…");
        new Thread(() -> {
            try {
                List<String> ids = Ck3d.importPack(this, u, fname);
                runOnUiThread(() -> {
                    if (!ids.isEmpty()) prefs.edit().putString("scene", ids.get(ids.size() - 1)).apply();
                    refresh();
                    toast(ids.size() + " scène(s) importée(s)");
                });
            } catch (Exception e) {
                runOnUiThread(() -> { status.setText("Import impossible : " + e.getMessage()); });
            }
        }).start();
    }

    private TextView text(String s, int sp, int col) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(col);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }
    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 13, Color.rgb(95, 227, 240));
        t.setPadding(0, dp(22), 0, dp(4));
        t.setLetterSpacing(.12f);
        return t;
    }
    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }
    private CheckBox check(String s, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(s); c.setTextColor(Color.WHITE); c.setTextSize(15);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        return c;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
