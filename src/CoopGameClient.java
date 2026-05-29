import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * CoopGameClient — Connects to CoopGameServer, sends input, renders state.
 *
 * KEY DESIGN DECISIONS:
 *
 *   1. INTERPOLATION: stores 2 consecutive snapshots per entity,
 *      blends between them over 50ms (one server tick) so movement
 *      appears smooth at 60fps even with 20Hz server updates.
 *
 *   2. CROSSHAIR SYNC: PlayerState includes aimAngle + mouseWorldX/Y.
 *      Each remote player's crosshair is drawn at their mouseWorldX/Y
 *      in world space. Local player uses real mouse pos for zero latency.
 *
 *   3. INPUT SENDING: ScheduledExecutor at 20Hz, NOT on EDT.
 *      Movement keys are sampled each send tick.
 *
 *   4. NO LOCAL ENEMIES: clients receive EnemyState[] from server.
 *      They NEVER run enemy logic locally in co-op mode.
 */
public class CoopGameClient {

    public static final int    PORT    = 55555;
    public static final int    TICK_HZ = 20;

    // ── Connection ────────────────────────────────────────────────
    private Socket             socket;
    private ObjectOutputStream out;
    private ObjectInputStream  in;
    private volatile boolean   connected = false;
    private String             host;

    // ── Identity ──────────────────────────────────────────────────
    private int    myPlayerId = -1;
    private String myUsername = "Player";

    // ── Latest world state from server ────────────────────────────
    private volatile GamePacket.PlayerState[] latestPlayers  = null;
    private volatile GamePacket.EnemyState[]  latestEnemies  = null;
    private volatile GamePacket.BulletState[] latestBullets  = null;
    private volatile GamePacket.PickupState[] latestPickups  = null;
    private volatile int latestWave  = 0;
    private volatile int latestLevel = 1;
    private volatile long stateTimestamp = 0;

    // ── Interpolation ─────────────────────────────────────────────
    // prevPos/currPos store the last two server positions per entity
    private final Map<Integer, float[]> prevPlayerPos = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> currPlayerPos = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> prevEnemyPos  = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> currEnemyPos  = new ConcurrentHashMap<>();
    private volatile float interpAlpha = 1f; // 0→1 between prev and curr

    // ── Input state (written by GamePanel each frame) ─────────────
    public volatile boolean inUp, inDown, inLeft, inRight;
    public volatile boolean inShoot, inDash;
    public volatile float   inAimAngle   = 0f;
    public volatile float   inMouseWorldX = 0f;
    public volatile float   inMouseWorldY = 0f;

    // ── Callbacks ─────────────────────────────────────────────────
    public Consumer<GamePacket> onLobbyUpdate  = null;
    public Consumer<GamePacket> onGameStart    = null;
    public Consumer<GamePacket> onChat         = null;
    public Consumer<GamePacket> onError        = null;
    public Runnable             onConnected    = null;
    public Runnable             onDisconnected = null;

    // ── Executor for input sending ────────────────────────────────
    private ScheduledExecutorService inputScheduler;

    // ── Ping ──────────────────────────────────────────────────────
    private volatile long pingMs    = 0L;
    private long          pingSentAt = 0L;

    // ── Grace period (prevents auto-fire on spawn) ────────────────
    private volatile int shootGrace = 30; // frames

    public CoopGameClient(String host, String username) {
        this.host       = host;
        this.myUsername = username;
    }

    // ═════════════════════════════════════════════════════════════
    // Connect
    // ═════════════════════════════════════════════════════════════
    public void connect() {
        new Thread(this::connectInternal, "CoopClient-Connect").start();
    }

    private void connectInternal() {
        try {
            System.out.println("[Client] Connecting to " + host + ":" + PORT);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, PORT), 6000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20000);

            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in  = new ObjectInputStream(socket.getInputStream());
            connected = true;

            System.out.println("[Client] Connected!");
            if (onConnected != null) onConnected.run();

            // Send JOIN
            GamePacket join  = new GamePacket();
            join.type        = GamePacket.TYPE_JOIN;
            join.username    = myUsername;
            send(join);

            // Start input sender at 20 Hz
            startInputSender();

