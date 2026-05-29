import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GameServer v2 — FIXED
 *
 * FIXES:
 *   1. Player spawn positions now come from the server's own Level instance
 *      (Level.spawnX/spawnY) instead of hardcoded pixel coordinates.
 *      Each player spawns near the level spawn point with small offsets
 *      so they don't overlap.
 *
 *   2. Bullet rate limiter is now PER-PLAYER (stored in ClientHandler)
 *      instead of a single shared field — so one player's rate limit
 *      doesn't bleed into another player's shooting.
 *
 *   3. applyInput() only calls spawnBullet() when input.shooting == true,
 *      and shooting is only true when the client explicitly sets it.
 */
public class GameServer {

    public static final int  PORT        = 55555;
    public static final int  MAX_PLAYERS = 4;
    public static final int  TICK_RATE   = 20;
    public static final long TICK_MS     = 1000L / TICK_RATE;

    private ServerSocket serverSocket;
    private final ConcurrentHashMap<Integer, ClientHandler> clients   = new ConcurrentHashMap<>();
    private final AtomicInteger nextId      = new AtomicInteger(1);
    private volatile boolean    running     = false;
    private volatile boolean    gameStarted = false;
    private String              gameMode    = "story";

    // ── Server-side game state ────────────────────────────────────
    private final ConcurrentHashMap<Integer, GamePacket.PlayerState> playerStates  = new ConcurrentHashMap<>();
    private final List<GamePacket.EnemyState>   enemyStates   = Collections.synchronizedList(new ArrayList<>());
    private final List<GamePacket.BulletState>  bulletStates  = Collections.synchronizedList(new ArrayList<>());
    private final List<GamePacket.PickupState>  pickupStates  = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger bulletIdGen = new AtomicInteger(0);

    private int waveNumber = 0;
    private DatabaseManager db;
    private boolean dbOk = false;

    // Server-side level for spawn point lookup
    private Level serverLevel = null;

    public static void main(String[] args) {
        new GameServer().start();
    }

    public void start() {
        db   = new DatabaseManager();
        dbOk = db.connect();

        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            running = true;

            System.out.println("╔══════════════════════════════════╗");
            System.out.println("║  VOIDWALKER SERVER v2.1 RUNNING  ║");
            System.out.printf ("║  Port: %-26d║%n", PORT);
            System.out.printf ("║  DB:   %-26s║%n", dbOk ? "CONNECTED" : "OFFLINE");
            System.out.println("╚══════════════════════════════════╝");

            while (running && !serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    sock.setTcpNoDelay(true);
                    sock.setSoTimeout(15000);

                    if (gameStarted) {
                        rejectClient(sock, "Game already in progress."); continue;
                    }
                    if (clients.size() >= MAX_PLAYERS) {
                        rejectClient(sock, "Server full."); continue;
                    }

                    int id = nextId.getAndIncrement();
                    ClientHandler ch = new ClientHandler(sock, id, this);
                    clients.put(id, ch);
                    new Thread(ch, "Client-" + id).start();
                    System.out.println("[Server] Client " + id + " connected: " + sock.getInetAddress());

                } catch (SocketException e) {
                    if (running) System.err.println("[Server] Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal: " + e.getMessage());
        }
    }

