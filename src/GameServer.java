import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * GameServer.java - FIXED VERSION
 * Fixes:
 *  - broadcastLobbyUpdate now includes username correctly
 *  - startGame properly initialises all player states
 *  - State broadcast loop only runs when game is active
 *  - Proper thread-safe client map
 */
public class GameServer {

    public static final int PORT        = 55555;
    public static final int MAX_PLAYERS = 4;

    private ServerSocket serverSocket;
    private final Map<Integer, ClientHandler> clients    = new ConcurrentHashMap<>();
    private final Map<Integer, GamePacket.PlayerState> gameStates = new ConcurrentHashMap<>();
    private volatile boolean gameStarted  = false;
    private          int     nextPlayerId = 1;
    private          int     matchId      = -1;
    private          String  gameMode     = "story";

    private final DatabaseManager db          = new DatabaseManager();
    private       boolean         dbAvailable = false;

    public static void main(String[] args) {
        new GameServer().start();
    }

    public void start() {
        dbAvailable = db.connect();
        if (!dbAvailable)
            System.out.println("[Server] Running WITHOUT database (offline mode).");

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("╔══════════════════════════════════╗");
            System.out.println("║   VOIDWALKER GAME SERVER v2.0    ║");
            System.out.println("╠══════════════════════════════════╣");
            System.out.printf ("║ Port    : %-23d║%n", PORT);
            System.out.printf ("║ Max     : %-23d║%n", MAX_PLAYERS);
            System.out.printf ("║ DB      : %-23s║%n", dbAvailable?"ONLINE":"OFFLINE");
            System.out.println("╚══════════════════════════════════╝");
            System.out.println("Waiting for players...\n");

            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();

                // Reject if game already started
                if (gameStarted) {
                    rejectClient(socket, "Game already in progress.");
                    continue;
                }
                // Reject if full
                if (clients.size() >= MAX_PLAYERS) {
                    rejectClient(socket, "Server full (" + MAX_PLAYERS + " max).");
                    continue;
                }

                int id = nextPlayerId++;
                ClientHandler handler = new ClientHandler(socket, id, this);
                clients.put(id, handler);
                new Thread(handler, "Player-" + id).start();
            }

        } catch (IOException e) {
            if (!serverSocket.isClosed())
                System.err.println("[Server] Fatal: " + e.getMessage());
        } finally {
            db.disconnect();
        }
    }

    private void rejectClient(Socket socket, String reason) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            GamePacket err = new GamePacket();
            err.type    = GamePacket.TYPE_ERROR;
            err.message = reason;
            out.writeObject(err);
            out.flush();
            socket.close();
            System.out.println("[Server] Rejected client: " + reason);
        } catch (IOException ignored) {}
    }

    // ── Start the game ────────────────────────────────────────────
    public synchronized void startGame(String mode) {
        if (gameStarted) {
            System.out.println("[Server] startGame() called but game already started.");
            return;
        }
        if (clients.isEmpty()) {
            System.out.println("[Server] No players connected — cannot start.");
            return;
        }

        gameStarted = true;
        gameMode    = mode;

        System.out.println("[Server] Starting game! Mode=" + mode +
                " Players=" + clients.size());

        // Save match to DB
        if (dbAvailable) {
            matchId = db.createMatch(-1, clients.size(), mode);
            System.out.println("[Server] Match #" + matchId + " created in DB.");
        }

        // Spawn positions for up to 4 players
        float[] spawnX = {200f, 400f, 200f, 400f};
        float[] spawnY = {200f, 200f, 400f, 400f};

        // Create initial state for each player
        for (ClientHandler ch : clients.values()) {
            int idx = Math.min(ch.playerId - 1, 3);
            gameStates.put(ch.playerId, new GamePacket.PlayerState(
                    ch.playerId, ch.username,
                    spawnX[idx], spawnY[idx],
                    100, 100, 0, 0, true, true, 1
            ));
            System.out.println("[Server] Initialised state for " +
                    ch.username + " at (" + spawnX[idx] + "," + spawnY[idx] + ")");
        }

        // Broadcast START to all clients
        GamePacket start = new GamePacket();
        start.type       = GamePacket.TYPE_START;
        start.gameMode   = mode;
        start.players    = gameStates.values()
                .toArray(new GamePacket.PlayerState[0]);
        broadcast(start);
        System.out.println("[Server] START packet broadcast to " +
                clients.size() + " clients.");

        // Start state sync loop (20fps)
        new Thread(this::stateBroadcastLoop, "StateBroadcast").start();
    }

    // ── Sync loop ─────────────────────────────────────────────────
    private void stateBroadcastLoop() {
        System.out.println("[Server] State broadcast loop started.");
        while (gameStarted && !serverSocket.isClosed()) {
            if (!gameStates.isEmpty()) {
                GamePacket state = new GamePacket();
                state.type       = GamePacket.TYPE_STATE;
                state.players    = gameStates.values()
                        .toArray(new GamePacket.PlayerState[0]);
                broadcast(state);
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        System.out.println("[Server] State broadcast loop ended.");
    }

    // ── Apply player input ────────────────────────────────────────
    public synchronized void applyInput(int playerId, GamePacket input) {
        GamePacket.PlayerState state = gameStates.get(playerId);
        if (state == null || !state.alive) return;

        float speed = 3.5f;
        if (input.moveUp)    state.y -= speed;
        if (input.moveDown)  state.y += speed;
        if (input.moveLeft)  { state.x -= speed; state.facingRight = false; }
        if (input.moveRight) { state.x += speed; state.facingRight = true;  }

        // Clamp to reasonable bounds
        state.x = Math.max(50, Math.min(1200, state.x));
        state.y = Math.max(50, Math.min(750,  state.y));
    }

    // ── Player disconnect ─────────────────────────────────────────
    public synchronized void playerLeft(int playerId) {
        ClientHandler ch = clients.remove(playerId);
        gameStates.remove(playerId);
        String name = ch != null ? ch.username : "Player " + playerId;
        System.out.println("[Server] " + name + " left. Remaining: " + clients.size());

        if (clients.isEmpty() && gameStarted) {
            endGame();
        } else {
            broadcastLobbyUpdate();
        }
    }

    // ── End game ──────────────────────────────────────────────────
    public synchronized void endGame() {
        if (!gameStarted) return;
        gameStarted = false;
        System.out.println("[Server] Game ending...");

        if (dbAvailable && matchId > 0) {
            for (GamePacket.PlayerState ps : gameStates.values()) {
                db.savePlayerStats(matchId, -1, ps.playerId,
                        ps.score, ps.kills, 1, ps.alive);
            }
            db.endMatch(matchId);
            System.out.println("[Server] Match #" + matchId + " saved to DB.");
        }

        GamePacket over = new GamePacket();
        over.type    = GamePacket.TYPE_GAME_OVER;
        over.players = gameStates.values().toArray(new GamePacket.PlayerState[0]);
        broadcast(over);
    }

    // ── Lobby update ──────────────────────────────────────────────
    public void broadcastLobbyUpdate() {
        GamePacket pkt  = new GamePacket();
        pkt.type        = GamePacket.TYPE_LOBBY_UPDATE;

        // Build player state list from connected clients
        List<GamePacket.PlayerState> list = new ArrayList<>();
        for (ClientHandler ch : clients.values()) {
            list.add(new GamePacket.PlayerState(
                    ch.playerId, ch.username,
                    0, 0, 100, 100, 0, 0, true, true, 1));
        }
        pkt.players = list.toArray(new GamePacket.PlayerState[0]);
        pkt.message = "Players: " + clients.size() + "/" + MAX_PLAYERS +
                (clients.size() == 1 ? "  [Waiting for more...]"
                        : "  [Host: press START to begin]");

        broadcast(pkt);
        System.out.println("[Server] Lobby update: " + clients.size() + " players.");
    }

    // ── Broadcast to all ─────────────────────────────────────────
    public void broadcast(GamePacket packet) {
        for (ClientHandler ch : clients.values()) {
            ch.send(packet);
        }
    }

    public boolean isGameStarted()                          { return gameStarted; }
    public Map<Integer, ClientHandler> getClients()         { return clients; }
}
