import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * CoopRenderer — Draws the entire co-op world from server state.
 *
 * CROSSHAIR FIX:
 *   Each PlayerState carries aimAngle + mouseWorldX/Y from the server.
 *   For the LOCAL player we use the real mouse position (zero latency).
 *   For REMOTE players we draw their crosshair at their mouseWorldX/Y
 *   in world space, converted to screen space by subtracting camera.
 *
 * ENEMY FIX:
 *   Enemies come entirely from server EnemyState[]. This renderer
 *   draws them — it never spawns or simulates enemies.
 *
 * INTERPOLATION:
 *   Positions are smoothed between two server snapshots via
 *   CoopGameClient.getInterpolatedPlayerPos() and getInterpolatedEnemyPos().
 */
public class CoopRenderer {

    private static final int SW = 960, SH = 640;

    // ── Cached colors — zero per-frame allocation ─────────────────
    private static final Color[] PLAYER_COLORS = {
            new Color(80,  160, 255),
            new Color(80,  220, 100),
            new Color(255, 160,  50),
            new Color(200,  60, 255),
    };
    private static final Color[] PLAYER_DARK = {
            new Color(20,  60, 140),
            new Color(20, 100,  40),
            new Color(140,  70,  10),
            new Color( 90,  10, 130),
    };

    private static final Color C_HP_BG    = new Color(15,  8,  8, 200);
    private static final Color C_HP_GREEN = new Color(70, 200,  70);
    private static final Color C_HP_YEL   = new Color(255,180,   0);
    private static final Color C_HP_RED   = new Color(220,  40,  40);
    private static final Color C_NAME     = new Color(210, 200, 255);
    private static final Color C_DEAD     = new Color(70,  70,  70, 100);
    private static final Color C_SHADOW   = new Color(0,    0,   0,  55);
    private static final Color C_EBODY    = new Color(175,  55,  55);
    private static final Color C_EDARK    = new Color( 90,  15,  15);
    private static final Color C_RBODY    = new Color( 55, 100, 175);
    private static final Color C_RDARK    = new Color( 15,  40,  90);
    private static final Color C_BBODY    = new Color(155,  15, 195);
    private static final Color C_BDARK    = new Color( 70,   5,  95);
    private static final Color C_EYE      = new Color(255, 225,  50);
    private static final Color C_ENRAGED  = new Color(255,  15,  15, 55);
    private static final Color C_PBULLET  = new Color(90,  195, 255);
    private static final Color C_EBULLET  = new Color(255,  90,  90);
    private static final Color C_PGLOW    = new Color(90,  195, 255, 45);
    private static final Color C_EGLOW    = new Color(255,  90,  90, 45);
    private static final Color C_WHITE    = new Color(255, 255, 255, 200);
    private static final Color C_HUD_BG   = new Color(0,    0,   0, 155);
    private static final Color C_PING_OK  = new Color(55,  215,  55);
    private static final Color C_PING_MID = new Color(255, 200,   0);
    private static final Color C_PING_BAD = new Color(220,  55,  55);
    private static final Color C_XHAIR    = new Color(255, 255, 255, 180);
    private static final Color C_XHAIR_EN = new Color(255,  80,  80, 180);
    private static final Color C_XHAIR_R  = new Color(100, 200, 255, 120);
    private static final Color C_DASH_TR  = new Color(140, 200, 255,  60);
    private static final Color C_PICKUP_H = new Color(220,  60,  60);
    private static final Color C_PICKUP_S = new Color(255, 200,  50);

    private static final Font  F_NAME   = new Font("Monospaced", Font.BOLD,  11);
    private static final Font  F_SMALL  = new Font("Monospaced", Font.PLAIN, 10);
    private static final Font  F_HUD    = new Font("Monospaced", Font.BOLD,  12);
    private static final Font  F_WAVE   = new Font("Monospaced", Font.BOLD,  14);
    private static final Font  F_DEAD   = new Font("Monospaced", Font.BOLD,  10);

    private static final BasicStroke STR1 = new BasicStroke(1f);
    private static final BasicStroke STR2 = new BasicStroke(2f);
    private static final BasicStroke STR3 = new BasicStroke(3f);
    private static final BasicStroke DASH = new BasicStroke(1.2f, BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND, 0, new float[]{5, 4}, 0);

    private final AffineTransform reusedAT = new AffineTransform();

    public CoopRenderer() {}

