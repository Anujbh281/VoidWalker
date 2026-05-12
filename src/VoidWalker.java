import javax.swing.*;
import java.awt.*;

/**
 * VOIDWALKER: Chronicles of the Shattered Realm
 * Entry point — shows Login Screen first, then the game.
 */
public class VoidWalker extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            VoidWalker game = new VoidWalker();
            game.setVisible(true);
        });
    }

    private GamePanel         gamePanel;
    private DatabaseManager   db;

    // Logged-in player info (accessible from anywhere)
    public static int    currentUserId   = -1;   // -1 = guest
    public static String currentUsername = "Guest";
    public static int    onlineHighScore = 0;

    public VoidWalker() {
        setTitle("VOIDWALKER: Chronicles of the Shattered Realm");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        // Try to connect to MySQL via WAMP
        db = new DatabaseManager();
        boolean dbOk = db.connect();

        if (dbOk) {
            showLoginScreen();  // DB available — show login
        } else {
            System.out.println("[VoidWalker] No database — starting as guest.");
            startGame();        // No DB — skip login, play as guest
        }
    }

    // ── Login Screen ─────────────────────────────────────────
    private void showLoginScreen() {
        setPreferredSize(new Dimension(960, 640));

        LoginScreen loginScreen = new LoginScreen(db, result -> {
            if (result.isGuest) {
                currentUserId   = -1;
                currentUsername = result.guestName;
            } else {
                currentUserId   = result.user.id;
                currentUsername = result.user.username;
                onlineHighScore = result.user.highScore;
                System.out.println("[VoidWalker] Logged in: " +
                        currentUsername + "  Best: " + onlineHighScore);
            }
            SwingUtilities.invokeLater(this::startGame);
        });

        getContentPane().removeAll();
        getContentPane().add(loginScreen);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Start Game ───────────────────────────────────────────
    private void startGame() {
        gamePanel            = new GamePanel();
        gamePanel.ownerFrame = this;
        gamePanel.db         = db;

        // Merge online high score with local save
        if (onlineHighScore > 0 && gamePanel.saveData != null) {
            gamePanel.saveData.highScore = Math.max(
                    gamePanel.saveData.highScore, onlineHighScore);
        }

        getContentPane().removeAll();
        getContentPane().add(gamePanel);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        gamePanel.requestFocusInWindow();
        gamePanel.startGame();
    }
}
