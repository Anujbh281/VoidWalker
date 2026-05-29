import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CoopGameServer — Fully authoritative co-op server.
 *
 * RESPONSIBILITIES:
 *   - Enemy spawning and AI (targets nearest alive player)
 *   - Damage calculation (bullet↔enemy, enemy↔player)
 *   - Collision detection (wall, bullet range)
 *   - Pickup spawning and collection
 *   - Level loading and world state
 *   - Broadcasting state snapshots at 20 Hz
 *
 * CLIENTS only send INPUT packets. Server decides everything else.
 *
 * ENEMY SPAWNING FIX:
 *   Enemies are spawned server-side in spawnWave(). The STATE packet
 *   includes all EnemyState[] so clients render from server data only.
 *   Clients NEVER spawn enemies locally in multiplayer.
 */
public class CoopGameServer {

    public static final int    PORT      = 55555;
    public static final int    MAX_PLAYERS = 4;
    public static final int    TICK_HZ   = 20;
    public static final long   TICK_MS   = 1000L / TICK_HZ;

    private ServerSocket  serverSocket;
    private final ConcurrentHashMap<Integer, CoopClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicInteger nextClientId  = new AtomicInteger(1);
    private final AtomicInteger nextEnemyId   = new AtomicInteger(1);
    private final AtomicInteger nextBulletId  = new AtomicInteger(1);
    private final AtomicInteger nextPickupId  = new AtomicInteger(1);

    private volatile boolean running     = false;
    private volatile boolean gameStarted = false;
    private String           gameMode    = "story";
    private int              levelNum    = 1;

    // ── Server-side world state ───────────────────────────────────
    private final ConcurrentHashMap<Integer, GamePacket.PlayerState> playerStates
            = new ConcurrentHashMap<>();
    private final List<ServerEnemy>          serverEnemies = Collections.synchronizedList(new ArrayList<>());
    private final List<GamePacket.BulletState> serverBullets = Collections.synchronizedList(new ArrayList<>());
    private final List<GamePacket.PickupState> serverPickups = Collections.synchronizedList(new ArrayList<>());

    private Level        serverLevel    = null;
    private int          waveNumber     = 0;
    private boolean      waitingForWave = true;
    private int          waveCooldown   = 120; // ticks
    private boolean      isEndless      = false;

    // ── DatabaseManager ───────────────────────────────────────────
    private DatabaseManager db;

    // ═════════════════════════════════════════════════════════════
    // Server Enemy — full AI-capable enemy living on the server
    // ═════════════════════════════════════════════════════════════
    static class ServerEnemy {
        int     id;
        float   x, y;
        int     hp, maxHp;
        boolean alive       = true;
        int     typeOrd     = 0;   // 0=GRUNT, 1=RANGER, 2=BOSS
        boolean facingRight = true;
        boolean enraged     = false;
        float   speed;
        int     damage;
        int     score;
        int     targetPlayerId = -1;
        long    lastShootMs = 0;
        float   shootCooldownMs = 1800f;

        // State for simple AI
        float   vx, vy;

        ServerEnemy(int id, float x, float y, int typeOrd) {
            this.id      = id;
            this.x       = x;
            this.y       = y;
            this.typeOrd = typeOrd;
            switch (typeOrd) {
                case 2 -> { // BOSS
                    maxHp  = 500; speed = 1.8f; damage = 25; score = 500;
                    enraged = false;
                }
                case 1 -> { // RANGER
                    maxHp  = 60;  speed = 1.6f; damage = 12; score = 75;
                }
                default -> { // GRUNT
                    maxHp  = 40;  speed = 2.2f; damage = 10; score = 50;
                }
            }
            this.hp = maxHp;
        }

        void takeDamage(int dmg) {
            hp -= dmg;
            if (hp <= 0) { hp = 0; alive = false; }
            if (typeOrd == 2 && hp < maxHp / 2) enraged = true;
        }

