import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GamePanel extends JPanel implements ActionListener {

    static final int SCREEN_W            = 960;
    static final int SCREEN_H            = 640;
    static final int FRAME_TIME          = 1000 / 60;
    static final int TRANSITION_DURATION = 60;

    // ── Core systems ─────────────────────────────────────────────
    Timer      gameTimer;
    JFrame     ownerFrame = null;  // set by VoidWalker
    DatabaseManager db = null;   // set by VoidWalker after login
    GameState  state     = GameState.MENU;
    GameState  prevState = GameState.MENU;

    Settings       settings;
    SaveData       saveData;
    Player         player;
    Level          currentLevel;
    Camera         camera;
    ParticleSystem particles;
    UIManager      ui;
    MenuSystem     menu;
    InputHandler   input;
    AudioManager   audio;

    int     levelNum        = 1;
    int     transitionTimer = 0;
    boolean enterPressed    = false;

    // ── Object pools ─────────────────────────────────────────────
    ProjectilePool projPool   = new ProjectilePool();
    int            shootCooldown = 0;

    // ── Aim ──────────────────────────────────────────────────────
    float   aimAngle         = 0f;
    int     crosshairPulse   = 0;
    boolean enemyUnderCursor = false;

    // ── Endless mode ─────────────────────────────────────────────
    boolean       endlessMode     = false;
    int           exitFrameCount  = 0;   // prevents instant-exit on spawn  // TRUE = we are in endless, never touches story save
    int           waveNumber      = 0;
    int           waveCooldown    = 0;
    boolean       waitingNextWave = false;
    PowerUpManager powerUps       = null;
    List<PowerUp>  currentOffers  = new ArrayList<>();

    // AI throttle per quality
    int aiThrottle      = 1;
    int aiThrottleFrame = 0;

    // ── Projectile finite range ───────────────────────────────────
    static final int PROJ_MAX_RANGE = 420;  // pixels — projectile deactivates beyond this

    // ── Cached Colors / Strokes / Fonts (zero per-frame alloc) ───
    private static final Color C_RED     = new Color(220,  50,  50);
    private static final Color C_BLUE    = new Color(  0, 150, 255);
    private static final Color C_GOLD    = new Color(255, 200,  60);
    private static final Color C_PROJ1   = new Color(100, 180, 255);
    private static final Color C_PROJ2   = new Color(120, 255, 180);
    private static final Color C_PROJ3   = new Color(255, 160,  60);
    private static final Color C_REAR    = new Color(200, 120,  50);
    private static final Color C_CHAIN   = new Color(220, 220,  80);
    private static final Color C_EXPLO   = new Color(255, 140,   0);
    private static final Color C_BOSS1   = new Color(255,  50, 100);
    private static final Color C_BOSS2   = new Color(255, 150,  50);
    private static final Color C_PHIT    = new Color(100, 150, 255);

    // Pre-cached AlphaComposite instances — avoid getInstance() per frame
    private static final AlphaComposite AC_SRC_OVER =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER);
    private static final BasicStroke STR_DASH = new BasicStroke(1.4f);
    private static final BasicStroke STR_RING = new BasicStroke(1.6f);
    private static final BasicStroke STR_TICK = new BasicStroke(1.8f);
    private static final BasicStroke STR_TRAJ = new BasicStroke(2.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STR_DEF  = new BasicStroke(1f);

    private static final Font F_MD  = new Font("Monospaced", Font.BOLD,  14);
    private static final Font F_LG  = new Font("Monospaced", Font.BOLD,  16);
    private static final Font F_SM  = new Font("Monospaced", Font.PLAIN, 11);

    // ── Spatial grid for collision (avoids O(n²) enemy checks) ───
    private static final int GRID_CELL = 128;
    private final java.util.HashMap<Long, java.util.List<Enemy>> spatialGrid = new java.util.HashMap<>();

    // ── Vignette cache ────────────────────────────────────────────
    // vignetteImage removed — replaced by ShadowRenderer

    // ── Cursor management ─────────────────────────────────────────
    private Cursor invisibleCursor;
    private Cursor defaultCursor;
    private boolean cursorHidden = false;

    // ─────────────────────────────────────────────────────────────
    GamePanel() {
        setPreferredSize(new Dimension(SCREEN_W, SCREEN_H));
        setBackground(Color.BLACK);
        setFocusable(true);
        setDoubleBuffered(true);  // ensures Swing uses offscreen buffer

        settings  = Settings.load();
        saveData  = SaveData.load();
        camera    = new Camera();
        particles = new ParticleSystem();
        ui        = new UIManager();
        menu      = new MenuSystem();
        audio     = new AudioManager();
        input     = new InputHandler(settings);

        applyQualitySettings();

        addKeyListener(input);
        addMouseListener(input);
        addMouseMotionListener(input);

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.VK_ENTER) enterPressed = true;
                if ((state==GameState.HELP || state==GameState.SETTINGS)
                        && k == KeyEvent.VK_ESCAPE) { audio.menuClick(); state = GameState.MENU; }
                if ((state==GameState.PLAYING || state==GameState.ENDLESS)
                        && k == settings.pauseKey) { prevState = state; state = GameState.PAUSED; }
                if (state==GameState.PAUSED && k == settings.pauseKey)
                    state = (prevState == GameState.ENDLESS ? GameState.ENDLESS : GameState.PLAYING);
                if ((state==GameState.PLAYING || state==GameState.ENDLESS)
                        && k == settings.interactKey && player != null)
                    player.activateAbility();
            }
        });

        gameTimer = new Timer(FRAME_TIME, this);

        // Prepare both cursors
        BufferedImage blank = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        invisibleCursor = Toolkit.getDefaultToolkit().createCustomCursor(blank, new Point(0,0), "blank");
        defaultCursor   = Cursor.getDefaultCursor();
        // Start with default cursor on menu
        setCursor(defaultCursor);
    }

    // ── Cursor control ───────────────────────────────────────────
    void showCursor() {
        if (cursorHidden) { setCursor(defaultCursor); cursorHidden = false; }
    }
    void hideCursor() {
        if (!cursorHidden) { setCursor(invisibleCursor); cursorHidden = true; }
    }

    void applyQualitySettings() {
        particles.applyQuality(settings.quality);
        aiThrottle = switch (settings.quality) {
            case LOW    -> 3;
            case MEDIUM -> 2;
            case HIGH   -> 1;
        };
    }

    void startGame() { gameTimer.start(); }

    // ── Story mode ───────────────────────────────────────────────
    void newGame() {
        endlessMode = false;
        levelNum = 1;
        startLevel();
        audio.playLevelMusic();
    }

    void loadGame() {
        endlessMode = false;
        levelNum = saveData.level;
        startLevel();
        if (player != null) player.score = saveData.score;
        audio.playLevelMusic();
    }

    void startLevel() {
        TextureFactory.preload(Level.TILE);
        Level.loadBackground();
        currentLevel  = new Level(levelNum, saveData);
        player        = new Player(currentLevel.spawnX, currentLevel.spawnY);
        camera        = new Camera();
        camera.x      = player.x - SCREEN_W / 2f;
        camera.y      = player.y - SCREEN_H / 2f;
        particles     = new ParticleSystem(); particles.applyQuality(settings.quality);
        projPool.clear(); shootCooldown = 0;
        spatialGrid.clear();
        exitFrameCount = 0;
    }

    void nextLevel() {
        // Guard: nextLevel must NEVER be called in endless mode
        if (endlessMode) return;

        int sc = player != null ? player.score : 0;
        int kc = player != null ? player.kills : 0;
        int pl = player != null ? player.level : 1;
        levelNum++;

        if (levelNum > 10) {
            state = GameState.WIN;
            saveData.highScore = Math.max(saveData.highScore, sc);
            saveData.level = 1; saveData.save(); return;
        }
        saveData.level = levelNum; saveData.score = sc;
        saveData.totalKills += kc; saveData.save();

        currentLevel = new Level(levelNum, saveData);
        player       = new Player(currentLevel.spawnX, currentLevel.spawnY);
        player.score = sc; player.kills = kc;
        for (int i = 1; i < pl; i++) player.levelUp();
        camera = new Camera();
        camera.x = player.x - SCREEN_W / 2f;
        camera.y = player.y - SCREEN_H / 2f;
        particles = new ParticleSystem(); particles.applyQuality(settings.quality);
        projPool.clear(); shootCooldown = 0;
        spatialGrid.clear();
        exitFrameCount = 0;
        state = GameState.TRANSITION; transitionTimer = TRANSITION_DURATION;
    }

    // ── Endless mode ─────────────────────────────────────────────
    void startEndlessMode() {
        endlessMode = true;           // ← key flag: no story saves touched
        TextureFactory.preload(Level.TILE);
        Level.loadBackground();
        currentLevel    = new Level(true);
        player          = new Player(currentLevel.spawnX, currentLevel.spawnY);
        powerUps        = new PowerUpManager(player);
        player.mods     = powerUps;
        camera          = new Camera();
        camera.x        = player.x - SCREEN_W / 2f;
        camera.y        = player.y - SCREEN_H / 2f;
        particles       = new ParticleSystem(); particles.applyQuality(settings.quality);
        projPool.clear(); shootCooldown = 0;
        spatialGrid.clear();
        waveNumber      = 0;
        waitingNextWave = true;
        waveCooldown    = 180;
        state           = GameState.ENDLESS;
        audio.playLevelMusic();
    }

    void spawnWave() {
        waveNumber++;
        currentLevel.enemies.clear();
        projPool.clear();   // clear stale enemy projectiles between waves

        float hpScale = 1f + waveNumber * 0.15f;
        float spScale = 1f + waveNumber * 0.04f;
        int baseCount = 3 + waveNumber * 2;

        float cx = currentLevel.widthPx()  / 2f;
        float cy = currentLevel.heightPx() / 2f;
        float arenaR = (currentLevel.widthPx() / 2f) - 80;

        boolean isBoss     = (waveNumber % 10 == 0);
        boolean isMiniBoss = (!isBoss && waveNumber % 5 == 0);
        int count = isBoss ? 1 : (isMiniBoss ? baseCount/2 + 1 : baseCount);
        // Cap count so the game stays playable at very high waves
        count = Math.min(count, 30);

        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            float ex = cx + (float)(Math.cos(angle)*arenaR*0.7f) + (float)(Math.random()*60-30);
            float ey = cy + (float)(Math.sin(angle)*arenaR*0.7f) + (float)(Math.random()*60-30);

            EnemyType et;
            if (isBoss)                       et = EnemyType.BOSS;
            else if (isMiniBoss && i == 0)    et = EnemyType.BOSS;
            else                              et = Math.random() < 0.35 ? EnemyType.RANGER : EnemyType.GRUNT;

            Enemy en = new Enemy(ex, ey, et);
            en.hp    = (int)(en.maxHp * hpScale);
            en.maxHp = en.hp;
            en.speed *= spScale;
            if (powerUps != null) en.speed *= powerUps.enemySlowFactor;
            currentLevel.enemies.add(en);
        }
        waitingNextWave = false;
    }

    // ── Spatial grid helpers (fast enemy lookups) ────────────────
    long cellKey(int cx, int cy) { return ((long)cx << 32) | (cy & 0xFFFFFFFFL); }

    void rebuildSpatialGrid() {
        spatialGrid.clear();
        for (Enemy en : currentLevel.enemies) {
            if (!en.alive) continue;
            int cx = (int)(en.x / GRID_CELL);
            int cy = (int)(en.y / GRID_CELL);
            spatialGrid.computeIfAbsent(cellKey(cx, cy), k -> new ArrayList<>()).add(en);
        }
    }

    /** Return enemies near (px,py) within radius — uses spatial grid. */
    List<Enemy> nearbyEnemies(float px, float py, float radius) {
        List<Enemy> result = new ArrayList<>();
        int r  = (int)(radius / GRID_CELL) + 1;
        int cx = (int)(px / GRID_CELL);
        int cy = (int)(py / GRID_CELL);
        for (int dy = -r; dy <= r; dy++)
            for (int dx = -r; dx <= r; dx++) {
                List<Enemy> cell = spatialGrid.get(cellKey(cx+dx, cy+dy));
                if (cell != null) result.addAll(cell);
            }
        return result;
    }

    // ── Aim helpers ──────────────────────────────────────────────
    float getAimAngle() {
        if (player == null) return 0f;
        float wmx = input.mouseX + camera.getX();
        float wmy = input.mouseY + camera.getY();
        return (float)Math.atan2(wmy - player.y, wmx - player.x);
    }
    float worldMouseX() { return input.mouseX + camera.getX(); }
    float worldMouseY() { return input.mouseY + camera.getY(); }

    void spawnProjectile(float angle, int damage, Color color) {
        if (player == null) return;
        boolean pierc = powerUps != null && powerUps.piercing;
        boolean expl  = powerUps != null && powerUps.explosive;
        int     sizeB = powerUps != null ? powerUps.projectileSizeBonus : 0;
        float   spdM  = powerUps != null ? powerUps.bulletSpeedMulti    : 1f;
        float   dmgM  = powerUps != null ? powerUps.damageMulti         : 1f;
        int     dmg   = (int)(damage * dmgM);
        if (powerUps != null && powerUps.critChance > 0 && Math.random() < powerUps.critChance)
            dmg *= 2;
        float tx = player.x + (float)Math.cos(angle) * 300;
        float ty = player.y + (float)Math.sin(angle) * 300;
        projPool.acquire(player.x, player.y, tx, ty, dmg, true, color,
                spdM, pierc, expl, sizeB);
    }

    // ── Main loop ────────────────────────────────────────────────
    @Override public void actionPerformed(ActionEvent e) { update(); repaint(); }

    void update() {
        // Cursor management based on state
        switch (state) {
            case MENU, HELP, SETTINGS, UPGRADE_SELECT, GAME_OVER, WIN, PAUSED -> showCursor();
            case PLAYING, ENDLESS -> hideCursor();
            default -> {}
        }

        switch (state) {
            case MENU           -> updateMenu();
            case PLAYING        -> updatePlaying();
            case ENDLESS        -> updateEndless();
            case UPGRADE_SELECT -> updateUpgradeSelect();
            case PAUSED         -> updatePaused();
            case GAME_OVER, WIN -> updateEndScreen();
            case TRANSITION     -> updateTransition();
            case HELP, SETTINGS -> {}
        }
        if (enterPressed) enterPressed = false;
    }

    void updateMenu() {
        // Make sure menu music plays
        if (audio.currentLoop != audio.menuMusicClip
                || (audio.menuMusicClip != null && !audio.menuMusicClip.isRunning())) {
            audio.playMenuMusic();
        }
        if (!input.mouseClicked) return;
        int cx = input.clickX, cy = input.clickY; input.consumeClick();
        List<MenuSystem.Button> btns = menu.mainButtons;
        if      (btns.get(0).clicked(cx,cy)) { audio.menuClick(); newGame();          state = GameState.TRANSITION; transitionTimer = TRANSITION_DURATION; }
        else if (btns.get(1).clicked(cx,cy)) { audio.menuClick(); loadGame();         state = GameState.PLAYING; }
        else if (btns.get(2).clicked(cx,cy)) { audio.menuClick(); startEndlessMode(); }
        else if (btns.get(3).clicked(cx,cy)) { audio.menuClick(); state = GameState.HELP; }
        else if (btns.get(4).clicked(cx,cy)) { audio.menuClick(); prevState = GameState.MENU; state = GameState.SETTINGS; }
        else if (btns.get(5).clicked(cx,cy)) { System.exit(0); }
        else if (btns.get(6).clicked(cx,cy)) {
            audio.menuClick();
            showMultiplayerMenu();
        }
    }

    void updatePaused() {
        if (!input.mouseClicked) return;
        int cx = input.clickX, cy = input.clickY; input.consumeClick();
        List<MenuSystem.Button> btns = menu.pauseButtons;
        GameState resume = (prevState == GameState.ENDLESS) ? GameState.ENDLESS : GameState.PLAYING;
        if      (btns.get(0).clicked(cx,cy)) { audio.menuClick(); state = resume; }
        else if (btns.get(1).clicked(cx,cy)) { audio.menuClick(); prevState = GameState.PAUSED; state = GameState.SETTINGS; }
        else if (btns.get(2).clicked(cx,cy)) { audio.menuClick(); endlessMode = false; state = GameState.MENU; audio.playMenuMusic(); }
        else if (btns.get(3).clicked(cx,cy)) { System.exit(0); }
    }

    void updateEndScreen() {
        if (player != null) {
            saveData.highScore = Math.max(saveData.highScore, player.score);
        }

        // ── ADD THIS BLOCK: save score to database ────────────────
        if (db != null && db.isConnected() && player != null
                && VoidWalker.currentUserId > 0) {
            // Update online high score
            db.updateHighScore(VoidWalker.currentUserId, player.score);
            System.out.println("[DB] Saved score " + player.score +
                    " for " + VoidWalker.currentUsername);
        }
        // ── END BLOCK ─────────────────────────────────────────────

        boolean clicked = input.mouseClicked;
        int cx = input.clickX, cy = input.clickY;

        // Play Again button: SCREEN_W/2+20 to +220, SCREEN_H/2+95 to +137
        boolean playAgain = clicked &&
                cx >= SCREEN_W/2+20 && cx <= SCREEN_W/2+220 &&
                cy >= SCREEN_H/2+95 && cy <= SCREEN_H/2+137;
        // Main Menu button: SCREEN_W/2-220 to -20
        boolean mainMenu  = clicked &&
                cx >= SCREEN_W/2-220 && cx <= SCREEN_W/2-20 &&
                cy >= SCREEN_H/2+95  && cy <= SCREEN_H/2+137;

        if (enterPressed || mainMenu) {
            input.consumeClick();
            saveData.save();
            endlessMode = false;
            state = GameState.MENU;
            audio.playMenuMusic();
            enterPressed = false;
        } else if (playAgain && state == GameState.GAME_OVER) {
            input.consumeClick();
            saveData.save();
            if (endlessMode) {
                startEndlessMode();
            } else {
                newGame();
                state = GameState.TRANSITION;
                transitionTimer = TRANSITION_DURATION;
            }
        }
    }

    void updateTransition() { if (--transitionTimer <= 0) state = GameState.PLAYING; }

    // ── Endless tick ─────────────────────────────────────────────
    void updateEndless() {
        if (player == null || !player.alive) {
            saveData.highScore = Math.max(saveData.highScore, player != null ? player.score : 0);
            // Do NOT touch saveData.level — that belongs to story mode
            saveData.save();
            state = GameState.GAME_OVER; return;
        }
        if (waitingNextWave) {
            if (--waveCooldown <= 0) spawnWave();
        } else if (currentLevel.allEnemiesDead()) {
            waitingNextWave = true;
            waveCooldown    = 60;
            currentOffers   = powerUps.generateOffers();
            state           = GameState.UPGRADE_SELECT;
            return;
        }
        powerUps.update();
        tickGameplay(GameState.ENDLESS);
    }

    // ── Upgrade select tick ──────────────────────────────────────
    void updateUpgradeSelect() {
        powerUps.setHover(input.mouseX, input.mouseY);
        if (input.mouseClicked) {
            boolean selected = powerUps.trySelect(input.clickX, input.clickY);
            input.consumeClick();
            if (selected) { audio.pickup(); state = GameState.ENDLESS; }
        }
    }

    // ── Story tick ───────────────────────────────────────────────
    void updatePlaying() {
        if (player == null || !player.alive) {
            saveData.highScore = Math.max(saveData.highScore, player != null ? player.score : 0);
            saveData.save(); state = GameState.GAME_OVER; return;
        }
        tickGameplay(GameState.PLAYING);
    }

    // ── Shared gameplay tick ─────────────────────────────────────
    void tickGameplay(GameState mode) {
        // Aim
        aimAngle = getAimAngle();
        if (player != null) player.facingRight = (Math.cos(aimAngle) >= 0);

        // Enemy under cursor
        enemyUnderCursor = false;
        float wmx = worldMouseX(), wmy = worldMouseY();

        // Rebuild spatial grid every frame (cheap for <30 enemies)
        rebuildSpatialGrid();

        // Check enemy under cursor using grid
        List<Enemy> nearCursor = nearbyEnemies(wmx, wmy, 80);
        for (Enemy en : nearCursor)
            if (en.alive && en.getBounds().contains(wmx, wmy)) { enemyUnderCursor = true; break; }

        // Player movement
        boolean dashJust = input.isDash() && player.dashCooldown <= 0;
        player.update(input.isUp(), input.isDown(), input.isLeft(), input.isRight(),
                dashJust, currentLevel);
        if (dashJust && player.dashing) {
            audio.dash();
            if (settings.quality != Quality.LOW)
                particles.emit(player.x, player.y, C_BLUE, 6, 6);
        }

        // Shooting
        if (shootCooldown > 0) shootCooldown--;
        if (input.isAttack() && shootCooldown <= 0) {
            float frM     = powerUps != null ? powerUps.fireRateMulti : 1f;
            int cooldown  = Math.max(3, (int)((18 - player.weaponLevel * 3) / frM));
            shootCooldown = cooldown;
            Color pc      = player.weaponLevel >= 3 ? C_PROJ3 : player.weaponLevel >= 2 ? C_PROJ2 : C_PROJ1;
            spawnProjectile(aimAngle, 20, pc);
            int extras = Math.max(powerUps != null ? powerUps.extraProjectiles : 0, player.weaponLevel - 1);
            if (extras >= 1) { spawnProjectile(aimAngle - 0.22f, 15, C_PROJ2); spawnProjectile(aimAngle + 0.22f, 15, C_PROJ2); }
            if (extras >= 2 || player.weaponLevel >= 3) spawnProjectile(aimAngle + (float)Math.PI, 10, C_REAR);
            audio.shoot();
            camera.shake(1.0f + player.weaponLevel * 0.4f);
            if (settings.quality != Quality.LOW)
                particles.emit(player.x, player.y, pc, 2, 4);
        }

        // Projectile updates — pool iteration, no allocation
        for (Projectile p : projPool.all()) {
            if (!p.active) continue;
            p.update();
            if (!p.active) continue;

            // ── Finite range check ───────────────────────────────
            float pdx = p.x - p.spawnX, pdy = p.y - p.spawnY;
            if (pdx*pdx + pdy*pdy > (float)PROJ_MAX_RANGE * PROJ_MAX_RANGE) {
                p.active = false;
                // Small puff at range limit
                if (settings.quality == Quality.HIGH)
                    particles.emit(p.x, p.y, C_PHIT, 3, 3);
                continue;
            }

            if (currentLevel.isWall(p.x, p.y)) {
                p.active = false;
                if (settings.quality != Quality.LOW)
                    particles.emit(p.x, p.y, C_PHIT, 3, 4);
                continue;
            }

            if (p.fromPlayer) {
                // Use spatial grid: only check enemies near the projectile
                List<Enemy> nearby = nearbyEnemies(p.x, p.y, 80);
                handlePlayerProjectile(p, nearby);
            } else {
                if (player.getBounds().intersects(p.getBounds())) {
                    player.takeDamage(p.damage); p.active = false;
                    if (settings.quality != Quality.LOW)
                        particles.emit(player.x, player.y, C_RED, 5, 5);
                    camera.shake(5f);
                }
            }
        }

        // Enemy AI — throttled by quality setting
        aiThrottleFrame++;
        List<Enemy> enemies = currentLevel.enemies;
        int eCount = enemies.size();
        for (int i = 0; i < eCount; i++) {
            Enemy en = enemies.get(i);
            if (!en.alive) continue;
            if (aiThrottle > 1 && (i % aiThrottle) != (aiThrottleFrame % aiThrottle)) continue;

            List<Projectile> eprojs = en.update(player, currentLevel);
            for (Projectile ep : eprojs) {
                Color ec = new Color(ep.r, ep.g, ep.b);
                projPool.acquire(ep.x, ep.y, ep.x + ep.vx * 50, ep.y + ep.vy * 50,
                        ep.damage, false, ec, 1f, false, false, 0);
            }
            // Grunt melee
            if (en.type == EnemyType.GRUNT && en.getBounds().intersects(player.getBounds())) {
                if (player.invincibleTimer <= 0) {
                    player.takeDamage(en.damage);
                    if (settings.quality != Quality.LOW)
                        particles.emit(player.x, player.y, C_RED, 4, 5);
                    camera.shake(6f);
                }
            }
        }

        // Pickups — iterator, no copy
        Iterator<Pickup> pkIt = currentLevel.pickups.iterator();
        while (pkIt.hasNext()) {
            Pickup pk = pkIt.next();
            pk.update();
            if (!pk.collected && pk.getBounds().intersects(player.getBounds())) {
                pk.collected = true;
                switch (pk.type) {
                    case "health" -> {
                        int heal = 25; player.hp = Math.min(player.maxHp, player.hp + heal);
                        ui.addFloatText(pk.x, pk.y, "+" + heal + " HP", C_RED);
                        particles.emit(pk.x, pk.y, C_RED, 5, 8);
                    }
                    case "score" -> {
                        int pts = 100 + levelNum * 10; player.score += pts;
                        ui.addFloatText(pk.x, pk.y, "+" + pts, C_GOLD);
                        particles.emit(pk.x, pk.y, C_GOLD, 5, 8);
                    }
                }
                audio.pickup(); pkIt.remove();
            }
        }

        // Exit check — story mode ONLY, guarded by endlessMode flag
        if (mode == GameState.PLAYING && !endlessMode && !currentLevel.exitReached) {
            // Only allow exit after 5 seconds (300 frames) of gameplay
            // This prevents instant level skip on spawn-near-exit levels
            exitFrameCount++;
            if (exitFrameCount > 300) {
                float edx = player.x - currentLevel.exitX, edy = player.y - currentLevel.exitY;
                if (Math.sqrt(edx*edx + edy*edy) < 48 && currentLevel.allEnemiesDead()) {
                    currentLevel.exitReached = true;
                    ui.addFloatText(SCREEN_W/2f-80, SCREEN_H/2f,
                            "FLOOR CLEARED!", new Color(100,255,160));
                    nextLevel(); return;
                }
            }
        }

        camera.follow(player.x, player.y, currentLevel);
        particles.update();
        ui.update();
    }

    /** Handle player projectile vs enemy — uses pre-filtered nearby list. */
    void handlePlayerProjectile(Projectile p, List<Enemy> nearby) {
        for (Enemy en : nearby) {
            if (!en.alive || !en.getBounds().intersects(p.getBounds())) continue;

            int dmg = p.damage;
            en.takeDamage(dmg, particles);
            audio.hit();

            if (powerUps != null) {
                // Knockback
                if (powerUps.knockback) {
                    float dx = en.x - player.x, dy = en.y - player.y;
                    float d  = (float)Math.sqrt(dx*dx + dy*dy);
                    if (d > 0) { en.x += dx/d*12; en.y += dy/d*12; }
                }
                // Freeze
                if (powerUps.freezeOnHit) en.speed = Math.max(0.2f, en.speed * 0.3f);
                // Chain lightning — use grid for nearest
                if (powerUps.chainLightning) {
                    Enemy nearest = null; float minD = 200;
                    for (Enemy e2 : nearbyEnemies(en.x, en.y, 200)) {
                        if (e2 == en || !e2.alive) continue;
                        float dx2=e2.x-en.x, dy2=e2.y-en.y;
                        float dd=(float)Math.sqrt(dx2*dx2+dy2*dy2);
                        if (dd < minD) { minD = dd; nearest = e2; }
                    }
                    if (nearest != null) {
                        nearest.takeDamage(dmg/2, particles);
                        particles.emit(nearest.x, nearest.y, C_CHAIN, 5, 6);
                    }
                }
                // Explosive AoE — use grid
                if (p.explosive) {
                    for (Enemy e2 : nearbyEnemies(p.x, p.y, 80)) {
                        if (e2 == en || !e2.alive) continue;
                        float dx2=e2.x-p.x, dy2=e2.y-p.y;
                        if (dx2*dx2+dy2*dy2 < 60*60) e2.takeDamage(dmg/2, particles);
                    }
                    int burst = settings.quality == Quality.LOW ? 6 : 12;
                    particles.emit(p.x, p.y, C_EXPLO, burst, 10);
                    camera.shake(5f);
                }
            }

            p.onHit();

            if (!en.alive) {
                player.kills++;
                player.score += en.score;
                player.gainXP(en.score / 5);
                player.onKill(dmg);
                ui.addFloatText(en.x, en.y-20, "+"+en.score, C_GOLD);
                if (Math.random() < 0.3) {
                    currentLevel.pickups.add(
                            new Pickup(en.x, en.y, Math.random() < 0.5 ? "health" : "score"));
                }
                camera.shake(en.type == EnemyType.BOSS ? 12f : 4f);
                audio.death();
                if (en.type == EnemyType.BOSS) {
                    int bc = settings.quality == Quality.LOW ? 3 : 6;
                    for (int i = 0; i < bc; i++) {
                        float bx = en.x+(float)(Math.random()*60-30);
                        float by = en.y+(float)(Math.random()*60-30);
                        particles.emit(bx, by, C_BOSS1, 10, 10);
                        particles.emit(bx, by, C_BOSS2,  8,  8);
                    }
                }
            }
            if (!p.active) break;
        }
    }

    // ── Rendering ────────────────────────────────────────────────
    @Override protected void paintComponent(Graphics g2) {
        super.paintComponent(g2);
        Graphics2D g = (Graphics2D) g2;
        g.setRenderingHint(RenderingHints.KEY_RENDERING,    RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        int pw = getWidth(), ph = getHeight();
        float sx = (float)pw / SCREEN_W;
        float sy = (float)ph / SCREEN_H;
        float sc = Math.min(sx, sy);
        int drawW = (int)(SCREEN_W * sc);
        int drawH = (int)(SCREEN_H * sc);
        int ox    = (pw - drawW) / 2;
        int oy    = (ph - drawH) / 2;

        // Black letterbox bars
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, pw, ph);

        // Apply transform so game renders in 960x640 logical space
        g.translate(ox, oy);
        if (sc != 1f) g.scale(sc, sc);

        // Update input coordinate mapping
        input.setScale(sc, ox, oy);

        // Render game content
        renderFrame(g);
    }

    /** All rendering — called with a 960x640 logical Graphics2D context. */
    public void paintGame(Graphics2D g) { renderFrame(g); }

    private void renderFrame(Graphics2D g) {
        switch (state) {
            case MENU           -> menu.drawMain(g, input.mouseX, input.mouseY, saveData);
            case PLAYING        -> drawGame(g);
            case ENDLESS        -> drawEndless(g);
            case UPGRADE_SELECT -> { drawEndless(g); powerUps.drawUpgradeSelect(g); }
            case PAUSED -> {
                if (prevState == GameState.ENDLESS) drawEndless(g); else drawGame(g);
                menu.drawPause(g, input.mouseX, input.mouseY);
            }
            case GAME_OVER -> {
                if (endlessMode) drawEndless(g); else drawGame(g);
                menu.drawGameOver(g, player, endlessMode ? waveNumber : levelNum, saveData);
            }
            case WIN            -> { drawGame(g); menu.drawWin(g, player, levelNum, saveData); }
            case HELP           -> menu.drawHelp(g);
            case SETTINGS       -> menu.drawSettings(g, settings, input.mouseX, input.mouseY);
            case TRANSITION     -> {
                if (currentLevel != null) drawGame(g);
                else { g.setColor(Color.BLACK); g.fillRect(0,0,SCREEN_W,SCREEN_H); }
                menu.drawTransition(g, transitionTimer, levelNum);
                handleSettingsClick();
            }
        }
        if (state == GameState.SETTINGS) handleSettingsClick();
    }

    void drawGame(Graphics2D g)    { drawGameBase(g, false); }
    void drawEndless(Graphics2D g) { drawGameBase(g, true); }

    void drawGameBase(Graphics2D g, boolean endless) {
        if (currentLevel == null) return;
        int cx = camera.getX(), cy = camera.getY();
        currentLevel.draw(g, cx, cy, settings);

        g.translate(-cx, -cy);
        currentLevel.pickups.forEach(pk -> pk.draw(g));
        particles.draw(g);

        // Viewport culling for enemies
        int vx1=cx-64, vx2=cx+SCREEN_W+64, vy1=cy-64, vy2=cy+SCREEN_H+64;
        for (Enemy en : currentLevel.enemies) {
            if (!en.alive) continue;
            if (en.x<vx1||en.x>vx2||en.y<vy1||en.y>vy2) continue;
            en.draw(g, settings);
        }

        // Draw active projectiles (pool)
        for (Projectile p : projPool.all()) {
            if (!p.active) continue;
            // Skip if off-screen
            if (p.x-cx<-20||p.x-cx>SCREEN_W+20||p.y-cy<-20||p.y-cy>SCREEN_H+20) continue;
            p.draw(g, settings);
        }

        if (player != null) player.draw(g, settings);
        g.translate(cx, cy);

        // ── Unified lighting pass (circular, no square artefacts) ──
        if (player != null && settings.quality != Quality.LOW) {
            int spx = (int)(player.x - cx);
            int spy = (int)(player.y - cy);
            ShadowRenderer.drawLightingPass(g, spx, spy,
                    currentLevel.enemies, cx, cy, settings.quality);
        }

        // Aim indicator (only when actually playing)
        if (state == GameState.PLAYING || state == GameState.ENDLESS) drawAimIndicator(g);
        if (player != null) ui.drawHUD(g, player, currentLevel, endless ? -1 : levelNum);
        if (endless) drawEndlessHUD(g);
        if (powerUps != null) powerUps.drawActiveHUD(g);

        // Vignette removed — ShadowRenderer darkness covers edge darkening
    }

    void drawEndlessHUD(Graphics2D g) {
        g.setColor(new Color(0,0,0,160));
        g.fillRoundRect(SCREEN_W/2-110, 10, 220, 38, 10, 10);
        g.setColor(new Color(200,180,255)); g.setFont(F_MD);
        String wt = "WAVE  " + waveNumber;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(wt, SCREEN_W/2 - fm.stringWidth(wt)/2, 26);
        int alive = (int)currentLevel.enemies.stream().filter(e->e.alive).count();
        g.setFont(F_SM); g.setColor(new Color(200,150,150));
        String et = alive + " enemies remaining";
        g.drawString(et, SCREEN_W/2 - g.getFontMetrics().stringWidth(et)/2, 42);
        if (waitingNextWave && waveCooldown > 0) {
            g.setColor(new Color(150,255,200)); g.setFont(F_LG);
            String s = "NEXT WAVE IN " + (waveCooldown/60+1) + "s";
            g.drawString(s, SCREEN_W/2 - g.getFontMetrics().stringWidth(s)/2, SCREEN_H/2);
        }
    }

    // ── Aim indicator ────────────────────────────────────────────
    void drawAimIndicator(Graphics2D g) {
        if (player == null) return;
        crosshairPulse++;
        int spx = (int)(player.x - camera.getX());
        int spy = (int)(player.y - camera.getY());
        int msx = input.mouseX, msy = input.mouseY;

        Color lc = player.weaponLevel>=3 ? C_PROJ3 : player.weaponLevel>=2 ? C_PROJ2 : C_PROJ1;
        if (enemyUnderCursor) lc = C_RED;
        int lr=lc.getRed(), lg=lc.getGreen(), lb=lc.getBlue();

        // Dashed aim line
        float dx=msx-spx, dy=msy-spy;
        float dist=(float)Math.sqrt(dx*dx+dy*dy);
        // Clamp line to finite range
        float maxLinePx = PROJ_MAX_RANGE;
        float lineDist  = Math.min(dist, maxLinePx);
        if (lineDist > 4) {
            float nx=dx/dist, ny=dy/dist;
            int dLen=6, gLen=5, segs=(int)(Math.min(lineDist,160)/(dLen+gLen));
            for (int i=0;i<segs;i++) {
                float t0=18f+i*(dLen+gLen), t1=t0+dLen;
                if (t1>lineDist) break;
                float alpha=0.72f-(float)i/segs*0.58f;
                g.setColor(new Color(lr,lg,lb,(int)(alpha*255)));
                g.setStroke(STR_DASH);
                g.drawLine((int)(spx+nx*t0),(int)(spy+ny*t0),(int)(spx+nx*t1),(int)(spy+ny*t1));
            }
            // Range limit marker — small X at max range
            if (dist > maxLinePx) {
                float ex2=spx+nx*maxLinePx, ey2=spy+ny*maxLinePx;
                g.setColor(new Color(lr,lg,lb,100));
                g.setStroke(STR_TICK);
                g.drawLine((int)ex2-4,(int)ey2-4,(int)ex2+4,(int)ey2+4);
                g.drawLine((int)ex2+4,(int)ey2-4,(int)ex2-4,(int)ey2+4);
            }
        }

        // Crosshair
        float pulse=(float)(Math.sin(crosshairPulse*0.15)*0.15+1.0);
        int cr=(int)(10*pulse);
        if (settings.quality==Quality.HIGH){g.setColor(new Color(lr,lg,lb,35));g.fillOval(msx-cr*2,msy-cr*2,cr*4,cr*4);}
        g.setColor(new Color(lr,lg,lb,210)); g.setStroke(STR_RING);
        g.drawOval(msx-cr,msy-cr,cr*2,cr*2);
        int gap=3,tl=5;
        g.setStroke(STR_TICK);
        g.drawLine(msx,msy-cr-gap,msx,msy-cr-gap-tl);
        g.drawLine(msx,msy+cr+gap,msx,msy+cr+gap+tl);
        g.drawLine(msx-cr-gap,msy,msx-cr-gap-tl,msy);
        g.drawLine(msx+cr+gap,msy,msx+cr+gap+tl,msy);
        g.setColor(new Color(255,255,255,190)); g.fillOval(msx-2,msy-2,4,4);

        // Trajectory preview (80px)
        float tx0=spx+(float)Math.cos(aimAngle)*22, ty0=spy+(float)Math.sin(aimAngle)*22;
        float tx1=spx+(float)Math.cos(aimAngle)*80, ty1=spy+(float)Math.sin(aimAngle)*80;
        g.setColor(new Color(lr,lg,lb,55)); g.setStroke(STR_TRAJ);
        g.drawLine((int)tx0,(int)ty0,(int)tx1,(int)ty1);
        g.setStroke(STR_DEF);
    }

    // ── Multiplayer Menu ─────────────────────────────────────────
    void showMultiplayerMenu() {
        JFrame frame = ownerFrame != null ? ownerFrame
                : (JFrame) javax.swing.SwingUtilities.getWindowAncestor(this);
        if (frame == null) return;

        MultiplayerMenu mp = new MultiplayerMenu(db, frame, () -> {
            newGame();
            state = GameState.TRANSITION;
            transitionTimer = TRANSITION_DURATION;
        });

        frame.getContentPane().removeAll();
        frame.getContentPane().add(mp);
        frame.revalidate();
        frame.repaint();
        mp.requestFocusInWindow();
    }

    // ── Settings click handler ────────────────────────────────────
    void handleSettingsClick() {
        if (!input.mouseClicked) return;
        int cx=input.clickX, cy=input.clickY;
        Quality[] qs={Quality.LOW,Quality.MEDIUM,Quality.HIGH};
        for (int i=0;i<3;i++) {
            if (cx>=60+i*120&&cx<=160+i*120&&cy>=120&&cy<=150) {
                settings.quality=qs[i]; settings.save();
                applyQualitySettings(); ShadowRenderer.invalidateCache();
                audio.menuClick(); input.consumeClick(); return;
            }
        }
        int dy=220;
        if (cx>=60&&cx<=190&&cy>=dy-20&&cy<=dy+10&&settings.fullScreen)  { settings.fullScreen=false; settings.save(); applyDisplayMode(); audio.menuClick(); input.consumeClick(); return; }
        if (cx>=220&&cx<=350&&cy>=dy-20&&cy<=dy+10&&!settings.fullScreen) { settings.fullScreen=true;  settings.save(); applyDisplayMode(); audio.menuClick(); input.consumeClick(); return; }
        if (cy > SCREEN_H-80) {
            state = (prevState == GameState.PAUSED) ? GameState.PAUSED : GameState.MENU;
            input.consumeClick();
        }
    }

    void applyDisplayMode() {
        JFrame frame = ownerFrame != null ? ownerFrame
                : (JFrame) SwingUtilities.getWindowAncestor(this);
        if (frame == null) return;

        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        if (settings.fullScreen) {
            frame.dispose();
            frame.setUndecorated(true);
            frame.setVisible(true);
            gd.setFullScreenWindow(frame);
        } else {
            gd.setFullScreenWindow(null);
            frame.dispose();
            frame.setUndecorated(false);
            frame.setResizable(false);
            setPreferredSize(new Dimension(SCREEN_W, SCREEN_H));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }
        ShadowRenderer.invalidateCache();
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    // getVignetteImage removed — ShadowRenderer handles edge darkening
}