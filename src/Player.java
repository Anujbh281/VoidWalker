import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

public class Player {
    float x, y;
    float vx, vy;
    int hp, maxHp;
    int shield, maxShield;
    int score;
    int kills;
    boolean alive = true;

    // Movement
    float speed = 3.5f;
    boolean facingRight = true;

    // Dash
    boolean dashing = false;
    int dashTimer   = 0;
    int dashCooldown= 0;
    static final int DASH_DURATION = 12;
    static final int BASE_DASH_CD  = 40;
    float dashVx, dashVy;

    // Animation
    int animFrame = 0;
    int animTimer = 0;
    int animSpeed = 8;

    // Combat
    int attackCooldown  = 0;
    int invincibleTimer = 0;
    int level           = 1;
    int xp              = 0;
    int xpToNext        = 100;

    // Ability
    int abilityCharge    = 0;
    int abilityMaxCharge = 200;
    boolean shieldActive = false;
    int shieldTimer      = 0;

    // Weapons
    int weaponLevel = 1;

    // Power-up reference (nullable — null in story mode)
    PowerUpManager mods = null;

    // Cached images to avoid repeated TextureFactory lookups
    private BufferedImage imgIdle, imgRun, imgDash;

    Player(float x, float y) {
        this.x = x; this.y = y;
        maxHp = 100; hp = maxHp;
        maxShield = 50; shield = 0;
        // Pre-cache textures
        imgIdle = TextureFactory.get("player_idle", 32, 40);
        imgRun  = TextureFactory.get("player_run",  32, 40);
        imgDash = TextureFactory.get("player_dash", 32, 40);
    }

    // Effective dash cooldown considering power-up modifier
    int effectiveDashCD() {
        float multi = mods != null ? mods.dashCooldownMulti : 1f;
        return Math.max(10, (int)(BASE_DASH_CD * multi));
    }

    void update(boolean up, boolean down, boolean left, boolean right,
                boolean dash, Level level) {
        vx = 0; vy = 0;
        if (!dashing) {
            if (left)  { vx -= speed; facingRight = false; }
            if (right) { vx += speed; facingRight = true; }
            if (up)    vy -= speed;
            if (down)  vy += speed;
            if (vx != 0 && vy != 0) { vx *= 0.707f; vy *= 0.707f; }
        }

        int dashCD = effectiveDashCD();
        if (dash && dashCooldown <= 0 && (vx != 0 || vy != 0)) {
            dashing = true; dashTimer = DASH_DURATION;
            float len = (float) Math.sqrt(vx*vx + vy*vy);
            dashVx = vx/len * 10; dashVy = vy/len * 10;
            dashCooldown = dashCD;
        }
        if (dashing) {
            vx = dashVx; vy = dashVy;
            dashTimer--;
            if (dashTimer <= 0) dashing = false;
        }
        if (dashCooldown > 0) dashCooldown--;
        if (attackCooldown > 0) attackCooldown--;
        if (invincibleTimer > 0) invincibleTimer--;

        // Shield ability
        if (shieldActive) {
            shieldTimer--;
            if (shieldTimer <= 0) shieldActive = false;
        }
        if (abilityCharge < abilityMaxCharge) abilityCharge++;

        // Shield regen — faster with power-up
        float shieldRecharge = mods != null ? mods.shieldRechargeMulti : 1f;
        if (shieldActive && shield < maxShield) {
            shield = Math.min(maxShield, shield + (int)(shieldRecharge));
        }

        // Move + wall collision
        float nx = x + vx, ny = y + vy;
        if (!level.isWall(nx, y)) x = nx;
        if (!level.isWall(x, ny)) y = ny;
        x = Math.max(20, Math.min(level.widthPx() - 20, x));
        y = Math.max(20, Math.min(level.heightPx() - 20, y));

        animTimer++;
        if (animTimer >= animSpeed) { animTimer = 0; animFrame = (animFrame + 1) % 4; }
    }

    void takeDamage(int dmg) {
        if (invincibleTimer > 0 || dashing) return;
        if (shield > 0) { shield = Math.max(0, shield - dmg); return; }
        hp = Math.max(0, hp - dmg);
        invincibleTimer = 60;
        if (hp <= 0) alive = false;
    }

    /** Called when player's projectile kills an enemy — lifesteal + kill-invincibility. */
    void onKill(int dmgDealt) {
        if (mods == null) return;
        if (mods.lifeStealPct > 0) {
            hp = Math.min(maxHp, hp + Math.max(1, (int)(dmgDealt * mods.lifeStealPct)));
        }
        if (mods.invincOnKill) invincibleTimer = Math.max(invincibleTimer, 30);
    }

    void gainXP(int amount) {
        xp += amount;
        abilityCharge = Math.min(abilityMaxCharge, abilityCharge + 20);
        if (xp >= xpToNext) { xp -= xpToNext; levelUp(); }
    }

    void levelUp() {
        level++;
        maxHp += 20; hp = Math.min(maxHp, hp + 30);
        speed = Math.min(6f, speed + 0.2f);
        xpToNext = (int)(xpToNext * 1.5f);
        if (level == 3) weaponLevel = 2;
        if (level == 5) weaponLevel = 3;
    }

    void activateAbility() {
        if (abilityCharge >= abilityMaxCharge) {
            shieldActive = true; shieldTimer = 180;
            shield = maxShield; abilityCharge = 0;
        }
    }

    void draw(Graphics2D g, Settings settings) {
        if (invincibleTimer > 0 && (invincibleTimer / 5) % 2 == 1) return;

        // ── 1. Soft radial ground shadow (drawn first, under sprite) ──
        ShadowRenderer.drawGroundShadow(g, x, y, vx, vy, dashing, settings.quality);

        // ── 2. Dash trail effect ──────────────────────────────────────
        if (dashing && settings.quality != Quality.LOW) {
            // Use gradient instead of flat oval
            java.awt.RadialGradientPaint rp = new java.awt.RadialGradientPaint(
                    new java.awt.geom.Point2D.Float(x, y), 18,
                    new float[]{0f, 1f},
                    new java.awt.Color[]{new java.awt.Color(0,180,255,70), new java.awt.Color(0,150,255,0)}
            );
            g.setPaint(rp);
            g.fillOval((int)x - 18, (int)y - 18, 36, 36);
        }

        // ── 3. Shield aura ────────────────────────────────────────────
        if (shieldActive && settings.quality != Quality.LOW) {
            float pulse = (float) Math.sin(System.currentTimeMillis() * 0.01) * 0.3f + 0.7f;
            java.awt.RadialGradientPaint rp2 = new java.awt.RadialGradientPaint(
                    new java.awt.geom.Point2D.Float(x, y), 28,
                    new float[]{0.4f, 1f},
                    new java.awt.Color[]{new java.awt.Color(60,120,255,(int)(60*pulse)), new java.awt.Color(60,120,255,0)}
            );
            g.setPaint(rp2);
            g.fillOval((int)x - 28, (int)y - 28, 56, 56);
        }

        // ── 4. Player sprite ──────────────────────────────────────────
        BufferedImage img = dashing ? imgDash : (vx != 0 || vy != 0 ? imgRun : imgIdle);
        AffineTransform at = AffineTransform.getTranslateInstance(x - 16, y - 20);
        if (!facingRight) { at.translate(32, 0); at.scale(-1, 1); }
        g.drawImage(img, at, null);
    }

    Rectangle getBounds() { return new Rectangle((int)x - 12, (int)y - 14, 24, 28); }
}
