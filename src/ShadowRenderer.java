import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.List;

/**
 * ShadowRenderer — Correct darkness-with-light-hole approach.
 * Uses DstIn composite to punch a transparent circle into a dark buffer.
 * Result: scene is visible inside the circle, dark outside. No blue glow.
 */
public class ShadowRenderer {

    static final int SW  = 960;
    static final int SH  = 640;
    static final int PAD = 300; // padding so edge punches never overflow

    // Dark buffer (screen + padding on all sides)
    private static BufferedImage darkBuffer = null;
    private static Graphics2D    darkG      = null;
    private static Quality       bufQuality = null;

    // Gradient config
    private static int      lightR      = 0;
    private static int      enemyLightR = 0;
    private static float[]  GRAD_FRAC   = { 0f, 0.45f, 0.80f, 1f };
    private static Color[]  GRAD_COLS   = null;
    private static Color[]  ENEMY_COLS  = null;

    // Ground shadow sprites
    private static BufferedImage shadowSprite     = null;
    private static BufferedImage shadowSpriteDash = null;
    private static Quality       shadowQuality    = null;

    private static final AffineTransform REUSE_AT = new AffineTransform();

    private static final AlphaComposite AC_SRC_OVER =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER);
    private static final AlphaComposite AC_DST_IN =
            AlphaComposite.getInstance(AlphaComposite.DST_IN);

    private static final Color DARK_BASE  = new Color(0, 0, 0, 185);
    private static final Color SHADOW_LOW = new Color(0, 0, 0, 50);

    // ── Main lighting pass ────────────────────────────────────────
    public static void drawLightingPass(Graphics2D screenG,
                                        int playerSX, int playerSY,
                                        List<Enemy> enemies,
                                        int camX, int camY,
                                        Quality quality) {
        if (quality == Quality.LOW) return;
        ensureBuffer(quality);

        int bw = SW + PAD*2;
        int bh = SH + PAD*2;

        // Reset buffer to solid dark
        darkG.setComposite(AC_SRC_OVER);
        darkG.setColor(DARK_BASE);
        darkG.fillRect(0, 0, bw, bh);

        // Enemy lights (dim, punched first)
        if (enemies != null) {
            for (Enemy en : enemies) {
                if (!en.alive) continue;
                int ex = (int)en.x - camX + PAD;
                int ey = (int)en.y - camY + PAD;
                if (ex < -enemyLightR || ex > bw + enemyLightR) continue;
                if (ey < -enemyLightR || ey > bh + enemyLightR) continue;
                punchLight(ex, ey, enemyLightR, ENEMY_COLS);
            }
        }

        // Player light (bright, punched last so it overrides)
        punchLight(playerSX + PAD, playerSY + PAD, lightR, GRAD_COLS);

        // Draw buffer offset by -PAD onto screen
        Composite saved = screenG.getComposite();
        screenG.setComposite(AC_SRC_OVER);
        screenG.drawImage(darkBuffer, -PAD, -PAD, null);
        screenG.setComposite(saved);
    }

    private static void punchLight(int cx, int cy, int r, Color[] cols) {
        RadialGradientPaint rp = new RadialGradientPaint(
                new Point2D.Float(cx, cy), r,
                GRAD_FRAC, cols,
                MultipleGradientPaint.CycleMethod.NO_CYCLE
        );
        darkG.setComposite(AC_DST_IN);
        darkG.setPaint(rp);
        darkG.fillOval(cx - r, cy - r, r*2, r*2);
        darkG.setComposite(AC_SRC_OVER);
    }

    // ── Ground shadow ─────────────────────────────────────────────
    public static void drawGroundShadow(Graphics2D g,
                                        float px, float py,
                                        float vx, float vy,
                                        boolean dashing,
                                        Quality quality) {
        ensureShadowSprites(quality);
        if (quality == Quality.LOW) {
            g.setColor(SHADOW_LOW);
            g.fillOval((int)px - 12, (int)py + 10, 24, 7);
            return;
        }
        float speed = (float)Math.sqrt(vx*vx + vy*vy);
        BufferedImage sprite;
        float scaleX, scaleY, rot;
        if (dashing) {
            sprite = shadowSpriteDash;
            scaleX = 1.5f; scaleY = 0.6f;
            rot = speed > 0 ? (float)Math.atan2(vy, vx) : 0f;
        } else if (speed > 0.3f) {
            sprite = shadowSprite;
            scaleX = Math.min(1.5f, 1f + speed * 0.065f);
            scaleY = 1f;
            rot = (float)Math.atan2(vy, vx);
        } else {
            sprite = shadowSprite;
            scaleX = 1f; scaleY = 1f; rot = 0f;
        }
        int hw = sprite.getWidth()  / 2;
        int hh = sprite.getHeight() / 2;
        REUSE_AT.setToTranslation(px, py + 13f);
        if (rot != 0f)   REUSE_AT.rotate(rot);
        if (scaleX != 1f || scaleY != 1f) REUSE_AT.scale(scaleX, scaleY);
        REUSE_AT.translate(-hw, -hh);
        g.drawImage(sprite, REUSE_AT, null);
    }

    public static void invalidateCache() {
        if (darkG != null) { darkG.dispose(); darkG = null; }
        darkBuffer = null; bufQuality = null;
        shadowSprite = null; shadowSpriteDash = null; shadowQuality = null;
        GRAD_COLS = null; ENEMY_COLS = null;
    }

    private static void ensureBuffer(Quality quality) {
        if (darkBuffer != null && bufQuality == quality) return;
        bufQuality = quality;
        if (darkG != null) darkG.dispose();
        int bw = SW + PAD*2, bh = SH + PAD*2;
        darkBuffer = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        darkG = darkBuffer.createGraphics();
        darkG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        darkG.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);
        lightR      = (quality == Quality.HIGH) ? 260 : 210;
        enemyLightR = (quality == Quality.HIGH) ?  80 :  60;
        // Player: transparent centre (bright) → fully dark edge
        GRAD_COLS = new Color[]{
                new Color(0,0,0,0),
                new Color(0,0,0,0),
                new Color(0,0,0,130),
                new Color(0,0,0,255),
        };
        // Enemy: dimmer — keeps more darkness
        ENEMY_COLS = new Color[]{
                new Color(0,0,0,80),
                new Color(0,0,0,140),
                new Color(0,0,0,210),
                new Color(0,0,0,255),
        };
    }

    private static void ensureShadowSprites(Quality quality) {
        if (shadowSprite != null && shadowQuality == quality) return;
        shadowQuality    = quality;
        shadowSprite     = buildShadowSprite(26, 8,  0.48f);
        shadowSpriteDash = buildShadowSprite(38, 6,  0.20f);
    }

    private static BufferedImage buildShadowSprite(int w, int h, float alpha) {
        int pw = w + 18, ph = h + 18;
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int ca = (int)(alpha * 200);
        float maxR = Math.max(pw, ph) / 2f;
        RadialGradientPaint rp = new RadialGradientPaint(
                new Point2D.Float(pw/2f, ph/2f), maxR,
                new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(0,0,0,ca), new Color(0,0,0,ca/2), new Color(0,0,0,0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE
        );
        g.translate(pw/2.0, ph/2.0);
        g.scale(1.0, (double)h/w);
        g.translate(-pw/2.0, -ph/2.0);
        g.setPaint(rp); g.fillOval(0, 0, pw, ph);
        g.dispose();
        return img;
    }
}
