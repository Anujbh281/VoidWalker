import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the power-up pool, active modifiers, and the upgrade-select UI.
 *
 * All 20 upgrades are defined here. Effects are stored as multiplier fields
 * that Player / Projectile / GamePanel read each frame.
 */
public class PowerUpManager {

    // ── Active modifier fields (read by game systems) ─────────────
    public float  fireRateMulti      = 1.0f;  // >1 = faster fire
    public float  speedMulti         = 1.0f;
    public int    bonusMaxHp         = 0;
    public float  lifeStealPct       = 0f;    // 0–1, fraction of dmg healed
    public int    extraProjectiles   = 0;     // extra shots per burst (double/triple)
    public boolean piercing          = false;
    public boolean explosive         = false;
    public float  shieldRechargeMulti= 1.0f;
    public float  dashCooldownMulti  = 1.0f;  // <1 = shorter cooldown
    public float  bulletSpeedMulti   = 1.0f;
    public float  critChance         = 0f;    // 0–1
    public int    regenPerSecond     = 0;     // HP regenerated per second (60 frames)
    public boolean freezeOnHit       = false;
    public boolean chainLightning    = false;
    public float  damageMulti        = 1.0f;
    public int    projectileSizeBonus= 0;     // added to projectile radius
    public boolean knockback         = false;
    public boolean invincOnKill      = false; // 30-frame invincibility per kill
    public float  enemySlowFactor    = 1.0f; // multiplied into enemy speed, <1 = slow
    public int    regenTimer         = 0;    // internal: counts frames for regen

    // ── Collected upgrade names (for display) ────────────────────
    public final List<String> collected = new ArrayList<>();

    // ── Upgrade selection state ───────────────────────────────────
    private List<PowerUp> currentOffers = new ArrayList<>();
    private int hoverIndex = -1;

    // Reference to player, set once
    private Player player;

    public PowerUpManager(Player player) {
        this.player = player;
    }

    /** Update player reference (called after nextWave creates a new Player? No — same player). */
    public void setPlayer(Player p) { this.player = p; }

