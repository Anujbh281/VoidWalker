import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// ── Particle (poolable) ──────────────────────────────────────────
public class Particle {
    float x, y, vx, vy;
    int   life, maxLife;
    int   r, g, b;   // cached colour channels (avoid Color object per draw)
    float size;
    boolean active = false;

    /** Reinitialise a pooled particle instead of allocating a new one. */
    void init(float x, float y, Color c, float sz) {
        this.x = x; this.y = y;
        this.r = c.getRed(); this.g = c.getGreen(); this.b = c.getBlue();
        this.size = sz;
        float speed = 2f + (float)(Math.random() * 4);
        float angle = (float)(Math.random() * Math.PI * 2);
        vx = (float)(Math.cos(angle) * speed);
        vy = (float)(Math.sin(angle) * speed);
        maxLife = life = 20 + (int)(Math.random() * 20);
        active = true;
    }

    void update() {
        x += vx; y += vy;
        vx *= 0.92f; vy *= 0.92f;
        vy += 0.1f;
        life--;
        if (life <= 0) active = false;
    }

    void draw(Graphics2D g2) {
        float alpha = (float) life / maxLife;
        g2.setColor(new Color(r, this.g, b, (int)(alpha * 200)));
        int s = Math.max(1, (int)(size * alpha));
        g2.fillOval((int)x - s/2, (int)y - s/2, s, s);
    }
}

// ── ParticleSystem with object pool ─────────────────────────────
class ParticleSystem {
    private static final int POOL_SIZE = 800;

    private final Particle[]   pool    = new Particle[POOL_SIZE];
    private final List<Integer> active = new ArrayList<>(POOL_SIZE);

    // Dynamic cap: reduced at LOW quality
    int maxParticles = 400;

    ParticleSystem() {
        for (int i = 0; i < POOL_SIZE; i++) pool[i] = new Particle();
    }

    /** Set particle budget based on quality setting. */
    void applyQuality(Quality q) {
        maxParticles = switch (q) {
            case LOW    -> 100;
            case MEDIUM -> 300;
            case HIGH   -> 600;
        };
    }

    /** Emit up to `count` particles, skipping if pool is full or budget exceeded. */
    void emit(float x, float y, Color c, int count, float size) {
        if (active.size() >= maxParticles) return;
        int spawned = 0;
        for (int i = 0; i < POOL_SIZE && spawned < count; i++) {
            if (!pool[i].active) {
                pool[i].init(x, y, c, size);
                active.add(i);
                spawned++;
            }
        }
    }

    void update() {
        // Iterate via index list; remove dead particles back into pool
        active.removeIf(idx -> {
            pool[idx].update();
            return !pool[idx].active;
        });
    }

    void draw(Graphics2D g) {
        for (int idx : active) pool[idx].draw(g);
    }

    int size() { return active.size(); }

    void clear() {
        for (int idx : active) pool[idx].active = false;
        active.clear();
    }
}
