import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MenuSystem {

    static final int SCREEN_W = 960;
    static final int SCREEN_H = 640;

    // ── Button ───────────────────────────────────────────────────
    static class Button {
        int x, y, w, h;
        String label;
        int hoverAnim = 0;

        Button(int x, int y, int w, int h, String label) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.label = label;
        }

        void update(int mx, int my) {
            boolean hovered = mx>=x && mx<=x+w && my>=y && my<=y+h;
            if (hovered && hoverAnim < 10) hoverAnim++;
            else if (!hovered && hoverAnim > 0) hoverAnim--;
        }

        boolean clicked(int cx, int cy) { return cx>=x && cx<=x+w && cy>=y && cy<=y+h; }

        void draw(Graphics2D g) {
            float t = hoverAnim / 10f;
            Color bg     = new Color((int)(15+30*t),(int)(15+20*t),(int)(40+40*t),220);
            Color border = new Color((int)(60+100*t),(int)(60+80*t),(int)(120+100*t));
            g.setColor(new Color(0,0,0,80));
            g.fillRoundRect(x+4,y+4,w,h,10,10);
            g.setColor(bg);
            g.fillRoundRect(x,y,w,h,10,10);
            g.setColor(border);
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(x,y,w,h,10,10);
            g.setFont(new Font("Monospaced",Font.BOLD,16));
            FontMetrics fm = g.getFontMetrics();
            int tx = x+(w-fm.stringWidth(label))/2;
            int ty = y+(h+fm.getAscent())/2-2;
            if (hoverAnim > 0) {
                g.setColor(new Color(200,220,255));
                g.drawString("> "+label+" <", tx-18, ty);
            } else {
                g.setColor(new Color(150,160,200));
                g.drawString(label, tx, ty);
            }
        }
    }

    long startTime = System.currentTimeMillis();
    List<Button> mainButtons  = new ArrayList<>();
    List<Button> pauseButtons = new ArrayList<>();

    MenuSystem() {
        int cx = SCREEN_W/2 - 110;
        mainButtons.add(new Button(cx, 220, 220, 42, "START GAME"));
        mainButtons.add(new Button(cx, 272, 220, 42, "LOAD GAME"));
        mainButtons.add(new Button(cx, 324, 220, 42, "ENDLESS MODE"));
        mainButtons.add(new Button(cx, 376, 220, 42, "HELP / LORE"));
        mainButtons.add(new Button(cx, 428, 220, 42, "SETTINGS"));
        mainButtons.add(new Button(cx, 480, 220, 42, "EXIT"));
        mainButtons.add(new Button(cx, 535, 220, 42, "MULTIPLAYER"));

        int px = SCREEN_W/2 - 100;
        pauseButtons.add(new Button(px, 260, 200, 40, "RESUME"));
        pauseButtons.add(new Button(px, 314, 200, 40, "SETTINGS"));
        pauseButtons.add(new Button(px, 368, 200, 40, "MAIN MENU"));
        pauseButtons.add(new Button(px, 422, 200, 40, "EXIT GAME"));
    }

    // ── Main Menu ────────────────────────────────────────────────
    void drawMain(Graphics2D g, int mx, int my, SaveData save) {
        long elapsed = System.currentTimeMillis() - startTime;

        g.setColor(new Color(5,5,15));
        g.fillRect(0,0,SCREEN_W,SCREEN_H);

        // Stars
        Random r = new Random(42);
        for (int i = 0; i < 120; i++) {
            float sx = r.nextFloat()*SCREEN_W;
            float sy = r.nextFloat()*SCREEN_H;
            float twinkle = (float)(Math.sin(elapsed*0.002+i*0.5)*0.5+0.5);
            g.setColor(new Color(1f,1f,1f,twinkle*0.8f));
            int ss = r.nextInt(3)+1;
            g.fillOval((int)sx,(int)sy,ss,ss);
        }

        // Void rift
        float t = elapsed*0.001f;
        for (int i=5;i>=1;i--) {
            float r2=80+i*20+(float)Math.sin(t+i)*10;
            g.setColor(new Color(60+i*5,0,120+i*10,40));
            g.fillOval(SCREEN_W/2-(int)r2,SCREEN_H/2-160-(int)r2,(int)r2*2,(int)r2*2);
        }

        drawTitle(g, elapsed);

        if (save.highScore > 0) {
            g.setFont(new Font("Monospaced",Font.BOLD,13));
            g.setColor(new Color(255,200,60,200));
            String hs = "BEST: "+String.format("%06d",save.highScore);
            g.drawString(hs, SCREEN_W/2-g.getFontMetrics().stringWidth(hs)/2, 230);
        }

        mainButtons.forEach(b -> { b.update(mx,my); b.draw(g); });

        // Endless mode tag under button
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.setColor(new Color(120, 255, 180, 160));
        g.drawString("∞ Infinite waves · Power-ups · Arena survival",
                SCREEN_W/2 - 130, 330);

        g.setFont(new Font("Monospaced",Font.PLAIN,10));
        g.setColor(new Color(80,80,120));
        g.drawString("v1.0 | VOIDWALKER ENGINE", 10, SCREEN_H-10);
    }

    void drawTitle(Graphics2D g, long elapsed) {
        float t = (float)(Math.sin(elapsed*0.0015)*3);
        for (int i=4;i>=1;i--) {
            g.setFont(new Font("Monospaced",Font.BOLD,42+i));
            g.setColor(new Color(100,50,200,30*i));
            FontMetrics fm = g.getFontMetrics();
            String title = "VOIDWALKER";
            g.drawString(title, SCREEN_W/2-fm.stringWidth(title)/2+i, 120+(int)t+i);
        }
        g.setFont(new Font("Monospaced",Font.BOLD,42));
        FontMetrics fm = g.getFontMetrics();
        String title = "VOIDWALKER";
        GradientPaint gp = new GradientPaint(0,80,new Color(180,120,255),0,130,new Color(100,60,200));
        g.setPaint(gp);
        g.drawString(title, SCREEN_W/2-fm.stringWidth(title)/2, 120+(int)t);

        g.setFont(new Font("Monospaced",Font.PLAIN,14));
        g.setColor(new Color(140,140,200,200));
        String sub = "Chronicles of the Shattered Realm";
        g.drawString(sub, SCREEN_W/2-g.getFontMetrics().stringWidth(sub)/2, 155+(int)t);
    }

    // ── Pause ────────────────────────────────────────────────────
    void drawPause(Graphics2D g, int mx, int my) {
        g.setColor(new Color(0,0,0,160));
        g.fillRect(0,0,SCREEN_W,SCREEN_H);
        g.setColor(new Color(10,10,30,220));
        g.fillRoundRect(SCREEN_W/2-160,200,320,290,20,20);
        g.setColor(new Color(60,60,120));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(SCREEN_W/2-160,200,320,290,20,20);
        g.setFont(new Font("Monospaced",Font.BOLD,24));
        g.setColor(new Color(180,150,255));
        g.drawString("PAUSED", SCREEN_W/2-50, 240);
        pauseButtons.forEach(b -> { b.update(mx,my); b.draw(g); });
    }

    // ── Help / Lore ──────────────────────────────────────────────
    void drawHelp(Graphics2D g) {
        g.setColor(new Color(5,5,15));
        g.fillRect(0,0,SCREEN_W,SCREEN_H);
        g.setColor(new Color(10,10,30,220));
        g.fillRoundRect(40,30,SCREEN_W-80,SCREEN_H-60,20,20);
        g.setColor(new Color(60,60,120));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(40,30,SCREEN_W-80,SCREEN_H-60,20,20);

        g.setFont(new Font("Monospaced",Font.BOLD,22));
        g.setColor(new Color(180,150,255));
        g.drawString("LORE & CONTROLS", 60, 70);

        String[] lore = {
                "THE SHATTERED REALM","",
                "Once a realm of harmony, the Void Realm was torn apart by the",
                "Architect, a god who sought to reshape existence itself. Now the",
                "realm lies in fragments — dungeons floating in endless void.","",
                "You are the VOIDWALKER — an entity born from the cracks between",
                "worlds. Your mission: descend through the shattered floors, defeat",
                "the Void Lords, and reclaim the Shard of Origin.","",
                "CONTROLS",
                "  WASD              - Move",
                "  LEFT MOUSE BUTTON - Shoot (hold for auto-fire)",
                "  SHIFT             - Dash (brief invincibility)",
                "  E                 - Activate void shield (when charged)",
                "  ESC               - Pause","",
                "GAMEPLAY",
                "  * Defeat all enemies to unlock the EXIT portal",
                "  * Collect health orbs (red) and score gems (gold)",
                "  * Level up to unlock more powerful weapons",
                "  * Every 5 floors you face a Void Lord BOSS",
                "  * Press E when shield is fully charged for protection","",
                "  [ESC or click to go back]"
        };

        int ly = 100;
        for (String line : lore) {
            if (line.equals("THE SHATTERED REALM")||line.equals("CONTROLS")||line.equals("GAMEPLAY")) {
                g.setFont(new Font("Monospaced",Font.BOLD,15));
                g.setColor(new Color(200,180,255));
            } else {
                g.setFont(new Font("Monospaced",Font.PLAIN,13));
                g.setColor(new Color(180,180,220));
            }
            g.drawString(line, 70, ly);
            ly += 20;
        }
    }

    // ── Settings ─────────────────────────────────────────────────
    void drawSettings(Graphics2D g, Settings settings, int mx, int my) {
        g.setColor(new Color(5,5,15));
        g.fillRect(0,0,SCREEN_W,SCREEN_H);
        g.setColor(new Color(10,10,30,220));
        g.fillRoundRect(40,30,SCREEN_W-80,SCREEN_H-60,20,20);
        g.setColor(new Color(60,60,120));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(40,30,SCREEN_W-80,SCREEN_H-60,20,20);

        g.setFont(new Font("Monospaced",Font.BOLD,22));
        g.setColor(new Color(180,150,255));
        g.drawString("SETTINGS", 60, 70);

        // Graphics Quality
        int y = 110;
        g.setFont(new Font("Monospaced",Font.BOLD,15));
        g.setColor(new Color(200,180,255));
        g.drawString("GRAPHICS QUALITY", 60, y);
        y += 30;
        Quality[] qs = {Quality.LOW, Quality.MEDIUM, Quality.HIGH};
        String[] qn  = {"LOW","MEDIUM","HIGH"};
        for (int i=0;i<3;i++) {
            boolean sel = settings.quality==qs[i];
            boolean hov = mx>=60+i*120&&mx<=160+i*120&&my>=y-20&&my<=y+10;
            g.setColor(sel?new Color(100,80,200):hov?new Color(40,40,80):new Color(20,20,40));
            g.fillRoundRect(60+i*120,y-20,100,30,8,8);
            g.setColor(sel?new Color(200,180,255):new Color(120,120,180));
            g.setStroke(new BasicStroke(sel?2f:1f));
            g.drawRoundRect(60+i*120,y-20,100,30,8,8);
            g.setFont(new Font("Monospaced",Font.BOLD,13));
            g.setColor(sel?Color.WHITE:new Color(160,160,200));
            g.drawString(qn[i],60+i*120+10,y+5);
        }

        // Display Mode
        y += 50;
        g.setFont(new Font("Monospaced",Font.BOLD,15));
        g.setColor(new Color(200,180,255));
        g.drawString("DISPLAY MODE", 60, y);
        y += 30;
        String[] modeLabels   = {"WINDOWED","FULLSCREEN"};
        boolean[] modeSelected = {!settings.fullScreen, settings.fullScreen};
        for (int i=0;i<2;i++) {
            boolean sel = modeSelected[i];
            boolean hov = mx>=60+i*160&&mx<=200+i*160&&my>=y-20&&my<=y+10;
            g.setColor(sel?new Color(60,120,60):hov?new Color(30,50,30):new Color(15,30,15));
            g.fillRoundRect(60+i*160,y-20,130,30,8,8);
            g.setColor(sel?new Color(120,255,120):new Color(80,140,80));
            g.setStroke(new BasicStroke(sel?2f:1f));
            g.drawRoundRect(60+i*160,y-20,130,30,8,8);
            g.setFont(new Font("Monospaced",Font.BOLD,13));
            g.setColor(sel?Color.WHITE:new Color(140,180,140));
            g.drawString(modeLabels[i],60+i*160+10,y+5);
        }
        y += 10;
        g.setFont(new Font("Monospaced",Font.PLAIN,11));
        g.setColor(new Color(100,120,100));
        g.drawString("(Takes effect immediately)", 60, y+16);

        // Key Bindings
        y += 40;
        g.setFont(new Font("Monospaced",Font.BOLD,15));
        g.setColor(new Color(200,180,255));
        g.drawString("KEY BINDINGS", 60, y);
        y += 25;
        String[][] binds = {
                {"Move Up",    java.awt.event.KeyEvent.getKeyText(settings.upKey)},
                {"Move Down",  java.awt.event.KeyEvent.getKeyText(settings.downKey)},
                {"Move Left",  java.awt.event.KeyEvent.getKeyText(settings.leftKey)},
                {"Move Right", java.awt.event.KeyEvent.getKeyText(settings.rightKey)},
                {"Dash",       java.awt.event.KeyEvent.getKeyText(settings.dashKey)},
                {"Ability",    java.awt.event.KeyEvent.getKeyText(settings.interactKey)},
        };
        g.setFont(new Font("Monospaced",Font.PLAIN,13));
        for (String[] b : binds) {
            g.setColor(new Color(160,160,200));
            g.drawString(b[0]+":", 60, y);
            g.setColor(new Color(220,200,255));
            g.drawString("["+b[1]+"]", 240, y);
            y += 22;
        }
        g.setColor(new Color(100,160,100));
        g.drawString("Attack: [LEFT MOUSE BUTTON — hold to auto-fire]", 60, y);

        g.setFont(new Font("Monospaced",Font.PLAIN,12));
        g.setColor(new Color(100,100,160));
        g.drawString("[ESC or click to go back]", 60, SCREEN_H-50);
    }

    // ── Game Over ────────────────────────────────────────────────
    void drawGameOver(Graphics2D g, Player player, int level, SaveData save) {
        long t = System.currentTimeMillis();

        // Full screen dark overlay with red tint
        g.setColor(new Color(60, 0, 0, 180));
        g.fillRect(0, 0, SCREEN_W, SCREEN_H);

        // Animated red vignette pulse
        float pulse = (float)(Math.sin(t * 0.002) * 0.5 + 0.5);
        g.setColor(new Color(120, 0, 0, (int)(60 * pulse)));
        g.fillRect(0, 0, SCREEN_W, SCREEN_H);

        // Main panel
        int pw = 520, ph = 380;
        int px2 = SCREEN_W/2 - pw/2, py2 = SCREEN_H/2 - ph/2;

        // Panel shadow
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(px2+8, py2+8, pw, ph, 24, 24);

        // Panel background
        GradientPaint panelGrad = new GradientPaint(
                px2, py2, new Color(25, 5, 5, 240),
                px2, py2+ph, new Color(8, 0, 0, 240));
        g.setPaint(panelGrad);
        g.fillRoundRect(px2, py2, pw, ph, 24, 24);

        // Animated border glow
        float glow = (float)(Math.sin(t * 0.003) * 0.4 + 0.6);
        g.setColor(new Color(180, 30, 30, (int)(200 * glow)));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRoundRect(px2, py2, pw, ph, 24, 24);
        // Inner border
        g.setColor(new Color(100, 20, 20, 120));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(px2+4, py2+4, pw-8, ph-8, 20, 20);

        // Red top accent bar
        GradientPaint accentGrad = new GradientPaint(
                px2, py2, new Color(180, 20, 20, 0),
                px2+pw/2, py2, new Color(220, 50, 50, 200));
        g.setPaint(accentGrad);
        g.fillRoundRect(px2, py2, pw/2, 4, 4, 4);
        GradientPaint accentGrad2 = new GradientPaint(
                px2+pw/2, py2, new Color(220, 50, 50, 200),
                px2+pw, py2, new Color(180, 20, 20, 0));
        g.setPaint(accentGrad2);
        g.fillRoundRect(px2+pw/2, py2, pw/2, 4, 4, 4);

        // ── YOU DIED title with glow ─────────────────────────────
        g.setFont(new Font("Monospaced", Font.BOLD, 48));
        FontMetrics fm = g.getFontMetrics();
        String title = "YOU DIED";
        int tx = SCREEN_W/2 - fm.stringWidth(title)/2;
        int ty = py2 + 72;
        // Glow layers
        for (int i = 4; i >= 1; i--) {
            g.setColor(new Color(200, 0, 0, 25 * i));
            g.drawString(title, tx + i, ty + i);
            g.drawString(title, tx - i, ty - i);
        }
        g.setColor(new Color(230, 50, 50));
        g.drawString(title, tx, ty);
        g.setColor(new Color(255, 150, 150, 180));
        g.drawString(title, tx+1, ty-1);

        // Divider line
        g.setColor(new Color(120, 30, 30, 180));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(px2+30, py2+90, px2+pw-30, py2+90);

        // ── Stats ────────────────────────────────────────────────
        int sx = px2 + 40, sy = py2 + 130;
        int col2 = px2 + pw/2 + 10;

        // Stat boxes
        drawStatBox(g, sx, sy,      pw/2-50, "FLOOR REACHED",
                level < 0 ? "ENDLESS" : "FLOOR " + level,
                new Color(150, 100, 255));
        drawStatBox(g, col2, sy,    pw/2-50, "ENEMIES KILLED",
                String.valueOf(player.kills),
                new Color(220, 80, 80));
        drawStatBox(g, sx, sy+90,   pw/2-50, "FINAL SCORE",
                String.format("%06d", player.score),
                new Color(255, 200, 60));
        drawStatBox(g, col2, sy+90, pw/2-50, "BEST SCORE",
                String.format("%06d", save.highScore),
                new Color(100, 200, 255));

        // New high score flash
        if (player.score > 0 && player.score >= save.highScore) {
            float hsPulse = (float)(Math.sin(t * 0.008) * 0.5 + 0.5);
            g.setFont(new Font("Monospaced", Font.BOLD, 15));
            String hs = "★  NEW HIGH SCORE!  ★";
            FontMetrics fm2 = g.getFontMetrics();
            g.setColor(new Color(255, 220, 0, (int)(200 + 55 * hsPulse)));
            g.drawString(hs, SCREEN_W/2 - fm2.stringWidth(hs)/2, py2 + 280);
        }

        // ── Buttons ──────────────────────────────────────────────
        drawGameOverButton(g, SCREEN_W/2 - 220, py2 + ph - 65, 200, 42,
                "MAIN MENU", new Color(80, 30, 30), new Color(200, 80, 80), t);
        drawGameOverButton(g, SCREEN_W/2 + 20,  py2 + ph - 65, 200, 42,
                "PLAY AGAIN", new Color(30, 60, 30), new Color(80, 200, 80), t);

        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.setColor(new Color(120, 80, 80, 180));
        String hint = "Press ENTER  or  click a button";
        g.drawString(hint, SCREEN_W/2 - g.getFontMetrics().stringWidth(hint)/2, py2 + ph + 20);
    }

    private void drawStatBox(Graphics2D g, int x, int y, int w,
                             String label, String value, Color accent) {
        int h = 70;
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(x+3, y+3, w, h, 10, 10);
        g.setColor(new Color(accent.getRed()/4, accent.getGreen()/4, accent.getBlue()/4, 200));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, y, w, h, 10, 10);

        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
        g.drawString(label, x + 10, y + 18);

        g.setFont(new Font("Monospaced", Font.BOLD, 20));
        g.setColor(Color.WHITE);
        g.drawString(value, x + 10, y + 50);
    }

    private void drawGameOverButton(Graphics2D g, int x, int y, int w, int h,
                                    String label, Color bg, Color border, long t) {
        g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 180));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(border);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(label, x + (w - fm.stringWidth(label))/2, y + h/2 + 5);
    }

    // ── Win ──────────────────────────────────────────────────────
    void drawWin(Graphics2D g, Player player, int level, SaveData save) {
        long t = System.currentTimeMillis();

        // Green overlay
        g.setColor(new Color(0, 40, 0, 170));
        g.fillRect(0, 0, SCREEN_W, SCREEN_H);
        float pulse = (float)(Math.sin(t * 0.002) * 0.5 + 0.5);
        g.setColor(new Color(0, 80, 0, (int)(40 * pulse)));
        g.fillRect(0, 0, SCREEN_W, SCREEN_H);

        int pw = 520, ph = 360;
        int px2 = SCREEN_W/2 - pw/2, py2 = SCREEN_H/2 - ph/2;

        // Panel shadow + body
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(px2+8, py2+8, pw, ph, 24, 24);
        GradientPaint pg = new GradientPaint(px2, py2, new Color(5, 20, 5, 240),
                px2, py2+ph, new Color(2, 8, 2, 240));
        g.setPaint(pg);
        g.fillRoundRect(px2, py2, pw, ph, 24, 24);

        float glow = (float)(Math.sin(t * 0.003) * 0.4 + 0.6);
        g.setColor(new Color(30, 180, 60, (int)(200 * glow)));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRoundRect(px2, py2, pw, ph, 24, 24);

        // Title
        g.setFont(new Font("Monospaced", Font.BOLD, 38));
        FontMetrics fm = g.getFontMetrics();
        String title = "REALM CLEANSED!";
        int tx = SCREEN_W/2 - fm.stringWidth(title)/2;
        int ty = py2 + 65;
        for (int i = 3; i >= 1; i--) {
            g.setColor(new Color(0, 180, 60, 30 * i));
            g.drawString(title, tx+i, ty+i);
        }
        g.setColor(new Color(80, 255, 130));
        g.drawString(title, tx, ty);

        g.setColor(new Color(50, 120, 50, 180));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(px2+30, py2+82, px2+pw-30, py2+82);

        // Stats
        int sx = px2 + 40, sy = py2 + 110;
        int col2 = px2 + pw/2 + 10;
        drawStatBox(g, sx,    sy,    pw/2-50, "FLOORS CLEARED", String.valueOf(level),     new Color(100, 200, 255));
        drawStatBox(g, col2,  sy,    pw/2-50, "ENEMIES KILLED", String.valueOf(player.kills), new Color(220, 80, 80));
        drawStatBox(g, sx,    sy+90, pw/2-50, "FINAL SCORE",    String.format("%06d", player.score), new Color(255, 200, 60));
        drawStatBox(g, col2,  sy+90, pw/2-50, "BEST SCORE",     String.format("%06d", save.highScore), new Color(80, 255, 130));

        if (player.score >= save.highScore && player.score > 0) {
            float hsPulse = (float)(Math.sin(t * 0.008) * 0.5 + 0.5);
            g.setFont(new Font("Monospaced", Font.BOLD, 15));
            String hs = "★  NEW HIGH SCORE!  ★";
            FontMetrics fm2 = g.getFontMetrics();
            g.setColor(new Color(255, 220, 0, (int)(200 + 55 * hsPulse)));
            g.drawString(hs, SCREEN_W/2 - fm2.stringWidth(hs)/2, py2 + 270);
        }

        drawGameOverButton(g, SCREEN_W/2-100, py2+ph-58, 200, 42,
                "MAIN MENU", new Color(20, 50, 20), new Color(80, 200, 80), t);

        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.setColor(new Color(80, 120, 80, 180));
        String hint = "Press ENTER to continue";
        g.drawString(hint, SCREEN_W/2 - g.getFontMetrics().stringWidth(hint)/2, py2+ph+20);
    }

    // ── Transition ───────────────────────────────────────────────
    void drawTransition(Graphics2D g, int timer, int levelNum) {
        float alpha = timer>20 ? (60-timer)/20f : timer/20f;
        g.setColor(new Color(0,0,0,(int)(255*alpha)));
        g.fillRect(0,0,SCREEN_W,SCREEN_H);
        if (timer>20&&timer<40) {
            g.setFont(new Font("Monospaced",Font.BOLD,28));
            g.setColor(new Color(180,150,255,(int)(255*((timer-20)/20f))));
            String s = "FLOOR "+levelNum;
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, SCREEN_W/2-fm.stringWidth(s)/2, SCREEN_H/2);
        }
    }
}