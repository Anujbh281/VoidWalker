import java.awt.*;
import java.util.List;

/**
 * Poolable projectile — reused via ProjectilePool instead of allocating.
 * Supports power-up modifiers: piercing, explosive, size bonus, bullet speed.
 */
public class Projectile {
    float   x, y, vx, vy;
    float   spawnX, spawnY;   // origin — used for range limit
    int     damage;
    boolean fromPlayer;
    boolean active = false;
    int     life;
    int     r, g, b;          // cached colour channels

    // Power-up flags (set by GamePanel from PowerUpManager)
    boolean piercing   = false;
    boolean explosive  = false;
    int     sizeBonus  = 0;   // extra radius in px
    int     hitCount   = 0;   // how many enemies pierced so far

    /** No-arg constructor for pool pre-allocation. */
    Projectile() {}

    /** Convenience constructor so Enemy can create temporary Projectiles for handoff to pool. */
    Projectile(float x, float y, float targetX, float targetY, int dmg, boolean fromPlayer, Color c) {
        init(x, y, targetX, targetY, dmg, fromPlayer, c, 1f, false, false, 0);
    }

    /** Initialise / re-initialise a pooled instance. */
    void init(float x, float y, float targetX, float targetY,
              int dmg, boolean fromPlayer, Color c,
              float speedMulti, boolean piercing, boolean explosive, int sizeBonus) {
        this.x = x; this.y = y;
        this.spawnX = x; this.spawnY = y;
        this.fromPlayer = fromPlayer;
        this.damage     = dmg;
        this.r = c.getRed(); this.g = c.getGreen(); this.b = c.getBlue();
        this.piercing   = piercing;
        this.explosive  = explosive;
        this.sizeBonus  = sizeBonus;
        this.hitCount   = 0;
        this.life       = 140;
        this.active     = true;

        float dist = (float) Math.sqrt((targetX-x)*(targetX-x) + (targetY-y)*(targetY-y));
        float speed = 8f * speedMulti;
        if (dist > 0) { vx = (targetX-x)/dist*speed; vy = (targetY-y)/dist*speed; }
        else          { vx = speed; vy = 0; }
    }

    void update() {
        x += vx; y += vy;
        life--;
        if (life <= 0) active = false;
    }

    void draw(Graphics2D g2, Settings s) {
        int rad = 5 + sizeBonus;
        g2.setColor(new Color(r, this.g, b));
        g2.fillOval((int)x - rad, (int)y - rad, rad*2, rad*2);
        if (s.quality == Quality.HIGH) {
            g2.setColor(new Color(r, this.g, b, 70));
            g2.fillOval((int)x - rad - 3, (int)y - rad - 3, (rad+3)*2, (rad+3)*2);
        }
    }

    Rectangle getBounds() {
        int rad = 5 + sizeBonus;
        return new Rectangle((int)x - rad, (int)y - rad, rad*2, rad*2);
    }

    /** Deactivate unless piercing allows more hits. */
    void onHit() {
        if (!piercing) { active = false; return; }
        hitCount++;
        if (hitCount >= 3) active = false; // max pierce chain
    }
}

// ── Object pool ──────────────────────────────────────────────────
class ProjectilePool {
    private static final int POOL_SIZE = 300;
    private final Projectile[] pool = new Projectile[POOL_SIZE];

    ProjectilePool() {
        for (int i = 0; i < POOL_SIZE; i++) pool[i] = new Projectile();
    }

    /** Borrow an inactive projectile and initialise it. Returns null if pool exhausted. */
    Projectile acquire(float x, float y, float tx, float ty, int dmg, boolean fromPlayer,
                       Color c, float speedMulti, boolean piercing, boolean explosive, int sizeBonus) {
        for (Projectile p : pool) {
            if (!p.active) {
                p.init(x, y, tx, ty, dmg, fromPlayer, c, speedMulti, piercing, explosive, sizeBonus);
                return p;
            }
        }
        return null; // pool full — silently drop
    }

    /** Iterate over ALL slots; caller must check p.active. */
    Projectile[] all() { return pool; }

    void clear() { for (Projectile p : pool) p.active = false; }
}
