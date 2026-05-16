import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/**
 * GameClient.java
 * Each player (including the host) runs this to connect to the server.
 * Runs network I/O on a background thread — never blocks the game loop.
 *
 * USAGE IN YOUR GAME:
 *   GameClient client = new GameClient();
 *   client.setOnStateReceived(packet -> { // update your game with packet.players });
 *   client.setOnLobbyUpdate(packet  -> { // update lobby UI });
 *   client.setOnGameStart(packet    -> { // switch to game screen });
 *   client.setOnGameOver(packet     -> { // show results screen });
 *   client.setOnError(msg           -> { // show error to user });
 *   client.connect("localhost", "Anuj");   // or LAN IP
 */
public class GameClient {

    public static final int PORT = 55555;

    // ── Connection ────────────────────────────────────────────
    private Socket             socket;
    private ObjectOutputStream out;
    private ObjectInputStream  in;
    private boolean            connected   = false;
    private int                myPlayerId  = -1;
    private String             myUsername  = "Player";

    // ── Callbacks (set from your game code) ───────────────────
    private Consumer<GamePacket>   onStateReceived;
    private Consumer<GamePacket>   onLobbyUpdate;
    private Consumer<GamePacket>   onGameStart;
    private Consumer<GamePacket>   onGameOver;
    private Consumer<GamePacket>   onChatReceived;
    private Consumer<String>       onError;
    private Consumer<String>       onConnected;

    // ── User account (optional — set if player is logged in) ──
    public  DatabaseManager.UserRecord loggedInUser = null;

    // ─────────────────────────────────────────────────────────
    //  CONNECT
    // ─────────────────────────────────────────────────────────

    /**
     * Connect to a game server.
     * @param host "localhost" for same PC, or "192.168.x.x" for LAN
     * @param username display name shown to other players
     */
    public void connect(String host, String username) {
        this.myUsername = username;
        new Thread(() -> {
            try {
                System.out.println("[Client] Connecting to " + host + ":" + PORT + "...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, PORT), 5000); // 5s timeout

                // Must create OutputStream first
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in  = new ObjectInputStream(socket.getInputStream());

                connected = true;
                System.out.println("[Client] Connected!");

                // Send JOIN packet
                send(GamePacket.join(username));

                // Start reading packets
                receiveLoop();

            } catch (ConnectException e) {
                notifyError("Could not connect to " + host + ":" + PORT +
                        ". Is the server running?");
            } catch (SocketTimeoutException e) {
                notifyError("Connection timed out after 5s. Check the IP: " + host);
            } catch (IOException e) {
                if (connected) notifyError("Connection lost: " + e.getMessage());
                else           notifyError("Connection failed: " + e.getMessage());
            }
        }, "VW-Network").start();
    }

    // ── Receive loop (background thread) ─────────────────────
    private void receiveLoop() {
        try {
            while (connected && !socket.isClosed()) {
                Object obj = in.readObject();
                if (!(obj instanceof GamePacket)) continue;
                GamePacket pkt = (GamePacket) obj;
                handlePacket(pkt);
            }
        } catch (EOFException | SocketException e) {
            notifyError("Disconnected from server.");
        } catch (IOException | ClassNotFoundException e) {
            notifyError("Network error: " + e.getMessage());
        } finally {
            connected = false;
        }
    }

    private void handlePacket(GamePacket pkt) {
        switch (pkt.type) {

            case GamePacket.TYPE_JOIN_ACK -> {
                myPlayerId = pkt.playerId;
                System.out.println("[Client] Assigned Player ID: " + myPlayerId);
                System.out.println("[Client] Server says: " + pkt.message);
                if (onConnected != null)
                    onConnected.accept("Player " + myPlayerId + ": " + pkt.message);
            }

            case GamePacket.TYPE_LOBBY_UPDATE -> {
                System.out.println("[Lobby] " + pkt.message);
                if (pkt.players != null) {
                    for (GamePacket.PlayerState ps : pkt.players) {
                        System.out.println("[Lobby]   P" + ps.playerId +
                                " = " + ps.username);
                    }
                }
                if (onLobbyUpdate != null) onLobbyUpdate.accept(pkt);
            }

            case GamePacket.TYPE_START -> {
                System.out.println("[Client] Game starting! Mode: " + pkt.gameMode);
                if (onGameStart != null) onGameStart.accept(pkt);
            }

            case GamePacket.TYPE_STATE -> {
                if (onStateReceived != null) onStateReceived.accept(pkt);
            }

            case GamePacket.TYPE_CHAT -> {
                System.out.println("[Chat] " + pkt.username + ": " + pkt.message);
                if (onChatReceived != null) onChatReceived.accept(pkt);
            }

            case GamePacket.TYPE_GAME_OVER -> {
                System.out.println("[Client] Game over!");
                if (onGameOver != null) onGameOver.accept(pkt);
            }

            case GamePacket.TYPE_ERROR -> {
                notifyError(pkt.message);
            }

            case GamePacket.TYPE_PING -> {
                send(GamePacket.ping()); // pong
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  SEND METHODS (call from your game loop)
    // ─────────────────────────────────────────────────────────

    /**
     * Send player movement and action inputs.
     * Call this every game frame (or every 50ms).
     */
    public void sendInput(boolean up, boolean down, boolean left, boolean right,
                          boolean shoot, boolean dash, boolean ability,
                          float mouseX, float mouseY) {
        if (!connected) return;
        send(GamePacket.input(myPlayerId, up, down, left, right,
                shoot, dash, ability, mouseX, mouseY));
    }

    /**
     * Send a chat message.
     */
    public void sendChat(String message) {
        if (!connected || message == null || message.isBlank()) return;
        send(GamePacket.chat(myPlayerId, myUsername, message));
    }

    /**
     * Host only: tell server to start the game.
     * @param mode "story" or "endless"
     */
    public void hostStartGame(String mode) {
        if (!connected) {
            System.err.println("[Client] Cannot start — not connected.");
            return;
        }
        GamePacket pkt = new GamePacket();
        pkt.type     = "START_REQUEST";  // matches ClientHandler case
        pkt.gameMode = mode;
        pkt.playerId = myPlayerId;
        send(pkt);
        System.out.println("[Client] Sent START_REQUEST mode=" + mode);
    }

    /** Disconnect cleanly. */
    public void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ── Thread-safe send ──────────────────────────────────────
    private synchronized void send(GamePacket packet) {
        try {
            out.writeObject(packet);
            out.flush();
            out.reset();
        } catch (IOException e) {
            connected = false;
        }
    }

    private void notifyError(String msg) {
        System.err.println("[Client] " + msg);
        if (onError != null) onError.accept(msg);
    }

    // ─────────────────────────────────────────────────────────
    //  GETTERS / SETTERS
    // ─────────────────────────────────────────────────────────
    public boolean isConnected()  { return connected; }
    public int     getPlayerId()  { return myPlayerId; }
    public String  getUsername()  { return myUsername; }

    public void setOnStateReceived(Consumer<GamePacket> cb)  { onStateReceived = cb; }
    public void setOnLobbyUpdate  (Consumer<GamePacket> cb)  { onLobbyUpdate   = cb; }
    public void setOnGameStart    (Consumer<GamePacket> cb)  { onGameStart     = cb; }
    public void setOnGameOver     (Consumer<GamePacket> cb)  { onGameOver      = cb; }
    public void setOnChatReceived (Consumer<GamePacket> cb)  { onChatReceived  = cb; }
    public void setOnError        (Consumer<String>     cb)  { onError         = cb; }
    public void setOnConnected    (Consumer<String>     cb)  { onConnected     = cb; }
}