    // ═════════════════════════════════════════════════════════════
    // MAIN DRAW — call from GamePanel.drawGameBase() in MP mode
    // g must already be translated to world space (-camX, -camY)
    // ═════════════════════════════════════════════════════════════
    public void drawWorld(Graphics2D g, int camX, int camY,
                          CoopGameClient client, int myPlayerId) {
        if (client == null || !client.isConnected()) return;

        client.tickInterpolation();

        GamePacket.PickupState[] pickups = client.getLatestPickups();
        GamePacket.EnemyState[]  enemies = client.getLatestEnemies();
        GamePacket.BulletState[] bullets = client.getLatestBullets();
        GamePacket.PlayerState[] players = client.getLatestPlayers();

        // Draw order: pickups → enemies → bullets → players → crosshairs
        if (pickups != null) drawPickups(g, pickups, camX, camY);
        if (enemies != null) drawEnemies(g, enemies, client, camX, camY);
        if (bullets != null) drawBullets(g, bullets, camX, camY);
        if (players != null) drawPlayers(g, players, client, myPlayerId, camX, camY);
        // Remote player crosshairs in world space
        if (players != null) drawRemoteCrosshairs(g, players, myPlayerId, camX, camY);
    }

    // ─────────────────────────────────────────────────────────────
    // PICKUPS
    // ─────────────────────────────────────────────────────────────
    private void drawPickups(Graphics2D g, GamePacket.PickupState[] pickups,
                             int camX, int camY) {
        for (GamePacket.PickupState pk : pickups) {
            if (pk.collected) continue;
            float sx = pk.x - camX, sy = pk.y - camY;
            if (sx < -30 || sx > SW+30 || sy < -30 || sy > SH+30) continue;

            int px = (int)pk.x, py = (int)pk.y;
            Color c = "health".equals(pk.pickupType) ? C_PICKUP_H : C_PICKUP_S;

            // Glow
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
            g.fillOval(px-14, py-14, 28, 28);
            // Body
            g.setColor(c);
            g.fillOval(px-8, py-8, 16, 16);
            // Icon
            g.setColor(Color.WHITE);
            if ("health".equals(pk.pickupType)) {
                g.fillRect(px-1, py-5, 2, 10);
                g.fillRect(px-5, py-1, 10, 2);
            } else {
                g.setFont(F_SMALL);
                g.drawString("$", px-4, py+4);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ENEMIES — drawn from server EnemyState[]
    // ─────────────────────────────────────────────────────────────
    private void drawEnemies(Graphics2D g, GamePacket.EnemyState[] enemies,
                             CoopGameClient client, int camX, int camY) {
        for (GamePacket.EnemyState en : enemies) {
            if (!en.alive) continue;

            // Interpolated position
            float[] ipos = client.getInterpolatedEnemyPos(en.id);
            float wx = ipos[0], wy = ipos[1];
            float sx = wx - camX, sy = wy - camY;
            if (sx < -80 || sx > SW+80 || sy < -80 || sy > SH+80) continue;

            boolean isBoss = (en.typeOrd == 2);
            int sz = isBoss ? 52 : 32;
            int ex = (int)wx, ey = (int)wy;

            // Shadow
            g.setColor(C_SHADOW);
            g.fillOval(ex-sz/2, ey+sz/2-6, sz, 9);

            // Enraged pulse
            if (en.enraged) {
                g.setColor(C_ENRAGED);
                g.fillOval(ex-sz/2-6, ey-sz/2-6, sz+12, sz+12);
            }

            // Flip for facing direction
            int flip = en.facingRight ? 1 : -1;
            g.translate(ex, ey);
            g.scale(flip, 1);
            drawEnemyBody(g, en, sz, isBoss);
            g.scale(flip, 1);
            g.translate(-ex, -ey);

            // HP bar
            int barW = isBoss ? 72 : 38;
            drawHPBar(g, ex, ey - sz/2 - 11, barW, en.hp, en.maxHp);
        }
    }

    private void drawEnemyBody(Graphics2D g, GamePacket.EnemyState en, int sz, boolean isBoss) {
        Color body, dark;
        if      (isBoss)        { body = C_BBODY; dark = C_BDARK; }
        else if (en.typeOrd==1) { body = C_RBODY; dark = C_RDARK; }
        else                    { body = C_EBODY; dark = C_EDARK; }

        int hx = -sz/2, hy = -sz/2;

        g.setColor(body);
        g.fillRoundRect(hx+2, hy+2, sz-4, sz-4, 8, 8);
        g.setColor(dark);
        g.fillRoundRect(hx+2, hy+sz/2, sz-4, sz/2-4, 8, 8);

        // Eyes
        g.setColor(C_EYE);
        if (isBoss) {
            g.fillOval(hx+sz/2-14, hy+sz/3, 7, 7);
            g.fillOval(hx+sz/2-4,  hy+sz/3-3, 7, 7);
            g.fillOval(hx+sz/2+7,  hy+sz/3, 7, 7);
            // Boss spike
            g.setColor(new Color(200, 0, 255));
            int[] px={hx+sz/2-5, hx+sz/2, hx+sz/2+5};
            int[] py={hy+2, hy-10, hy+2};
            g.fillPolygon(px, py, 3);
        } else if (en.typeOrd==1) {
            g.fillOval(hx+sz/2-4, hy+sz/3, 9, 9);
            g.setColor(Color.BLACK);
            g.fillOval(hx+sz/2-2, hy+sz/3+2, 5, 5);
        } else {
            g.fillOval(hx+sz/2-9, hy+sz/3, 6, 6);
            g.fillOval(hx+sz/2+3, hy+sz/3, 6, 6);
        }

        g.setColor(dark); g.setStroke(STR1);
        g.drawRoundRect(hx+2, hy+2, sz-4, sz-4, 8, 8);
    }

    // ─────────────────────────────────────────────────────────────
    // BULLETS
    // ─────────────────────────────────────────────────────────────
    private void drawBullets(Graphics2D g, GamePacket.BulletState[] bullets,
                             int camX, int camY) {
        for (GamePacket.BulletState b : bullets) {
            if (!b.active) continue;
            float sx = b.x - camX, sy = b.y - camY;
            if (sx < -20 || sx > SW+20 || sy < -20 || sy > SH+20) continue;

            int bx = (int)b.x, by = (int)b.y;
            boolean isP = b.fromPlayer;

            g.setColor(isP ? C_PGLOW : C_EGLOW);
            g.fillOval(bx-8, by-8, 16, 16);
            g.setColor(isP ? C_PBULLET : C_EBULLET);
            g.fillOval(bx-4, by-4, 8, 8);
            g.setColor(C_WHITE);
            g.fillOval(bx-1, by-1, 3, 3);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PLAYERS
    // ─────────────────────────────────────────────────────────────
    private void drawPlayers(Graphics2D g, GamePacket.PlayerState[] players,
                             CoopGameClient client, int myPlayerId,
                             int camX, int camY) {
        for (GamePacket.PlayerState ps : players) {
            float[] ipos = client.getInterpolatedPlayerPos(ps.playerId);
            float wx = ipos[0], wy = ipos[1];
            float sx = wx - camX, sy = wy - camY;
            if (sx < -80 || sx > SW+80 || sy < -80 || sy > SH+80) continue;

            int px = (int)wx, py = (int)wy;
            boolean isMe = (ps.playerId == myPlayerId);
            int ci = Math.max(0, Math.min(ps.playerId-1, 3));
            Color pc   = PLAYER_COLORS[ci];
            Color dark = PLAYER_DARK[ci];

            if (!ps.alive) {
                g.setColor(C_DEAD);
                g.fillOval(px-14, py-14, 28, 28);
                g.setColor(new Color(180, 80, 80, 160));
                g.setStroke(STR1);
                g.drawOval(px-14, py-14, 28, 28);
                g.setFont(F_DEAD);
                g.setColor(new Color(200, 80, 80, 180));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("DEAD", px - fm.stringWidth("DEAD")/2, py-18);
                continue;
            }

            // Shadow
            g.setColor(C_SHADOW);
            g.fillOval(px-13, py+11, 26, 8);

            // Dash trail
            if (ps.dashing) {
                g.setColor(C_DASH_TR);
                g.fillOval(px-20, py-20, 40, 40);
            }

            // Draw body (flipped for direction)
            int flip = ps.facingRight ? 1 : -1;
            g.translate(px, py);
            g.scale(flip, 1);
            drawPlayerBody(g, ps, pc, dark);
            g.scale(flip, 1);
            g.translate(-px, -py);

            // Color ring
            g.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), isMe ? 210 : 130));
            g.setStroke(isMe ? STR3 : STR2);
            g.drawOval(px-15, py-15, 30, 30);
            g.setStroke(STR1);

            // HP bar
            drawHPBar(g, px, py-31, 42, ps.hp, ps.maxHp);

            // Name tag
            String tag = (isMe ? "★ " : "") + ps.username;
            g.setFont(F_NAME);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(tag);
            g.setColor(C_HUD_BG);
            g.fillRoundRect(px-tw/2-4, py-46, tw+8, 13, 4, 4);
            g.setColor(isMe ? pc : C_NAME);
            g.drawString(tag, px-tw/2, py-36);

            // Score
            g.setFont(F_SMALL);
            g.setColor(new Color(200, 200, 100, 200));
            String sc = String.valueOf(ps.score);
            fm = g.getFontMetrics();
            g.drawString(sc, px - fm.stringWidth(sc)/2, py-50);
        }
    }

    private void drawPlayerBody(Graphics2D g, GamePacket.PlayerState ps,
                                Color pc, Color dark) {
        // Legs
        g.setColor(dark);
        g.fillRoundRect(-8,  8, 6, 10, 3, 3);
        g.fillRoundRect( 2,  8, 6, 10, 3, 3);
        // Body
        g.setColor(pc);
        g.fillRoundRect(-10,-10, 20, 20, 6, 6);
        // Chest detail
        g.setColor(dark);
        g.fillRoundRect(-5, -4, 10, 8, 3, 3);
        // Head
        g.setColor(pc);
        g.fillRoundRect(-7,-20, 14, 13, 5, 5);
        // Eye
        g.setColor(Color.WHITE);
        g.fillOval(1,-17, 5, 5);
        g.setColor(Color.BLACK);
        g.fillOval(2,-16, 3, 3);
        // Gun arm
        g.setColor(dark);
        g.fillRoundRect( 8, -3, 10, 5, 2, 2);
        g.setColor(new Color(55, 55, 65));
        g.fillRoundRect(15, -4,  8, 6, 2, 2);
        // Shoot flash
        if (ps.shooting) {
            g.setColor(new Color(255, 240, 100, 185));
            g.fillOval(22, -5, 8, 8);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // REMOTE CROSSHAIRS — shows where each remote player is aiming
    // Drawn in world space so they move with the world.
    // ─────────────────────────────────────────────────────────────
    private void drawRemoteCrosshairs(Graphics2D g, GamePacket.PlayerState[] players,
                                      int myPlayerId, int camX, int camY) {
        for (GamePacket.PlayerState ps : players) {
            if (ps.playerId == myPlayerId) continue; // local player draws own crosshair
            if (!ps.alive) continue;

            // Crosshair is at player's mouseWorldX/Y in world space
            float cx = ps.mouseWorldX - camX;
            float cy = ps.mouseWorldY - camY;
            if (cx < -20 || cx > SW+20 || cy < -20 || cy > SH+20) continue;

            int ci = Math.max(0, Math.min(ps.playerId-1, 3));
            Color pc = PLAYER_COLORS[ci];

            // Draw a small colored crosshair at their aim point
            g.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), 160));
            g.setStroke(STR2);
            int icx = (int)cx, icy = (int)cy, r = 9;
            g.drawOval(icx-r, icy-r, r*2, r*2);
            g.setStroke(STR1);
            // Tick marks
            g.drawLine(icx,    icy-r-3, icx,    icy-r-7);
            g.drawLine(icx,    icy+r+3, icx,    icy+r+7);
            g.drawLine(icx-r-3, icy,    icx-r-7, icy);
            g.drawLine(icx+r+3, icy,    icx+r+7, icy);

            // Dashed aim line from player to crosshair
            float[] ipos = new float[]{ps.x, ps.y}; // use direct (no interp needed for line)
            float plx = ipos[0] - camX, ply = ipos[1] - camY;
            g.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), 50));
            g.setStroke(DASH);
            g.drawLine((int)plx, (int)ply, icx, icy);
            g.setStroke(STR1);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOCAL PLAYER CROSSHAIR — drawn in screen space
    // Call AFTER g.translate(camX, camY) — i.e. back in screen space
    // ─────────────────────────────────────────────────────────────
    public void drawLocalCrosshair(Graphics2D g, int mouseScreenX, int mouseScreenY,
                                   float aimAngle, boolean enemyUnderCursor,
                                   int weaponLevel) {
        Color[] wc = {
                new Color(90,  180, 255),
                new Color(90,  255, 140),
                new Color(255, 160,  50),
        };
        Color c = enemyUnderCursor ? C_XHAIR_EN
                : wc[Math.min(weaponLevel-1, 2)];

        int r = 10;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
        g.setStroke(STR2);
        g.drawOval(mouseScreenX-r, mouseScreenY-r, r*2, r*2);
        g.setStroke(STR1);

        int gap = 4, tl = 6;
        g.drawLine(mouseScreenX, mouseScreenY-r-gap, mouseScreenX, mouseScreenY-r-gap-tl);
        g.drawLine(mouseScreenX, mouseScreenY+r+gap, mouseScreenX, mouseScreenY+r+gap+tl);
        g.drawLine(mouseScreenX-r-gap, mouseScreenY, mouseScreenX-r-gap-tl, mouseScreenY);
        g.drawLine(mouseScreenX+r+gap, mouseScreenY, mouseScreenX+r+gap+tl, mouseScreenY);

        g.setColor(C_WHITE);
        g.fillOval(mouseScreenX-2, mouseScreenY-2, 4, 4);
    }

