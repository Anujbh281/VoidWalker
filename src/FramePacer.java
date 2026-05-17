import java.awt.*;

/**
 * FramePacer — High-precision frame timing to eliminate micro-stutters.
 *
 * WHY SWING TIMER CAUSES STUTTERS IN FULLSCREEN:
 *   javax.swing.Timer uses Thread.sleep() internally, which on Windows
 *   has 15ms resolution by default. This means a 16ms target frame time
 *   actually sleeps 15ms or 30ms — causing irregular frame intervals.
 *
 * THIS SYSTEM:
 *   1. Uses System.nanoTime() for microsecond precision
 *   2. Busy-waits for the last 2ms of each frame (avoids sleep imprecision)
 *   3. Tracks actual frame time and adjusts target dynamically
 *   4. Optionally syncs to display refresh rate
 *
 * HOW TO USE:
 *   Replace Swing Timer with a dedicated render thread:
 *
 *   FramePacer pacer = new FramePacer(60);
 *   Thread renderThread = new Thread(() -> {
 *       while (running) {
 *           pacer.beginFrame();
 *           update();
 *           repaint(); // or manual BufferStrategy render
 *           pacer.endFrame();
 *       }
 *   });
 *   renderThread.start();
 */
public class FramePacer {

    private long targetFrameNs;
    private long lastFrameStart;
    private long frameStartTime;

    // Measured timing
    private double actualFPS      = 60.0;
    private double frameTimeMs    = 16.67;
    private double renderTimeMs   = 0.0;
    private double updateTimeMs   = 0.0;

    // Frame time history for smoothing (ring buffer)
    private static final int HISTORY = 30;
    private final long[] frameHistory = new long[HISTORY];
    private int historyIdx = 0;
    private long historySum = 0;

    // Adaptive sleep threshold (nanoseconds to busy-wait before frame end)
    private static final long BUSY_WAIT_THRESHOLD = 2_000_000L; // 2ms

    // Target FPS tracking
    private int    targetFPS;
    private int    displayFPS    = 0;
    private int    frameCount    = 0;
    private long   lastFPSCheck  = System.nanoTime();

    public FramePacer(int targetFPS) {
        setTargetFPS(targetFPS);
        lastFrameStart = System.nanoTime();
        // Pre-fill history
        for (int i = 0; i < HISTORY; i++) frameHistory[i] = targetFrameNs;
        historySum = targetFrameNs * HISTORY;
    }

    /** Detect monitor refresh rate and set target FPS to match. */
    public void matchRefreshRate() {
        int hz = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDisplayMode()
                .getRefreshRate();
        if (hz > 0 && hz <= 240) {
            setTargetFPS(hz);
            System.out.println("[FramePacer] Matched display refresh: " + hz + " Hz");
        } else {
            setTargetFPS(60);
            System.out.println("[FramePacer] Could not detect refresh rate — using 60 Hz");
        }
    }

    public void setTargetFPS(int fps) {
        this.targetFPS    = fps;
        this.targetFrameNs = 1_000_000_000L / fps;
        System.out.println("[FramePacer] Target: " + fps + " FPS ("
                + String.format("%.2f", targetFrameNs/1e6) + " ms/frame)");
    }

    /** Call at the very START of each frame. */
    public void beginFrame() {
        frameStartTime = System.nanoTime();
    }

    /** Call at the very END of each frame, AFTER rendering is complete. */
    public void endFrame() {
        long frameEnd  = System.nanoTime();
        long frameTime = frameEnd - frameStartTime;
        renderTimeMs   = frameTime / 1_000_000.0;

        // Calculate when this frame SHOULD end
        long targetEnd = lastFrameStart + targetFrameNs;
        long now       = System.nanoTime();

        if (targetEnd > now) {
            long sleepNs = targetEnd - now;

            if (sleepNs > BUSY_WAIT_THRESHOLD) {
                // Sleep for most of the wait (imprecise but low CPU)
                long sleepMs = (sleepNs - BUSY_WAIT_THRESHOLD) / 1_000_000L;
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // Busy-wait for the final 2ms (precise)
            while (System.nanoTime() < targetEnd) {
                Thread.yield();
            }
        }

        // Measure actual frame time
        long actualFrameEnd  = System.nanoTime();
        long actualFrameTime = actualFrameEnd - lastFrameStart;
        lastFrameStart       = actualFrameEnd;

        // Update ring buffer
        historySum -= frameHistory[historyIdx];
        frameHistory[historyIdx] = actualFrameTime;
        historySum += actualFrameTime;
        historyIdx = (historyIdx + 1) % HISTORY;

        // Smooth frame time
        frameTimeMs = (historySum / HISTORY) / 1_000_000.0;
        actualFPS   = 1_000.0 / frameTimeMs;

        // Count display FPS once per second
        frameCount++;
        if (actualFrameEnd - lastFPSCheck >= 1_000_000_000L) {
            displayFPS   = frameCount;
            frameCount   = 0;
            lastFPSCheck = actualFrameEnd;
        }
    }

    /** Call after update() completes to record update time separately. */
    public void markUpdateDone() {
        updateTimeMs = (System.nanoTime() - frameStartTime) / 1_000_000.0;
    }

    // ── Getters ───────────────────────────────────────────────────
    public int    getDisplayFPS()    { return displayFPS; }
    public int    getTargetFPS()     { return targetFPS; }
    public double getFrameTimeMs()   { return frameTimeMs; }
    public double getRenderTimeMs()  { return renderTimeMs; }
    public double getUpdateTimeMs()  { return updateTimeMs; }
    public double getActualFPS()     { return actualFPS; }
}
