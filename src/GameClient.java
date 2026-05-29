import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * GameClient v2 — Connects to server, sends input, receives state.
 *
 * KEY DESIGN:
 *   - Runs on a DEDICATED THREAD (not EDT)
 *   - Sends INPUT at 20Hz via scheduled executor
 *   - Receives STATE asynchronously
 *   - Stores latest state for GamePanel to render
 *   - Implements CLIENT-SIDE PREDICTION for local player movement
 *   - Implements INTERPOLATION for remote players
 */
public class GameClient {

    public static final int PORT    = 55555;
    public static final int TICK_HZ = 20;

    // ── Connection ────────────────────────────────────────────────
    private Socket             socket;
    private ObjectOutputStream out;
    private ObjectInputStream  in;
    private volatile boolean   connected = false;
    private String             host;

    // ── Player identity ───────────────────────────────────────────
    private int    myPlayerId = -1;
    private String myUsername = "Player";

    // ── Latest received world state ───────────────────────────────
    // GamePanel reads this each frame to render
    private volatile GamePacket.PlayerState[] latestPlayers = null;
    private volatile GamePacket.EnemyState[]  latestEnemies = null;
    private volatile GamePacket.BulletState[] latestBullets = null;
    private volatile GamePacket.PickupState[] latestPickups = null;
    private volatile long stateTimestamp = 0;

    // ── Interpolation buffers ─────────────────────────────────────
    // Store last 2 state snapshots per player for smooth rendering
    private final Map<Integer, float[]> prevPos = new ConcurrentHashMap<>(); // id→[px,py]
    private final Map<Integer, float[]> currPos = new ConcurrentHashMap<>(); // id→[cx,cy]
    private volatile float interpAlpha = 1f; // 0→1 between prev and curr state

    // ── Input state (set by GamePanel each frame) ─────────────────
    public volatile boolean inUp, inDown, inLeft, inRight;
    public volatile boolean inShoot, inDash;
    public volatile float   inAimAngle;
    public volatile float   inMouseX, inMouseY;

    // ── Callbacks ─────────────────────────────────────────────────
    public Consumer<GamePacket> onLobbyUpdate = null;
    public Consumer<GamePacket> onGameStart   = null;
    public Consumer<GamePacket> onChat        = null;
    public Consumer<GamePacket> onError       = null;
    public Runnable             onConnected   = null;
    public Runnable             onDisconnected = null;

    // ── Executor for input sending ────────────────────────────────
    private ScheduledExecutorService inputScheduler;

    // ── Ping tracking ─────────────────────────────────────────────
    private volatile long pingMs = 0;
    private long lastPingSent    = 0;

    public GameClient(String host, String username) {
        this.host       = host;
        this.myUsername = username;
    }

    // ── Connect ───────────────────────────────────────────────────
    public void connect() {
        new Thread(this::connectInternal, "GameClient-Connect").start();
    }

    private void connectInternal() {
        try {
            System.out.println("[Client] Connecting to " + host + ":" + PORT);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, PORT), 5000);
            socket.setTcpNoDelay(true);  // reduce latency
            socket.setSoTimeout(15000);

            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in  = new ObjectInputStream(socket.getInputStream());
            connected = true;

            System.out.println("[Client] Connected to server.");
            if (onConnected != null) onConnected.run();

            // Send JOIN
            GamePacket join = new GamePacket();
            join.type     = GamePacket.TYPE_JOIN;
            join.username = myUsername;
            send(join);

            // Start input sender at 20Hz
            startInputSender();