    // ─────────────────────────────────────────────────────────────
    //  Build all 20 upgrades
    // ─────────────────────────────────────────────────────────────
    public List<PowerUp> buildAllUpgrades() {
        List<PowerUp> all = new ArrayList<>();
        PowerUpManager m = this; // shorthand

        all.add(new PowerUp("+20% Fire Rate",
                "Shoot 20% faster",
                new Color(255, 200, 50),
                () -> { m.fireRateMulti *= 1.20f; }));

        all.add(new PowerUp("+15% Move Speed",
                "Move 15% faster",
                new Color(80, 220, 255),
                () -> { m.speedMulti *= 1.15f;
                    player.speed = Math.min(8f, player.speed * 1.15f); }));

        all.add(new PowerUp("+25 Max HP",
                "Increase max health by 25",
                new Color(220, 60, 60),
                () -> { m.bonusMaxHp += 25; player.maxHp += 25; player.hp = Math.min(player.maxHp, player.hp + 25); }));

        all.add(new PowerUp("Lifesteal",
                "Heal 5% of damage dealt",
                new Color(200, 50, 200),
                () -> { m.lifeStealPct = Math.min(0.5f, m.lifeStealPct + 0.05f); }));

        all.add(new PowerUp("Double Shot",
                "Fire an extra parallel projectile",
                new Color(100, 200, 255),
                () -> { m.extraProjectiles = Math.max(m.extraProjectiles, 1); }));

        all.add(new PowerUp("Triple Shot",
                "Fire two extra spread projectiles",
                new Color(50, 255, 150),
                () -> { m.extraProjectiles = Math.max(m.extraProjectiles, 2); }));

        all.add(new PowerUp("Piercing Bullets",
                "Bullets pass through enemies",
                new Color(255, 255, 80),
                () -> { m.piercing = true; }));

        all.add(new PowerUp("Explosive Shots",
                "Bullets deal AoE splash damage",
                new Color(255, 140, 0),
                () -> { m.explosive = true; }));

        all.add(new PowerUp("Shield Recharge +50%",
                "Shield regenerates 50% faster",
                new Color(60, 120, 255),
                () -> { m.shieldRechargeMulti *= 1.50f; }));

        all.add(new PowerUp("Dash Cooldown -40%",
                "Dash refreshes 40% sooner",
                new Color(0, 230, 230),
                () -> { m.dashCooldownMulti *= 0.60f; }));

        all.add(new PowerUp("Bullet Speed +30%",
                "Projectiles travel 30% faster",
                new Color(180, 255, 180),
                () -> { m.bulletSpeedMulti *= 1.30f; }));

        all.add(new PowerUp("Critical Hits",
                "20% chance to deal double damage",
                new Color(255, 80, 80),
                () -> { m.critChance = Math.min(0.80f, m.critChance + 0.20f); }));

        all.add(new PowerUp("Regeneration",
                "Heal 1 HP per second passively",
                new Color(80, 255, 120),
                () -> { m.regenPerSecond += 1; }));

        all.add(new PowerUp("Freeze on Hit",
                "Hits slow enemies for 1 second",
                new Color(180, 230, 255),
                () -> { m.freezeOnHit = true; }));

        all.add(new PowerUp("Chain Lightning",
                "Hits arc to a nearby enemy",
                new Color(220, 220, 80),
                () -> { m.chainLightning = true; }));

        all.add(new PowerUp("Damage Boost +25%",
                "All damage increased by 25%",
                new Color(255, 100, 40),
                () -> { m.damageMulti *= 1.25f; }));

        all.add(new PowerUp("Larger Projectiles",
                "Bullet hitbox grows by 4px",
                new Color(160, 100, 255),
                () -> { m.projectileSizeBonus += 4; }));

        all.add(new PowerUp("Knockback",
                "Hits push enemies back",
                new Color(255, 180, 80),
                () -> { m.knockback = true; }));

        all.add(new PowerUp("Kill Invincibility",
                "0.5s invincibility on each kill",
                new Color(255, 255, 200),
                () -> { m.invincOnKill = true; }));

        all.add(new PowerUp("Time Slow",
                "All enemies move 20% slower",
                new Color(140, 140, 255),
                () -> { m.enemySlowFactor *= 0.80f; }));

        return all;
    }

    /** Pick 3 random distinct upgrades for the selection screen. */
    public List<PowerUp> generateOffers() {
        List<PowerUp> all = buildAllUpgrades();
        Collections.shuffle(all);
        currentOffers = all.subList(0, Math.min(3, all.size()));
        hoverIndex = -1;
        return currentOffers;
    }