        GamePacket.EnemyState toState() {
            GamePacket.EnemyState s = new GamePacket.EnemyState();
            s.id           = id;
            s.x            = x;  s.y = y;
            s.hp           = hp; s.maxHp = maxHp;
            s.alive        = alive;
            s.typeOrd      = typeOrd;
            s.facingRight  = facingRight;
            s.enraged      = enraged;
            s.targetPlayerId = targetPlayerId;
            return s;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Entry point
    // ═════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        new CoopGameServer().start();
    }

    public void start() {
        db = new DatabaseManager();
        db.connect();

        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            running = true;

            System.out.println("╔════════════════════════════════════╗");
            System.out.println("║  VOIDWALKER CO-OP SERVER v3.0      ║");
            System.out.printf ("║  Port  : %-26d║%n", PORT);
            System.out.printf ("║  MaxPly: %-26d║%n", MAX_PLAYERS);
            System.out.println("╚════════════════════════════════════╝");

            while (running && !serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    sock.setTcpNoDelay(true);
                    sock.setSoTimeout(20000);

                    if (gameStarted) {
                        sendImmediate(sock, GamePacket.error("Game already in progress.")); continue;
                    }
                    if (clients.size() >= MAX_PLAYERS) {
                        sendImmediate(sock, GamePacket.error("Server full.")); continue;
                    }

                    int id = nextClientId.getAndIncrement();
                    CoopClientHandler ch = new CoopClientHandler(sock, id, this);
                    clients.put(id, ch);
                    new Thread(ch, "Client-" + id).start();
                    System.out.println("[Server] Client " + id + " connected from " + sock.getInetAddress());

                } catch (SocketException e) {
                    if (running) System.err.println("[Server] Accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal: " + e.getMessage());
        }
    }

    private void sendImmediate(Socket s, GamePacket p) {
        try {
            ObjectOutputStream o = new ObjectOutputStream(s.getOutputStream());
            o.writeObject(p); o.flush(); s.close();
        } catch (IOException ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // Start game
    // ═════════════════════════════════════════════════════════════
    public synchronized void startGame(String mode) {
        if (gameStarted || clients.isEmpty()) return;
        gameStarted = true;
        gameMode    = mode;
        isEndless   = "endless".equals(mode);
        levelNum    = 1;

        loadLevel(levelNum);

        float baseX = (serverLevel != null) ? serverLevel.spawnX : 300f;
        float baseY = (serverLevel != null) ? serverLevel.spawnY : 300f;
        float[][] offsets = {{0,0},{48,0},{-48,0},{0,48}};

        int i = 0;
        for (CoopClientHandler ch : clients.values()) {
            float[] off = offsets[Math.min(i++, 3)];
            GamePacket.PlayerState ps = new GamePacket.PlayerState(
                    ch.playerId, ch.username,
                    baseX + off[0], baseY + off[1], 100, 100);
            playerStates.put(ch.playerId, ps);
            System.out.printf("[Server] Spawn P%d '%s' at (%.0f,%.0f)%n",
                    ch.playerId, ch.username, baseX+off[0], baseY+off[1]);
        }

        // Notify clients
        GamePacket start = new GamePacket();
        start.type     = GamePacket.TYPE_START;
        start.gameMode = mode;
        start.levelNum = levelNum;
        start.players  = playerStates.values().toArray(new GamePacket.PlayerState[0]);
        broadcast(start);

        System.out.println("[Server] Game started (" + mode + ") with " + clients.size() + " players");

        // Start the game loop
        new Thread(this::gameLoop, "ServerGameLoop").start();
    }

    private void loadLevel(int num) {
        serverEnemies.clear();
        serverBullets.clear();
        serverPickups.clear();
        try {
            serverLevel = new Level(num, new SaveData());
            System.out.println("[Server] Level " + num + " loaded. Spawn=("
                    + (int)serverLevel.spawnX + "," + (int)serverLevel.spawnY + ")");
        } catch (Exception e) {
            System.err.println("[Server] Level load error: " + e.getMessage());
            serverLevel = null;
        }
        waveNumber     = 0;
        waitingForWave = true;
        waveCooldown   = 120;
    }

    // ═════════════════════════════════════════════════════════════
    // Main game loop — runs at TICK_HZ
    // ═════════════════════════════════════════════════════════════
    private void gameLoop() {
        System.out.println("[Server] Game loop started at " + TICK_HZ + " Hz");
        while (gameStarted && running) {
            long start = System.currentTimeMillis();
            try {
                tick();
            } catch (Exception e) {
                System.err.println("[Server] Tick error: " + e.getMessage());
            }
            long elapsed = System.currentTimeMillis() - start;
            long sleep   = TICK_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ex) { break; }
            }
        }
        System.out.println("[Server] Game loop ended.");
    }

