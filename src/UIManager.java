import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * UIManager — HUD rendering with fully cached fonts, colors, strokes.
 * Zero per-frame allocation except FloatText color (alpha varies).
 */
public class UIManager {

    static final int SCREEN_W = 960;
    static final int SCREEN_H = 640;

    // ── Cached resources — created ONCE ──────────────────────────
    private static final Font   F_BOLD_20  = new Font("Monospaced", Font.BOLD,  20);
    private static final Font   F_BOLD_14  = new Font("Monospaced", Font.BOLD,  14);
    private static final Font   F_BOLD_13  = new Font("Monospaced", Font.BOLD,  13);
    private static final Font   F_BOLD_12  = new Font("Monospaced", Font.BOLD,  12);
    private static final Font   F_BOLD_11  = new Font("Monospaced", Font.BOLD,  11);
    private static final Font   F_BOLD_10  = new Font("Monospaced", Font.BOLD,  10);
    private static final Font   F_PLAIN_11 = new Font("Monospaced", Font.PLAIN, 11);

    private static final Color  C_PANEL_BG   = new Color(0,   0,   0, 180);
    private static final Color  C_PANEL_BDR  = new Color(60,  60, 100);
    private static final Color  C_SCORE_LBL  = new Color(255, 200,  0);
    private static final Color  C_FLOOR_LBL  = new Color(150, 200, 255);
    private static final Color  C_ENEMY_CNT  = new Color(200, 150, 150);
    private static final Color  C_HP_FG      = new Color(200,  50,  50);
    private static final Color  C_HP_BG      = new Color( 40,  10,  10);
    private static final Color  C_SH_FG      = new Color( 60, 120, 255);
    private static final Color  C_SH_BG      = new Color( 10,  20,  50);
    private static final Color  C_XP_FG      = new Color(200, 150,  50);
    private static final Color  C_XP_BG      = new Color( 30,  20,   5);
    private static final Color  C_AB_FG      = new Color(100,  80, 255);
    private static final Color  C_AB_BG      = new Color( 20,  10,  50);
    private static final Color  C_DASH_FG    = new Color(  0, 200, 255);
    private static final Color  C_DASH_BG    = new Color(  0,  30,  50);
    private static final Color  C_WPN        = new Color(255, 180,  80);
    private static final Color  C_AB_RDY     = new Color(200, 180, 255);
    private static final Color  C_AB_NOT     = new Color(100,  80, 180);
    private static final Color  C_SCR_BG     = new Color(  0,   0,   0, 160);
    private static final BasicStroke STR_HUD = new BasicStroke(1.5f);

    // ── Floating text ─────────────────────────────────────────────
    static class FloatText {
        float x, y, vy;
        String text;
        Color color;
        int life = 60;

        FloatText(float x, float y, String t, Color c) {
            this.x=x; this.y=y; text=t; color=c; vy=-1.5f;
        }

        void update() { y+=vy; vy*=0.98f; life--; }

        void draw(Graphics2D g) {
            float a = Math.min(1f, life/30f);
            // Alpha varies — must create new Color here (intentional)
            g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),(int)(255*a)));
            g.setFont(F_BOLD_14);
            g.drawString(text,(int)x,(int)y);
        }

        boolean dead() { return life<=0; }
    }

    List<FloatText> floatTexts = new ArrayList<>();

    void addFloatText(float x,float y,String text,Color c){
        floatTexts.add(new FloatText(x,y,text,c));
    }

    void update(){
        floatTexts.removeIf(FloatText::dead);
        floatTexts.forEach(FloatText::update);
    }

    void drawHUD(Graphics2D g, Player player, Level level, int levelNum){
        // Bottom-left panel
        g.setColor(C_PANEL_BG);
        g.fillRoundRect(10,SCREEN_H-80,280,70,12,12);
        g.setColor(C_PANEL_BDR);
        g.setStroke(STR_HUD);
        g.drawRoundRect(10,SCREEN_H-80,280,70,12,12);

        drawBar(g,20,SCREEN_H-68,200,14,player.hp,      player.maxHp,        C_HP_FG,C_HP_BG,"HP");
        if(player.shield>0||player.shieldActive)
            drawBar(g,20,SCREEN_H-50,200,10,player.shield,player.maxShield,   C_SH_FG,C_SH_BG,"");
        drawBar(g,20,SCREEN_H-36,200,8, player.xp,      player.xpToNext,     C_XP_FG,C_XP_BG,"");

        g.setFont(F_BOLD_12); g.setColor(Color.WHITE);
        g.drawString("LV "+player.level, 228, SCREEN_H-56);
        g.drawString("KO "+player.kills, 228, SCREEN_H-40);

        // Score (top-right)
        g.setColor(C_SCR_BG);
        g.fillRoundRect(SCREEN_W-180,10,170,60,10,10);
        g.setColor(C_SCORE_LBL); g.setFont(F_BOLD_14);
        g.drawString("SCORE",SCREEN_W-170,32);
        g.setFont(F_BOLD_20);
        g.drawString(String.format("%06d",player.score),SCREEN_W-170,58);

        // Floor (top-left) — skip in endless (levelNum == -1)
        g.setColor(C_SCR_BG);
        g.fillRoundRect(10,10,150,40,10,10);
        g.setColor(C_FLOOR_LBL); g.setFont(F_BOLD_13);
        g.drawString(levelNum<0?"ENDLESS":"FLOOR "+levelNum, 20,28);
        int alive=(int)level.enemies.stream().filter(e->e.alive).count();
        g.setFont(F_PLAIN_11); g.setColor(C_ENEMY_CNT);
        g.drawString("ENEMIES: "+alive, 20,44);

        // Ability charge
        g.setColor(C_SCR_BG);
        g.fillRoundRect(SCREEN_W-180,78,170,28,8,8);
        float cp=(float)player.abilityCharge/player.abilityMaxCharge;
        drawBar(g,SCREEN_W-170,84,150,14,player.abilityCharge,player.abilityMaxCharge,C_AB_FG,C_AB_BG,"");
        g.setFont(F_BOLD_10);
        g.setColor(cp>=1f?C_AB_RDY:C_AB_NOT);
        g.drawString(cp>=1f?"SHIELD READY":"SHIELD "+Math.round(cp*100)+"%",SCREEN_W-168,95);

        // Dash cooldown
        if(player.dashCooldown>0){
            float pct=1f-(float)player.dashCooldown/Player.BASE_DASH_CD;
            g.setColor(C_SCR_BG);
            g.fillRoundRect(10,SCREEN_H-100,90,16,6,6);
            drawBar(g,14,SCREEN_H-99,82,14,(int)(pct*100),100,C_DASH_FG,C_DASH_BG,"DASH");
        }

        // Float texts
        floatTexts.forEach(ft->ft.draw(g));

        // Weapon level
        g.setColor(C_SCR_BG);
        g.fillRoundRect(300,SCREEN_H-40,110,30,8,8);
        g.setFont(F_BOLD_11); g.setColor(C_WPN);
        g.drawString("WPN LV "+player.weaponLevel,310,SCREEN_H-20);
    }

    void drawBar(Graphics2D g,int x,int y,int w,int h,int cur,int max,Color fg,Color bg,String label){
        g.setColor(bg); g.fillRoundRect(x,y,w,h,h,h);
        float pct=max>0?(float)cur/max:0;
        if(pct>0){g.setColor(fg);g.fillRoundRect(x,y,(int)(w*pct),h,h,h);}
        if(!label.isEmpty()){
            g.setColor(Color.WHITE); g.setFont(F_BOLD_10);
            g.drawString(label,x+4,y+h-2);
        }
    }
}
