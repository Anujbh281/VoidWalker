import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import javax.swing.*;
import java.awt.image.BufferStrategy;

/**
 * FullscreenManager — Handles true exclusive fullscreen + borderless windowed.
 *
 * WHY FULLSCREEN WAS SLOW:
 *   JPanel.paintComponent() runs on the EDT (Event Dispatch Thread).
 *   In fullscreen, Swing still routes rendering through EDT + repaint manager,
 *   causing synchronization overhead. True fullscreen uses a dedicated
 *   rendering thread that bypasses EDT completely.
 *
 * THIS SYSTEM:
 *   1. Uses GraphicsDevice.setFullScreenWindow() for TRUE exclusive fullscreen
 *   2. Renders via BufferStrategy (triple-buffered, hardware-accelerated)
 *   3. Uses VolatileImage as off-screen render target (GPU-resident memory)
 *   4. Separates update thread from render thread
 *
 * INTEGRATION:
 *   Replace applyDisplayMode() in GamePanel with FullscreenManager calls.
 */
public class FullscreenManager {

    // ── Internal render resolution (game always renders at this size) ──
    // Scaling to actual screen is done ONCE per frame via drawImage()
    public static final int RENDER_W = 960;
    public static final int RENDER_H = 640;

    private final JFrame         frame;
    private final GraphicsDevice device;
    private final GraphicsConfiguration gc;

    private boolean          fullscreen    = false;
    private boolean          borderless    = false;

    // Off-screen render surface — GPU-resident VolatileImage
    private VolatileImage    renderSurface = null;

    // Cached scaling info (updated on resolution change)
    private int drawX, drawY, drawW, drawH;
    private float scale;

    // ── Hardware acceleration status ──────────────────────────────
    private boolean hwAccelerated = false;

    public FullscreenManager(JFrame frame) {
        this.frame  = frame;
        this.device = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        this.gc     = device.getDefaultConfiguration();
        checkHardwareAcceleration();
    }

    // ── Check if hardware acceleration is available ───────────────
    private void checkHardwareAcceleration() {
        BufferedImage test = gc.createCompatibleImage(16, 16);
        hwAccelerated = (test.getCapabilities(gc).isAccelerated());
        System.out.println("[FS] Hardware acceleration: " + hwAccelerated);
        System.out.println("[FS] Display: " + device.getDisplayMode().getWidth()
                + "×" + device.getDisplayMode().getHeight()
                + " @" + device.getDisplayMode().getRefreshRate() + "Hz");
    }

    // ── Enter TRUE exclusive fullscreen ───────────────────────────
    public void enterFullscreen() {
        if (fullscreen) return;
        if (!device.isFullScreenSupported()) {
            System.out.println("[FS] Exclusive fullscreen not supported — using borderless");
            enterBorderless();
            return;
        }

        frame.dispose();
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setIgnoreRepaint(true);  // critical: bypass Swing repaint
        frame.setVisible(true);

        device.setFullScreenWindow(frame);

        // Try to match display refresh rate
        DisplayMode dm = device.getDisplayMode();
        System.out.println("[FS] Entered exclusive fullscreen: "
                + dm.getWidth() + "×" + dm.getHeight()
                + " @" + dm.getRefreshRate() + "Hz");

        fullscreen   = true;
        borderless   = false;
        updateScaling();
        createRenderSurface();
    }

    // ── Borderless windowed fullscreen (fallback) ─────────────────
    public void enterBorderless() {
        frame.dispose();
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setIgnoreRepaint(true);

        DisplayMode dm = device.getDisplayMode();
        frame.setSize(dm.getWidth(), dm.getHeight());
        frame.setLocation(0, 0);
        frame.setVisible(true);

        System.out.println("[FS] Entered borderless windowed: "
                + dm.getWidth() + "×" + dm.getHeight());

        fullscreen = false;
        borderless = true;
        updateScaling();
        createRenderSurface();
    }

    // ── Exit fullscreen ───────────────────────────────────────────
    public void exitFullscreen(int windowW, int windowH) {
        if (fullscreen) device.setFullScreenWindow(null);

        frame.dispose();
        frame.setUndecorated(false);
        frame.setResizable(false);
        frame.setIgnoreRepaint(false);
        frame.setSize(windowW, windowH);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        fullscreen = false;
        borderless = false;
        updateScaling();

        System.out.println("[FS] Returned to windowed: " + windowW + "×" + windowH);
    }

