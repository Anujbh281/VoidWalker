import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * MultiplayerRenderer — Draws the world from server-received state.
 *
 * FIXES vs previous version:
 *   1. No longer calls TextureFactory.get() — draws directly using Graphics2D
 *      so it works even when textures aren't preloaded in MP mode.
 *   2. Lighting pass is handled inside draw() so shadows appear in MP.
 *   3. Enemy drawing uses visible shapes matching singleplayer style.
 */
public class MultiplayerRenderer {

    private static final int SCREEN_W = 960;
    private static final int SCREEN_H = 640;

    // ── Pre-cached colors (ZERO allocations per frame) ───────────
    private static final Color[] PLAYER_COLORS = {
            new Color(100, 180, 255),   // P1 blue
            new Color(100, 255, 130),   // P2 green
            new Color(255, 160,  60),   // P3 orange
            new Color(220,  80, 255),   // P4 purple
    };
    private static final Color[] PLAYER_DARK = {
            new Color( 30,  70, 140),
            new Color( 30, 110,  50),
            new Color(130,  70,  10),
            new Color(100,  20, 140),
    };

    private static final Color C_HP_BG      = new Color(20, 10, 10, 200);
    private static final Color C_HP_FG      = new Color(80, 200, 80);
    private static final Color C_HP_MID     = new Color(255, 180, 0);
    private static final Color C_HP_LOW     = new Color(220, 40, 40);
    private static final Color C_NAME       = new Color(220, 210, 255);
    private static final Color C_DEAD       = new Color(80, 80, 80, 120);
    private static final Color C_SHADOW     = new Color(0, 0, 0, 60);
    private static final Color C_BULLET_P   = new Color(100, 200, 255);
    private static final Color C_BULLET_E   = new Color(255, 100, 100);
    private static final Color C_BULLET_GLW = new Color(100, 200, 255, 50);
    private static final Color C_ENEMY_BODY = new Color(180,  60,  60);
    private static final Color C_ENEMY_DARK = new Color(100,  20,  20);
    private static final Color C_ENEMY_EYE  = new Color(255, 220,  50);
    private static final Color C_BOSS_BODY  = new Color(160,  20, 200);
    private static final Color C_BOSS_DARK  = new Color( 80,   5, 110);
    private static final Color C_ENRAGED    = new Color(255,  20,  20, 60);
    private static final Color C_PING_OK    = new Color(60,  220,  60);
    private static final Color C_PING_MID   = new Color(255, 200,   0);
    private static final Color C_PING_BAD   = new Color(220,  60,  60);
    private static final Color C_HUD_BG     = new Color(0,    0,   0, 150);
    private static final Color C_WHITE_190  = new Color(255, 255, 255, 190);
    private static final Color C_DASH_TRAIL = new Color(150, 200, 255, 70);

    private static final Font F_NAME  = new Font("Monospaced", Font.BOLD,  11);
    private static final Font F_SCORE = new Font("Monospaced", Font.PLAIN, 10);
    private static final Font F_HUD   = new Font("Monospaced", Font.BOLD,  12);
    private static final Font F_DEAD  = new Font("Monospaced", Font.BOLD,  10);

    private static final BasicStroke STR_1  = new BasicStroke(1f);
    private static final BasicStroke STR_2  = new BasicStroke(2f);
    private static final BasicStroke STR_3  = new BasicStroke(3f);

    // ── Reusable AffineTransform ──────────────────────────────────
    private final AffineTransform at = new AffineTransform();

    public MultiplayerRenderer() {}

    // ─────────────────────────────────────────────────────────────
    // MAIN DRAW — call from GamePanel.drawGameBase() when in MP mode
    // g is already translated by (-cx, -cy) into world space
    // ─────────────────────────────────────────────────────────────
    public void draw(Graphics2D g, int camX, int camY,
                     GameClient client, int myPlayerId) {
        if (client == null || !client.isConnected()) return;

        // Advance interpolation: server sends at 20Hz, we render at 60Hz
        // so alpha advances by 1/3 each frame (3 render frames per server tick)
        client.tickInterpolation(1f / 3f);

        GamePacket.EnemyState[]  enemies = client.getLatestEnemies();
        GamePacket.BulletState[] bullets = client.getLatestBullets();
        GamePacket.PlayerState[] players = client.getLatestPlayers();

        // Draw order: shadows → enemies → bullets → players
        if (enemies != null) drawEnemies(g, enemies, camX, camY);
        if (bullets != null) drawBullets(g, bullets, camX, camY);
        if (players != null) drawPlayers(g, players, client, myPlayerId, camX, camY);
    }

