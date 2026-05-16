import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.HashMap;
import java.util.Map;

/**
 * RenderCache — Central cache for ALL expensive rendering resources.
 *
 * WHY: Creating new Color/Font/RadialGradientPaint every frame triggers GC.
 *      Pre-allocating them as static finals eliminates all allocation in hot paths.
 *
 * ADD TO GamePanel:
 *   // At class level — already static, no instance needed
 *   // Just call RenderCache.FONT_HUD, RenderCache.C_RED, etc.
 */
public final class RenderCache {

    private RenderCache() {} // static only

    // ════════════════════════════════════════════════════════════
    //  COLORS — static finals = zero allocation per frame
    // ════════════════════════════════════════════════════════════

    // Game colors
    public static final Color C_RED         = new Color(220,  50,  50);
    public static final Color C_RED_DARK    = new Color(140,  30,  30);
    public static final Color C_RED_TRANS   = new Color(220,  50,  50, 120);
    public static final Color C_BLUE        = new Color(  0, 150, 255);
    public static final Color C_BLUE_DARK   = new Color(  0,  80, 180);
    public static final Color C_GOLD        = new Color(255, 200,  60);
    public static final Color C_GREEN       = new Color( 80, 220, 120);
    public static final Color C_PURPLE      = new Color(140,  80, 255);
    public static final Color C_CYAN        = new Color(  0, 220, 255);
    public static final Color C_WHITE       = Color.WHITE;
    public static final Color C_BLACK       = Color.BLACK;

    // Projectile colors
    public static final Color C_PROJ1       = new Color(100, 180, 255);
    public static final Color C_PROJ2       = new Color(120, 255, 180);
    public static final Color C_PROJ3       = new Color(255, 160,  60);
    public static final Color C_PROJ_REAR   = new Color(200, 120,  50);
    public static final Color C_PROJ_HIT    = new Color(100, 150, 255);
    public static final Color C_PROJ_ENEMY  = new Color(255, 100, 100);

    // Combat colors
    public static final Color C_CHAIN       = new Color(220, 220,  80);
    public static final Color C_EXPLO       = new Color(255, 140,   0);
    public static final Color C_BOSS1       = new Color(255,  50, 100);
    public static final Color C_BOSS2       = new Color(255, 150,  50);
    public static final Color C_FREEZE      = new Color(150, 200, 255);
    public static final Color C_HEAL        = new Color( 80, 255, 130);

    // HUD colors
    public static final Color C_HP_FG       = new Color(200,  50,  50);
    public static final Color C_HP_BG       = new Color( 40,  10,  10);
    public static final Color C_SHIELD_FG   = new Color( 60, 120, 255);
    public static final Color C_SHIELD_BG   = new Color( 10,  20,  50);
    public static final Color C_XP_FG       = new Color(200, 150,  50);
    public static final Color C_XP_BG       = new Color( 30,  20,   5);
    public static final Color C_PANEL_BG    = new Color(  0,   0,   0, 180);
    public static final Color C_PANEL_BDR   = new Color( 60,  60, 100);

    // Transparency variants
    public static final Color C_DARK_60     = new Color(0, 0, 0,  60);
    public static final Color C_DARK_100    = new Color(0, 0, 0, 100);
    public static final Color C_DARK_160    = new Color(0, 0, 0, 160);
    public static final Color C_DARK_185    = new Color(0, 0, 0, 185);
    public static final Color C_TRANS       = new Color(0, 0, 0,   0);

    // ════════════════════════════════════════════════════════════
    //  FONTS — pre-created once
    // ════════════════════════════════════════════════════════════

    public static final Font F_MONO_10B = new Font("Monospaced", Font.BOLD,  10);
    public static final Font F_MONO_11  = new Font("Monospaced", Font.PLAIN, 11);
    public static final Font F_MONO_12B = new Font("Monospaced", Font.BOLD,  12);
    public static final Font F_MONO_13B = new Font("Monospaced", Font.BOLD,  13);
    public static final Font F_MONO_14B = new Font("Monospaced", Font.BOLD,  14);
    public static final Font F_MONO_16B = new Font("Monospaced", Font.BOLD,  16);
    public static final Font F_MONO_20B = new Font("Monospaced", Font.BOLD,  20);
    public static final Font F_MONO_24B = new Font("Monospaced", Font.BOLD,  24);
    public static final Font F_MONO_28B = new Font("Monospaced", Font.BOLD,  28);
    public static final Font F_MONO_32B = new Font("Monospaced", Font.BOLD,  32);
    public static final Font F_MONO_42B = new Font("Monospaced", Font.BOLD,  42);
    public static final Font F_MONO_48B = new Font("Monospaced", Font.BOLD,  48);

