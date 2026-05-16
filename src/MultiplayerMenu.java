import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * MultiplayerMenu.java
 * The screen that appears when player clicks "MULTIPLAYER" in the main menu.
 * Lets them choose to Host or Join, then transitions to LobbyScreen.
 *
 * HOW TO ADD TO YOUR GAME (MenuSystem.java):
 *   1. Add a "MULTIPLAYER" button to mainButtons in MenuSystem
 *   2. In GamePanel.updateMenu(), when that button is clicked:
 *        MultiplayerMenu mpMenu = new MultiplayerMenu(db, frame, onStart);
 *        // show mpMenu in your JFrame
 */
public class MultiplayerMenu extends JPanel {

    private static final Color BG     = new Color(8,   6, 18);
    private static final Color ACCENT = new Color(120, 80, 255);
    private static final Color TEXT   = new Color(200, 190, 255);

    private final DatabaseManager         db;
    private final JFrame                  frame;
    private final Runnable                onGameStart;
    private       GameClient              client;

    public MultiplayerMenu(DatabaseManager db, JFrame frame, Runnable onGameStart) {
        this.db          = db;
        this.frame       = frame;
        this.onGameStart = onGameStart;
        buildUI();
    }

    private void buildUI() {
        setLayout(new GridBagLayout());
        setBackground(BG);

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(new Color(15, 12, 30, 230));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 2),
                BorderFactory.createEmptyBorder(30, 50, 30, 50)
        ));

        JLabel title = lbl("MULTIPLAYER", 24, ACCENT);
        title.setAlignmentX(CENTER_ALIGNMENT);
        JLabel sub = lbl("Up to 4 players — same PC or LAN", 12, TEXT);
        sub.setAlignmentX(CENTER_ALIGNMENT);

        // IP input (used when joining)
        JLabel ipLbl = lbl("Host IP Address:", 12, TEXT);
        ipLbl.setAlignmentX(LEFT_ALIGNMENT);
        JTextField ipField = new JTextField("localhost");
        styleField(ipField);

        // Username input
        JLabel nameLbl = lbl("Your Name:", 12, TEXT);
        nameLbl.setAlignmentX(LEFT_ALIGNMENT);
        JTextField nameField = new JTextField("Player");
        styleField(nameField);

        JLabel status = lbl("", 12, new Color(255, 80, 80));
        status.setAlignmentX(CENTER_ALIGNMENT);

        // Buttons
        JButton hostBtn = btn("HOST GAME",  new Color(60, 40, 140));
        JButton joinBtn = btn("JOIN GAME",  new Color(40, 80, 60));
        JButton backBtn = btn("BACK",       new Color(50, 30, 30));
        hostBtn.setAlignmentX(CENTER_ALIGNMENT);
        joinBtn.setAlignmentX(CENTER_ALIGNMENT);
        backBtn.setAlignmentX(CENTER_ALIGNMENT);

        // ── HOST GAME ─────────────────────────────────────────
        hostBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { status.setText("Enter your name."); return; }

            // Start server in background
            status.setForeground(new Color(80, 200, 120));
            status.setText("Starting server on port " + GameClient.PORT + "...");
            new Thread(() -> {
                GameServer server = new GameServer();
                new Thread(server::start, "VW-Server").start();
                // Give server a moment to bind
                try { Thread.sleep(600); } catch (InterruptedException ex) {}
                // Now connect as a client to our own server
                SwingUtilities.invokeLater(() -> {
                    status.setText("Server started! Connecting...");
                    connectClient("localhost", name, status, true);
                });
            }, "VW-ServerStart").start();
        });

        // ── JOIN GAME ─────────────────────────────────────────
        joinBtn.addActionListener(e -> {
            String ip   = ipField.getText().trim();
            String name = nameField.getText().trim();
            if (ip.isEmpty())   { status.setText("Enter host IP."); return; }
            if (name.isEmpty()) { status.setText("Enter your name."); return; }
            status.setForeground(TEXT);
            status.setText("Connecting to " + ip + "...");
            connectClient(ip, name, status, false);
        });

        backBtn.addActionListener(e -> {
            // Remove this panel and go back — parent handles this
            Container parent = getParent();
            if (parent != null) {
                parent.remove(this);
                parent.revalidate();
                parent.repaint();
            }
        });

        box.add(title);
        box.add(Box.createVerticalStrut(4));
        box.add(sub);
        box.add(Box.createVerticalStrut(24));
        box.add(nameLbl);
        box.add(Box.createVerticalStrut(4));
        box.add(nameField);
        box.add(Box.createVerticalStrut(12));
        box.add(ipLbl);
        box.add(Box.createVerticalStrut(4));
        box.add(ipField);
        box.add(Box.createVerticalStrut(6));
        box.add(status);
        box.add(Box.createVerticalStrut(20));
        box.add(hostBtn);
        box.add(Box.createVerticalStrut(8));
        box.add(joinBtn);
        box.add(Box.createVerticalStrut(8));
        box.add(backBtn);

        add(box);
    }

    private void connectClient(String ip, String name, JLabel status, boolean isHost) {
        client = new GameClient();

        client.setOnConnected(msg -> SwingUtilities.invokeLater(() -> {
            status.setForeground(new Color(80, 220, 120));
            status.setText("Connected! Opening lobby...");
            showLobby(isHost, name);
        }));

        client.setOnError(msg -> SwingUtilities.invokeLater(() -> {
            status.setForeground(new Color(255, 80, 80));
            status.setText("Error: " + msg);
        }));

        client.connect(ip, name);
    }

    private void showLobby(boolean isHost, String name) {
        LobbyScreen lobby = new LobbyScreen(client, isHost, name);

        // When server sends START packet, switch frame back to game
        lobby.setOnGameStart(() -> {
            System.out.println("[MultiplayerMenu] Game started — switching to GamePanel");
            // Find the GamePanel that was hidden when we opened multiplayer menu
            // by calling the original onGameStart callback which is in GamePanel
            javax.swing.SwingUtilities.invokeLater(() -> {
                // Remove lobby from frame
                frame.getContentPane().removeAll();

                // onGameStart was set in GamePanel.showMultiplayerMenu():
                //   frame.getContentPane().removeAll();
                //   frame.getContentPane().add(mp);  ← mp = this MultiplayerMenu
                // So we need to re-add the GamePanel.
                // The onGameStart runnable calls newGame() AND adds panel back.
                if (onGameStart != null) onGameStart.run();

                frame.revalidate();
                frame.repaint();
            });
        });

        frame.getContentPane().removeAll();
        frame.getContentPane().add(lobby);
        frame.revalidate();
        frame.repaint();
    }

    // ── Helpers ───────────────────────────────────────────────
    private JLabel lbl(String text, int size, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.BOLD, size));
        l.setForeground(c);
        return l;
    }

    private JTextField styleField(JTextField f) {
        f.setBackground(new Color(20, 15, 40));
        f.setForeground(new Color(220, 210, 255));
        f.setCaretColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 60, 140), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        f.setFont(new Font("Monospaced", Font.PLAIN, 13));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        f.setAlignmentX(LEFT_ALIGNMENT);
        return f;
    }

    private JButton btn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Monospaced", Font.BOLD, 13));
        b.setBorder(BorderFactory.createEmptyBorder(9, 20, 9, 20));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return b;
    }
}
