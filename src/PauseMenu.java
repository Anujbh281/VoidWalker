import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

public class PauseMenu {

    public enum Screen { NONE, MAIN, CONTROLS, AUDIO, GRAPHICS }

    private Screen screen    = Screen.NONE;
    public Runnable onResume = null, onMainMenu = null, onExit = null, onApplyGfx = null;
    public Runnable onKeybindChanged = null;  // ADDED: callback for keybind changes

    private final KeybindManager keybinds;
    private final Settings       settings;
    private final AudioManager   audio;

    private float fadeAlpha = 0f, slideY = 60f;
    private long  openTime  = 0;

    // KEY FIX: track actual drawn Y so hit boxes match visual position
    private int currentPanelY = 60;

    private KeybindManager.KeyAction rebindingAction = null;
    private String                   rebindConflict  = null;

    private int hoverBtn = -1, mouseX = 0, mouseY = 0;

    private int   draggingSlider = -1;
    private float masterVol = 1f, musicVol = 0.7f, sfxVol = 1f;
    private boolean muted   = false;
    private int controlsScroll = 0;

    static final int SW = 960, SH = 640;
    static final int PW = 460, PH = 520;
    static final int PX = (SW - PW) / 2;
    static final int PY = (SH - PH) / 2;

    private static final Color C_PANEL    = new Color( 12,  8, 28, 235);
    private static final Color C_BORDER   = new Color( 80, 50,160, 180);
    private static final Color C_BORDER_H = new Color(140, 90,255, 220);
    private static final Color C_TEXT     = new Color(200,185,255);
    private static final Color C_TEXT_DIM = new Color(120,105,175);
    private static final Color C_ACCENT   = new Color(130, 80,255);
    private static final Color C_BTN_HV   = new Color( 40, 25, 80, 200);
    private static final Color C_BTN_NM   = new Color( 20, 12, 45, 180);
    private static final Color C_RED      = new Color(220, 60, 60);
    private static final Color C_GREEN    = new Color( 60,220,100);
    private static final Color C_GOLD     = new Color(255,200, 50);

    private static final Font F_TITLE = new Font("Monospaced", Font.BOLD, 26);
    private static final Font F_BTN   = new Font("Monospaced", Font.BOLD, 15);
    private static final Font F_LABEL = new Font("Monospaced", Font.BOLD, 12);
    private static final Font F_SMALL = new Font("Monospaced", Font.PLAIN, 11);
    private static final Font F_KEY   = new Font("Monospaced", Font.BOLD, 13);

    private static final String[] MAIN_BTNS = {
            "RESUME", "CONTROLS", "AUDIO", "GRAPHICS", "MAIN MENU", "EXIT GAME"
    };

    public PauseMenu(KeybindManager keybinds, Settings settings, AudioManager audio) {
        this.keybinds = keybinds;
        this.settings = settings;
        this.audio    = audio;
    }

    // ── Open / close / toggle ─────────────────────────────────────
    public void open() {
        if (screen == Screen.NONE) {
            screen        = Screen.MAIN;
            openTime      = System.currentTimeMillis();
            fadeAlpha     = 0f;
            slideY        = 40f;
            currentPanelY = PY + 40;
        }
    }

    public void close() {
        screen          = Screen.NONE;
        rebindingAction = null;
        if (onResume != null) onResume.run();
    }

    public void toggle() {
        if (screen == Screen.NONE) open();
        else if (screen == Screen.MAIN) close();
        else screen = Screen.MAIN;
    }

    public boolean isOpen() { return screen != Screen.NONE; }

    // ADDED: isAnimating method for performance optimization
    public boolean isAnimating() {
        return slideY > 0.5f || fadeAlpha < 0.99f;
    }