    // ─────────────────────────────────────────────────────────────
    // LIGHTING — call from GamePanel AFTER g.translate(cx,cy)
    // i.e. back in screen space, same as singleplayer lighting call
    // ─────────────────────────────────────────────────────────────
    public void drawLighting(Graphics2D g, GameClient client,
                             int myPlayerId, int camX, int camY,
                             Quality quality) {
        if (client == null || quality == Quality.LOW) return;

        GamePacket.PlayerState[] players = client.getLatestPlayers();
        if (players == null) return;

        // Find MY player's screen position
        int spx = SCREEN_W / 2, spy = SCREEN_H / 2; // fallback = centre
        for (GamePacket.PlayerState ps : players) {
            if (ps.playerId == myPlayerId && ps.alive) {
                float[] ipos = client.getInterpolatedPos(myPlayerId);
                spx = (int)(ipos[0] - camX);
                spy = (int)(ipos[1] - camY);
                break;
            }
        }

        // Build fake enemy list for ShadowRenderer using server state
        java.util.List<Enemy> fakeEnemies = new java.util.ArrayList<>();
        GamePacket.EnemyState[] enemies = client.getLatestEnemies();
        if (enemies != null) {
            for (GamePacket.EnemyState en : enemies) {
                if (!en.alive) continue;
                // Create a minimal Enemy stub just for lighting
                Enemy stub = new Enemy(en.x, en.y,
                        en.typeOrd == 2 ? EnemyType.BOSS : EnemyType.GRUNT);
                stub.alive = true;
                fakeEnemies.add(stub);
            }
        }

        Shape    oldClip      = g.getClip();
        Composite oldComposite = g.getComposite();
        g.setClip(null);

        ShadowRenderer.drawLightingPass(g, spx, spy, fakeEnemies, camX, camY, quality);

        g.setClip(oldClip);
        g.setComposite(oldComposite);
    }

    // ─────────────────────────────────────────────────────────────
    // DRAW ENEMIES
    // ─────────────────────────────────────────────────────────────
    private void drawEnemies(Graphics2D g, GamePacket.EnemyState[] enemies,
                             int camX, int camY) {
        for (GamePacket.EnemyState en : enemies) {
            if (!en.alive) continue;

            // Viewport cull
            float sx = en.x - camX, sy = en.y - camY;
            if (sx < -80 || sx > SCREEN_W + 80 || sy < -80 || sy > SCREEN_H + 80) continue;

            boolean isBoss = (en.typeOrd == 2);
            int sz = isBoss ? 52 : 32;
            int ex = (int)en.x, ey = (int)en.y;

            // Ground shadow
            g.setColor(C_SHADOW);
            g.fillOval(ex - sz/2, ey + sz/2 - 6, sz, 10);

            // Body — flip horizontally if facing left
            int drawX = ex - sz/2;
            if (!en.facingRight) {
                // Mirror: translate right edge then scale -1
                g.translate(ex + sz/2, ey - sz/2);
                g.scale(-1, 1);
                drawX = 0;
            } else {
                g.translate(drawX, ey - sz/2);
                drawX = 0;
            }

            drawEnemyBody(g, en, sz, isBoss);

            // Restore transform
            g.scale(en.facingRight ? 1 : -1, 1);
            g.translate(en.facingRight ? -(ex - sz/2) : -(ex + sz/2), -(ey - sz/2));

            // Enraged glow overlay
            if (en.enraged) {
                g.setColor(C_ENRAGED);
                g.fillOval(ex - sz/2 - 6, ey - sz/2 - 6, sz + 12, sz + 12);
            }

            // HP bar
            drawHPBar(g, ex, ey - sz/2 - 12, isBoss ? 70 : 36, en.hp, en.maxHp);
        }
    }

    private void drawEnemyBody(Graphics2D g, GamePacket.EnemyState en, int sz, boolean isBoss) {
        Color bodyColor = isBoss ? C_BOSS_BODY : C_ENEMY_BODY;
        Color darkColor = isBoss ? C_BOSS_DARK : C_ENEMY_DARK;

        // Main body rectangle
        g.setColor(bodyColor);
        g.fillRoundRect(2, 2, sz - 4, sz - 4, 8, 8);

        // Dark shading on bottom half
        g.setColor(darkColor);
        g.fillRoundRect(2, sz/2, sz - 4, sz/2 - 4, 8, 8);

        // Eyes (always face right since we handle flip at call site)
        int eyeY = sz / 3;
        g.setColor(C_ENEMY_EYE);
        if (isBoss) {
            // Boss: 3 eyes
            g.fillOval(sz/2 - 16, eyeY, 8, 8);
            g.fillOval(sz/2 - 4,  eyeY - 4, 8, 8);
            g.fillOval(sz/2 + 8,  eyeY, 8, 8);
            // Boss spike on top
            g.setColor(new Color(200, 0, 255));
            int[] sx = {sz/2-6, sz/2, sz/2+6};
            int[] sy = {0, -10, 0};
            g.fillPolygon(sx, sy, 3);
        } else if (en.typeOrd == 1) {
            // Ranger: single large eye
            g.fillOval(sz/2 - 5, eyeY, 10, 10);
            g.setColor(Color.BLACK);
            g.fillOval(sz/2 - 2, eyeY + 3, 4, 4);
        } else {
            // Grunt: two small eyes
            g.fillOval(sz/2 - 9, eyeY, 6, 6);
            g.fillOval(sz/2 + 3, eyeY, 6, 6);
        }

        // Outline
        g.setColor(darkColor);
        g.setStroke(STR_1);
        g.drawRoundRect(2, 2, sz - 4, sz - 4, 8, 8);
        g.setStroke(STR_1);
    }