    private void tick() {
        // 1. Wave management
        tickWaves();

        // 2. Move enemies + run AI
        tickEnemies();

        // 3. Move bullets + collision
        tickBullets();

        // 4. Check pickups
        tickPickups();

        // 5. Broadcast state
        broadcast(buildStatePacket());
    }

    // ── Wave management ───────────────────────────────────────────
    private void tickWaves() {
        if (!waitingForWave) {
            // Check if all enemies dead
            boolean anyAlive = false;
            synchronized (serverEnemies) {
                for (ServerEnemy en : serverEnemies)
                    if (en.alive) { anyAlive = true; break; }
            }
            if (!anyAlive) {
                waitingForWave = true;
                waveCooldown   = 100; // ~5s at 20Hz
                System.out.println("[Server] Wave " + waveNumber + " cleared!");
            }
        } else {
            if (--waveCooldown <= 0) spawnWave();
        }
    }

    /**
     * ENEMY SPAWNING — server-side only.
     * Enemies are placed around the arena perimeter so they
     * always spawn in walkable space away from spawn point.
     */
    private void spawnWave() {
        waveNumber++;
        synchronized (serverEnemies) { serverEnemies.clear(); }
        synchronized (serverBullets) { serverBullets.clear(); }
        waitingForWave = false;

        float cx = (serverLevel != null) ? serverLevel.widthPx()  / 2f : 600f;
        float cy = (serverLevel != null) ? serverLevel.heightPx() / 2f : 600f;
        float arenaR = Math.min(cx, cy) * 0.7f;

        float hpScale  = 1f + waveNumber * 0.12f;
        float spdScale = 1f + waveNumber * 0.03f;

        boolean isBossWave   = (waveNumber % 10 == 0);
        boolean isMiniBoss   = (!isBossWave && waveNumber % 5 == 0);
        int count = isBossWave ? 1 : (isMiniBoss ? 3 + waveNumber : 4 + waveNumber * 2);
        count = Math.min(count, 35);

        Random rng = new Random(waveNumber * 9999L);
        synchronized (serverEnemies) {
            for (int i = 0; i < count; i++) {
                double angle = Math.PI * 2 * i / count + rng.nextDouble() * 0.3;
                float  r     = arenaR * (0.6f + rng.nextFloat() * 0.4f);
                float  ex    = cx + (float)(Math.cos(angle) * r);
                float  ey    = cy + (float)(Math.sin(angle) * r);

                // Clamp to level bounds
                if (serverLevel != null) {
                    ex = Math.max(48, Math.min(serverLevel.widthPx()  - 48, ex));
                    ey = Math.max(48, Math.min(serverLevel.heightPx() - 48, ey));
                }

                int typeOrd;
                if (isBossWave)              typeOrd = 2;
                else if (isMiniBoss && i==0) typeOrd = 2;
                else                         typeOrd = rng.nextFloat() < 0.3f ? 1 : 0;

                ServerEnemy en = new ServerEnemy(nextEnemyId.getAndIncrement(), ex, ey, typeOrd);
                en.hp    = (int)(en.maxHp * hpScale);
                en.maxHp = en.hp;
                en.speed *= spdScale;
                serverEnemies.add(en);
            }
        }
        System.out.printf("[Server] Wave %d spawned: %d enemies%n", waveNumber, count);
    }

