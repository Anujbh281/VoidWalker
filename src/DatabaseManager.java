import java.sql.*;

/**
 * DatabaseManager.java
 * Handles all MySQL database operations for VoidWalker.
 * Uses WAMP default credentials (root, no password).
 *
 * REQUIRES: mysql-connector-j-X.X.X.jar in your project
 * Download: https://dev.mysql.com/downloads/connector/j/
 */
public class DatabaseManager {

    // ── WAMP default credentials ──────────────────────────────
    private static final String HOST = "localhost";
    private static final int    PORT = 3306;
    private static final String DB   = "voidwalker_db";
    private static final String USER = "root";
    private static final String PASS = "";   // WAMP default = empty

    private static final String URL =
            "jdbc:mysql://" + HOST + ":" + PORT + "/" + DB +
                    "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

    private Connection connection;

    // ── Connect to MySQL ─────────────────────────────────────
    public boolean connect() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(URL, USER, PASS);
            System.out.println("[DB] Connected to MySQL successfully.");
            return true;
        } catch (ClassNotFoundException e) {
            System.err.println("[DB] ERROR: MySQL JDBC Driver not found.");
            System.err.println("     Download mysql-connector-j from:");
            System.err.println("     https://dev.mysql.com/downloads/connector/j/");
            return false;
        } catch (SQLException e) {
            System.err.println("[DB] ERROR: Cannot connect to MySQL.");
            System.err.println("     Make sure WAMP is running and MySQL is started.");
            System.err.println("     Details: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("[DB] Disconnected from MySQL.");
            }
        } catch (SQLException ignored) {}
    }

    public boolean isConnected() {
        try { return connection != null && !connection.isClosed(); }
        catch (SQLException e) { return false; }
    }

    // ════════════════════════════════════════════════════════
    //  USER OPERATIONS
    // ════════════════════════════════════════════════════════

    /**
     * Register a new user.
     * @return "ok" on success, error message on failure
     */
    public String registerUser(String username, String password) {
        if (username == null || username.trim().length() < 3)
            return "Username must be at least 3 characters.";
        if (password == null || password.length() < 4)
            return "Password must be at least 4 characters.";
        if (!username.matches("[a-zA-Z0-9_]+"))
            return "Username can only contain letters, numbers, underscores.";

        try {
            // Check if username taken
            PreparedStatement check = connection.prepareStatement(
                    "SELECT id FROM users WHERE username = ?");
            check.setString(1, username.trim());
            if (check.executeQuery().next())
                return "Username already taken.";

            // Insert new user (simple hash for demo — use BCrypt in production)
            PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)");
            insert.setString(1, username.trim());
            insert.setString(2, simpleHash(password));
            insert.executeUpdate();
            return "ok";

        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }

    /**
     * Login a user.
     * @return UserRecord on success, null on failure
     */
    public UserRecord loginUser(String username, String password) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                    "SELECT id, username, high_score FROM users " +
                            "WHERE username = ? AND password = ?");
            stmt.setString(1, username.trim());
            stmt.setString(2, simpleHash(password));
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) return null;

            UserRecord user = new UserRecord();
            user.id        = rs.getInt("id");
            user.username  = rs.getString("username");
            user.highScore = rs.getInt("high_score");

            // Update last login
            PreparedStatement upd = connection.prepareStatement(
                    "UPDATE users SET last_login = NOW() WHERE id = ?");
            upd.setInt(1, user.id);
            upd.executeUpdate();

            return user;
        } catch (SQLException e) {
            System.err.println("[DB] Login error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Update a user's high score (only if new score is higher).
     */
    public void updateHighScore(int userId, int score) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE users SET high_score = ? " +
                            "WHERE id = ? AND high_score < ?");
            stmt.setInt(1, score);
            stmt.setInt(2, userId);
            stmt.setInt(3, score);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] High score update error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    //  MATCH OPERATIONS
    // ════════════════════════════════════════════════════════

    /**
     * Create a new match record and return its ID.
     */
    public int createMatch(int hostUserId, int playerCount, String mode) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO matches (host_user_id, player_count, mode) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            if (hostUserId > 0) stmt.setInt(1, hostUserId);
            else                stmt.setNull(1, Types.INTEGER);
            stmt.setInt(2, playerCount);
            stmt.setString(3, mode);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;
        } catch (SQLException e) {
            System.err.println("[DB] Create match error: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Mark a match as ended.
     */
    public void endMatch(int matchId) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE matches SET ended_at = NOW() WHERE id = ?");
            stmt.setInt(1, matchId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] End match error: " + e.getMessage());
        }
    }

    /**
     * Save a player's stats for a match.
     */
    public void savePlayerStats(int matchId, int userId, int slot,
                                int score, int kills, int floors, boolean survived) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO player_stats " +
                            "(match_id, user_id, player_slot, score, kills, floors, survived) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)");
            stmt.setInt(1, matchId);
            if (userId > 0) stmt.setInt(2, userId);
            else            stmt.setNull(2, Types.INTEGER);
            stmt.setInt(3, slot);
            stmt.setInt(4, score);
            stmt.setInt(5, kills);
            stmt.setInt(6, floors);
            stmt.setBoolean(7, survived);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Save stats error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    //  LEADERBOARD
    // ════════════════════════════════════════════════════════

    /**
     * Get top 10 leaderboard entries.
     */
    public LeaderboardEntry[] getLeaderboard() {
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs   = stmt.executeQuery("SELECT * FROM leaderboard");
            java.util.List<LeaderboardEntry> list = new java.util.ArrayList<>();
            int rank = 1;
            while (rs.next()) {
                LeaderboardEntry e = new LeaderboardEntry();
                e.rank      = rank++;
                e.username  = rs.getString("username");
                e.highScore = rs.getInt("high_score");
                list.add(e);
            }
            return list.toArray(new LeaderboardEntry[0]);
        } catch (SQLException e) {
            System.err.println("[DB] Leaderboard error: " + e.getMessage());
            return new LeaderboardEntry[0];
        }
    }

    // ════════════════════════════════════════════════════════
    //  HELPER CLASSES
    // ════════════════════════════════════════════════════════

    public static class UserRecord {
        public int    id;
        public String username;
        public int    highScore;
        @Override public String toString() {
            return username + " (Best: " + highScore + ")";
        }
    }

    public static class LeaderboardEntry {
        public int    rank;
        public String username;
        public int    highScore;
        @Override public String toString() {
            return String.format("#%d  %-20s  %06d", rank, username, highScore);
        }
    }

    // ── Very simple password hash (XOR + hex) ────────────────
    // NOTE: For a real project use BCrypt. This is for simplicity.
    private String simpleHash(String input) {
        StringBuilder sb  = new StringBuilder();
        String        key = "VW2024";
        for (int i = 0; i < input.length(); i++) {
            sb.append(String.format("%02x",
                    (input.charAt(i) ^ key.charAt(i % key.length())) & 0xFF));
        }
        return sb.toString();
    }
}
