import java.sql.*;

/**
 * DatabaseManager — XAMPP/MySQL integration.
 *
 * XAMPP DEFAULT CREDENTIALS:
 *   Host     : localhost
 *   Port     : 3306
 *   Username : root
 *   Password : (empty string)
 *
 * AUTO-SETUP:
 *   - Creates database 'voidwalker_db' if missing
 *   - Creates all tables if missing
 *   - No manual SQL import needed
 *
 * USAGE:
 *   DatabaseManager db = new DatabaseManager();
 *   db.connect();
 *   db.updateHighScore(userId, score);
 */
public class DatabaseManager {

    // ── XAMPP credentials ─────────────────────────────────────────
    private static final String HOST     = "localhost";
    private static final int    PORT     = 3306;
    private static final String DB_NAME  = "voidwalker_db";
    private static final String USER     = "root";
    private static final String PASSWORD = "";           // XAMPP default: empty

    private static final String URL_NO_DB = "jdbc:mysql://" + HOST + ":" + PORT
            + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String URL       = "jdbc:mysql://" + HOST + ":" + PORT
            + "/" + DB_NAME
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private Connection conn = null;

    // ─────────────────────────────────────────────────────────────
    // Wrapper methods for VoidWalker compatibility
    // ─────────────────────────────────────────────────────────────

    public static class UserRecord {
        public int userId;
        public String username;
        public int highScore;

        public UserRecord(int userId, String username, int highScore) {
            this.userId = userId;
            this.username = username;
            this.highScore = highScore;
        }
    }

    public boolean registerUser(String username, String password) {
        int result = register(username, password);
        return result > 0;  // true if successful
    }

    public UserRecord loginUser(String username, String password) {
        int userId = login(username, password);
        if (userId > 0) {
            return new UserRecord(userId, username, getHighScore(userId));
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Connect — creates DB + tables automatically
    // ─────────────────────────────────────────────────────────────
    public boolean connect() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");

            // Step 1: connect without DB to create it if needed
            try (Connection tmp = DriverManager.getConnection(URL_NO_DB, USER, PASSWORD);
                 Statement  st  = tmp.createStatement()) {
                st.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB_NAME
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                System.out.println("[DB] Database '" + DB_NAME + "' ready.");
            }

            // Step 2: connect to the database
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
            System.out.println("[DB] Connected to MySQL via XAMPP.");

            createTables();
            return true;

        } catch (ClassNotFoundException e) {
            System.err.println("[DB] mysql-connector-j.jar not found on classpath!");
            System.err.println("[DB] Place it in: project_root/lib/mysql-connector-j.jar");
        } catch (SQLException e) {
            System.err.println("[DB] Connection failed: " + e.getMessage());
            System.err.println("[DB] Make sure XAMPP MySQL is running on port 3306.");
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Create tables
    // ─────────────────────────────────────────────────────────────
    private void createTables() {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {

            // Users / player profiles
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    id         INT AUTO_INCREMENT PRIMARY KEY,
                    username   VARCHAR(50) UNIQUE NOT NULL,
                    password   VARCHAR(255) NOT NULL,
                    high_score INT DEFAULT 0,
                    total_kills INT DEFAULT 0,
                    total_games INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
            """);

            // Multiplayer match history
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS mp_matches (
                    id          INT AUTO_INCREMENT PRIMARY KEY,
                    mode        VARCHAR(20) NOT NULL,
                    player_count INT NOT NULL,
                    wave_reached INT DEFAULT 0,
                    played_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
            """);

            // Per-player stats for each MP match
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS mp_player_stats (
                    id         INT AUTO_INCREMENT PRIMARY KEY,
                    match_id   INT NOT NULL,
                    user_id    INT NOT NULL,
                    score      INT DEFAULT 0,
                    kills      INT DEFAULT 0,
                    deaths     INT DEFAULT 0,
                    FOREIGN KEY (match_id) REFERENCES mp_matches(id)
                ) ENGINE=InnoDB
            """);

            // Settings per user
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_settings (
                    user_id    INT PRIMARY KEY,
                    quality    VARCHAR(10) DEFAULT 'HIGH',
                    fullscreen TINYINT DEFAULT 0,
                    volume     FLOAT DEFAULT 1.0,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                ) ENGINE=InnoDB
            """);

            System.out.println("[DB] Tables verified/created.");

        } catch (SQLException e) {
            System.err.println("[DB] Table creation error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Authentication
    // ─────────────────────────────────────────────────────────────

    /** Register new user. Returns user ID or -1 on failure, -2 if username taken. */
    public int register(String username, String password) {
        if (conn == null) return -1;
        try {
            // Check if username taken
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM users WHERE username=?")) {
                ps.setString(1, username);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return -2; // username taken
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, hashPassword(password));
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    int id = keys.getInt(1);
                    // Create default settings row
                    try (PreparedStatement ps2 = conn.prepareStatement(
                            "INSERT IGNORE INTO user_settings (user_id) VALUES (?)")) {
                        ps2.setInt(1, id);
                        ps2.executeUpdate();
                    }
                    System.out.println("[DB] Registered: " + username + " (id=" + id + ")");
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Register error: " + e.getMessage());
        }
        return -1;
    }

    /** Login. Returns user ID or -1 if credentials wrong. */
    public int login(String username, String password) {
        if (conn == null) return -1;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, password FROM users WHERE username=?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String stored = rs.getString("password");
                if (stored.equals(hashPassword(password))) {
                    return rs.getInt("id");
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Login error: " + e.getMessage());
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────

    public void updateHighScore(int userId, int score) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET high_score = GREATEST(high_score, ?) WHERE id=?")) {
            ps.setInt(1, score);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] updateHighScore: " + e.getMessage());
        }
    }

    public void incrementKills(int userId, int kills) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET total_kills = total_kills + ? WHERE id=?")) {
            ps.setInt(1, kills);
            ps.setInt(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] incrementKills: " + e.getMessage());
        }
    }

    public int getHighScore(int userId) {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT high_score FROM users WHERE id=?")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("high_score");
        } catch (SQLException e) {
            System.err.println("[DB] getHighScore: " + e.getMessage());
        }
        return 0;
    }

    /** Save a multiplayer match and per-player stats. */
    public void saveMpMatch(String mode, int playerCount, int waveReached,
                            int[] userIds, int[] scores, int[] kills) {
        if (conn == null) return;
        try {
            int matchId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO mp_matches (mode, player_count, wave_reached) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, mode);
                ps.setInt(2, playerCount);
                ps.setInt(3, waveReached);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (!keys.next()) return;
                matchId = keys.getInt(1);
            }
            for (int i = 0; i < userIds.length; i++) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO mp_player_stats (match_id,user_id,score,kills) VALUES (?,?,?,?)")) {
                    ps.setInt(1, matchId);
                    ps.setInt(2, userIds[i]);
                    ps.setInt(3, i < scores.length ? scores[i] : 0);
                    ps.setInt(4, i < kills.length  ? kills[i]  : 0);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] saveMpMatch: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    public boolean isConnected() {
        try { return conn != null && !conn.isClosed(); }
        catch (SQLException e) { return false; }
    }

    public void close() {
        try { if (conn != null) conn.close(); }
        catch (SQLException ignored) {}
    }

    /** Simple SHA-256 hash — production would use BCrypt. */
    private String hashPassword(String pw) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(pw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return pw; } // fallback
    }
}