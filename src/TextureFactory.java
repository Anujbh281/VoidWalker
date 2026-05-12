import java.awt.*;
import java.awt.image.*;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * TextureFactory — loads and caches all game textures.
 *
 * KEY OPTIMISATION: every image is pre-converted to a hardware-accelerated
 * BufferedImage (TYPE_INT_ARGB) at load time and cached forever.
 * drawImage() on a pre-converted image is a single blitter call with zero
 * per-frame allocation.
 */
public class TextureFactory {

    // Single global cache — images loaded once, reused every frame
    private static final Map<String, BufferedImage> cache = new HashMap<>(32);

    public static BufferedImage get(String name, int w, int h) {
        return cache.computeIfAbsent(name, k -> load(k, w, h));
    }

    /** Load from assets/ folder, fall back to procedural if missing. */
    private static BufferedImage load(String name, int w, int h) {
        try {
            File f = new File("assets/" + name + ".png");
            if (f.exists()) {
                BufferedImage raw = javax.imageio.ImageIO.read(f);
                if (raw != null) return toARGB(raw, w, h);
            }
        } catch (Exception ignored) {}
        return generate(name, w, h);
    }

    /**
     * Convert any image to TYPE_INT_ARGB at the exact tile size.
     * This is done ONCE at startup — never during gameplay.
     * TYPE_INT_ARGB is the fastest format for Java2D blitting.
     */
    private static BufferedImage toARGB(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        // Use NEAREST for pixel art (no blur), BILINEAR for photos
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** Pre-warm the cache for all known textures — call once at game start. */
    public static void preload(int tileSize) {
        get("tile_floor",  tileSize, tileSize);
        get("tile_wall",   tileSize, tileSize);
        get("tile_void",   tileSize, tileSize);
        get("player_idle", 32, 40);
        get("player_run",  32, 40);
        get("player_dash", 32, 40);
        get("enemy_grunt", 32, 32);
        get("enemy_ranger",32, 32);
        get("enemy_boss",  64, 64);
        get("tile_floor_shadow", tileSize, tileSize);
    }

    public static void clearCache() { cache.clear(); }

    // ── Procedural fallback generators ───────────────────────────
    static BufferedImage generate(String name, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        switch (name) {
            case "player_idle" -> drawPlayer(g, w, h, 0);
            case "player_run"  -> drawPlayer(g, w, h, 1);
            case "player_dash" -> drawPlayer(g, w, h, 2);
            case "enemy_grunt" -> drawGrunt(g, w, h);
            case "enemy_ranger"-> drawRanger(g, w, h);
            case "enemy_boss"  -> drawBoss(g, w, h);
            case "tile_floor"  -> drawFloor(g, w, h);
            case "tile_floor_shadow" -> drawFloorShadow(g, w, h);
            case "tile_wall"   -> drawWall(g, w, h);
            case "tile_void"   -> drawVoid(g, w, h);
            case "orb_health"  -> drawOrb(g, w, h, new Color(220, 60, 60));
            case "orb_score"   -> drawOrb(g, w, h, new Color(255, 215, 0));
            case "projectile"  -> drawProjectile(g, w, h);
            case "particle"    -> drawParticle(g, w, h);
            default -> { g.setColor(Color.MAGENTA); g.fillRect(0, 0, w, h); }
        }
        g.dispose();
        return img;
    }

    static void drawPlayer(Graphics2D g, int w, int h, int variant) {
        Color glowColor = variant == 2 ? new Color(0, 200, 255) : new Color(80, 120, 255);
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(4, h-8, w-8, 6);
        int[] bx = {w/2, w-4, w-6, w/2, 6, 4};
        int[] by = {4, h-4, h, h-2, h, h-4};
        g.setColor(new Color(30, 30, 80));
        g.fillPolygon(bx, by, 6);
        g.setColor(glowColor);
        g.setStroke(new BasicStroke(2));
        g.drawPolygon(bx, by, 6);
        g.setColor(new Color(20, 20, 60));
        g.fillOval(w/2-8, 2, 16, 14);
        g.setColor(glowColor);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(w/2-8, 2, 16, 14);
        g.fillOval(w/2-5, 7, 4, 3);
        g.fillOval(w/2+1, 7, 4, 3);
        if (variant == 2) { g.setColor(new Color(0, 200, 255, 80)); g.fillRect(0, 0, w, h); }
    }

    static void drawGrunt(Graphics2D g, int w, int h) {
        g.setColor(new Color(180, 40, 40)); g.fillRect(6, 10, w-12, h-16);
        g.setColor(new Color(200, 60, 30)); g.fillOval(w/2-8, 2, 16, 14);
        g.setColor(Color.YELLOW);
        g.fillOval(w/2-5, 6, 4, 3); g.fillOval(w/2+1, 6, 4, 3);
        g.setColor(new Color(120, 30, 20));
        g.fillPolygon(new int[]{w/2-6,w/2-3,w/2-9}, new int[]{3,-3,-3}, 3);
        g.fillPolygon(new int[]{w/2+6,w/2+3,w/2+9}, new int[]{3,-3,-3}, 3);
    }

    static void drawRanger(Graphics2D g, int w, int h) {
        g.setColor(new Color(40, 100, 40)); g.fillRect(8, 12, w-16, h-18);
        g.setColor(new Color(50, 130, 50)); g.fillOval(w/2-7, 3, 14, 13);
        g.setColor(new Color(150, 255, 150));
        g.fillOval(w/2-4, 7, 3, 3); g.fillOval(w/2+1, 7, 3, 3);
        g.setColor(new Color(139, 90, 43));
        g.setStroke(new BasicStroke(2)); g.drawArc(w-10, 8, 8, 20, -90, 180);
    }

    static void drawBoss(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0,0,new Color(80,0,120),w,h,new Color(200,0,80)));
        g.fillOval(2, 2, w-4, h-4);
        g.setColor(new Color(150, 0, 200));
        g.setStroke(new BasicStroke(3));
        for (int i=0;i<8;i++) {
            double a=Math.PI*2*i/8;
            g.drawLine(w/2+(int)(Math.cos(a)*(w/2-4)), h/2+(int)(Math.sin(a)*(h/2-4)),
                    w/2+(int)(Math.cos(a)*(w/2+4)), h/2+(int)(Math.sin(a)*(h/2+4)));
        }
        g.setColor(new Color(255,0,100)); g.fillOval(w/2-10,h/2-10,20,20);
        g.setColor(Color.WHITE);
        g.fillOval(w/2-5,h/2-6,4,5); g.fillOval(w/2+1,h/2-6,4,5);
    }

    static void drawFloor(Graphics2D g, int w, int h) {
        g.setColor(new Color(25, 25, 40)); g.fillRect(0,0,w,h);
        g.setColor(new Color(35, 35, 55)); g.drawRect(0,0,w-1,h-1);
    }

    static void drawFloorShadow(Graphics2D g, int w, int h) {
        drawFloor(g, w, h);
        // Add top shadow gradient
        for (int y = 0; y < h/3; y++) {
            float alpha = (1f - (float)y / (h/3f)) * 140f;
            g.setColor(new Color(0, 0, 0, (int)alpha));
            g.fillRect(0, y, w, 1);
        }
    }

    static void drawWall(Graphics2D g, int w, int h) {
        g.setColor(new Color(15, 15, 30)); g.fillRect(0,0,w,h);
        g.setColor(new Color(50, 50, 80)); g.fillRect(2,2,w-4,h-4);
        g.setColor(new Color(60,60,100,150));
        g.drawLine(6,h/2,w-6,h/2); g.drawLine(w/2,6,w/2,h-6);
    }

    static void drawVoid(Graphics2D g, int w, int h) {
        g.setColor(new Color(5,5,10)); g.fillRect(0,0,w,h);
    }

    static void drawOrb(Graphics2D g, int w, int h, Color c) {
        g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),180));
        g.fillOval(2,2,w-4,h-4);
        g.setColor(c.brighter());
        g.setStroke(new BasicStroke(2)); g.drawOval(2,2,w-4,h-4);
    }

    static void drawProjectile(Graphics2D g, int w, int h) {
        g.setColor(new Color(0,200,255,200)); g.fillOval(2,2,w-4,h-4);
        g.setColor(Color.WHITE); g.fillOval(w/2-2,h/2-2,4,4);
    }

    static void drawParticle(Graphics2D g, int w, int h) {
        g.setColor(new Color(255,150,50,200)); g.fillOval(0,0,w,h);
    }
}