    // ── Enemy AI ─────────────────────────────────────────────────
    private void tickEnemies() {
        if (playerStates.isEmpty()) return;

        // Collect alive players once per tick
        List<GamePacket.PlayerState> alivePlayers = new ArrayList<>();
        for (GamePacket.PlayerState ps : playerStates.values())
            if (ps.alive) alivePlayers.add(ps);
        if (alivePlayers.isEmpty()) return;

        long now = System.currentTimeMillis();

        synchronized (serverEnemies) {
            for (ServerEnemy en : serverEnemies) {
                if (!en.alive) continue;

                // Find nearest alive player
                GamePacket.PlayerState target = null;
                float minDist = Float.MAX_VALUE;
                for (GamePacket.PlayerState ps : alivePlayers) {
                    float dx = ps.x - en.x, dy = ps.y - en.y;
                    float d  = dx*dx + dy*dy;
                    if (d < minDist) { minDist = d; target = ps; }
                }
                if (target == null) continue;
                en.targetPlayerId = target.playerId;

                float dx = target.x - en.x;
                float dy = target.y - en.y;
                float dist = (float)Math.sqrt(dx*dx + dy*dy);

                if (en.typeOrd == 1) {
                    // RANGER: stay at range, shoot
                    float preferDist = 220f;
                    if (dist > preferDist + 20) {
                        moveEnemyToward(en, target.x, target.y, en.speed);
                    } else if (dist < preferDist - 20) {
                        moveEnemyToward(en, target.x, target.y, -en.speed);
                    }
                    // Shoot if in range
                    if (dist < 400 && now - en.lastShootMs > en.shootCooldownMs) {
                        en.lastShootMs = now;
                        float angle = (float)Math.atan2(dy, dx);
                        spawnEnemyBullet(en.x, en.y, angle, en.damage);
                    }
                } else {
                    // GRUNT / BOSS: chase
                    float spd = en.enraged ? en.speed * 1.5f : en.speed;
                    moveEnemyToward(en, target.x, target.y, spd);

                    // Melee contact
                    if (dist < 28) {
                        target.hp -= en.damage;
                        if (target.hp < 0) target.hp = 0;
                        if (target.hp == 0) target.alive = false;
                    }
                }
                en.facingRight = (dx >= 0);
            }
        }
    }

    private void moveEnemyToward(ServerEnemy en, float tx, float ty, float speed) {
        float dx = tx - en.x, dy = ty - en.y;
        float d  = (float)Math.sqrt(dx*dx + dy*dy);
        if (d < 1f) return;
        float nx = en.x + (dx/d) * speed;
        float ny = en.y + (dy/d) * speed;

        // Wall collision
        if (serverLevel != null) {
            if (!serverLevel.isWall(nx, en.y)) en.x = nx;
            if (!serverLevel.isWall(en.x, ny)) en.y = ny;
        } else {
            en.x = nx; en.y = ny;
        }
    }

    private void spawnEnemyBullet(float x, float y, float angle, int damage) {
        float spd = 6f;
        GamePacket.BulletState b = new GamePacket.BulletState();
        b.id         = nextBulletId.getAndIncrement();
        b.x          = x + (float)Math.cos(angle) * 20;
        b.y          = y + (float)Math.sin(angle) * 20;
        b.vx         = (float)Math.cos(angle) * spd;
        b.vy         = (float)Math.sin(angle) * spd;
        b.ownerId    = -1;
        b.fromPlayer = false;
        b.r = 255; b.g = 80; b.b = 80;
        b.damage     = damage;
        b.active     = true;
        synchronized (serverBullets) {
            if (serverBullets.size() < 500) serverBullets.add(b);
        }
    }

