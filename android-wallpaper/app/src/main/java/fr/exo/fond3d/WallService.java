package fr.exo.fond3d;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fond d'écran animé en relief : rejoue une scène cockpit3d (cabine ou fond) avec OpenGL ES 3.
 * Même géométrie que le lecteur web de mobiGlas : chaque pixel est projeté à sa profondeur (inverse de la distance),
 * l'œil se déplace un peu quand on incline le téléphone (capteur de rotation), et on tourne la tête en changeant de page d'accueil.
 */
public class WallService extends WallpaperService {
    static final String TAG = "Fond3D";

    @Override
    public Engine onCreateEngine() { return new FondEngine(); }

    // =====================================================================================================
    class FondEngine extends Engine implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private SharedPreferences prefs;
        private SensorManager sm;
        private Sensor rot;
        private RenderThread thread;
        private volatile boolean visible;
        volatile float xOff = 0.5f;
        // capteur : orientation relative à une référence qui suit lentement la position de repos du téléphone
        private final float[] qCur = new float[4], qRef = new float[4];
        private boolean haveRef;
        private long lastNs;
        volatile float tiltX, tiltY;          // rad, autour de l'axe horizontal / vertical de l'écran

        @Override
        public void onCreate(SurfaceHolder h) {
            super.onCreate(h);
            prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(this);
            sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sm != null) {
                rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (rot == null) rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
            setOffsetNotificationsEnabled(true);
        }