    // ── Key handler ───────────────────────────────────────────────
    public boolean handleKey(int keyCode) {
        if (!isOpen()) return false;
        if (rebindingAction != null) {
            if (keyCode == KeyEvent.VK_ESCAPE) {
                rebindingAction = null; rebindConflict = null;
            } else {
                KeybindManager.KeyAction conflict = keybinds.getConflict(keyCode, rebindingAction);
                if (conflict != null) {
                    rebindConflict = "Already used by: " + conflict.displayName;
                } else {
                    keybinds.setKey(rebindingAction, keyCode);
                    // ADDED: notify GamePanel to recache keybinds
                    if (onKeybindChanged != null) onKeybindChanged.run();
                    rebindingAction = null; rebindConflict = null;
                }
            }
            return true;
        }
        if (keyCode == KeyEvent.VK_ESCAPE) { toggle(); return true; }
        return false;
    }

    // ── Update: animate + track currentPanelY for hit testing ─────
    public void update(int mx, int my) {
        mouseX = mx; mouseY = my;
        long elapsed = System.currentTimeMillis() - openTime;
        fadeAlpha     = Math.min(1f, elapsed / 150f);
        slideY        = Math.max(0f, slideY - 3f);
        // KEY FIX: always keep currentPanelY in sync with draw position
        currentPanelY = PY + (int)slideY;

        if (screen == Screen.MAIN) {
            hoverBtn = -1;
            for (int i = 0; i < MAIN_BTNS.length; i++) {
                if (btnBounds(i).contains(mx, my)) { hoverBtn = i; break; }
            }
        }
    }

    // ── Click handler — all bounds use currentPanelY ──────────────
    public boolean handleClick(int cx, int cy) {
        if (!isOpen()) return false;
        switch (screen) {
            case MAIN     -> handleMainClick(cx, cy);
            case CONTROLS -> handleControlsClick(cx, cy);
            case AUDIO    -> handleAudioClick(cx, cy);
            case GRAPHICS -> handleGraphicsClick(cx, cy);
        }
        return true;
    }

    private void handleMainClick(int cx, int cy) {
        for (int i = 0; i < MAIN_BTNS.length; i++) {
            if (btnBounds(i).contains(cx, cy)) {
                switch (i) {
                    case 0 -> close();
                    case 1 -> { screen = Screen.CONTROLS; controlsScroll = 0; }
                    case 2 -> screen = Screen.AUDIO;
                    case 3 -> screen = Screen.GRAPHICS;
                    case 4 -> { screen = Screen.NONE; if (onMainMenu != null) onMainMenu.run(); }
                    case 5 -> { if (onExit != null) onExit.run(); else System.exit(0); }
                }
                return;
            }
        }
    }

    private void handleControlsClick(int cx, int cy) {
        if (backBtnBounds().contains(cx, cy)) { screen = Screen.MAIN; rebindingAction = null; return; }
        if (resetBtnBounds().contains(cx, cy)) { keybinds.resetToDefaults(); keybinds.save(); return; }
        if (rebindingAction != null) { rebindingAction = null; rebindConflict = null; return; }

        KeybindManager.KeyAction[] actions = KeybindManager.KeyAction.values();
        int startY = currentPanelY + 90;
        for (int i = 0; i < actions.length; i++) {
            int rowY = startY + i * 48 - controlsScroll;
            if (new Rectangle(PX + PW - 140, rowY + 6, 120, 30).contains(cx, cy)) {
                rebindingAction = actions[i];
                rebindConflict  = null;
                return;
            }
        }
    }

    private void handleAudioClick(int cx, int cy) {
        if (backBtnBounds().contains(cx, cy)) { screen = Screen.MAIN; return; }
        if (new Rectangle(PX + PW/2 - 50, currentPanelY + PH - 70, 100, 32).contains(cx, cy)) {
            muted = !muted; applyVolume();
        }
    }

