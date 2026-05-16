import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * PerformanceManager — FPS counter, dynamic quality scaling, debug overlay.
 *
 * ADD TO GamePanel:
 *   PerformanceManager perf = new PerformanceManager();
 *
 * IN update():
 *   perf.onUpdate();
 *
 * IN paintComponent() AFTER rendering:
 *   perf.onFrame(g, state == GameState.PLAYING);
 *
 * TOGGLE DEBUG: Press F3
 */
public class PerformanceManager {

    // ── FPS / timing ─────────────────────────────────────────────
    private long   lastFrameTime  = System.nanoTime();
    private long   lastSecond     = System.nanoTime();
    private int    frameCount     = 0;
    private int    updateCount    = 0;
    private int    displayFPS     = 60;
    private int    displayUPS     = 60;
    private double frameTimeMs    = 16.67;

    // ── Timing buckets (nanoseconds) ─────────────────────────────
    private long   renderStartNs  = 0;
    private long   updateStartNs  = 0;
    private long   renderTimeNs   = 0;
    private long   updateTimeNs   = 0;

    // ── Dynamic quality ───────────────────────────────────────────
    private static final int TARGET_FPS     = 60;
    private static final int LOW_FPS_THRESH = 45;
    private static final int HI_FPS_THRESH  = 58;
    private static final int ADAPT_DELAY    = 120; // frames between adjustments

    private int    adaptCooldown   = 0;
    private int    qualityLevel    = 2; // 0=LOW 1=MED 2=HIGH
    private boolean autoQuality    = true;

    // ── Debug overlay ─────────────────────────────────────────────
    private boolean debugVisible   = false;
    private BufferedImage debugBg  = null;

    // Stats counters (set by game systems each frame)
    public int statEnemies    = 0;
    public int statParticles  = 0;
    public int statBullets    = 0;
    public int statLights     = 0;

    // ── Cached resources ──────────────────────────────────────────
    private static final Font   DBG_FONT  = new Font("Monospaced", Font.PLAIN, 11);
    private static final Color  DBG_BG    = new Color(0, 0, 0, 180);
    private static final Color  DBG_GREEN = new Color(100, 255, 100);
    private static final Color  DBG_YELLOW= new Color(255, 220,  60);
    private static final Color  DBG_RED   = new Color(255,  80,  80);
    private static final Color  DBG_CYAN  = new Color(100, 220, 255);
    private static final Color  DBG_WHITE = new Color(220, 220, 255);

    // ── Runtime ──────────────────────────────────────────────────
    private final Runtime rt = Runtime.getRuntime();

    public PerformanceManager() {}

    // ── Call at START of update() ─────────────────────────────────
    public void updateStart() {
        updateStartNs = System.nanoTime();
    }

    // ── Call at END of update() ───────────────────────────────────
    public void updateEnd() {
        updateTimeNs = System.nanoTime() - updateStartNs;
        updateCount++;
    }

    // ── Call at START of paintComponent ──────────────────────────
    public void renderStart() {
        renderStartNs = System.nanoTime();
        frameCount++;

        long now     = System.nanoTime();
        frameTimeMs  = (now - lastFrameTime) / 1_000_000.0;
        lastFrameTime = now;

        // Update display FPS every second
        if (now - lastSecond >= 1_000_000_000L) {
            displayFPS    = frameCount;
            displayUPS    = updateCount;
            frameCount    = 0;
            updateCount   = 0;
            lastSecond    = now;
        }
    }

    // ── Call at END of paintComponent ────────────────────────────
    public void renderEnd() {
        renderTimeNs = System.nanoTime() - renderStartNs;
    }

