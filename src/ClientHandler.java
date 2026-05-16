import java.io.*;
import java.net.*;

/**
 * ClientHandler.java
 * One instance runs on the server for each connected player.
 * FIXED:
 *  - START command now uses its own packet type (TYPE_START_REQUEST)
 *  - JOIN_ACK sent before lobby update so client is ready to receive
 *  - Proper logging of all events
 */
public class ClientHandler implements Runnable {

    public  final int         playerId;
    public        String      username  = "Player";
    private final Socket      socket;
    private final GameServer  server;

    private ObjectOutputStream out;
    private ObjectInputStream  in;

    public ClientHandler(Socket socket, int playerId, GameServer server) {
        this.socket   = socket;
        this.playerId = playerId;
        this.server   = server;
    }

    @Override
    public void run() {
        try {
            // OutputStream FIRST — critical for Java object streams
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in  = new ObjectInputStream(socket.getInputStream());

            System.out.println("[Server] Player " + playerId +
                    " connected from " + socket.getInetAddress());

            while (!socket.isClosed()) {
                Object obj = in.readObject();
                if (!(obj instanceof GamePacket)) continue;
                handlePacket((GamePacket) obj);
            }

        } catch (EOFException | SocketException e) {
            System.out.println("[Server] Player " + playerId + " disconnected.");
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Server] Error with player " + playerId +
                    ": " + e.getMessage());
        } finally {
            server.playerLeft(playerId);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePacket(GamePacket pkt) {
        switch (pkt.type) {

            // ── Player joins lobby ────────────────────────────────
            case GamePacket.TYPE_JOIN -> {
                this.username = (pkt.username != null && !pkt.username.isBlank())
                        ? pkt.username : "Player" + playerId;

                System.out.println("[Server] '" + username +
                        "' joined as Player " + playerId);

                // Send ACK first so client knows its ID
                GamePacket ack = new GamePacket();
                ack.type       = GamePacket.TYPE_JOIN_ACK;
                ack.playerId   = playerId;
                ack.username   = this.username;
                ack.message    = "Welcome " + this.username +
                        "! You are Player " + playerId + ".";
                send(ack);

                // NOW broadcast updated player list to everyone
                server.broadcastLobbyUpdate();
            }

            // ── Player input during game ──────────────────────────
            case GamePacket.TYPE_INPUT -> {
                if (server.isGameStarted()) {
                    server.applyInput(playerId, pkt);
                }
            }

            // ── Chat message ──────────────────────────────────────
            case GamePacket.TYPE_CHAT -> {
                pkt.playerId = this.playerId;
                pkt.username = this.username;
                server.broadcast(pkt);
                System.out.println("[Chat] " + username + ": " + pkt.message);
            }

            // ── Host requests game start ──────────────────────────
            case "START_REQUEST" -> {
                if (playerId != 1) {
                    GamePacket err = new GamePacket();
                    err.type    = GamePacket.TYPE_ERROR;
                    err.message = "Only the host (Player 1) can start the game.";
                    send(err);
                    return;
                }
                if (server.isGameStarted()) {
                    GamePacket err = new GamePacket();
                    err.type    = GamePacket.TYPE_ERROR;
                    err.message = "Game already started.";
                    send(err);
                    return;
                }
                String mode = (pkt.gameMode != null &&
                        pkt.gameMode.equals("endless")) ? "endless" : "story";
                System.out.println("[Server] Host requested start: mode=" + mode);
                server.startGame(mode);
            }

            // ── Keepalive ─────────────────────────────────────────
            case GamePacket.TYPE_PING -> {
                send(GamePacket.ping());
            }

            default -> {
                // Ignore unknown packet types silently
            }
        }
    }

    /** Thread-safe send to this client. */
    public synchronized void send(GamePacket packet) {
        try {
            out.writeObject(packet);
            out.flush();
            out.reset(); // prevents stale cached objects
        } catch (IOException e) {
            // Client disconnected — run() loop will catch it
        }
    }
}