    private void handleGraphicsClick(int cx, int cy) {
        if (backBtnBounds().contains(cx, cy)) { screen = Screen.MAIN; return; }
        Quality[] qs = {Quality.LOW, Quality.MEDIUM, Quality.HIGH};
        int qY = currentPanelY + 110;
        for (int i = 0; i < 3; i++) {
            if (new Rectangle(PX + 20 + i * 130, qY, 120, 32).contains(cx, cy)) {
                settings.quality = qs[i]; settings.save(); return;
            }
        }
        if (new Rectangle(PX + 20,  currentPanelY + 180, 130, 32).contains(cx, cy)) {
            settings.fullScreen = true;  settings.save(); if (onApplyGfx != null) onApplyGfx.run(); return;
        }
        if (new Rectangle(PX + 160, currentPanelY + 180, 130, 32).contains(cx, cy)) {
            settings.fullScreen = false; settings.save(); if (onApplyGfx != null) onApplyGfx.run();
        }
    }

    // ── Sliders ───────────────────────────────────────────────────
    public void handleMousePress(int cx, int cy) {
        if (screen != Screen.AUDIO) return;
        int[] ys = {currentPanelY+140, currentPanelY+210, currentPanelY+280};
        int sliderX = PX+30, sliderW = PW-60;
        for (int i = 0; i < ys.length; i++) {
            if (new Rectangle(sliderX, ys[i]-10, sliderW, 20).contains(cx, cy)) {
                draggingSlider = i; handleMouseDrag(cx, cy); return;
            }
        }
    }

    public void handleMouseDrag(int cx, int cy) {
        if (screen != Screen.AUDIO || draggingSlider < 0) return;
        float t = Math.max(0f, Math.min(1f, (float)(cx - PX - 30) / (PW - 60)));
        switch (draggingSlider) { case 0->masterVol=t; case 1->musicVol=t; case 2->sfxVol=t; }
        applyVolume();
    }

    public void handleMouseRelease() { draggingSlider = -1; }

    private void applyVolume() {
        float m = muted ? 0f : masterVol;
        if (audio != null) { audio.setMasterVolume(m); audio.setMusicVolume(musicVol * m); }
    }

    // ── Bounds — all use currentPanelY ────────────────────────────
    private Rectangle btnBounds(int i) {
        return new Rectangle(PX+40, currentPanelY+88 + i*(44+10), PW-80, 44);
    }
    private Rectangle backBtnBounds()  { return new Rectangle(PX+16,      currentPanelY+PH-48, 110, 32); }
    private Rectangle resetBtnBounds() { return new Rectangle(PX+PW-136,  currentPanelY+PH-48, 120, 32); }

    // ── Draw ──────────────────────────────────────────────────────
    public void draw(Graphics2D g) {
        if (screen == Screen.NONE) return;
        g.setColor(new Color(0, 0, 0, (int)(180 * fadeAlpha)));
        g.fillRect(0, 0, SW, SH);
        switch (screen) {
            case MAIN     -> drawMain(g);
            case CONTROLS -> drawControls(g);
            case AUDIO    -> drawAudio(g);
            case GRAPHICS -> drawGraphics(g);
        }
    }

    private void drawMain(Graphics2D g) {
        long t = System.currentTimeMillis();
        drawPanel(g, PX, currentPanelY, PW, PH);

        float glow = (float)(Math.sin(t*0.003)*0.4+0.6);
        g.setFont(F_TITLE);
        String title = "GAME PAUSED";
        FontMetrics fm = g.getFontMetrics();
        int tx = SW/2 - fm.stringWidth(title)/2;
        for (int i=3;i>=1;i--) {
            g.setColor(new Color(100,50,200,(int)(30*i*glow)));
            g.drawString(title, tx+i, currentPanelY+55+i);
        }
        g.setColor(C_TEXT); g.drawString(title, tx, currentPanelY+55);
        g.setColor(C_BORDER); g.setStroke(new BasicStroke(1f));
        g.drawLine(PX+30, currentPanelY+68, PX+PW-30, currentPanelY+68);

        for (int i = 0; i < MAIN_BTNS.length; i++) {
            drawMenuBtn(g, btnBounds(i), MAIN_BTNS[i], hoverBtn==i,
                    i==4?C_RED:i==0?C_GREEN:null, t);
        }
    }