    // ─────────────────────────────────────────────────────────────
    // HUD — drawn in screen space
    // ─────────────────────────────────────────────────────────────
    public void drawHUD(Graphics2D g, GamePacket.PlayerState[] players,
                        int myPlayerId, long pingMs, int wave, int level) {
        if (players == null) return;

        // Ping
        Color pc = pingMs < 50 ? C_PING_OK : pingMs < 120 ? C_PING_MID : C_PING_BAD;
        g.setFont(F_HUD);
        g.setColor(C_HUD_BG);
        g.fillRoundRect(SW-135, 5, 128, 20, 5, 5);
        g.setColor(pc);
        g.drawString("PING: " + pingMs + "ms", SW-130, 20);

        // Wave + Level
        g.setColor(C_HUD_BG);
        g.fillRoundRect(SW/2-80, 5, 160, 20, 5, 5);
        g.setColor(new Color(200, 180, 255));
        g.setFont(F_WAVE);
        String wt = "LVL " + level + "  WAVE " + wave;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(wt, SW/2 - fm.stringWidth(wt)/2, 20);

        // Player list
        int hx = 8, hy = 8;
        for (GamePacket.PlayerState ps : players) {
            int ci  = Math.max(0, Math.min(ps.playerId-1, 3));
            Color col = PLAYER_COLORS[ci];

            g.setColor(C_HUD_BG);
            g.fillRoundRect(hx, hy, 195, 24, 5, 5);

            // Color dot
            g.setColor(ps.alive ? col : Color.GRAY);
            g.fillOval(hx+4, hy+6, 12, 12);

            // Name
            g.setFont(F_NAME);
            boolean isMe = ps.playerId == myPlayerId;
            g.setColor(isMe ? Color.WHITE : C_NAME);
            String label = (ps.playerId==1 ? "[H] " : "     ") + ps.username;
            g.drawString(label, hx+20, hy+17);

            // HP bar
            int barX = hx + 95, barW = 90;
            g.setColor(C_HP_BG);
            g.fillRoundRect(barX, hy+7, barW, 9, 4, 4);
            float pct = ps.maxHp > 0 ? (float)ps.hp / ps.maxHp : 0;
            g.setColor(pct > 0.5f ? C_HP_GREEN : pct > 0.25f ? C_HP_YEL : C_HP_RED);
            g.fillRoundRect(barX, hy+7, (int)(barW*pct), 9, 4, 4);

            hy += 30;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────
    private void drawHPBar(Graphics2D g, int cx, int top, int barW, int hp, int maxHp) {
        int bx = cx - barW/2;
        g.setColor(C_HP_BG);
        g.fillRoundRect(bx, top, barW, 5, 3, 3);
        if (maxHp <= 0) return;
        float pct = (float)hp / maxHp;
        g.setColor(pct > 0.5f ? C_HP_GREEN : pct > 0.25f ? C_HP_YEL : C_HP_RED);
        g.fillRoundRect(bx, top, (int)(barW*pct), 5, 3, 3);
        g.setColor(new Color(0,0,0,80));
        g.setStroke(STR1);
        g.drawRoundRect(bx, top, barW, 5, 3, 3);
    }
}