    // ── Bullet simulation ─────────────────────────────────────────
    private void tickBullets() {
        synchronized (serverBullets) {
            for (GamePacket.BulletState b : serverBullets) {
                if (!b.active) continue;

                b.x += b.vx;
                b.y += b.vy;

                // Out of bounds / wall
                if (serverLevel != null && serverLevel.isWall(b.x, b.y)) {
                    b.active = false; continue;
                }
                if (serverLevel != null) {
                    if (b.x < 0 || b.y < 0
                            || b.x > serverLevel.widthPx()
                            || b.y > serverLevel.heightPx()) {
                        b.active = false; continue;
                    }
                }

                if (b.fromPlayer) {
                    // Check enemy hits
                    synchronized (serverEnemies) {
                        for (ServerEnemy en : serverEnemies) {
                            if (!en.alive) continue;
                            float dx = en.x - b.x, dy = en.y - b.y;
                            if (dx*dx + dy*dy < 24*24) {
                                en.takeDamage(b.damage);
                                b.active = false;

                                if (!en.alive) {
                                    onEnemyKilled(en);
                                }
                                break;
                            }
                        }
                    }
                } else {
                    // Enemy bullet hits players
                    for (GamePacket.PlayerState ps : playerStates.values()) {
                        if (!ps.alive) continue;
                        float dx = ps.x - b.x, dy = ps.y - b.y;
                        if (dx*dx + dy*dy < 20*20) {
                            ps.hp -= b.damage;
                            if (ps.hp < 0) ps.hp = 0;
                            if (ps.hp == 0) ps.alive = false;
                            b.active = false;
                            break;
                        }
                    }
                }
            }
        }
    }

    private void onEnemyKilled(ServerEnemy en) {
        // Give score to nearest player
        GamePacket.PlayerState nearest = null;
        float minD = Float.MAX_VALUE;
        for (GamePacket.PlayerState ps : playerStates.values()) {
            if (!ps.alive) continue;
            float dx = ps.x - en.x, dy = ps.y - en.y;
            float d  = dx*dx + dy*dy;
            if (d < minD) { minD = d; nearest = ps; }
        }
        if (nearest != null) {
            nearest.score += en.score;
            nearest.kills++;
        }

        // Chance to spawn pickup
        if (Math.random() < 0.30) {
            GamePacket.PickupState pk = new GamePacket.PickupState();
            pk.id         = nextPickupId.getAndIncrement();
            pk.x          = en.x;
            pk.y          = en.y;
            pk.pickupType = Math.random() < 0.5 ? "health" : "score";
            pk.collected  = false;
            synchronized (serverPickups) { serverPickups.add(pk); }
        }
    }

    private void tickPickups() {
        synchronized (serverPickups) {
            for (GamePacket.PickupState pk : serverPickups) {
                if (pk.collected) continue;
                for (GamePacket.PlayerState ps : playerStates.values()) {
                    if (!ps.alive) continue;
                    float dx = ps.x - pk.x, dy = ps.y - pk.y;
                    if (dx*dx + dy*dy < 28*28) {
                        pk.collected = true;
                        switch (pk.pickupType) {
                            case "health" -> ps.hp = Math.min(ps.maxHp, ps.hp + 25);
                            case "score"  -> ps.score += 100;
                        }
                        break;
                    }
                }
            }
            serverPickups.removeIf(pk -> pk.collected);
        }
    }

    // ── State packet builder ──────────────────────────────────────
    private GamePacket buildStatePacket() {
        GamePacket p   = new GamePacket();
        p.type         = GamePacket.TYPE_STATE;
        p.timestamp    = System.currentTimeMillis();
        p.wave         = waveNumber;
        p.levelNum     = levelNum;

        p.players = playerStates.values().toArray(new GamePacket.PlayerState[0]);

        synchronized (serverEnemies) {
            p.enemies = serverEnemies.stream()
                    .map(ServerEnemy::toState)
                    .toArray(GamePacket.EnemyState[]::new);
        }
        synchronized (serverBullets) {
            p.bullets = serverBullets.stream()
                    .filter(b -> b.active)
                    .toArray(GamePacket.BulletState[]::new);
        }
        synchronized (serverPickups) {
            p.pickups = serverPickups.toArray(new GamePacket.PickupState[0]);
        }

        if (serverLevel != null) {
            p.exitX = (int)serverLevel.exitX;
            p.exitY = (int)serverLevel.exitY;
        }

        return p;
    }