    private void drawMenuBtn(Graphics2D g, Rectangle b, String label,
                             boolean hov, Color accent, long t) {
        g.setColor(new Color(0,0,0,60));
        g.fillRoundRect(b.x+3,b.y+3,b.width,b.height,10,10);
        g.setColor(hov ? C_BTN_HV : C_BTN_NM);
        g.fillRoundRect(b.x,b.y,b.width,b.height,10,10);
        if (hov) {
            float glow=(float)(Math.sin(t*0.006)*0.3+0.7);
            g.setColor(new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),(int)(40*glow)));
            g.fillRoundRect(b.x,b.y,b.width,b.height,10,10);
        }
        Color bdr = accent!=null ? new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),hov?220:140)
                : (hov?C_BORDER_H:C_BORDER);
        g.setColor(bdr); g.setStroke(new BasicStroke(hov?2f:1.2f));
        g.drawRoundRect(b.x,b.y,b.width,b.height,10,10);
        g.setFont(F_BTN);
        FontMetrics fm=g.getFontMetrics();
        String disp = hov?"> "+label+" <":label;
        g.setColor(accent!=null?accent:(hov?Color.WHITE:C_TEXT));
        g.drawString(disp, b.x+(b.width-fm.stringWidth(disp))/2, b.y+b.height/2+fm.getAscent()/2-2);
    }

    private void drawControls(Graphics2D g) {
        int ph2 = PH+20;
        drawPanel(g, PX, currentPanelY, PW, ph2);
        drawScreenTitle(g, "CONTROLS", currentPanelY);
        KeybindManager.KeyAction[] actions = KeybindManager.KeyAction.values();
        int startY = currentPanelY+85; String lastCat="";
        for (int i=0;i<actions.length;i++) {
            KeybindManager.KeyAction action=actions[i];
            int rowY=startY+i*48-controlsScroll;
            if (!action.category.equals(lastCat)) {
                lastCat=action.category;
                g.setFont(F_SMALL); g.setColor(C_TEXT_DIM);
                g.drawString("── "+lastCat+" ──",PX+20,rowY-4); rowY+=14;
            }
            boolean isR=(rebindingAction==action);
            g.setColor(isR?new Color(60,30,120,180):new Color(15,8,35,140));
            g.fillRoundRect(PX+16,rowY,PW-32,36,8,8);
            g.setColor(isR?C_BORDER_H:C_BORDER); g.setStroke(new BasicStroke(isR?2f:1f));
            g.drawRoundRect(PX+16,rowY,PW-32,36,8,8);
            g.setFont(F_LABEL); g.setColor(C_TEXT);
            g.drawString(action.displayName,PX+28,rowY+23);
            String kl=isR?"Press any key...":keybinds.getKeyName(action);
            Color kc=isR?C_GOLD:C_ACCENT;
            int kbW=120,kbX=PX+PW-140,kbY=rowY+3;
            g.setColor(new Color(kc.getRed()/4,kc.getGreen()/4,kc.getBlue()/4,200));
            g.fillRoundRect(kbX,kbY,kbW,30,8,8);
            g.setColor(kc); g.setStroke(new BasicStroke(isR?2f:1.2f));
            g.drawRoundRect(kbX,kbY,kbW,30,8,8);
            g.setFont(F_KEY); FontMetrics fm=g.getFontMetrics();
            g.setColor(Color.WHITE);
            g.drawString(kl,kbX+(kbW-fm.stringWidth(kl))/2,kbY+20);
        }
        if (rebindConflict!=null){g.setFont(F_SMALL);g.setColor(C_RED);g.drawString(rebindConflict,PX+20,currentPanelY+ph2-55);}
        g.setFont(F_SMALL);g.setColor(C_TEXT_DIM);
        g.drawString("Click a key to rebind  |  ESC = cancel",PX+20,currentPanelY+ph2-38);
        drawBackBtn(g,currentPanelY+ph2); drawResetBtn(g,currentPanelY+ph2);
    }

    private void drawAudio(Graphics2D g) {
        drawPanel(g,PX,currentPanelY,PW,PH); drawScreenTitle(g,"AUDIO",currentPanelY);
        String[] labels={"Master Volume","Music Volume","SFX Volume"};
        float[] vals={masterVol,musicVol,sfxVol};
        for (int i=0;i<3;i++) {
            int sy=currentPanelY+115+i*75;
            g.setFont(F_LABEL);g.setColor(C_TEXT);g.drawString(labels[i],PX+30,sy);
            g.setFont(F_SMALL);g.setColor(C_TEXT_DIM);g.drawString((int)(vals[i]*100)+"%",PX+PW-60,sy);
            drawSlider(g,PX+30,sy+12,PW-60,vals[i],muted);
        }
        Rectangle mb=new Rectangle(PX+PW/2-60,currentPanelY+PH-80,120,34);
        g.setColor(muted?new Color(80,20,20,200):new Color(20,60,20,200));
        g.fillRoundRect(mb.x,mb.y,mb.width,mb.height,10,10);
        g.setColor(muted?C_RED:C_GREEN); g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(mb.x,mb.y,mb.width,mb.height,10,10);
        g.setFont(F_BTN);g.setColor(Color.WHITE);
        String ml=muted?"UNMUTE":"MUTE"; FontMetrics fm=g.getFontMetrics();
        g.drawString(ml,mb.x+(mb.width-fm.stringWidth(ml))/2,mb.y+mb.height/2+fm.getAscent()/2-2);
        drawBackBtn(g,currentPanelY+PH);
    }

    private void drawSlider(Graphics2D g,int x,int y,int w,float v,boolean dis) {
        int f=(int)(w*v);
        g.setColor(new Color(30,20,60)); g.fillRoundRect(x,y,w,10,5,5);
        g.setColor(dis?new Color(80,80,80):C_ACCENT); if(f>0)g.fillRoundRect(x,y,f,10,5,5);
        g.setColor(dis?Color.GRAY:Color.WHITE); g.fillOval(x+f-7,y-4,14,18);
        g.setColor(dis?new Color(80,80,80):C_ACCENT); g.setStroke(new BasicStroke(2f));
        g.drawOval(x+f-7,y-4,14,18);
    }

    private void drawGraphics(Graphics2D g) {
        drawPanel(g,PX,currentPanelY,PW,PH); drawScreenTitle(g,"GRAPHICS",currentPanelY);
        int y=currentPanelY+90;
        g.setFont(F_LABEL);g.setColor(C_TEXT_DIM);g.drawString("RENDER QUALITY",PX+20,y);y+=18;
        Quality[] qs={Quality.LOW,Quality.MEDIUM,Quality.HIGH};
        String[] qn={"LOW","MEDIUM","HIGH"};
        for (int i=0;i<3;i++) {
            boolean sel=settings.quality==qs[i];
            Rectangle r=new Rectangle(PX+20+i*130,y,120,34);
            g.setColor(sel?new Color(60,40,120,200):new Color(20,12,40,180));g.fillRoundRect(r.x,r.y,r.width,r.height,8,8);
            g.setColor(sel?C_BORDER_H:C_BORDER);g.setStroke(new BasicStroke(sel?2f:1f));g.drawRoundRect(r.x,r.y,r.width,r.height,8,8);
            g.setFont(F_KEY);g.setColor(sel?Color.WHITE:C_TEXT_DIM);
            FontMetrics fm=g.getFontMetrics();g.drawString(qn[i],r.x+(r.width-fm.stringWidth(qn[i]))/2,r.y+22);
        }
        y+=55;
        g.setFont(F_LABEL);g.setColor(C_TEXT_DIM);g.drawString("DISPLAY MODE",PX+20,y);y+=18;
        String[] modes={"FULLSCREEN","WINDOWED"};boolean[] mSel={settings.fullScreen,!settings.fullScreen};
        for (int i=0;i<2;i++) {
            Rectangle r=new Rectangle(PX+20+i*150,y,130,34);
            g.setColor(mSel[i]?new Color(40,60,20,200):new Color(20,12,40,180));g.fillRoundRect(r.x,r.y,r.width,r.height,8,8);
            g.setColor(mSel[i]?new Color(80,200,80,200):C_BORDER);g.setStroke(new BasicStroke(mSel[i]?2f:1f));g.drawRoundRect(r.x,r.y,r.width,r.height,8,8);
            g.setFont(F_KEY);g.setColor(mSel[i]?Color.WHITE:C_TEXT_DIM);
            FontMetrics fm=g.getFontMetrics();g.drawString(modes[i],r.x+(r.width-fm.stringWidth(modes[i]))/2,r.y+22);
        }
        y+=55;
        g.setFont(F_SMALL);g.setColor(C_TEXT_DIM);
        g.drawString("Changes apply immediately",PX+20,y);
        g.drawString("F3 = Performance overlay",PX+20,y+18);
        g.drawString("Quality: "+settings.quality+" | "+(settings.fullScreen?"Fullscreen":"Windowed"),PX+20,y+36);
        drawBackBtn(g,currentPanelY+PH);
    }

    private void drawPanel(Graphics2D g,int x,int y,int w,int h) {
        g.setColor(new Color(0,0,0,100));g.fillRoundRect(x+6,y+6,w,h,20,20);
        g.setColor(C_PANEL);g.fillRoundRect(x,y,w,h,20,20);
        long t=System.currentTimeMillis();
        float glow=(float)(Math.sin(t*0.002)*0.3+0.7);
        g.setColor(new Color(C_BORDER.getRed(),C_BORDER.getGreen(),C_BORDER.getBlue(),(int)(C_BORDER.getAlpha()*glow)));
        g.setStroke(new BasicStroke(2f));g.drawRoundRect(x,y,w,h,20,20);
        g.setColor(new Color(80,50,160,40));g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x+4,y+4,w-8,h-8,16,16);
    }

    private void drawScreenTitle(Graphics2D g,String title,int panelY) {
        g.setFont(F_TITLE);g.setColor(C_TEXT);
        FontMetrics fm=g.getFontMetrics();
        g.drawString(title,SW/2-fm.stringWidth(title)/2,panelY+48);
        g.setColor(C_BORDER);g.setStroke(new BasicStroke(1f));
        g.drawLine(PX+30,panelY+60,PX+PW-30,panelY+60);
    }

    private void drawBackBtn(Graphics2D g,int bottomY) {
        Rectangle b=backBtnBounds();
        boolean hov=b.contains(mouseX,mouseY);
        g.setColor(hov?new Color(40,20,80,200):new Color(20,10,40,180));
        g.fillRoundRect(b.x,b.y,b.width,b.height,8,8);
        g.setColor(hov?C_BORDER_H:C_BORDER);g.setStroke(new BasicStroke(hov?2f:1f));
        g.drawRoundRect(b.x,b.y,b.width,b.height,8,8);
        g.setFont(F_KEY);g.setColor(C_TEXT);g.drawString("← BACK",b.x+12,b.y+21);
    }

    private void drawResetBtn(Graphics2D g,int bottomY) {
        Rectangle b=resetBtnBounds();
        boolean hov=b.contains(mouseX,mouseY);
        g.setColor(hov?new Color(80,20,20,200):new Color(40,10,10,180));
        g.fillRoundRect(b.x,b.y,b.width,b.height,8,8);
        g.setColor(hov?C_RED:new Color(140,40,40,180));g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(b.x,b.y,b.width,b.height,8,8);
        g.setFont(F_KEY);g.setColor(C_RED);g.drawString("RESET ALL",b.x+10,b.y+21);
    }
}