            // Receive loop (blocks until disconnected)
            receiveLoop();

        } catch (ConnectException e) {
            notifyError("Cannot connect to " + host + ". Is the server running?");
        } catch (SocketTimeoutException e) {
            notifyError("Connection timed out. Check IP: " + host);
        } catch (IOException e) {
            notifyError("Connection error: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    private void receiveLoop() {
        while (connected) {
            try {
                Object obj = in.readObject();
                if (obj instanceof GamePacket pkt) handlePacket(pkt);
            } catch (EOFException | SocketException e) {
                break;
            } catch (IOException | ClassNotFoundException e) {
                if (connected) System.err.println("[Client] Recv: " + e.getMessage());
                break;
            }
        }
    }

    private void handlePacket(GamePacket pkt) {
        switch (pkt.type) {

            case GamePacket.TYPE_JOIN_ACK -> {
                myPlayerId = pkt.playerId;
                myUsername = pkt.username;
                System.out.println("[Client] Joined as P" + myPlayerId + " (" + myUsername + ")");
            }

            case GamePacket.TYPE_LOBBY_UPDATE -> {
                if (onLobbyUpdate != null) onLobbyUpdate.accept(pkt);
            }

            case GamePacket.TYPE_START -> {
                System.out.println("[Client] Game starting: " + pkt.gameMode);
                latestLevel = pkt.levelNum;
                // Seed initial positions for interpolation
                if (pkt.players != null) {
                    latestPlayers = pkt.players;
                    for (GamePacket.PlayerState ps : pkt.players)
                        currPlayerPos.put(ps.playerId, new float[]{ps.x, ps.y});
                }
                shootGrace = 30; // prevent auto-fire on start
                if (onGameStart != null) onGameStart.accept(pkt);
            }

            case GamePacket.TYPE_STATE -> {
                // Save previous positions for interpolation
                if (latestPlayers != null)
                    for (GamePacket.PlayerState ps : latestPlayers)
                        prevPlayerPos.put(ps.playerId, currPlayerPos.getOrDefault(
                                ps.playerId, new float[]{ps.x, ps.y}));
                if (latestEnemies != null)
                    for (GamePacket.EnemyState en : latestEnemies)
                        prevEnemyPos.put(en.id, currEnemyPos.getOrDefault(
                                en.id, new float[]{en.x, en.y}));

                // Store new state
                if (pkt.players != null) {
                    latestPlayers = pkt.players;
                    for (GamePacket.PlayerState ps : pkt.players)
                        currPlayerPos.put(ps.playerId, new float[]{ps.x, ps.y});
                }
                if (pkt.enemies != null) {
                    latestEnemies = pkt.enemies;
                    for (GamePacket.EnemyState en : pkt.enemies)
                        currEnemyPos.put(en.id, new float[]{en.x, en.y});
                }
                if (pkt.bullets != null) latestBullets = pkt.bullets;
                if (pkt.pickups != null) latestPickups  = pkt.pickups;
                latestWave       = pkt.wave;
                latestLevel      = pkt.levelNum;
                stateTimestamp   = pkt.timestamp;
                interpAlpha      = 0f; // reset interpolation
            }

            case GamePacket.TYPE_CHAT -> {
                if (onChat != null) onChat.accept(pkt);
            }

            case GamePacket.TYPE_PING -> {
                pingMs = System.currentTimeMillis() - pingSentAt;
            }

            case GamePacket.TYPE_ERROR -> notifyError(pkt.message);

            case GamePacket.TYPE_GAME_OVER -> {
                System.out.println("[Client] Game over.");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Input sender — 20 Hz, off EDT
    // ═════════════════════════════════════════════════════════════
    private void startInputSender() {
        inputScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CoopClient-Input");
            t.setDaemon(true);
            return t;
        });
        inputScheduler.scheduleAtFixedRate(this::sendInput,
                50, 1000L / TICK_HZ, TimeUnit.MILLISECONDS);
    }

    private void sendInput() {
        if (!connected || myPlayerId < 0) return;

        // Decrement shoot grace
        if (shootGrace > 0) { shootGrace--; }

        GamePacket input    = new GamePacket();
        input.type          = GamePacket.TYPE_INPUT;
        input.playerId      = myPlayerId;
        input.moveUp        = inUp;
        input.moveDown      = inDown;
        input.moveLeft      = inLeft;
        input.moveRight     = inRight;
        input.shooting      = (shootGrace <= 0) && inShoot;
        input.dashing       = inDash;
        input.aimAngle      = inAimAngle;
        input.mouseWorldX   = inMouseWorldX;
        input.mouseWorldY   = inMouseWorldY;
        send(input);

        // Periodic ping
        long now = System.currentTimeMillis();
        if (now - pingSentAt > 2000) {
            pingSentAt = now;
            send(GamePacket.ping());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Interpolation helpers
    // ═════════════════════════════════════════════════════════════

    /**
     * Advance interpolation alpha by one render frame.
     * At 60fps render / 20Hz server: alpha advances by 1/3 per frame.
     */
    public void tickInterpolation() {
        interpAlpha = Math.min(1f, interpAlpha + (1f / 3f));
    }

    /**
     * Get smoothly interpolated player position.
     * For the LOCAL player, this should NOT be used —
     * use actual server position so camera follows correctly.
     */
    public float[] getInterpolatedPlayerPos(int playerId) {
        float[] prev = prevPlayerPos.get(playerId);
        float[] curr = currPlayerPos.get(playerId);
        if (curr == null) return new float[]{0, 0};
        if (prev == null) return curr;
        float a = interpAlpha;
        return new float[]{
                prev[0] + (curr[0] - prev[0]) * a,
                prev[1] + (curr[1] - prev[1]) * a
        };
    }

    /**
     * Get smoothly interpolated enemy position.
     */
    public float[] getInterpolatedEnemyPos(int enemyId) {
        float[] prev = prevEnemyPos.get(enemyId);
        float[] curr = currEnemyPos.get(enemyId);
        if (curr == null) return new float[]{0, 0};
        if (prev == null) return curr;
        float a = interpAlpha;
        return new float[]{
                prev[0] + (curr[0] - prev[0]) * a,
                prev[1] + (curr[1] - prev[1]) * a
        };
    }

    // ═════════════════════════════════════════════════════════════
    // Host start
    // ═════════════════════════════════════════════════════════════
    public void hostStartGame(String mode) {
        if (!connected) return;
        GamePacket pkt  = new GamePacket();
        pkt.type        = GamePacket.TYPE_START_REQUEST;
        pkt.gameMode    = mode;
        pkt.playerId    = myPlayerId;
        send(pkt);
        System.out.println("[Client] Sent START_REQUEST mode=" + mode);
    }

    // ═════════════════════════════════════════════════════════════
    // Send
    // ═════════════════════════════════════════════════════════════
    public synchronized void send(GamePacket packet) {
        if (!connected || out == null) return;
        try {
            out.writeObject(packet);
            out.flush();
            out.reset();
        } catch (IOException e) {
            connected = false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Disconnect
    // ═════════════════════════════════════════════════════════════
    public void disconnect() {
        connected = false;
        if (inputScheduler != null) inputScheduler.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (onDisconnected != null) onDisconnected.run();
        System.out.println("[Client] Disconnected.");
    }

    private void notifyError(String msg) {
        System.err.println("[Client] ERROR: " + msg);
        if (onError != null) {
            GamePacket err = GamePacket.error(msg);
            onError.accept(err);
        }
    }

    // ── Getters ───────────────────────────────────────────────────
    public int     getPlayerId()       { return myPlayerId; }
    public String  getUsername()       { return myUsername; }
    public boolean isConnected()       { return connected; }
    public long    getPing()           { return pingMs; }
    public float   getInterpAlpha()    { return interpAlpha; }
    public int     getLatestWave()     { return latestWave; }
    public int     getLatestLevel()    { return latestLevel; }

    public GamePacket.PlayerState[] getLatestPlayers() { return latestPlayers; }
    public GamePacket.EnemyState[]  getLatestEnemies() { return latestEnemies; }
    public GamePacket.BulletState[] getLatestBullets() { return latestBullets; }
    public GamePacket.PickupState[] getLatestPickups() { return latestPickups; }

    /** Get MY current state from latest server snapshot. */
    public GamePacket.PlayerState getMyState() {
        if (latestPlayers == null) return null;
        for (GamePacket.PlayerState ps : latestPlayers)
            if (ps.playerId == myPlayerId) return ps;
        return null;
    }
}
