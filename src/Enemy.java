import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class Enemy {
    float x, y;
    float vx, vy;
    int hp, maxHp;
    int damage;
    int score;
    EnemyType type;
    boolean alive = true;
    boolean facingRight = false;

    // AI
    int aiTimer = 0;
    float targetX, targetY;
    boolean canSeePlayer = false;
    int shootCooldown = 0;

    // Animation
    int animFrame = 0;
    int animTimer = 0;
    float speed;

    // Patrol
    float patrolX, patrolY;
    int patrolDir = 1;
    int patrolTimer = 0;

    // Boss phases
    int phase = 1;
    boolean enraged = false;

    Enemy(float x, float y, EnemyType type) {
        this.x = x; this.y = y; this.type = type;
        this.patrolX = x; this.patrolY = y;
        switch (type) {
            case GRUNT  -> { hp = maxHp = 40;  damage = 10; score = 50;  speed = 1.8f; }
            case RANGER -> { hp = maxHp = 30;  damage = 15; score = 75;  speed = 1.2f; }
            case BOSS   -> { hp = maxHp = 500; damage = 20; score = 500; speed = 1.0f; }
        }
    }

    List<Projectile> update(Player player, Level lvl) {
        List<Projectile> projs = new ArrayList<>();
        if (!alive) return projs;

        float dx = player.x - x, dy = player.y - y;
        float dist = (float) Math.sqrt(dx*dx + dy*dy);
        canSeePlayer = dist < 300 && lvl.hasLos(x, y, player.x, player.y);

        animTimer++;
        if (animTimer >= 8) { animTimer = 0; animFrame = (animFrame + 1) % 4; }
        if (shootCooldown > 0) shootCooldown--;

        switch (type) {
            case GRUNT  -> updateGrunt(player, dist, dx, dy, lvl);
            case RANGER -> projs.addAll(updateRanger(player, dist, dx, dy, lvl));
            case BOSS   -> projs.addAll(updateBoss(player, dist, dx, dy, lvl));
        }

        float nx = x + vx, ny = y + vy;
        if (!lvl.isWall(nx, y)) x = nx; else { vx = -vx; patrolDir = -patrolDir; }
        if (!lvl.isWall(x, ny)) y = ny; else vy = -vy;
        facingRight = dx > 0;
        return projs;
    }

    void updateGrunt(Player player, float dist, float dx, float dy, Level lvl) {
        if (canSeePlayer) {
            if (dist > 0) { vx = dx/dist * speed; vy = dy/dist * speed; }
        } else {
            patrolTimer++;
            if (patrolTimer > 80) { patrolDir = -patrolDir; patrolTimer = 0; }
            vx = patrolDir * speed * 0.5f; vy = 0;
        }
    }

    List<Projectile> updateRanger(Player player, float dist, float dx, float dy, Level lvl) {
        List<Projectile> projs = new ArrayList<>();
        if (canSeePlayer) {
            if      (dist < 150) { if (dist > 0) { vx = -dx/dist*speed; vy = -dy/dist*speed; } }
            else if (dist > 200) { if (dist > 0) { vx =  dx/dist*speed*0.5f; vy = dy/dist*speed*0.5f; } }
            else { vx *= 0.8f; vy *= 0.8f; }
            if (shootCooldown <= 0 && dist < 280) {
                projs.add(new Projectile(x, y, player.x, player.y, damage, false, new Color(150,255,100)));
                shootCooldown = 90;
            }
        } else {
            patrolTimer++;
            if (patrolTimer > 100) { patrolDir = -patrolDir; patrolTimer = 0; }
            vx = patrolDir * speed * 0.4f; vy = 0;
        }
        return projs;
    }

    List<Projectile> updateBoss(Player player, float dist, float dx, float dy, Level lvl) {
        List<Projectile> projs = new ArrayList<>();
        if (hp < maxHp/2 && !enraged) { enraged = true; speed = 2.0f; }
        if (dist > 0) { vx = dx/dist * (enraged ? speed*1.5f : speed); vy = dy/dist*speed; }
        if (shootCooldown <= 0) {
            if (!enraged) {
                projs.add(new Projectile(x, y, player.x, player.y, damage, false, new Color(255,80,255)));
                shootCooldown = 50;
            } else {
                for (int i = -1; i <= 1; i++) {
                    float ang = (float) Math.atan2(dy, dx) + i * 0.4f;
                    float ex  = x + (float) Math.cos(ang) * 200;
                    float ey  = y + (float) Math.sin(ang) * 200;
                    projs.add(new Projectile(x, y, ex, ey, damage, false, new Color(255,50,100)));
                }
                shootCooldown = 30;
            }
        }
        return projs;
    }

    void takeDamage(int dmg, ParticleSystem ps) {
        hp -= dmg;
        Color c = type == EnemyType.BOSS ? new Color(255,0,100) : new Color(255,80,80);
        ps.emit(x, y, c, 6, 6);
        if (hp <= 0) {
            alive = false;
            ps.emit(x, y, c, 20, 10);
        }
    }

    void draw(Graphics2D g, Settings settings) {
        if (!alive) return;

        // Soft ground shadow under enemy
        ShadowRenderer.drawGroundShadow(g, x, y, vx, vy, false, settings.quality);

        String texName = switch (type) {
            case GRUNT  -> "enemy_grunt";
            case RANGER -> "enemy_ranger";
            case BOSS   -> "enemy_boss";
        };
        int sz = type == EnemyType.BOSS ? 64 : 32;
        BufferedImage img = TextureFactory.get(texName, sz, sz);
        AffineTransform at = AffineTransform.getTranslateInstance(x - sz/2.0, y - sz/2.0);
        if (!facingRight) { at.translate(sz, 0); at.scale(-1, 1); }
        g.drawImage(img, at, null);

        // HP bar
        int barW = type == EnemyType.BOSS ? 80 : 36;
        int barX = (int)x - barW/2, barY = (int)y - sz/2 - 10;
        g.setColor(new Color(40, 10, 10));
        g.fillRect(barX, barY, barW, 5);
        float pct = (float)hp / maxHp;
        Color hpColor = pct > 0.5f ? new Color(80,200,80)
                : pct > 0.25f ? new Color(255,180,0) : new Color(220,40,40);
        g.setColor(hpColor);
        g.fillRect(barX, barY, (int)(barW * pct), 5);

        if (enraged && settings.quality != Quality.LOW) {
            g.setColor(new Color(255, 0, 80, 40));
            g.fillOval((int)x-sz/2-4, (int)y-sz/2-4, sz+8, sz+8);
        }
    }

    Rectangle getBounds() {
        int sz = type == EnemyType.BOSS ? 28 : 14;
        return new Rectangle((int)x - sz, (int)y - sz, sz*2, sz*2);
    }
}
