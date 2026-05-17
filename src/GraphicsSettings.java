import java.io.*;
import java.awt.*;

/**
 * GraphicsSettings — Extended settings for fullscreen performance tuning.
 *
 * Separates graphics-specific settings from game settings (Settings.java).
 * Saved to voidwalker_graphics.dat alongside existing save files.
 *
 * ADD TO GamePanel:
 *   GraphicsSettings gfx = GraphicsSettings.load();
 *
 * USE IN applyDisplayMode():
 *   if (gfx.fullscreen) fsManager.enterFullscreen();
 *   else if (gfx.borderless) fsManager.enterBorderless();
 */
public class GraphicsSettings implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final String SAVE_FILE = "voidwalker_graphics.dat";

    // ── Display ───────────────────────────────────────────────────
    public boolean fullscreen   = false;
    public boolean borderless   = false;
    public boolean vsync        = false;   // VSync (limits FPS to refresh rate)

    // ── Render Scale ──────────────────────────────────────────────
    // 1.0 = native 960×640, 0.75 = 720×480 scaled up, 0.5 = 480×320 scaled up
    // Lower = faster, slightly blurrier
    public float   renderScale  = 1.0f;

    // ── Target FPS ────────────────────────────────────────────────
    public int     targetFPS    = 60;      // 60, 120, or 144

    // ── Quality ───────────────────────────────────────────────────
    public int     lightingRes  = 1;       // 1=full, 2=half, 4=quarter resolution
    public boolean showDebug    = false;
    public boolean autoQuality  = true;

    // ── Render dimensions (computed from renderScale) ─────────────
    public int getRenderW() { return (int)(960 * renderScale); }
    public int getRenderH() { return (int)(640 * renderScale); }

    public static GraphicsSettings load() {
        try (ObjectInputStream in = new ObjectInputStream(
                new FileInputStream(SAVE_FILE))) {
            return (GraphicsSettings) in.readObject();
        } catch (Exception e) {
            return new GraphicsSettings(); // defaults
        }
    }

    public void save() {
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(SAVE_FILE))) {
            out.writeObject(this);
        } catch (IOException e) {
            System.err.println("[GFX] Could not save graphics settings: " + e.getMessage());
        }
    }

    @Override public String toString() {
        return String.format(
                "GFX[fs=%b bl=%b scale=%.0f%% fps=%d lightRes=%d]",
                fullscreen, borderless, renderScale*100, targetFPS, lightingRes);
    }

    // ── Preset helpers ────────────────────────────────────────────
    public void applyPresetLow() {
        renderScale = 0.75f; lightingRes = 2; targetFPS = 60; vsync = false;
    }
    public void applyPresetMedium() {
        renderScale = 1.0f;  lightingRes = 1; targetFPS = 60; vsync = false;
    }
    public void applyPresetHigh() {
        renderScale = 1.0f;  lightingRes = 1; targetFPS = 144; vsync = false;
    }
    public void applyPresetUltra() {
        renderScale = 1.0f;  lightingRes = 1; targetFPS = 144; vsync = true;
    }
}
