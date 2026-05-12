import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * GameServer.java
 * The host runs this. Accepts up to 4 players, manages lobby,
 * syncs game state, saves results to MySQL.
 *
 * HOW TO RUN:
 *   javac -cp ".;mysql-connector-j-9.x.x.jar" GameServer.java GamePacket.java DatabaseManager.java
 *   java  -cp ".;mysql-connector-j-9.x.x.jar" GameServer
 */
public class GameServer {

    public static final int    PORT       = 55555;
    public static final int    MAX_PLAYERS = 4;

    // ── State ────────────────────────────────────────────────
    private ServerSocket                         serverSocket;
    private final Map<Integer, ClientHandler>    clients    = new ConcurrentHashMap<>();
    private final Map<Integer, GamePacket.PlayerState> gameStates = new ConcurrentHashMap<>();
    private       boolean                        gameStarted = false;
    private       int                            nextPlayerId = 1;
    private       int                            matchId      = -1;
    private       String                         gameMode     = "story";

    // ── Database ─────────────────────────────────────────────
    private final DatabaseManager db = new DatabaseManager();
    private       boolean         dbAvailable = false;

    // ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        GameServer server = new GameServer();
        server.start();
    }

    public void start() {
        // Try to connect to database
        dbAvailable = db.connect();
        if (!dbAvailable)
            System.out.println("[Server] Running WITHOUT database (offline mode).");

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("╔══════════════════════════════════╗");
            System.out.println("║   VOIDWALKER GAME SERVER v1.0    ║");
            System.out.println("╠══════════════════════════════════╣");
            System.out.println("║ Port    : " + PORT                      + "                  ║");
            System.out.println("║ Players : max " + MAX_PLAYERS            + "                     ║");
            System.out.println("║ DB      : " + (dbAvailable?"ONLINE":"OFFLINE") + "                ║");
            System.out.println("╚══════════════════════════════════╝");
            System.out.println("Waiting for players to connect...\n");

            // Accept loop
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                if (gameStarted) {
                    // Reject latecomers
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    GamePacket err = new GamePacket();
                    err.type    = GamePacket.TYPE_ERROR;
                    err.message = "Game already in progress.";
                    out.writeObject(err);
                    socket.close();
                    continue;
                }
                if (clients.size() >= MAX_PLAYERS) {
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    GamePacket err = new GamePacket();
                    err.type    = GamePacket.TYPE_ERROR;
                    err.message = "Server is full (max " + MAX_PLAYERS + " players).";
                    out.writeObject(err);
                    socket.close();
                    continue;
                }
                int id = nextPlayerId++;
                ClientHandler handler = new ClientHandler(socket, id, this);
                clients.put(id, handler);
                new Thread(handler, "Player-" + id).start();
            }

        } catch (IOException e) {
            if (!serverSocket.isClosed())
                System.err.println("[Server] Fatal error: " + e.getMessage());
        } finally {
            db.disconnect();
        }
    }

    // ── Called by host to start the game ─────────────────────
    public synchronized void startGame(String mode) {
        if (gameStarted || clients.isEmpty()) return;
        gameStarted = true;
        gameMode    = mode;

        // Create match record in DB
        if (dbAvailable) {
            matchId = db.createMatch(-1, clients.size(), mode);
            System.out.println("[Server] Match #" + matchId + " started (" + mode + ")");
        }

        // Initialise player states
        float[] spawnX = {200, 300, 200, 300};
        float[] spawnY = {200, 200, 300, 300};
        for (ClientHandler ch : clients.values()) {
            int idx = ch.playerId - 1;
            gameStates.put(ch.playerId, new GamePacket.PlayerState(
                    ch.playerId, ch.username,
                    spawnX[idx], spawnY[idx],
                    100, 100, 0, 0, true, true, 1
            ));
        }

        // Broadcast START to all
        GamePacket start  = new GamePacket();
        start.type        = GamePacket.TYPE_START;
        start.gameMode    = mode;
        start.players     = gameStates.values().toArray(new GamePacket.PlayerState[0]);
        broadcast(start);

        System.out.println("[Server] Game started! Mode: " + mode);

        // Start state broadcast loop (20 times per second)
        new Thread(this::stateBroadcastLoop, "StateBroadcast").start();
    }

    // ── Broadcast game state 20× per second ──────────────────
    private void stateBroadcastLoop() {
        while (gameStarted && !serverSocket.isClosed()) {
            GamePacket state   = new GamePacket();
            state.type         = GamePacket.TYPE_STATE;
            state.players      = gameStates.values().toArray(new GamePacket.PlayerState[0]);
            broadcast(state);
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
    }

    // ── Apply input from a client ─────────────────────────────
    public synchronized void applyInput(int playerId, GamePacket input) {
        GamePacket.PlayerState state = gameStates.get(playerId);
        if (state == null || !state.alive) return;

        float speed = 3.5f;
        if (input.moveUp)    state.y -= speed;
        if (input.moveDown)  state.y += speed;
        if (input.moveLeft)  { state.x -= speed; state.facingRight = false; }
        if (input.moveRight) { state.x += speed; state.facingRight = true; }

        // Clamp to arena bounds (rough estimate)
        state.x = Math.max(20, Math.min(1100, state.x));
        state.y = Math.max(20, Math.min(700,  state.y));
    }

    // ── Player disconnected ───────────────────────────────────
    public synchronized void playerLeft(int playerId) {
        clients.remove(playerId);
        gameStates.remove(playerId);
        System.out.println("[Server] Player " + playerId + " disconnected. (" + clients.size() + " remaining)");
        broadcastLobbyUpdate();

        if (gameStarted && clients.isEmpty()) {
            endGame();
        }
    }

    // ── Game over ─────────────────────────────────────────────
    public synchronized void endGame() {
        if (!gameStarted) return;
        gameStarted = false;

        // Save stats to DB
        if (dbAvailable && matchId > 0) {
            for (GamePacket.PlayerState ps : gameStates.values()) {
                db.savePlayerStats(matchId, -1, ps.playerId,
                        ps.score, ps.kills, 1, ps.alive);
                db.updateHighScore(-1, ps.score);
            }
            db.endMatch(matchId);
            System.out.println("[Server] Match #" + matchId + " saved to database.");
        }

        GamePacket over = new GamePacket();
        over.type       = GamePacket.TYPE_GAME_OVER;
        over.players    = gameStates.values().toArray(new GamePacket.PlayerState[0]);
        broadcast(over);
        System.out.println("[Server] Game over. Results saved.");
    }

    // ── Send lobby update to all clients ─────────────────────
    public void broadcastLobbyUpdate() {
        GamePacket pkt = new GamePacket();
        pkt.type    = GamePacket.TYPE_LOBBY_UPDATE;
        pkt.players = clients.values().stream()
                .map(ch -> new GamePacket.PlayerState(
                        ch.playerId, ch.username, 0, 0, 100, 100, 0, 0, true, true, 1))
                .toArray(GamePacket.PlayerState[]::new);
        pkt.message = "Players: " + clients.size() + "/" + MAX_PLAYERS +
                (clients.size() == 1 ? "  (Waiting for more...)" :
                        "  (Host: press ENTER to start)");
        broadcast(pkt);
    }

    // ── Send to all connected clients ────────────────────────
    public void broadcast(GamePacket packet) {
        for (ClientHandler ch : clients.values()) {
            ch.send(packet);
        }
    }

    public boolean isGameStarted() { return gameStarted; }
    public Map<Integer, ClientHandler> getClients() { return clients; }
}