    // ── Create/recreate the off-screen render surface ─────────────
    /**
     * VolatileImage lives in GPU VRAM — drawing to it is hardware-accelerated.
     * It can be "lost" (GPU memory reclaimed) so we check/restore each frame.
     */
    public void createRenderSurface() {
        if (renderSurface != null) renderSurface.flush();
        renderSurface = gc.createCompatibleVolatileImage(
                RENDER_W, RENDER_H, Transparency.OPAQUE);
        System.out.println("[FS] Render surface created: "
                + RENDER_W + "×" + RENDER_H
                + " accelerated=" + renderSurface.getCapabilities(gc).isAccelerated());
    }

    /**
     * Get the Graphics2D for the render surface.
     * Call at the START of each frame. Checks if surface was lost and recreates.
     *
     * @return Graphics2D in 960×640 game space, or null if surface unavailable
     */
    public Graphics2D beginFrame() {
        if (renderSurface == null) createRenderSurface();

        // VolatileImage can be lost (GPU memory reclaimed) — restore it
        int attempt = 0;
        while (attempt < 3) {
            int status = renderSurface.validate(gc);
            if (status == VolatileImage.IMAGE_INCOMPATIBLE) {
                createRenderSurface(); // hardware changed — rebuild
            }
            attempt++;
            if (!renderSurface.contentsLost()) break;
        }

        Graphics2D g = renderSurface.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING,    RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return g;
    }

    /**
     * Blit the render surface to the screen.
     * Call AFTER all game rendering is done.
     * Uses BufferStrategy for hardware-accelerated output.
     */
    public void endFrame(Graphics2D gameG) {
        gameG.dispose(); // release render surface graphics

        if (renderSurface.contentsLost()) return; // surface lost mid-frame — skip

        // Get screen graphics from BufferStrategy
        BufferStrategy bs = frame.getBufferStrategy();
        if (bs == null) {
            frame.createBufferStrategy(3); // triple buffer
            return;
        }

        do {
            do {
                Graphics screenG = bs.getDrawGraphics();
                try {
                    // Black background (letterbox bars)
                    screenG.setColor(Color.BLACK);
                    screenG.fillRect(0, 0, frame.getWidth(), frame.getHeight());

                    // Scale render surface to screen
                    screenG.drawImage(renderSurface,
                            drawX, drawY, drawX + drawW, drawY + drawH,
                            0, 0, RENDER_W, RENDER_H, null);
                } finally {
                    screenG.dispose();
                }
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());

        // Sync to display (reduces tearing on Linux/Mac)
        Toolkit.getDefaultToolkit().sync();
    }

    // ── Scaling calculations ──────────────────────────────────────
    private void updateScaling() {
        int screenW = frame.getWidth();
        int screenH = frame.getHeight();
        if (screenW <= 0 || screenH <= 0) {
            // Fallback to display mode dimensions
            DisplayMode dm = device.getDisplayMode();
            screenW = dm.getWidth(); screenH = dm.getHeight();
        }

        float sx = (float) screenW / RENDER_W;
        float sy = (float) screenH / RENDER_H;
        scale = Math.min(sx, sy);  // maintain aspect ratio

        drawW = (int)(RENDER_W * scale);
        drawH = (int)(RENDER_H * scale);
        drawX = (screenW - drawW) / 2;
        drawY = (screenH - drawH) / 2;

        System.out.println("[FS] Scaling: " + RENDER_W + "×" + RENDER_H
                + " → " + drawW + "×" + drawH
                + " at (" + drawX + "," + drawY + ") scale=" + scale);
    }

    // ── Getters ───────────────────────────────────────────────────
    public boolean isFullscreen()     { return fullscreen; }
    public boolean isBorderless()     { return borderless; }
    public boolean isHwAccelerated()  { return hwAccelerated; }
    public float   getScale()         { return scale; }
    public int     getDrawX()         { return drawX; }
    public int     getDrawY()         { return drawY; }

    /** Convert screen coordinates to game coordinates. Used by InputHandler. */
    public int screenToGameX(int screenX) {
        return (int)((screenX - drawX) / scale);
    }
    public int screenToGameY(int screenY) {
        return (int)((screenY - drawY) / scale);
    }

    public void dispose() {
        if (fullscreen) device.setFullScreenWindow(null);
        if (renderSurface != null) { renderSurface.flush(); renderSurface = null; }
    }
}