    // ── Start game ────────────────────────────────────────────────
    public synchronized void startGame(String mode) {
        if (gameStarted || clients.isEmpty()) return;
        gameStarted = true;
        gameMode    = mode;

        // Load a server-side level so we can read the real spawn point
        try {
            serverLevel = new Level(1, new SaveData());
            System.out.println("[Server] Level loaded. Spawn: ("
                    + serverLevel.spawnX + ", " + serverLevel.spawnY + ")");
        } catch (Exception e) {
            System.err.println("[Server] Could not load level: " + e.getMessage());
            serverLevel = null;
        }

        // Determine base spawn from level, or fall back to centre-ish
        float baseX = (serverLevel != null) ? serverLevel.spawnX : 400f;
        float baseY = (serverLevel != null) ? serverLevel.spawnY : 400f;

        // Small offsets so players don't stack on top of each other
        // (offset in pixels, same tile row)
        float[][] offsets = {
                {  0f,   0f},
                { 48f,   0f},
                {-48f,   0f},
                {  0f,  48f},
        };

        int i = 0;
        for (ClientHandler ch : clients.values()) {
            float[] off = offsets[Math.min(i++, 3)];
            float spawnX = baseX + off[0];
            float spawnY = baseY + off[1];

            // Clamp to level bounds if level loaded
            if (serverLevel != null) {
                float maxX = serverLevel.widthPx()  - 32f;
                float maxY = serverLevel.heightPx() - 32f;
                spawnX = Math.max(32, Math.min(maxX, spawnX));
                spawnY = Math.max(32, Math.min(maxY, spawnY));
            }

            GamePacket.PlayerState ps = new GamePacket.PlayerState(
                    ch.playerId, ch.username,
                    spawnX, spawnY,
                    100, 100, 0, 0, true, true, 1);
            playerStates.put(ch.playerId, ps);

            System.out.println("[Server] Spawning P" + ch.playerId
                    + " (" + ch.username + ") at ("
                    + (int)spawnX + ", " + (int)spawnY + ")");
        }

        // Notify all clients
        GamePacket start  = new GamePacket();
        start.type        = GamePacket.TYPE_START;
        start.gameMode    = mode;
        start.players     = playerStates.values().toArray(new GamePacket.PlayerState[0]);
        broadcast(start);

        System.out.println("[Server] Game started: " + mode
                + " with " + clients.size() + " players");

        new Thread(this::stateBroadcastLoop, "StateBroadcast").start();
    }