        @Override
        public void onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
            stopSensor();
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder h) {
            super.onSurfaceCreated(h);
            thread = new RenderThread(this, h.getSurface());
            thread.setVisible(isVisible());
            thread.start();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder h, int format, int w, int hh) {
            super.onSurfaceChanged(h, format, w, hh);
            if (thread != null) thread.resize(w, hh);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder h) {
            if (thread != null) { thread.quit(); thread = null; }
            super.onSurfaceDestroyed(h);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) startSensor(); else stopSensor();
            if (thread != null) thread.setVisible(v);
        }

        @Override
        public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) {
            xOff = xo;
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            if (thread != null) thread.requestReload();
            if (("scene".equals(key) || "rev".equals(key)) && Build.VERSION.SDK_INT >= 27) { colorsFor = null; notifyColorsChanged(); }
        }

        /* Couleurs annoncées au système (icônes à thème, couleurs système de ColorOS / Material You) :
           tirées de l'image de la scène, comme pour un fond d'écran fixe. Sans ça, le système invente une couleur (vert). */
        private Object colors;
        private String colorsFor;

        @android.annotation.TargetApi(27)
        @Override
        public android.app.WallpaperColors onComputeColors() {
            String id = prefs != null ? prefs.getString("scene", null) : null;
            if (id == null) return null;
            if (id.equals(colorsFor) && colors != null) return (android.app.WallpaperColors) colors;
            try {
                File dir = new File(Ck3d.scenesDir(WallService.this), id);
                boolean wall = false;
                try { wall = "wallpaper".equals(new JSONObject(Ck3d.readText(new File(dir, "meta.json"))).optString("kind")); } catch (Exception ignored) {}
                File img = new File(dir, wall ? "plate0.jpg" : "still.jpg");
                if (!img.exists()) img = new File(dir, "still.jpg");
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                Bitmap b = BitmapFactory.decodeFile(img.getAbsolutePath(), o);
                if (b == null) return null;
                android.app.WallpaperColors c = android.app.WallpaperColors.fromBitmap(b);
                b.recycle();
                colors = c; colorsFor = id;
                return c;
            } catch (Throwable t) {
                Log.w(TAG, "couleurs", t);
                return null;
            }
        }

        SharedPreferences prefs() { return prefs; }

        private void startSensor() {
            if (sm != null && rot != null) { haveRef = false; sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME); }
        }
        private void stopSensor() { if (sm != null) sm.unregisterListener(this); }

        @Override
        public void onSensorChanged(SensorEvent e) {
            SensorManager.getQuaternionFromVector(qCur, e.values);    // w, x, y, z
            long now = e.timestamp;
            if (!haveRef) { System.arraycopy(qCur, 0, qRef, 0, 4); haveRef = true; lastNs = now; return; }
            float dt = Math.max(0f, Math.min(0.2f, (now - lastNs) * 1e-9f));
            lastNs = now;
            // la référence rattrape lentement la position actuelle (constante de temps ~3 s) : le relief se recentre tout seul
            float a = 1f - (float) Math.exp(-dt / 3.0f);
            float dot = qRef[0] * qCur[0] + qRef[1] * qCur[1] + qRef[2] * qCur[2] + qRef[3] * qCur[3];
            float sg = dot < 0 ? -1f : 1f;
            float n = 0;
            for (int i = 0; i < 4; i++) { qRef[i] += (sg * qCur[i] - qRef[i]) * a; n += qRef[i] * qRef[i]; }
            n = (float) Math.sqrt(n);
            for (int i = 0; i < 4; i++) qRef[i] /= n;
            // q_rel = conj(q_ref) * q_cur  (rotation exprimée dans le repère du téléphone au repos)
            float aw = qRef[0], ax = -qRef[1], ay = -qRef[2], az = -qRef[3];
            float bw = qCur[0], bx = qCur[1], by = qCur[2], bz = qCur[3];
            float rw = aw * bw - ax * bx - ay * by - az * bz;
            float rx = aw * bx + ax * bw + ay * bz - az * by;
            float ry = aw * by - ax * bz + ay * bw + az * bx;
            if (rw < 0) { rx = -rx; ry = -ry; }
            float tx = clamp(2f * rx, -0.6f, 0.6f), ty = clamp(2f * ry, -0.6f, 0.6f);
            tiltX += (tx - tiltX) * 0.35f;
            tiltY += (ty - tiltY) * 0.35f;
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {}
    }

    static float clamp(float x, float a, float b) { return x < a ? a : (x > b ? b : x); }

    // =====================================================================================================
    /** Fil de rendu : contexte EGL à lui, boucle ~30 images/s quand le fond est visible. */
    static class RenderThread extends Thread {
        private final FondEngine eng;
        private final Surface surface;
        private final Object lock = new Object();
        private boolean running = true, vis = true, reload = true;
        private int w, h;
        private EGLDisplay dpy;
        private EGLContext ctx;
        private EGLSurface surf;
        private Renderer r;

        RenderThread(FondEngine e, Surface s) { super("fond3d-gl"); eng = e; surface = s; }

        void resize(int ww, int hh) { synchronized (lock) { w = ww; h = hh; lock.notifyAll(); } }
        void setVisible(boolean v) { synchronized (lock) { vis = v; lock.notifyAll(); } }
        void requestReload() { synchronized (lock) { reload = true; lock.notifyAll(); } }
        void quit() {
            synchronized (lock) { running = false; lock.notifyAll(); }
            try { join(3000); } catch (InterruptedException ignored) {}
        }

        @Override
        public void run() {
            try {
                if (!initEgl()) return;
                r = new Renderer(null);
                r.init();
                boolean wasVis = true;
                while (true) {
                    boolean doReload, v;
                    int ww, hh;
                    synchronized (lock) {
                        while (running && !vis) {
                            if (wasVis) { r.pauseVideos(); wasVis = false; }
                            lock.wait();
                        }
                        if (!running) break;
                        doReload = reload; reload = false; ww = w; hh = h;
                    }
                    if (!wasVis) { r.resumeVideos(); wasVis = true; }
                    if (doReload) r.load(eng.prefs(), WallServiceHolder.filesDir);
                    long t0 = SystemClock.uptimeMillis();
                    int[] sz = new int[1], sz2 = new int[1];
                    EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, sz, 0);
                    EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, sz2, 0);
                    if (sz[0] > 0) { ww = sz[0]; hh = sz2[0]; }
                    r.draw(ww, hh, eng.xOff, eng.tiltX, eng.tiltY, eng.prefs());
                    if (!EGL14.eglSwapBuffers(dpy, surf)) {
                        int err = EGL14.eglGetError();
                        Log.w(TAG, "swap " + err);
                        if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) break;
                    }
                    long dt = SystemClock.uptimeMillis() - t0;
                    long wait = 33 - dt;
                    if (wait > 0) synchronized (lock) { if (running && !reload) lock.wait(wait); }
                }
            } catch (Throwable t) {
                Log.e(TAG, "rendu", t);
            } finally {
                if (r != null) r.release();
                releaseEgl();
            }
        }

        private boolean initEgl() {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return false;
            int[] attr = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
                    EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, num, 0) || num[0] < 1) { Log.e(TAG, "pas de config EGL"); return false; }
            int[] ca = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, ca, 0);
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) { Log.e(TAG, "pas de contexte GLES3"); return false; }
            surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], surface, new int[]{EGL14.EGL_NONE}, 0);
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) { Log.e(TAG, "pas de surface"); return false; }
            return EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        }

        private void releaseEgl() {
            if (dpy == null) return;
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != null) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != null) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
            dpy = null;
        }
    }

    /** Le dossier des scènes est connu du service ; le fil de rendu le lit ici. */
    static final class WallServiceHolder { static File filesDir; }

    @Override
    public void onCreate() {
        super.onCreate();
        WallServiceHolder.filesDir = Ck3d.scenesDir(this);
    }

    // =====================================================================================================
    /** Une vidéo jouée en boucle, muette, dans une texture externe. */
    static class Vid implements SurfaceTexture.OnFrameAvailableListener {
        final int tex;
        final SurfaceTexture st;
        final Surface surface;
        final MediaPlayer mp;
        final float[] m = new float[16];
        final AtomicBoolean fresh = new AtomicBoolean(false);
        boolean ready;

        Vid(File f) throws Exception {
            int[] t = new int[1];
            GLES30.glGenTextures(1, t, 0);
            tex = t[0];
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            st = new SurfaceTexture(tex);
            st.setOnFrameAvailableListener(this);
            surface = new Surface(st);
            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.prepare();
            mp.start();
            android.opengl.Matrix.setIdentityM(m, 0);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture s) { fresh.set(true); }

        /** à appeler sur le fil GL : récupère la dernière image décodée. */
        boolean update() {
            if (fresh.getAndSet(false)) {
                try { st.updateTexImage(); st.getTransformMatrix(m); ready = true; } catch (Exception ignored) {}
            }
            return ready;
        }
        void pause() { try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) {} }
        void resume() { try { mp.start(); } catch (Exception ignored) {} }
        void release() {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            surface.release();
            st.release();
            GLES30.glDeleteTextures(1, new int[]{tex}, 0);
        }
    }

    // =====================================================================================================
    static class Mesh {
        int vbo, ibo, n;
        void release() { GLES30.glDeleteBuffers(2, new int[]{vbo, ibo}, 0); }
    }

    static class Scene {
        JSONObject meta;
        int W, H;
        float near, far, hfov;
        float[] ext, plext;
        float fadeX, fadeY, dmax;
        boolean plrot;
        int still, alpha, plate0;
        Mesh front, back;
        Vid plate, delta;
    }

    /** Tout ce qui touche OpenGL : programmes, chargement des scènes, dessin. */
    static class Renderer {
        private int pFront, pFrontD, pBack, pBackV;
        private Scene sc;
        private String loadedKey = "";
        private int maxTex = 4096;

        Renderer(Context unused) {}

        static final String VS =
                "#version 100\n" +
                "attribute vec4 a_v;\n" +                     // u, v, 1/z, saut de profondeur
                "uniform mat3 u_RT; uniform vec3 u_T; uniform vec2 u_tanSrc; uniform vec2 u_proj;\n" +
                "varying vec2 v_uv; varying float v_gap;\n" +
                "void main(){\n" +
                "  vec3 ray = vec3((a_v.x-0.5)*2.0*u_tanSrc.x, (a_v.y-0.5)*2.0*u_tanSrc.y, 1.0);\n" +
                "  vec3 c = u_RT*(ray - u_T*a_v.z);\n" +
                "  float zc = a_v.z>0.0001 ? c.z/a_v.z : 1000.0;\n" +
                "  float zn = clamp(zc/80.0, 0.0, 1.0)*2.0-1.0;\n" +
                "  if(c.z<0.02){ gl_Position = vec4(3.0,3.0,1.0,1.0); }\n" +
                "  else { gl_Position = vec4(c.x*u_proj.x, -c.y*u_proj.y, zn*c.z, c.z); }\n" +
                "  v_uv = a_v.xy; v_gap = a_v.w;\n" +
                "}\n";

        static String fsFront(boolean delta) {
            return "#version 100\n" +
                    (delta ? "#extension GL_OES_EGL_image_external : require\n" : "") +
                    "precision highp float;\n" +
                    "uniform sampler2D u_still; uniform sampler2D u_alpha;\n" +
                    (delta ? "uniform samplerExternalOES u_delta; uniform mat4 u_dm; uniform vec4 u_pl; uniform float u_dk;\n" : "") +
                    "uniform vec2 u_wh; uniform vec2 u_fade; uniform float u_sk; uniform vec2 u_sf;\n" +
                    "varying vec2 v_uv; varying float v_gap;\n" +
                    "void main(){\n" +
                    "  vec2 uvc = clamp(v_uv, 0.0, 1.0);\n" +
                    "  float fade = 1.0;\n" +
                    "  if(u_fade.x > 0.0){ vec2 ins = min(v_uv, 1.0 - v_uv)*u_wh; fade = smoothstep(0.0, 1.0, min(ins.x/u_fade.x, ins.y/u_fade.y)); }\n" +
                    "  float a = texture2D(u_alpha, uvc).r * fade;\n" +
                    "  a *= 1.0 - smoothstep(u_sf.x, u_sf.y, v_gap*u_sk);\n" +
                    "  if(a < 0.004) discard;\n" +
                    "  vec3 col = texture2D(u_still, uvc, -0.3).rgb;\n" +
                    (delta ?
                    "  vec2 du = uvc*u_pl.xy + u_pl.zw;\n" +
                    "  vec2 dd = step(vec2(0.0), du) * step(du, vec2(1.0));\n" +
                    "  vec2 dc = clamp(du, 0.0, 1.0);\n" +
                    "  vec3 dl = (texture2D(u_delta, (u_dm*vec4(dc.x, 1.0-dc.y, 0.0, 1.0)).xy).rgb - 0.50196) * (u_dk*dd.x*dd.y);\n" +
                    "  col = clamp(col + dl, 0.0, 1.0);\n" : "") +
                    "  gl_FragColor = vec4(col, a);\n" +
                    "}\n";
        }

        static String fsBack(boolean video) {
            return "#version 100\n" +
                    (video ? "#extension GL_OES_EGL_image_external : require\n" : "") +
                    "precision highp float;\n" +
                    (video ? "uniform samplerExternalOES u_plate; uniform mat4 u_pm; uniform float u_rot;\n" : "uniform sampler2D u_plate;\n") +
                    "uniform vec4 u_pp;\n" +
                    "varying vec2 v_uv; varying float v_gap;\n" +
                    "void main(){\n" +
                    "  vec2 uv = clamp(v_uv*u_pp.xy + u_pp.zw, 0.0, 1.0);\n" +
                    (video ?
                    "  vec2 s = u_rot > 0.5 ? vec2(uv.y, uv.x) : uv;\n" +
                    "  vec3 col = texture2D(u_plate, (u_pm*vec4(s.x, 1.0-s.y, 0.0, 1.0)).xy).rgb;\n" :
                    "  vec3 col = texture2D(u_plate, uv).rgb;\n") +
                    "  gl_FragColor = vec4(col, 1.0);\n" +
                    "}\n";
        }

        void init() {
            int[] mt = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, mt, 0);
            if (mt[0] > 0) maxTex = mt[0];
            pFront = prog(VS, fsFront(false));
            pFrontD = prog(VS, fsFront(true));
            pBack = prog(VS, fsBack(false));
            pBackV = prog(VS, fsBack(true));
        }

        private static int shader(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("shader : " + GLES30.glGetShaderInfoLog(s));
            return s;
        }
        private static int prog(String vs, String fs) {
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs));
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs));
            GLES30.glBindAttribLocation(p, 0, "a_v");
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("programme : " + GLES30.glGetProgramInfoLog(p));
            return p;
        }

        // ------------------------------------------------------------------ chargement
        void load(SharedPreferences prefs, File scenes) {
            String id = prefs.getString("scene", null);
            boolean anim = prefs.getBoolean("anim", true);
            String key = id + "|" + anim + "|" + prefs.getLong("rev", 0);   // rev change quand on réimporte une scène du même nom
            if (key.equals(loadedKey) && sc != null) return;
            loadedKey = key;
            freeScene();
            if (id == null || scenes == null) return;
            File dir = new File(scenes, id);
            if (!dir.isDirectory()) return;
            try {
                sc = loadScene(dir, anim);
            } catch (Throwable t) {
                Log.e(TAG, "scène " + id, t);
                freeScene();
            }
        }

        private static float[] arr(JSONObject m, String k, float[] def) {
            JSONArray a = m.optJSONArray(k);
            if (a == null || a.length() < 4) return def;
            return new float[]{(float) a.optDouble(0), (float) a.optDouble(1), (float) a.optDouble(2), (float) a.optDouble(3)};
        }

        private Scene loadScene(File dir, boolean anim) throws Exception {
            Scene s = new Scene();
            JSONObject m = new JSONObject(Ck3d.readText(new File(dir, "meta.json")));
            s.meta = m;
            s.W = m.optInt("W", 1920);
            s.H = m.optInt("H", 1080);
            s.near = (float) m.optDouble("near", 0.5);
            s.far = (float) m.optDouble("far", 3.0);
            s.hfov = (float) m.optDouble("hfov", 100);
            s.dmax = (float) m.optDouble("dmax", 50);
            s.ext = arr(m, "ext", new float[]{0, 0, s.W, s.H});
            s.plext = arr(m, "plext", s.ext);
            JSONArray fd = m.optJSONArray("fade");
            s.fadeX = fd != null ? (float) fd.optDouble(0, 0) : s.W * 0.05f;
            s.fadeY = fd != null ? (float) fd.optDouble(1, 0) : s.H * 0.06f;
            s.plrot = m.optInt("plrot", 0) == 1;

            s.still = texFile(new File(dir, "still.jpg"), true);
            s.alpha = texFile(new File(dir, "alpha.png"), false);
            File p0 = new File(dir, "plate0.jpg");
            s.plate0 = p0.exists() ? texFile(p0, false) : 0;

            float inN = 1f / s.near, inF = 1f / s.far;
            Depth dp = readDepth(new File(dir, "depth.png"));
            s.front = buildMesh(dp, s.W, s.H, inN, inF, true, 0.03f, 160000);
            String back = m.optString("back", "");
            if (!back.isEmpty() && new File(dir, back).exists()) {
                s.back = buildMesh(readDepth(new File(dir, back)), s.W, s.H, inN, inF, false, 0.03f, 90000);
            } else {
                s.back = planeAtInfinity(s);
            }
            if (anim) {
                File pv = new File(dir, "plate.mp4"), dv = new File(dir, "delta.mp4");
                try { if (pv.exists()) s.plate = new Vid(pv); } catch (Exception e) { Log.w(TAG, "plate.mp4", e); }
                try { if (dv.exists()) s.delta = new Vid(dv); } catch (Exception e) { Log.w(TAG, "delta.mp4", e); }
            }
            return s;
        }

        private int texFile(File f, boolean mip) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return 0;
            int mx = Math.max(b.getWidth(), b.getHeight());
            if (mx > maxTex) {
                float k = maxTex / (float) mx;
                Bitmap c = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                b.recycle();
                b = c;
            }
            int[] t = new int[1];
            GLES30.glGenTextures(1, t, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
            b.recycle();
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            if (mip) {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
            } else {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            }
            return t[0];
        }

        static class Depth { int w, h; int[] px; }

        private static Depth readDepth(File f) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            o.inPremultiplied = false;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            Depth d = new Depth();
            d.w = b.getWidth(); d.h = b.getHeight();
            d.px = new int[d.w * d.h];
            b.getPixels(d.px, 0, d.w, 0, 0, d.w, d.h);
            b.recycle();
            return d;
        }

        private static float mir(float u) {
            float m = u % 2f; if (m < 0) m += 2f;
            return 1f - Math.abs(1f - m);
        }

        /** 1/z au point (u,v) : bleu = 1 + 254·proximité, vert > 127 = à l'infini. */
        private static float samp(Depth d, float u, float v, float inN, float inF) {
            u = mir(u); v = mir(v);
            float x = u * (d.w - 1), y = v * (d.h - 1);
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
            int x1 = Math.min(d.w - 1, x0 + 1), y1 = Math.min(d.h - 1, y0 + 1);
            float fx = x - x0, fy = y - y0;
            int p00 = d.px[y0 * d.w + x0], p10 = d.px[y0 * d.w + x1], p01 = d.px[y1 * d.w + x0], p11 = d.px[y1 * d.w + x1];
            int g = Math.max(Math.max((p00 >> 8) & 255, (p10 >> 8) & 255), Math.max((p01 >> 8) & 255, (p11 >> 8) & 255));
            if (g > 127) return 0f;
            float r = ((p00 & 255) * (1 - fx) + (p10 & 255) * fx) * (1 - fy) + ((p01 & 255) * (1 - fx) + (p11 & 255) * fx) * fy;
            float dn = Math.min(1f, Math.max(0f, (r - 1f) / 254f));
            return inF + dn * (inN - inF);
        }

        private Mesh buildMesh(Depth d, int W, int H, float inN, float inF, boolean dilate, float mg, int maxVerts) {
            float span = 1f + 2f * mg;
            float cell = Math.max(4f, (float) Math.sqrt(span * W * span * H / maxVerts));
            int nx = (int) Math.ceil(span * W / cell), ny = (int) Math.ceil(span * H / cell);
            int W1 = nx + 1, H1 = ny + 1, vc = W1 * H1;
            float[] zz = new float[vc], us = new float[vc], vs = new float[vc];
            for (int j = 0; j <= ny; j++) {
                float v = -mg + span * j / ny;
                for (int i = 0; i <= nx; i++) {
                    float u = -mg + span * i / nx;
                    int k = j * W1 + i;
                    us[k] = u; vs[k] = v;
                    zz[k] = samp(d, u, v, inN, inF);
                }
            }
            if (dilate) {
                // les objets proches débordent un peu sur le fond aux arêtes : l'étirement montre le fond, pas un bord flou
                float thr = 0.12f * (inN - inF);
                int R = 3;
                float[] hm = new float[vc], vm = new float[vc];
                for (int j = 0; j < H1; j++) for (int i = 0; i < W1; i++) {
                    float m1 = zz[j * W1 + i];
                    for (int q = -R; q <= R; q++) { int x = i + q; if (x < 0 || x >= W1) continue; float z = zz[j * W1 + x]; if (z > m1) m1 = z; }
                    hm[j * W1 + i] = m1;
                }
                for (int j = 0; j < H1; j++) for (int i = 0; i < W1; i++) {
                    float m2 = hm[j * W1 + i];
                    for (int q = -R; q <= R; q++) { int y = j + q; if (y < 0 || y >= H1) continue; float z = hm[y * W1 + i]; if (z > m2) m2 = z; }
                    vm[j * W1 + i] = m2;
                }
                for (int k = 0; k < vc; k++) if (vm[k] - zz[k] > thr) zz[k] = vm[k];
            }
            FloatBuffer vb = ByteBuffer.allocateDirect(vc * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int j = 0; j < H1; j++) for (int i = 0; i < W1; i++) {
                int k = j * W1 + i;
                float z0 = zz[k], gp = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    int y = j + dj; if (y < 0 || y >= H1) continue;
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int x = i + di; if (x < 0 || x >= W1) continue;
                        float g = Math.abs(zz[y * W1 + x] - z0); if (g > gp) gp = g;
                    }
                }
                vb.put(us[k]).put(vs[k]).put(z0).put(gp);
            }
            vb.position(0);
            return upload(vb, grid(nx, ny));
        }

        private static IntBuffer grid(int nx, int ny) {
            IntBuffer ib = ByteBuffer.allocateDirect(nx * ny * 6 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
                int a = j * (nx + 1) + i, b = a + 1, c = a + nx + 1, dd = c + 1;
                ib.put(a).put(c).put(b).put(b).put(c).put(dd);
            }
            ib.position(0);
            return ib;
        }

        /** Décor à l'infini (cabines) : simple grille sur l'étendue de la plaque, profondeur nulle. */
        private Mesh planeAtInfinity(Scene s) {
            int n = 48;
            float u0 = s.plext[0] / s.W, v0 = s.plext[1] / s.H, du = s.plext[2] / s.W, dv = s.plext[3] / s.H;
            FloatBuffer vb = ByteBuffer.allocateDirect((n + 1) * (n + 1) * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int j = 0; j <= n; j++) for (int i = 0; i <= n; i++)
                vb.put(u0 + du * i / n).put(v0 + dv * j / n).put(0f).put(0f);
            vb.position(0);
            return upload(vb, grid(n, n));
        }

        private static Mesh upload(FloatBuffer vb, IntBuffer ib) {
            Mesh m = new Mesh();
            int[] b = new int[2];
            GLES30.glGenBuffers(2, b, 0);
            m.vbo = b[0]; m.ibo = b[1];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vb.capacity() * 4, vb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib.capacity() * 4, ib, GLES30.GL_STATIC_DRAW);
            m.n = ib.capacity();
            return m;
        }

        private void freeScene() {
            if (sc == null) return;
            int[] t = {sc.still, sc.alpha, sc.plate0};
            GLES30.glDeleteTextures(3, t, 0);
            if (sc.front != null) sc.front.release();
            if (sc.back != null) sc.back.release();
            if (sc.plate != null) sc.plate.release();
            if (sc.delta != null) sc.delta.release();
            sc = null;
        }

        void pauseVideos() { if (sc != null) { if (sc.plate != null) sc.plate.pause(); if (sc.delta != null) sc.delta.pause(); } }
        void resumeVideos() { if (sc != null) { if (sc.plate != null) sc.plate.resume(); if (sc.delta != null) sc.delta.resume(); } }
        void release() { freeScene(); }

        // ------------------------------------------------------------------ dessin
        private final float[] RT = new float[9];

        void draw(int vw, int vh, float xOff, float tiltX, float tiltY, SharedPreferences prefs) {
            GLES30.glViewport(0, 0, Math.max(1, vw), Math.max(1, vh));
            GLES30.glClearColor(0.02f, 0.07f, 0.10f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            Scene s = sc;
            if (s == null || vw < 2 || vh < 2) return;

            float inten = prefs.getInt("intensity", 60) / 60f;
            boolean inv = prefs.getBoolean("invert", false);
            boolean pages = prefs.getBoolean("pages", true);

            // champ de la photo entière (mêmes règles que le lecteur web)
            float tanH0 = (float) Math.tan(Math.toRadians(s.hfov / 2));
            float tanHs = tanH0 * s.W / s.ext[2], tanVs = tanH0 * s.H / s.ext[2];
            float aspect = vw / (float) vh;
            // vue : remplit l'écran avec une marge pour pouvoir bouger, sans dépasser ~80° en vertical
            boolean wall = "wallpaper".equals(s.meta.optString("kind"));
            float zoom = wall ? 0.92f : 0.86f;                    // un fond d'écran garde presque tout son cadrage
            float tv = Math.min(tanVs, tanHs / aspect) * zoom;
            if (!wall) tv = Math.min(tv, 0.9f);
            float th = tv * aspect;
            float yawRoom = (float) (Math.atan(tanHs) - Math.atan(th));
            float pitchRoom = (float) (Math.atan(tanVs) - Math.atan(tv));

            // inclinaison du téléphone -> l'œil glisse un peu (relief) et le regard tourne légèrement
            float sg = inv ? -1f : 1f;
            float nx = (float) Math.tanh(tiltY / 0.30f) * sg;     // tourner le téléphone à droite/gauche
            float ny = (float) Math.tanh(tiltX / 0.30f) * sg;     // le pencher vers l'avant/l'arrière
            float tAmp = 0.065f * s.near * inten;
            float Tx = -nx * tAmp, Ty = -ny * tAmp;
            float yaw = nx * 0.35f * yawRoom * Math.min(1f, inten);
            float pitch = -ny * 0.35f * pitchRoom * Math.min(1f, inten);
            if (pages) yaw += (xOff - 0.5f) * 2f * yawRoom * 0.85f;
            yaw = clamp(yaw, -yawRoom * 0.97f, yawRoom * 0.97f);
            pitch = clamp(pitch, -pitchRoom * 0.97f, pitchRoom * 0.97f);

            // R = Ry(lacet)·Rx(tangage) (caméra -> photo) ; on passe R en lignes = R^T en colonnes
            float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw), cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
            float[] R = {cy, sy * sp, sy * cp,
                         0, cp, -sp,
                         -sy, cy * sp, cy * cp};
            System.arraycopy(R, 0, RT, 0, 9);
            float projX = 1f / th, projY = 1f / tv;

            GLES30.glDisable(GLES30.GL_BLEND);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);

            // ---- arrière-plan : décor (vidéo ou image fixe) à sa propre profondeur, ou à l'infini
            boolean pv = s.plate != null && s.plate.update();
            int pb = pv ? pBackV : pBack;
            if (pv || s.plate0 != 0) {
                GLES30.glUseProgram(pb);
                common(pb, Tx, Ty, tanHs, tanVs, projX, projY);
                float[] PX = s.plext;
                GLES30.glUniform4f(loc(pb, "u_pp"), s.W / PX[2], s.H / PX[3], -PX[0] / PX[2], -PX[1] / PX[3]);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                if (pv) {
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.plate.tex);
                    GLES30.glUniformMatrix4fv(loc(pb, "u_pm"), 1, false, s.plate.m, 0);
                    GLES30.glUniform1f(loc(pb, "u_rot"), s.plrot ? 1f : 0f);
                } else {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.plate0);
                }
                GLES30.glUniform1i(loc(pb, "u_plate"), 0);
                drawMesh(s.back);
            }
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);

            // ---- premier plan : cabine / objets proches, avec la vidéo des écrans et lumières
            boolean dv = s.delta != null && s.delta.update();
            int pf = dv ? pFrontD : pFront;
            GLES30.glUseProgram(pf);
            common(pf, Tx, Ty, tanHs, tanVs, projX, projY);
            GLES30.glUniform2f(loc(pf, "u_wh"), s.W, s.H);
            GLES30.glUniform2f(loc(pf, "u_fade"), s.fadeX, s.fadeY);
            float th2 = (float) Math.hypot(Tx, Ty);
            GLES30.glUniform1f(loc(pf, "u_sk"), th2 / (2f * tanHs * 4f / s.W));
            GLES30.glUniform2f(loc(pf, "u_sf"), 3f, 9f);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.still);
            GLES30.glUniform1i(loc(pf, "u_still"), 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.alpha);
            GLES30.glUniform1i(loc(pf, "u_alpha"), 2);
            if (dv) {
                float[] EX = s.ext;
                GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.delta.tex);
                GLES30.glUniform1i(loc(pf, "u_delta"), 3);
                GLES30.glUniformMatrix4fv(loc(pf, "u_dm"), 1, false, s.delta.m, 0);
                GLES30.glUniform4f(loc(pf, "u_pl"), s.W / EX[2], s.H / EX[3], -EX[0] / EX[2], -EX[1] / EX[3]);
                GLES30.glUniform1f(loc(pf, "u_dk"), s.dmax / 127f);
            }
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            drawMesh(s.front);
            GLES30.glDisable(GLES30.GL_BLEND);
        }

        private void common(int p, float Tx, float Ty, float tanHs, float tanVs, float projX, float projY) {
            GLES30.glUniformMatrix3fv(loc(p, "u_RT"), 1, false, RT, 0);
            GLES30.glUniform3f(loc(p, "u_T"), Tx, Ty, 0f);
            GLES30.glUniform2f(loc(p, "u_tanSrc"), tanHs, tanVs);
            GLES30.glUniform2f(loc(p, "u_proj"), projX, projY);
        }

        private final java.util.HashMap<String, Integer> locs = new java.util.HashMap<>();
        private int loc(int p, String n) {
            String k = p + n;
            Integer v = locs.get(k);
            if (v == null) { v = GLES30.glGetUniformLocation(p, n); locs.put(k, v); }
            return v;
        }

        private static void drawMesh(Mesh m) {
            if (m == null) return;
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.n, GLES30.GL_UNSIGNED_INT, 0);
            GLES30.glDisableVertexAttribArray(0);
        }
    }
}