    // ═════════════════════════════════════════════════════════════
    // Apply client input — called by CoopClientHandler
    // ═════════════════════════════════════════════════════════════
    public void applyInput(int playerId, GamePacket input) {
        GamePacket.PlayerState ps = playerStates.get(playerId);
        if (ps == null || !ps.alive) return;

        float speed = 3.5f;

        // Movement
        float nx = ps.x, ny = ps.y;
        if (input.moveUp)    ny -= speed;
        if (input.moveDown)  ny += speed;
        if (input.moveLeft)  { nx -= speed; ps.facingRight = false; }
        if (input.moveRight) { nx += speed; ps.facingRight = true; }

        // Wall collision
        if (serverLevel != null) {
            float maxX = serverLevel.widthPx()  - 28f;
            float maxY = serverLevel.heightPx() - 28f;
            nx = Math.max(28, Math.min(maxX, nx));
            ny = Math.max(28, Math.min(maxY, ny));
            if (!serverLevel.isWall(nx, ps.y)) ps.x = nx;
            if (!serverLevel.isWall(ps.x, ny)) ps.y = ny;
        } else {
            ps.x = Math.max(28, Math.min(2000, nx));
            ps.y = Math.max(28, Math.min(2000, ny));
        }

        // Aim state — stored in PlayerState so ALL clients see this player's aim
        ps.aimAngle   = input.aimAngle;
        ps.mouseWorldX = input.mouseWorldX;
        ps.mouseWorldY = input.mouseWorldY;
        ps.shooting   = input.shooting;
        ps.dashing    = input.dashing;

        // Shoot — rate limited per player in CoopClientHandler
        if (input.shooting) spawnPlayerBullet(ps);
    }

    private void spawnPlayerBullet(GamePacket.PlayerState ps) {
        float spd   = 9f;
        float angle = ps.aimAngle;
        GamePacket.BulletState b = new GamePacket.BulletState();
        b.id         = nextBulletId.getAndIncrement();
        b.x          = ps.x + (float)Math.cos(angle) * 20;
        b.y          = ps.y + (float)Math.sin(angle) * 20;
        b.vx         = (float)Math.cos(angle) * spd;
        b.vy         = (float)Math.sin(angle) * spd;
        b.ownerId    = ps.playerId;
        b.fromPlayer = true;
        b.damage     = 20 * ps.weaponLevel;
        b.r = 100; b.g = 200; b.b = 255;
        b.active     = true;
        synchronized (serverBullets) {
            if (serverBullets.size() < 500) serverBullets.add(b);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Player connect / disconnect
    // ═════════════════════════════════════════════════════════════
    public synchronized void playerDisconnected(int playerId) {
        clients.remove(playerId);
        playerStates.remove(playerId);
        System.out.println("[Server] P" + playerId + " disconnected. Players: " + clients.size());
        if (gameStarted && clients.isEmpty()) {
            System.out.println("[Server] All players gone, stopping.");
            gameStarted = false;
        } else {
            broadcastLobbyUpdate();
        }
    }

    public void broadcastLobbyUpdate() {
        GamePacket pkt  = new GamePacket();
        pkt.type        = GamePacket.TYPE_LOBBY_UPDATE;
        List<GamePacket.PlayerState> list = new ArrayList<>();
        for (CoopClientHandler ch : clients.values())
            list.add(new GamePacket.PlayerState(ch.playerId, ch.username, 0, 0, 100, 100));
        pkt.players = list.toArray(new GamePacket.PlayerState[0]);
        pkt.message = clients.size() + "/" + MAX_PLAYERS + " players";
        broadcast(pkt);
    }

    // ═════════════════════════════════════════════════════════════
    // Networking helpers
    // ═════════════════════════════════════════════════════════════
    public void broadcast(GamePacket packet) {
        for (CoopClientHandler ch : clients.values()) ch.send(packet);
    }

    public boolean isGameStarted()   { return gameStarted; }
    public ConcurrentHashMap<Integer, CoopClientHandler> getClients() { return clients; }
}