    /** Per-frame update: passive regen. */
    public void update() {
        if (regenPerSecond > 0 && player != null) {
            regenTimer++;
            if (regenTimer >= 60) {
                regenTimer = 0;
                player.hp = Math.min(player.maxHp, player.hp + regenPerSecond);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Upgrade selection UI
    // ─────────────────────────────────────────────────────────────
    private static final int CARD_W  = 240;
    private static final int CARD_H  = 130;
    private static final int CARD_Y  = 220;
    private static final int CARD_GAP = 30;

    /** Returns index of the card under (mx, my) or -1. */
    public int cardAt(int mx, int my) {
        int total   = currentOffers.size();
        int startX  = (960 - (total * CARD_W + (total-1) * CARD_GAP)) / 2;
        for (int i = 0; i < total; i++) {
            int cx = startX + i * (CARD_W + CARD_GAP);
            if (mx >= cx && mx <= cx+CARD_W && my >= CARD_Y && my <= CARD_Y+CARD_H) return i;
        }
        return -1;
    }

    public void setHover(int mx, int my) { hoverIndex = cardAt(mx, my); }

    /** Called when player clicks; returns true if a card was selected. */
    public boolean trySelect(int cx, int cy) {
        int idx = cardAt(cx, cy);
        if (idx >= 0 && idx < currentOffers.size()) {
            PowerUp pick = currentOffers.get(idx);
            pick.apply();
            collected.add(pick.name);
            currentOffers = new ArrayList<>();
            return true;
        }
        return false;
    }

    /** Draw the upgrade-select overlay. */
    public void drawUpgradeSelect(Graphics2D g) {
        // Dark overlay
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, 960, 640);

        // Title
        g.setFont(new Font("Monospaced", Font.BOLD, 26));
        g.setColor(new Color(220, 200, 255));
        String title = "WAVE COMPLETE  —  CHOOSE AN UPGRADE";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, 960/2 - fm.stringWidth(title)/2, 180);

        int total  = currentOffers.size();
        int startX = (960 - (total * CARD_W + (total-1) * CARD_GAP)) / 2;

        for (int i = 0; i < total; i++) {
            PowerUp pu = currentOffers.get(i);
            int cx = startX + i * (CARD_W + CARD_GAP);
            boolean hov = (i == hoverIndex);

            // Card background
            g.setColor(hov ? new Color(40, 35, 70, 230) : new Color(15, 12, 30, 220));
            g.fillRoundRect(cx, CARD_Y, CARD_W, CARD_H, 16, 16);

            // Coloured border
            Color border = hov ? pu.color.brighter() : pu.color.darker();
            g.setColor(border);
            g.setStroke(new BasicStroke(hov ? 2.5f : 1.5f));
            g.drawRoundRect(cx, CARD_Y, CARD_W, CARD_H, 16, 16);

            // Colour swatch strip at top
            g.setColor(new Color(pu.color.getRed(), pu.color.getGreen(), pu.color.getBlue(), 120));
            g.fillRoundRect(cx+2, CARD_Y+2, CARD_W-4, 20, 10, 10);

            // Name
            g.setFont(new Font("Monospaced", Font.BOLD, 15));
            g.setColor(hov ? Color.WHITE : new Color(220, 210, 255));
            g.drawString(pu.name, cx + 14, CARD_Y + 32);

            // Description (word-wrap at ~28 chars)
            g.setFont(new Font("Monospaced", Font.PLAIN, 12));
            g.setColor(new Color(180, 175, 210));
            String[] words = pu.description.split(" ");
            StringBuilder line = new StringBuilder();
            int lineY = CARD_Y + 56;
            for (String w : words) {
                if (line.length() + w.length() + 1 > 28) {
                    g.drawString(line.toString(), cx + 14, lineY);
                    lineY += 18; line = new StringBuilder();
                }
                if (line.length() > 0) line.append(' ');
                line.append(w);
            }
            if (line.length() > 0) g.drawString(line.toString(), cx + 14, lineY);

            // Hover hint
            if (hov) {
                g.setFont(new Font("Monospaced", Font.BOLD, 11));
                g.setColor(new Color(200, 255, 200));
                g.drawString("[ CLICK TO SELECT ]", cx + 14, CARD_Y + CARD_H - 12);
            }
        }

        // Collected upgrades (bottom bar)
        if (!collected.isEmpty()) {
            g.setFont(new Font("Monospaced", Font.PLAIN, 10));
            g.setColor(new Color(120, 110, 160));
            String owned = "Active: " + String.join(", ", collected);
            if (owned.length() > 90) owned = owned.substring(0, 87) + "…";
            g.drawString(owned, 30, 600);
        }

        g.setStroke(new BasicStroke(1f));
    }

    /** Draw compact active-upgrades HUD strip (bottom of screen in ENDLESS). */
    public void drawActiveHUD(Graphics2D g) {
        if (collected.isEmpty()) return;
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(420, 640-38, 530, 28, 8, 8);
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.setColor(new Color(160, 150, 210));
        String txt = "UPGRADES: " + collected.size();
        g.drawString(txt, 428, 640-19);
    }
}