    // ─────────────────────────────────────────────────────────────
    // DRAW BULLETS
    // ─────────────────────────────────────────────────────────────
    private void drawBullets(Graphics2D g, GamePacket.BulletState[] bullets,
                             int camX, int camY) {
        for (GamePacket.BulletState b : bullets) {
            if (!b.active) continue;

            float sx = b.x - camX, sy = b.y - camY;
            if (sx < -20 || sx > SCREEN_W + 20 || sy < -20 || sy > SCREEN_H + 20) continue;

            int bx = (int)b.x, by = (int)b.y;
            boolean isPlayer = (b.ownerId >= 0);

            // Outer glow
            g.setColor(isPlayer ? C_BULLET_GLW
                    : new Color(255, 100, 100, 45));
            g.fillOval(bx - 8, by - 8, 16, 16);

            // Core
            g.setColor(isPlayer ? C_BULLET_P : C_BULLET_E);
            g.fillOval(bx - 4, by - 4, 8, 8);

            // Bright centre
            g.setColor(C_WHITE_190);
            g.fillOval(bx - 1, by - 1, 3, 3);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DRAW PLAYERS
    // ─────────────────────────────────────────────────────────────
    private void drawPlayers(Graphics2D g, GamePacket.PlayerState[] players,
                             GameClient client, int myPlayerId,
                             int camX, int camY) {
        for (GamePacket.PlayerState ps : players) {
            // Use interpolated position for smooth movement
            float[] ipos = client.getInterpolatedPos(ps.playerId);
            float worldX = ipos[0], worldY = ipos[1];

            // Viewport cull
            float sx = worldX - camX, sy = worldY - camY;
            if (sx < -80 || sx > SCREEN_W + 80 || sy < -80 || sy > SCREEN_H + 80) continue;

            int px = (int)worldX, py = (int)worldY;
            boolean isMe = (ps.playerId == myPlayerId);
            int colorIdx = Math.max(0, Math.min(ps.playerId - 1, 3));
            Color pc   = PLAYER_COLORS[colorIdx];
            Color dark = PLAYER_DARK[colorIdx];

            if (!ps.alive) {
                // Ghost outline
                g.setColor(C_DEAD);
                g.fillOval(px - 14, py - 14, 28, 28);
                g.setColor(new Color(150, 150, 150, 140));
                g.setStroke(STR_1);
                g.drawOval(px - 14, py - 14, 28, 28);
                g.setFont(F_DEAD);
                g.setColor(new Color(200, 100, 100, 180));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("DEAD", px - fm.stringWidth("DEAD")/2, py - 18);
                continue;
            }

            // Ground shadow
            g.setColor(C_SHADOW);
            g.fillOval(px - 13, py + 11, 26, 8);

            // Dash trail
            if (ps.dashing) {
                g.setColor(C_DASH_TRAIL);
                g.fillOval(px - 20, py - 20, 40, 40);
            }

            // ── Player body (hand-drawn, no TextureFactory) ───────
            // Flip for facing direction
            int flipSign = ps.facingRight ? 1 : -1;
            g.translate(px, py);
            g.scale(flipSign, 1);

            // Legs
            g.setColor(dark);
            g.fillRoundRect(-8, 8, 6, 10, 3, 3);   // left leg
            g.fillRoundRect( 2, 8, 6, 10, 3, 3);   // right leg

            // Body
            g.setColor(pc);
            g.fillRoundRect(-10, -10, 20, 20, 6, 6);

            // Chest detail
            g.setColor(dark);
            g.fillRoundRect(-5, -4, 10, 8, 3, 3);

            // Head
            g.setColor(pc);
            g.fillRoundRect(-7, -20, 14, 13, 5, 5);

            // Eye
            g.setColor(Color.WHITE);
            g.fillOval(1, -17, 5, 5);
            g.setColor(Color.BLACK);
            g.fillOval(2, -16, 3, 3);

            // Gun arm (always pointing right before flip)
            g.setColor(dark);
            g.fillRoundRect(8, -3, 10, 5, 2, 2);   // arm
            g.setColor(new Color(60, 60, 70));
            g.fillRoundRect(15, -4, 8, 6, 2, 2);   // gun barrel

            // Shooting flash
            if (ps.shooting) {
                g.setColor(new Color(255, 240, 100, 180));
                g.fillOval(22, -5, 8, 8);
            }

            // Restore transform
            g.scale(flipSign, 1);
            g.translate(-px, -py);

            // ── Outline ring (shows player color) ────────────────
            g.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), isMe ? 200 : 120));
            g.setStroke(isMe ? STR_3 : STR_2);
            g.drawOval(px - 14, py - 14, 28, 28);
            g.setStroke(STR_1);

            // ── HP bar ────────────────────────────────────────────
            drawHPBar(g, px, py - 30, 40, ps.hp, ps.maxHp);

            // ── Name tag ─────────────────────────────────────────
            String tag = (isMe ? "★ " : "") + ps.username;
            g.setFont(F_NAME);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(tag);
            g.setColor(C_HUD_BG);
            g.fillRoundRect(px - tw/2 - 4, py - 44, tw + 8, 13, 4, 4);
            g.setColor(isMe ? pc : C_NAME);
            g.drawString(tag, px - tw/2, py - 34);

            // ── Score ─────────────────────────────────────────────
            g.setFont(F_SCORE);
            g.setColor(new Color(200, 200, 100, 200));
            String sc = String.valueOf(ps.score);
            g.drawString(sc, px - g.getFontMetrics().stringWidth(sc)/2, py - 48);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HP BAR helper
    // ─────────────────────────────────────────────────────────────
    private void drawHPBar(Graphics2D g, int cx, int top, int barW, int hp, int maxHp) {
        int bx = cx - barW / 2;
        g.setColor(C_HP_BG);
        g.fillRoundRect(bx, top, barW, 5, 3, 3);
        if (maxHp <= 0) return;
        float pct = (float)hp / maxHp;
        g.setColor(pct > 0.5f ? C_HP_FG : pct > 0.25f ? C_HP_MID : C_HP_LOW);
        g.fillRoundRect(bx, top, (int)(barW * pct), 5, 3, 3);
        // Border
        g.setColor(new Color(0, 0, 0, 80));
        g.setStroke(STR_1);
        g.drawRoundRect(bx, top, barW, 5, 3, 3);
        g.setStroke(STR_1);
    }

    // ─────────────────────────────────────────────────────────────
    // MULTIPLAYER HUD — call in screen space (after g.translate(cx,cy))
    // ─────────────────────────────────────────────────────────────
    public void drawMultiplayerHUD(Graphics2D g, GamePacket.PlayerState[] players,
                                   int myPlayerId, long pingMs) {
        if (players == null) return;

        // ── Ping (top right) ─────────────────────────────────────
        Color pingColor = pingMs < 50 ? C_PING_OK : pingMs < 120 ? C_PING_MID : C_PING_BAD;
        g.setFont(F_HUD);
        g.setColor(C_HUD_BG);
        g.fillRoundRect(SCREEN_W - 130, 6, 120, 20, 6, 6);
        g.setColor(pingColor);
        g.drawString("PING: " + pingMs + "ms", SCREEN_W - 124, 21);

        // ── Player list (top left) ────────────────────────────────
        int hx = 8, hy = 8;
        for (GamePacket.PlayerState ps : players) {
            int colorIdx = Math.max(0, Math.min(ps.playerId - 1, 3));
            Color pc = PLAYER_COLORS[colorIdx];

            // Background
            g.setColor(C_HUD_BG);
            g.fillRoundRect(hx, hy, 190, 24, 6, 6);

            // Color dot
            g.setColor(ps.alive ? pc : Color.GRAY);
            g.fillOval(hx + 5, hy + 6, 12, 12);

            // Name
            g.setFont(F_NAME);
            boolean isMe = (ps.playerId == myPlayerId);
            g.setColor(isMe ? Color.WHITE : C_NAME);
            String nameTag = (ps.playerId == 1 ? "[H] " : "     ") + ps.username;
            g.drawString(nameTag, hx + 22, hy + 17);

            // HP bar
            int barX = hx + 95, barW = 88;
            g.setColor(C_HP_BG);
            g.fillRoundRect(barX, hy + 7, barW, 9, 4, 4);
            float pct = ps.maxHp > 0 ? (float)ps.hp / ps.maxHp : 0;
            g.setColor(pct > 0.5f ? C_HP_FG : pct > 0.25f ? C_HP_MID : C_HP_LOW);
            g.fillRoundRect(barX, hy + 7, (int)(barW * pct), 9, 4, 4);

            hy += 30;
        }
    }
}