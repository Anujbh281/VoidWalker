import java.io.*;
import java.net.*;

/**
 * ClientHandler.java
 * One instance runs on the server for each connected player.
 * Reads packets from that player and acts on them.
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
            // Must create OutputStream FIRST, then InputStream
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in  = new ObjectInputStream(socket.getInputStream());

            System.out.println("[Server] Player " + playerId +
                    " connected from " + socket.getInetAddress());

            // Read packets in a loop
            while (!socket.isClosed()) {
                Object obj = in.readObject();
                if (!(obj instanceof GamePacket)) continue;
                GamePacket pkt = (GamePacket) obj;
                handlePacket(pkt);
            }

        } catch (EOFException | SocketException e) {
            // Client disconnected normally
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Server] Error with player " + playerId + ": " + e.getMessage());
        } finally {
            server.playerLeft(playerId);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePacket(GamePacket pkt) {
        switch (pkt.type) {

            case GamePacket.TYPE_JOIN -> {
                // Player introducing themselves
                this.username = pkt.username != null ? pkt.username : "Player" + playerId;

                // Send welcome acknowledgement
                GamePacket ack  = new GamePacket();
                ack.type        = GamePacket.TYPE_JOIN_ACK;
                ack.playerId    = playerId;
                ack.username    = this.username;
                ack.message     = "Welcome, " + this.username + "! You are Player " + playerId + ".";
                send(ack);

                System.out.println("[Server] " + username + " joined as Player " + playerId);
                server.broadcastLobbyUpdate();
            }

            case GamePacket.TYPE_INPUT -> {
                // Apply movement/actions
                if (server.isGameStarted()) {
                    server.applyInput(playerId, pkt);
                }
            }

            case GamePacket.TYPE_CHAT -> {
                // Relay chat to all
                pkt.playerId = this.playerId;
                pkt.username = this.username;
                server.broadcast(pkt);
                System.out.println("[Chat] " + username + ": " + pkt.message);
            }

            case GamePacket.TYPE_PING -> {
                // Respond to keepalive
                send(GamePacket.ping());
            }

            // Host-only: start game command sent as a chat "/start"
            // (Player 1 = host)
            default -> {
                if (pkt.message != null && pkt.message.startsWith("/start")
                        && playerId == 1 && !server.isGameStarted()) {
                    String mode = pkt.message.contains("endless") ? "endless" : "story";
                    server.startGame(mode);
                }
            }
        }
    }

    /** Thread-safe send. */
    public synchronized void send(GamePacket packet) {
        try {
            out.writeObject(packet);
            out.flush();
            out.reset(); // important: prevents stale cached objects
        } catch (IOException e) {
            // Client likely disconnected — will be caught in run() loop
        }
    }
}
