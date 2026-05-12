import java.io.Serializable;

/**
 * GamePacket.java
 * All data sent between server and clients uses this class.
 * Must be on BOTH server and client sides.
 *
 * Implements Serializable so Java can send it over sockets automatically.
 */
public class GamePacket implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Packet types ─────────────────────────────────────────
    public static final String TYPE_JOIN         = "JOIN";         // client → server: join lobby
    public static final String TYPE_JOIN_ACK     = "JOIN_ACK";     // server → client: welcome + your ID
    public static final String TYPE_LOBBY_UPDATE = "LOBBY_UPDATE"; // server → all: who is connected
    public static final String TYPE_START        = "START";        // server → all: game is starting
    public static final String TYPE_INPUT        = "INPUT";        // client → server: player input
    public static final String TYPE_STATE        = "STATE";        // server → all: full game state
    public static final String TYPE_CHAT         = "CHAT";         // either direction: chat message
    public static final String TYPE_GAME_OVER    = "GAME_OVER";    // server → all: game ended
    public static final String TYPE_PING         = "PING";         // keepalive
    public static final String TYPE_ERROR        = "ERROR";        // server → client: error message

    // ── Fields ───────────────────────────────────────────────
    public String  type;           // one of the TYPE_ constants above
    public int     playerId;       // which player this is from/to (1–4)
    public String  username;       // player's display name
    public String  message;        // generic text message / error text

    // ── Input fields (TYPE_INPUT) ─────────────────────────────
    public boolean moveUp, moveDown, moveLeft, moveRight;
    public boolean shooting, dashing, ability;
    public float   mouseX, mouseY;   // aim direction in world coords

    // ── Player state (inside TYPE_STATE) ─────────────────────
    public PlayerState[] players;   // all players' states

    // ── Match info ────────────────────────────────────────────
    public String gameMode;         // "story" or "endless"
    public int    waveNumber;       // endless mode wave
    public int    floorNumber;      // story mode floor

    // ── Constructor helpers ───────────────────────────────────
    public static GamePacket join(String username) {
        GamePacket p  = new GamePacket();
        p.type        = TYPE_JOIN;
        p.username    = username;
        return p;
    }

    public static GamePacket input(int playerId,
                                   boolean up, boolean down,
                                   boolean left, boolean right,
                                   boolean shoot, boolean dash,
                                   boolean ability,
                                   float mx, float my) {
        GamePacket p  = new GamePacket();
        p.type        = TYPE_INPUT;
        p.playerId    = playerId;
        p.moveUp      = up;   p.moveDown  = down;
        p.moveLeft    = left; p.moveRight = right;
        p.shooting    = shoot; p.dashing  = dash;
        p.ability     = ability;
        p.mouseX      = mx;   p.mouseY   = my;
        return p;
    }

    public static GamePacket chat(int playerId, String username, String msg) {
        GamePacket p  = new GamePacket();
        p.type        = TYPE_CHAT;
        p.playerId    = playerId;
        p.username    = username;
        p.message     = msg;
        return p;
    }

    public static GamePacket ping() {
        GamePacket p = new GamePacket();
        p.type       = TYPE_PING;
        return p;
    }

    // ── PlayerState inner class ───────────────────────────────
    /**
     * Snapshot of one player's state — sent in every STATE packet.
     */
    public static class PlayerState implements Serializable {
        private static final long serialVersionUID = 1L;

        public int     playerId;
        public String  username;
        public float   x, y;           // world position
        public int     hp, maxHp;
        public int     score;
        public int     kills;
        public boolean alive;
        public boolean facingRight;
        public int     weaponLevel;

        public PlayerState() {}

        public PlayerState(int id, String name, float x, float y,
                           int hp, int maxHp, int score, int kills,
                           boolean alive, boolean facingRight, int weaponLevel) {
            this.playerId    = id;
            this.username    = name;
            this.x           = x;   this.y     = y;
            this.hp          = hp;  this.maxHp = maxHp;
            this.score       = score;
            this.kills       = kills;
            this.alive       = alive;
            this.facingRight = facingRight;
            this.weaponLevel = weaponLevel;
        }
    }

    @Override
    public String toString() {
        return "[Packet:" + type + " player=" + playerId +
                (message != null ? " msg=" + message : "") + "]";
    }
}