    // ── State broadcast loop ──────────────────────────────────────
    private void stateBroadcastLoop() {
        System.out.println("[Server] State broadcast at " + TICK_RATE + "Hz");
        while (gameStarted && running) {
            long t0 = System.currentTimeMillis();

            // Move bullets
            tickBullets();

            GamePacket state = buildStatePacket();
            broadcast(state);

            long elapsed = System.currentTimeMillis() - t0;
            long sleep   = TICK_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { break; }
            }
        }
    }

    /** Move active bullets one server tick */
    private void tickBullets() {
        synchronized (bulletStates) {
            for (GamePacket.BulletState b : bulletStates) {
                if (!b.active) continue;
                b.x += b.vx;
                b.y += b.vy;

                // Deactivate if out of world bounds
                if (b.x < 0 || b.y < 0
                        || (serverLevel != null && (b.x > serverLevel.widthPx()
                        || b.y > serverLevel.heightPx()))) {
                    b.active = false;
                    continue;
                }

                // Deactivate if hits wall
                if (serverLevel != null && serverLevel.isWall(b.x, b.y)) {
                    b.active = false;
                }
            }
        }
    }

    private GamePacket buildStatePacket() {
        GamePacket p  = new GamePacket();
        p.type        = GamePacket.TYPE_STATE;
        p.timestamp   = System.currentTimeMillis();
        p.players     = playerStates.values().toArray(new GamePacket.PlayerState[0]);
        p.enemies     = enemyStates.toArray(new GamePacket.EnemyState[0]);
        p.wave        = waveNumber;

        synchronized (bulletStates) {
            List<GamePacket.BulletState> active = new ArrayList<>();
            for (GamePacket.BulletState b : bulletStates)
                if (b.active) active.add(b);
            p.bullets = active.toArray(new GamePacket.BulletState[0]);
        }

        p.pickups = pickupStates.toArray(new GamePacket.PickupState[0]);
        return p;
    }

    // ── Apply client input ────────────────────────────────────────
    public void applyInput(int playerId, GamePacket input) {
        GamePacket.PlayerState ps = playerStates.get(playerId);
        if (ps == null || !ps.alive) return;

        float speed = 3.5f;
        float nx = ps.x, ny = ps.y;

        if (input.moveUp)    ny -= speed;
        if (input.moveDown)  ny += speed;
        if (input.moveLeft)  { nx -= speed; ps.facingRight = false; }
        if (input.moveRight) { nx += speed; ps.facingRight = true; }

        // Clamp to level bounds
        if (serverLevel != null) {
            float maxX = serverLevel.widthPx()  - 32f;
            float maxY = serverLevel.heightPx() - 32f;
            nx = Math.max(32, Math.min(maxX, nx));
            ny = Math.max(32, Math.min(maxY, ny));

            // Wall collision
            if (!serverLevel.isWall(nx, ps.y)) ps.x = nx;
            if (!serverLevel.isWall(ps.x, ny)) ps.y = ny;
        } else {
            ps.x = nx;
            ps.y = ny;
        }

        ps.aimAngle = input.aimAngle;
        ps.shooting = input.shooting;
        ps.dashing  = input.dashing;

        // Only spawn bullet if client explicitly says shooting AND rate allows
        // Rate limiting is handled in ClientHandler before this call
        if (input.shooting) {
            spawnBulletForPlayer(playerId, ps.x, ps.y, input.aimAngle);
        }
    }

    /**
     * Spawn a bullet for a specific player.
     * Rate limiting is done per-player in ClientHandler.handlePacket(),
     * so by the time we get here shooting==true, we can fire.
     */
    void spawnBulletForPlayer(int ownerId, float x, float y, float angle) {
        float spd = 9f;
        GamePacket.BulletState b = new GamePacket.BulletState();
        b.id      = bulletIdGen.getAndIncrement();
        b.x       = x + (float)Math.cos(angle) * 20; // spawn ahead of player
        b.y       = y + (float)Math.sin(angle) * 20;
        b.vx      = (float)Math.cos(angle) * spd;
        b.vy      = (float)Math.sin(angle) * spd;
        b.ownerId = ownerId;
        b.r       = 100; b.g = 200; b.b = 255;
        b.active  = true;

        synchronized (bulletStates) {
            // Reuse inactive slot
            for (GamePacket.BulletState existing : bulletStates) {
                if (!existing.active) {
                    copyBullet(b, existing);
                    return;
                }
            }
            if (bulletStates.size() < 400) bulletStates.add(b);
        }
    }

    private void copyBullet(GamePacket.BulletState src, GamePacket.BulletState dst) {
        dst.id = src.id; dst.x = src.x; dst.y = src.y;
        dst.vx = src.vx; dst.vy = src.vy;
        dst.ownerId = src.ownerId;
        dst.r = src.r; dst.g = src.g; dst.b = src.b;
        dst.active = true;
    }

    // ── Player disconnect ─────────────────────────────────────────
    public synchronized void playerLeft(int playerId) {
        clients.remove(playerId);
        playerStates.remove(playerId);
        System.out.println("[Server] P" + playerId + " disconnected. Remaining: " + clients.size());
        if (gameStarted && clients.isEmpty()) {
            System.out.println("[Server] All players gone. Stopping.");
            gameStarted = false;
        } else {
            broadcastLobbyUpdate();
        }
    }

    // ── Lobby update ──────────────────────────────────────────────
    public void broadcastLobbyUpdate() {
        GamePacket pkt  = new GamePacket();
        pkt.type        = GamePacket.TYPE_LOBBY_UPDATE;
        List<GamePacket.PlayerState> list = new ArrayList<>();
        for (ClientHandler ch : clients.values())
            list.add(new GamePacket.PlayerState(
                    ch.playerId, ch.username, 0, 0, 100, 100, 0, 0, true, true, 1));
        pkt.players = list.toArray(new GamePacket.PlayerState[0]);
        pkt.message = clients.size() + "/" + MAX_PLAYERS + " players connected";
        broadcast(pkt);
        System.out.println("[Server] Lobby: " + clients.size() + " players");
    }

    public void broadcast(GamePacket packet) {
        for (ClientHandler ch : clients.values()) ch.send(packet);
    }

    public void sendTo(int playerId, GamePacket packet) {
        ClientHandler ch = clients.get(playerId);
        if (ch != null) ch.send(packet);
    }

    private void rejectClient(Socket socket, String reason) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            GamePacket err = new GamePacket();
            err.type = GamePacket.TYPE_ERROR;
            err.message = reason;
            out.writeObject(err); out.flush();
            socket.close();
        } catch (IOException ignored) {}
    }

    public boolean isGameStarted()   { return gameStarted; }
    public ConcurrentHashMap<Integer, ClientHandler> getClients() { return clients; }
    public Level getServerLevel()    { return serverLevel; }
}