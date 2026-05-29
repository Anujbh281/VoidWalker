import java.io.*;
import java.net.*;

/**
 * CoopClientHandler — One thread per connected player on the server.
 *
 * RESPONSIBILITIES:
 *   - Receive INPUT packets from one client
 *   - Forward to CoopGameServer.applyInput()
 *   - Send STATE/LOBBY/START packets back
 *   - Per-player bullet rate limiting (NOT shared)
 */
public class CoopClientHandler implements Runnable {

    public  final int        playerId;
    public        String     username  = "Player";
    private final Socket     socket;
    private final CoopGameServer server;

    private ObjectOutputStream out;
    private ObjectInputStream  in;
    private volatile boolean   connected = true;

    // Per-player bullet rate limiter
    private long lastBulletMs           = 0L;
    private static final long BULLET_INTERVAL = 95L; // ~10/sec max

    public CoopClientHandler(Socket socket, int playerId, CoopGameServer server) {
        this.socket   = socket;
        this.playerId = playerId;
        this.server   = server;
    }

    @Override
    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in  = new ObjectInputStream(socket.getInputStream());

            while (connected && !socket.isClosed()) {
                Object obj = in.readObject();
                if (obj instanceof GamePacket pkt) handlePacket(pkt);
            }
        } catch (EOFException | SocketException ignored) {
            // Normal disconnect
        } catch (IOException | ClassNotFoundException e) {
            if (connected) System.err.println("[Handler-" + playerId + "] " + e.getMessage());
        } finally {
            connected = false;
            server.playerDisconnected(playerId);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePacket(GamePacket pkt) {
        switch (pkt.type) {

            case GamePacket.TYPE_JOIN -> {
                username = (pkt.username != null && !pkt.username.isBlank())
                        ? pkt.username.trim() : "Player" + playerId;
                System.out.println("[Handler-" + playerId + "] JOIN as: " + username);

                // Send ACK
                GamePacket ack  = new GamePacket();
                ack.type        = GamePacket.TYPE_JOIN_ACK;
                ack.playerId    = playerId;
                ack.username    = username;
                ack.message     = "Welcome " + username + "! You are Player " + playerId;
                send(ack);

                server.broadcastLobbyUpdate();
            }

            case GamePacket.TYPE_INPUT -> {
                if (!server.isGameStarted()) return;

                // Per-player shoot rate limit
                if (pkt.shooting) {
                    long now = System.currentTimeMillis();
                    if (now - lastBulletMs < BULLET_INTERVAL) {
                        pkt.shooting = false;
                    } else {
                        lastBulletMs = now;
                    }
                }

                server.applyInput(playerId, pkt);
            }

            case GamePacket.TYPE_START_REQUEST -> {
                if (playerId != 1) {
                    send(GamePacket.error("Only the host can start."));
                    return;
                }
                String mode = "endless".equals(pkt.gameMode) ? "endless" : "story";
                System.out.println("[Handler-" + playerId + "] START_REQUEST mode=" + mode);
                server.startGame(mode);
            }

            case GamePacket.TYPE_CHAT -> {
                pkt.playerId = this.playerId;
                pkt.username = this.username;
                server.broadcast(pkt);
            }

            case GamePacket.TYPE_PING -> send(GamePacket.ping());
        }
    }

    /** Thread-safe send with reset() to prevent object cache growth. */
    public synchronized void send(GamePacket packet) {
        if (!connected) return;
        try {
            out.writeObject(packet);
            out.flush();
            out.reset(); // CRITICAL: prevents memory leak
        } catch (IOException e) {
            connected = false;
        }
    }

    public boolean isConnected() { return connected; }
}
