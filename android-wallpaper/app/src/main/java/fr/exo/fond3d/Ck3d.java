package fr.exo.fond3d;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Lecture des fichiers .cockpit3d (format CK3D de mobiGlas) et rangement des scènes dans le stockage de l'appli. */
public final class Ck3d {
    private Ck3d() {}

    public static File scenesDir(Context c) {
        File d = new File(c.getFilesDir(), "scenes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String labelFor(String key) {
        switch (key) {
            case "cors": return "Corsair";
            case "vult": return "Vulture";
            case "f7c": return "F7C";
            case "fond": return "Mon fond d'écran";
            default: return key;
        }
    }

    /** Importe toutes les cabines d'un fichier .cockpit3d. Renvoie les identifiants des scènes créées. */
    public static List<String> importPack(Context c, Uri uri, String displayName) throws Exception {
        File tmp = new File(c.getCacheDir(), "import.ck3d");
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new Exception("fichier illisible");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        List<String> ids = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(tmp, "r")) {
            byte[] head = new byte[8];
            f.readFully(head);
            if (head[0] != 'C' || head[1] != 'K' || head[2] != '3' || head[3] != 'D') throw new Exception("ce n'est pas un fichier cockpit3d");
            long n = (head[4] & 0xffL) | ((head[5] & 0xffL) << 8) | ((head[6] & 0xffL) << 16) | ((head[7] & 0xffL) << 24);
            if (n < 2 || n > 4_000_000) throw new Exception("en-tête invalide");
            byte[] js = new byte[(int) n];
            f.readFully(js);
            JSONObject idx = new JSONObject(new String(js, "UTF-8"));
            long base = 8 + n;
            JSONObject ships = idx.getJSONObject("ships");
            String stem = displayName == null ? "cabines" : displayName.replaceAll("\\.cockpit3d$", "").replaceAll("[^A-Za-z0-9_-]+", "_");
            Iterator<String> keys = ships.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject s = ships.getJSONObject(k);
                String id = stem + "-" + k;
                File dir = new File(scenesDir(c), id);
                deleteRec(dir);
                dir.mkdirs();
                JSONObject meta = s.getJSONObject("meta");
                if (!meta.has("label")) meta.put("label", labelFor(k));
                meta.put("source", stem);
                meta.put("ship", k);
                try (FileOutputStream mo = new FileOutputStream(new File(dir, "meta.json"))) { mo.write(meta.toString().getBytes("UTF-8")); }
                JSONObject files = s.getJSONObject("files");
                Iterator<String> fn = files.keys();
                while (fn.hasNext()) {
                    String name = fn.next();
                    if (!name.matches("[A-Za-z0-9_.-]{1,60}")) continue;
                    org.json.JSONArray e = files.getJSONArray(name);
                    long off = e.getLong(0), len = e.getLong(1);
                    f.seek(base + off);
                    try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
                        byte[] buf = new byte[1 << 16];
                        long left = len;
                        while (left > 0) {
                            int r = f.read(buf, 0, (int) Math.min(buf.length, left));
                            if (r <= 0) break;
                            o.write(buf, 0, r);
                            left -= r;
                        }
                    }
                }
                ids.add(id);
            }
        } finally {
            tmp.delete();
        }
        return ids;
    }

    public static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteRec(x);
        f.delete();
    }

    public static String readText(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int o = 0;
            while (o < b.length) { int r = in.read(b, o, b.length - o); if (r <= 0) break; o += r; }
        }
        return new String(b, "UTF-8");
    }
}
