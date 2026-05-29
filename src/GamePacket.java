import java.io.Serializable;

/**
 * GamePacket — Complete packet system for VoidWalker co-op.
 *
 * DESIGN: One class, type-field discriminator.
 * Avoids per-class Java serialization overhead (~40% smaller packets).
 *
 * PACKET FLOW:
 *   Client → Server : JOIN, INPUT, PING, START_REQUEST, CHAT
 *   Server → Client : JOIN_ACK, LOBBY_UPDATE, START, STATE, CHAT, ERROR, GAME_OVER, LEVEL_CHANGE
 */
public class GamePacket implements Serializable {
    private static final long serialVersionUID = 4L;

    // ── Type constants ────────────────────────────────────────────
    public static final String TYPE_JOIN          = "JOIN";
    public static final String TYPE_JOIN_ACK      = "JOIN_ACK";
    public static final String TYPE_LOBBY_UPDATE  = "LOBBY_UPDATE";
    public static final String TYPE_START         = "START";
    public static final String TYPE_START_REQUEST = "START_REQUEST";
    public static final String TYPE_INPUT         = "INPUT";
    public static final String TYPE_STATE         = "STATE";
    public static final String TYPE_CHAT          = "CHAT";
    public static final String TYPE_PING          = "PING";
    public static final String TYPE_ERROR         = "ERROR";
    public static final String TYPE_GAME_OVER     = "GAME_OVER";
    public static final String TYPE_LEVEL_CHANGE  = "LEVEL_CHANGE";

    // ── Common fields ─────────────────────────────────────────────
    public String type      = "";
    public int    playerId  = 0;
    public String username  = "";
    public String message   = "";
    public String gameMode  = "story";
    public long   timestamp = 0L;
    public int    levelNum  = 1;

    // ── Input fields (Client → Server, sent 20x/sec) ─────────────
    public boolean moveUp      = false;
    public boolean moveDown    = false;
    public boolean moveLeft    = false;
    public boolean moveRight   = false;
    public boolean shooting    = false;
    public boolean dashing     = false;
    public float   aimAngle    = 0f;       // radians from Math.atan2
    public float   mouseWorldX = 0f;      // world-space mouse for crosshair
    public float   mouseWorldY = 0f;

    // ── World snapshot (Server → all clients, 20x/sec) ───────────
    public PlayerState[] players = null;
    public EnemyState[]  enemies = null;
    public BulletState[] bullets = null;
    public PickupState[] pickups = null;
    public int     wave          = 0;
    public boolean allDead       = false;
    public int     exitX         = 0;
    public int     exitY         = 0;

    // ── Factories ─────────────────────────────────────────────────
    public static GamePacket ping() {
        GamePacket p = new GamePacket();
        p.type      = TYPE_PING;
        p.timestamp = System.currentTimeMillis();
        return p;
    }
    public static GamePacket error(String msg) {
        GamePacket p = new GamePacket();
        p.type    = TYPE_ERROR;
        p.message = msg;
        return p;
    }

    // ══════════════════════════════════════════════════════════════
    // Nested state classes
    // ══════════════════════════════════════════════════════════════

    /**
     * PlayerState — full per-player snapshot every tick.
     * Includes aimAngle + mouseWorldX/Y so remote clients can
     * draw the correct crosshair for each player.
     */
    public static class PlayerState implements Serializable {
        private static final long serialVersionUID = 1L;

        public int     playerId;
        public String  username     = "";
        public float   x, y;
        public float   vx, vy;         // velocity for client interpolation
        public int     hp, maxHp;
        public int     score, kills;
        public boolean alive        = true;
        public boolean facingRight  = true;
        public int     weaponLevel  = 1;
        public boolean dashing      = false;
        public boolean shooting     = false;
        public float   aimAngle     = 0f;   // WHERE this player is aiming
        public float   mouseWorldX  = 0f;   // crosshair world position
        public float   mouseWorldY  = 0f;
        public int     playerLevel  = 1;
        public int     xp           = 0;

        public PlayerState() {}

        // Constructor for lobby (2 params)
        public PlayerState(int id, String name) {
            this.playerId = id;
            this.username = name;
            this.hp = 100;
            this.maxHp = 100;
            this.alive = true;
        }

        // Constructor for gameplay (6 params)
        public PlayerState(int id, String name, float x, float y, int hp, int maxHp) {
            this.playerId = id;
            this.username = name;
            this.x = x;
            this.y = y;
            this.hp = hp;
            this.maxHp = maxHp;
            this.alive = true;
            this.facingRight = true;
            this.weaponLevel = 1;
        }

        // FULL constructor for server use (11 params)
        public PlayerState(int playerId, String username,
                           float x, float y,
                           int hp, int maxHp,
                           int score, int kills,
                           boolean alive, boolean facingRight, int weaponLevel) {
            this.playerId = playerId;
            this.username = username;
            this.x = x;
            this.y = y;
            this.hp = hp;
            this.maxHp = maxHp;
            this.score = score;
            this.kills = kills;
            this.alive = alive;
            this.facingRight = facingRight;
            this.weaponLevel = weaponLevel;
            this.aimAngle = 0f;
            this.mouseWorldX = x;
            this.mouseWorldY = y;
            this.shooting = false;
            this.dashing = false;
        }
    }

    /**
     * EnemyState — compact enemy snapshot.
     * targetPlayerId lets clients show which enemy is chasing whom.
     */
    public static class EnemyState implements Serializable {
        private static final long serialVersionUID = 1L;

        public int     id;
        public float   x, y;
        public int     hp, maxHp;
        public boolean alive          = true;
        public int     typeOrd        = 0;   // EnemyType.ordinal()
        public boolean facingRight    = true;
        public boolean enraged        = false;
        public int     targetPlayerId = -1;

        public EnemyState() {}
    }

    /**
     * BulletState — active projectile.
     * Server ticks bullets; clients just render them.
     */
    public static class BulletState implements Serializable {
        private static final long serialVersionUID = 1L;

        public int     id;
        public float   x, y;
        public float   vx, vy;
        public int     ownerId     = -1; // playerId or -1 for enemy
        public int     r, g, b;
        public boolean active      = true;
        public boolean fromPlayer  = true;
        public int     damage      = 20;

        public BulletState() {}
    }

    /**
     * PickupState — shared pickup that disappears when ANY player collects it.
     */
    public static class PickupState implements Serializable {
        private static final long serialVersionUID = 1L;

        public int     id;
        public float   x, y;
        public String  pickupType = "health";
        public boolean collected  = false;

        public PickupState() {}
    }
}