    // ── Dynamic quality adjustment ────────────────────────────────
    public Quality getRecommendedQuality(Quality current) {
        if (!autoQuality) return current;
        if (--adaptCooldown > 0) return current;
        adaptCooldown = ADAPT_DELAY;

        if (displayFPS < LOW_FPS_THRESH && qualityLevel > 0) {
            qualityLevel--;
            System.out.println("[Perf] FPS=" + displayFPS +
                    " → Reducing quality to " + qualityName());
        } else if (displayFPS >= HI_FPS_THRESH && qualityLevel < 2) {
            qualityLevel++;
            System.out.println("[Perf] FPS=" + displayFPS +
                    " → Increasing quality to " + qualityName());
        }
        return switch (qualityLevel) {
            case 0 -> Quality.LOW;
            case 1 -> Quality.MEDIUM;
            default -> Quality.HIGH;
        };
    }

    private String qualityName() {
        return switch (qualityLevel) { case 0->"LOW"; case 1->"MEDIUM"; default->"HIGH"; };
    }

    // ── Toggle debug overlay (call from key handler on F3) ───────
    public void toggleDebug() { debugVisible = !debugVisible; }
    public boolean isDebugVisible() { return debugVisible; }

    // ── Draw debug overlay ────────────────────────────────────────
    public void drawDebug(Graphics2D g, Settings settings) {
        if (!debugVisible) return;

        long usedMB  = (rt.totalMemory() - rt.freeMemory()) / (1024*1024);
        long totalMB = rt.totalMemory() / (1024*1024);
        double renderMs = renderTimeNs / 1_000_000.0;
        double updateMs = updateTimeNs / 1_000_000.0;

        String[] lines = {
                "─── VOIDWALKER DEBUG (F3) ───",
                "FPS:      " + displayFPS + "  /  UPS: " + displayUPS,
                "Frame:    " + String.format("%.2f", frameTimeMs) + " ms",
                "Render:   " + String.format("%.2f", renderMs)   + " ms",
                "Update:   " + String.format("%.2f", updateMs)   + " ms",
                "─────────────────────────────",
                "Enemies:  " + statEnemies,
                "Particles:" + statParticles,
                "Bullets:  " + statBullets,
                "Lights:   " + statLights,
                "─────────────────────────────",
                "Memory:   " + usedMB + " / " + totalMB + " MB",
                "Quality:  " + settings.quality,
                "AutoQ:    " + (autoQuality ? "ON" : "OFF"),
        };

        int x = 10, y = 10;
        int w = 210, lh = 15;
        int h = lines.length * lh + 10;

        // Background
        g.setColor(DBG_BG);
        g.fillRoundRect(x, y, w, h, 8, 8);

        // Lines
        g.setFont(DBG_FONT);
        int ty = y + lh;
        for (String line : lines) {
            Color c = DBG_WHITE;
            if (line.startsWith("FPS")) {
                c = displayFPS >= 55 ? DBG_GREEN : displayFPS >= 40 ? DBG_YELLOW : DBG_RED;
            } else if (line.startsWith("───")) {
                c = new Color(80, 80, 120);
            } else if (line.contains("Memory")) {
                c = usedMB > totalMB * 0.8 ? DBG_RED : DBG_CYAN;
            }
            g.setColor(c);
            g.drawString(line, x + 6, ty);
            ty += lh;
        }

        // FPS bar
        int barY = ty + 4;
        float fpsPct = Math.min(1f, displayFPS / (float)TARGET_FPS);
        g.setColor(new Color(30, 30, 30));
        g.fillRect(x + 6, barY, w - 12, 6);
        g.setColor(fpsPct >= 0.9f ? DBG_GREEN : fpsPct >= 0.6f ? DBG_YELLOW : DBG_RED);
        g.fillRect(x + 6, barY, (int)((w - 12) * fpsPct), 6);
    }

    // ── Getters ───────────────────────────────────────────────────
    public int    getFPS()          { return displayFPS; }
    public int    getUPS()          { return displayUPS; }
    public double getFrameTimeMs()  { return frameTimeMs; }
    public void   setAutoQuality(boolean b) { autoQuality = b; }
    public boolean isAutoQuality()  { return autoQuality; }
}