    // ════════════════════════════════════════════════════════════
    //  STROKES — pre-created once
    // ════════════════════════════════════════════════════════════

    public static final BasicStroke STR_1   = new BasicStroke(1f);
    public static final BasicStroke STR_15  = new BasicStroke(1.5f);
    public static final BasicStroke STR_2   = new BasicStroke(2f);
    public static final BasicStroke STR_25  = new BasicStroke(2.5f);
    public static final BasicStroke STR_3   = new BasicStroke(3f);
    public static final BasicStroke STR_DASH = new BasicStroke(1.4f);
    public static final BasicStroke STR_RING = new BasicStroke(1.6f);
    public static final BasicStroke STR_TICK = new BasicStroke(1.8f);
    public static final BasicStroke STR_TRAJ = new BasicStroke(2.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    // ════════════════════════════════════════════════════════════
    //  COMPOSITES — pre-created once
    // ════════════════════════════════════════════════════════════

    public static final AlphaComposite AC_SRC_OVER =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER);
    public static final AlphaComposite AC_DST_IN =
            AlphaComposite.getInstance(AlphaComposite.DST_IN);
    public static final AlphaComposite AC_CLEAR =
            AlphaComposite.getInstance(AlphaComposite.CLEAR);
    public static final AlphaComposite AC_50 =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f);
    public static final AlphaComposite AC_75 =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f);

    // ════════════════════════════════════════════════════════════
    //  GRADIENT CACHE — RadialGradientPaint is expensive to create
    //  Cache by key = "cx_cy_r_colorsHash"
    // ════════════════════════════════════════════════════════════

    private static final Map<Long, RadialGradientPaint> gradCache = new HashMap<>(64);
    private static final int MAX_GRAD_CACHE = 200;

    /**
     * Get or create a RadialGradientPaint.
     * Key is position + radius — position changes every frame so this is
     * mainly useful for fixed-position gradients (UI, HUD).
     * For moving entities (player light), use uncached version.
     */
    public static RadialGradientPaint getRadialGrad(
            float cx, float cy, float r, float[] fracs, Color[] cols) {
        long key = ((long)(cx*10) << 32) | ((long)(cy*10) << 16) | (long)(r*10);
        RadialGradientPaint cached = gradCache.get(key);
        if (cached == null) {
            cached = new RadialGradientPaint(
                    new Point2D.Float(cx, cy), r, fracs, cols,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            if (gradCache.size() < MAX_GRAD_CACHE)
                gradCache.put(key, cached);
        }
        return cached;
    }

    public static void clearGradCache() { gradCache.clear(); }

    // ════════════════════════════════════════════════════════════
    //  PRE-BAKED IMAGES — health bar, shield bar backgrounds etc.
    // ════════════════════════════════════════════════════════════

    // Pre-rendered health bar backgrounds (avoids per-frame fillRoundRect)
    private static BufferedImage hpBarBg     = null;
    private static BufferedImage shieldBarBg = null;

    public static BufferedImage getHpBarBg(int w, int h) {
        if (hpBarBg == null || hpBarBg.getWidth() != w) {
            hpBarBg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = hpBarBg.createGraphics();
            g.setColor(C_HP_BG);
            g.fillRoundRect(0, 0, w, h, h, h);
            g.dispose();
        }
        return hpBarBg;
    }

    public static BufferedImage getShieldBarBg(int w, int h) {
        if (shieldBarBg == null || shieldBarBg.getWidth() != w) {
            shieldBarBg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = shieldBarBg.createGraphics();
            g.setColor(C_SHIELD_BG);
            g.fillRoundRect(0, 0, w, h, h, h);
            g.dispose();
        }
        return shieldBarBg;
    }

    /** Invalidate pre-baked images (call on window resize). */
    public static void invalidateBakedImages() {
        hpBarBg     = null;
        shieldBarBg = null;
    }
}