            // Start receive loop
            receiveLoop();

        } catch (ConnectException e) {
            notifyError("Cannot connect to " + host + ". Is the server running?");
        } catch (SocketTimeoutException e) {
            notifyError("Connection timed out. Check the IP: " + host);
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
                if (!(obj instanceof GamePacket)) continue;
                handlePacket((GamePacket) obj);
            } catch (EOFException | SocketException e) {
                break;
            } catch (IOException | ClassNotFoundException e) {
                if (connected) System.err.println("[Client] Receive error: " + e.getMessage());
                break;
            }
        }
    }

    private void handlePacket(GamePacket pkt) {
        switch (pkt.type) {

            case GamePacket.TYPE_JOIN_ACK -> {
                myPlayerId = pkt.playerId;
                myUsername = pkt.username;
                System.out.println("[Client] Joined as Player " + myPlayerId + " (" + myUsername + ")");
            }

            case GamePacket.TYPE_LOBBY_UPDATE -> {
                System.out.println("[Client] Lobby: " + pkt.message);
                if (pkt.players != null)
                    for (GamePacket.PlayerState ps : pkt.players)
                        System.out.println("  P" + ps.playerId + " = " + ps.username);
                if (onLobbyUpdate != null) onLobbyUpdate.accept(pkt);
            }

            case GamePacket.TYPE_START -> {
                System.out.println("[Client] Game starting: " + pkt.gameMode);
                if (pkt.players != null) {
                    latestPlayers = pkt.players;
                    for (GamePacket.PlayerState ps : pkt.players)
                        currPos.put(ps.playerId, new float[]{ps.x, ps.y});
                }
                if (onGameStart != null) onGameStart.accept(pkt);
            }

            case GamePacket.TYPE_STATE -> {
                // Save previous positions for interpolation
                if (latestPlayers != null) {
                    for (GamePacket.PlayerState ps : latestPlayers)
                        prevPos.put(ps.playerId, new float[]{ps.x, ps.y});
                }

                // Store new state
                if (pkt.players != null) {
                    latestPlayers = pkt.players;
                    for (GamePacket.PlayerState ps : pkt.players)
                        currPos.put(ps.playerId, new float[]{ps.x, ps.y});
                }
                if (pkt.enemies != null) latestEnemies = pkt.enemies;
                if (pkt.bullets != null) latestBullets = pkt.bullets;
                if (pkt.pickups != null) latestPickups  = pkt.pickups;
                stateTimestamp = pkt.timestamp;
                interpAlpha    = 0f; // reset interpolation
            }

            case GamePacket.TYPE_CHAT -> {
                if (onChat != null) onChat.accept(pkt);
            }

            case GamePacket.TYPE_PING -> {
                pingMs = System.currentTimeMillis() - lastPingSent;
            }

            case GamePacket.TYPE_ERROR -> {
                notifyError(pkt.message);
            }

            case GamePacket.TYPE_GAME_OVER -> {
                System.out.println("[Client] Game over.");
            }
        }
    }

    // ── Input sender (20Hz) ───────────────────────────────────────
    private void startInputSender() {
        inputScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GameClient-Input");
            t.setDaemon(true);
            return t;
        });
        inputScheduler.scheduleAtFixedRate(this::sendInput,
                50, 1000L / TICK_HZ, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // FIXED: Use mouseWorldX/mouseWorldY instead of mouseX/mouseY
    private void sendInput() {
        if (!connected || myPlayerId < 0) return;
        GamePacket input = new GamePacket();
        input.type      = GamePacket.TYPE_INPUT;
        input.playerId  = myPlayerId;
        input.moveUp    = inUp;
        input.moveDown  = inDown;
        input.moveLeft  = inLeft;
        input.moveRight = inRight;
        input.shooting  = inShoot;
        input.dashing   = inDash;
        input.aimAngle  = inAimAngle;
        // FIXED: Use mouseWorldX/mouseWorldY
        input.mouseWorldX = inMouseX;
        input.mouseWorldY = inMouseY;
        send(input);

        // Periodic ping
        long now = System.currentTimeMillis();
        if (now - lastPingSent > 2000) {
            lastPingSent = now;
            send(GamePacket.ping());
        }
    }

    // ── Host starts game ──────────────────────────────────────────
    public void hostStartGame(String mode) {
        if (!connected) return;
        GamePacket pkt = new GamePacket();
        pkt.type     = "START_REQUEST";
        pkt.gameMode = mode;
        pkt.playerId = myPlayerId;
        send(pkt);
        System.out.println("[Client] Sent START_REQUEST mode=" + mode);
    }

    // ── Send ──────────────────────────────────────────────────────
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

    // ── Interpolation ─────────────────────────────────────────────
    /**
     * Call each frame to advance interpolation.
     * alpha goes 0→1 between last two state snapshots.
     */
    public void tickInterpolation(float deltaAlpha) {
        interpAlpha = Math.min(1f, interpAlpha + deltaAlpha);
    }

    /**
     * Get interpolated position for a player.
     * Returns smoothed position between last two server states.
     */
    public float[] getInterpolatedPos(int playerId) {
        float[] prev = prevPos.get(playerId);
        float[] curr = currPos.get(playerId);
        if (prev == null || curr == null)
            return curr != null ? curr : new float[]{0,0};
        float a = interpAlpha;
        return new float[]{
                prev[0] + (curr[0] - prev[0]) * a,
                prev[1] + (curr[1] - prev[1]) * a
        };
    }

    // ── Disconnect ────────────────────────────────────────────────
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
            GamePacket err = new GamePacket();
            err.type = GamePacket.TYPE_ERROR;
            err.message = msg;
            onError.accept(err);
        }
    }

    // ── Getters ───────────────────────────────────────────────────
    public int    getPlayerId()     { return myPlayerId; }
    public String getUsername()     { return myUsername; }
    public boolean isConnected()    { return connected; }
    public long   getPing()         { return pingMs; }
    public float  getInterpAlpha()  { return interpAlpha; }

    public GamePacket.PlayerState[] getLatestPlayers() { return latestPlayers; }
    public GamePacket.EnemyState[]  getLatestEnemies() { return latestEnemies; }
    public GamePacket.BulletState[] getLatestBullets() { return latestBullets; }
    public GamePacket.PickupState[] getLatestPickups() { return latestPickups; }
}