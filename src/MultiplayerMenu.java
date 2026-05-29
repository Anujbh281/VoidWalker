import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * MultiplayerMenu — Host/Join screen using CoopGameClient + CoopGameServer.
 *
 * FLOW:
 *   HOST  → starts CoopGameServer in background thread
 *           → connects CoopGameClient to localhost
 *           → shows LobbyScreen
 *
 *   JOIN  → connects CoopGameClient to entered IP
 *           → shows LobbyScreen
 *
 * On game start, calls onGameStart.onStart(client) passing the
 * connected CoopGameClient to GamePanel.
 */
public class MultiplayerMenu extends JPanel {

    private static final Color BG     = new Color(8,   6, 18);
    private static final Color ACCENT = new Color(110, 70, 255);
    private static final Color TEXT   = new Color(200, 190, 255);
    private static final Color GREEN  = new Color( 60, 200, 100);
    private static final Color RED    = new Color(220,  60,  60);

    public interface GameClientCallback {
        void onStart(CoopGameClient client);
    }

    private final DatabaseManager    db;
    private final JFrame             frame;
    private final GameClientCallback onGameStart;
    private       Runnable           onBack = null;
    private       CoopGameClient     client = null;

    public MultiplayerMenu(DatabaseManager db, JFrame frame,
                           GameClientCallback onGameStart) {
        this.db          = db;
        this.frame       = frame;
        this.onGameStart = onGameStart;
        buildUI();
    }

    public void setOnBack(Runnable r) { this.onBack = r; }

    private void buildUI() {
        setLayout(new GridBagLayout());
        setBackground(BG);

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(new Color(12, 10, 26, 235));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 2),
                BorderFactory.createEmptyBorder(30, 55, 30, 55)));

        // Title
        JLabel title = label("CO-OP MULTIPLAYER", 22, ACCENT);
        title.setAlignmentX(CENTER_ALIGNMENT);
        JLabel sub   = label("Up to 4 players — LAN / Same WiFi", 11, TEXT);
        sub.setAlignmentX(CENTER_ALIGNMENT);

        // Name field
        JLabel nameLbl = label("Your Name:", 12, TEXT);
        nameLbl.setAlignmentX(LEFT_ALIGNMENT);
        JTextField nameField = styledField("Player");

        // IP field
        JLabel ipLbl = label("Host IP (for joining):", 12, TEXT);
        ipLbl.setAlignmentX(LEFT_ALIGNMENT);
        JTextField ipField = styledField("192.168.x.x");

        // Status label
        JLabel status = label("", 12, RED);
        status.setAlignmentX(CENTER_ALIGNMENT);

        // Buttons
        JButton hostBtn = btn("HOST GAME",  new Color(50, 35, 130));
        JButton joinBtn = btn("JOIN GAME",  new Color(30, 75, 50));
        JButton backBtn = btn("BACK",       new Color(55, 25, 25));
        hostBtn.setAlignmentX(CENTER_ALIGNMENT);
        joinBtn.setAlignmentX(CENTER_ALIGNMENT);
        backBtn.setAlignmentX(CENTER_ALIGNMENT);

        // ── HOST ─────────────────────────────────────────────────
        hostBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { setStatus(status, "Enter your name.", RED); return; }
            setStatus(status, "Starting server...", GREEN);
            hostBtn.setEnabled(false);
            joinBtn.setEnabled(false);

            new Thread(() -> {
                // Launch server
                CoopGameServer server = new CoopGameServer();
                new Thread(server::start, "CoopServer").start();

                // Wait for server to bind
                try { Thread.sleep(700); } catch (InterruptedException ignored) {}

                SwingUtilities.invokeLater(() -> {
                    setStatus(status, "Server ready! Connecting...", GREEN);
                    connectAndShowLobby("localhost", name, status, true);
                });
            }, "ServerLauncher").start();
        });

        // ── JOIN ─────────────────────────────────────────────────
        joinBtn.addActionListener(e -> {
            String ip   = ipField.getText().trim();
            String name = nameField.getText().trim();
            if (ip.isEmpty())   { setStatus(status, "Enter host IP.",    RED); return; }
            if (name.isEmpty()) { setStatus(status, "Enter your name.",  RED); return; }
            setStatus(status, "Connecting to " + ip + "...", TEXT);
            hostBtn.setEnabled(false);
            joinBtn.setEnabled(false);
            connectAndShowLobby(ip, name, status, false);
        });

        // ── BACK ─────────────────────────────────────────────────
        backBtn.addActionListener(e -> {
            if (onBack != null) onBack.run();
            else {
                Container p = getParent();
                if (p != null) { p.remove(this); p.revalidate(); p.repaint(); }
            }
        });

        // Assemble
        box.add(title);              box.add(Box.createVerticalStrut(4));
        box.add(sub);                box.add(Box.createVerticalStrut(24));
        box.add(nameLbl);            box.add(Box.createVerticalStrut(4));
        box.add(nameField);          box.add(Box.createVerticalStrut(14));
        box.add(ipLbl);              box.add(Box.createVerticalStrut(4));
        box.add(ipField);            box.add(Box.createVerticalStrut(8));
        box.add(status);             box.add(Box.createVerticalStrut(20));
        box.add(hostBtn);            box.add(Box.createVerticalStrut(8));
        box.add(joinBtn);            box.add(Box.createVerticalStrut(8));
        box.add(backBtn);
        add(box);
    }

    private void connectAndShowLobby(String ip, String name,
                                     JLabel status, boolean isHost) {
        client = new CoopGameClient(ip, name);

        client.onConnected = () -> SwingUtilities.invokeLater(() -> {
            setStatus(status, "Connected! Opening lobby...", GREEN);
            showLobby(isHost);
        });

        client.onError = pkt -> SwingUtilities.invokeLater(() ->
                setStatus(status, "Error: " + pkt.message, RED)
        );

        client.connect();
    }

    private void showLobby(boolean isHost) {
        CoopLobbyScreen lobby = new CoopLobbyScreen(client, isHost, client.getUsername());

        lobby.setOnGameStart(() -> SwingUtilities.invokeLater(() -> {
            frame.getContentPane().removeAll();
            if (onGameStart != null) onGameStart.onStart(client);
            frame.revalidate();
            frame.repaint();
        }));

        frame.getContentPane().removeAll();
        frame.getContentPane().add(lobby);
        frame.revalidate();
        frame.repaint();
    }

    // ── Helpers ───────────────────────────────────────────────────
    private void setStatus(JLabel lbl, String text, Color c) {
        lbl.setText(text);
        lbl.setForeground(c);
    }

    private JLabel label(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.BOLD, size));
        l.setForeground(color);
        return l;
    }

    private JTextField styledField(String placeholder) {
        JTextField f = new JTextField(placeholder);
        f.setBackground(new Color(18, 14, 36));
        f.setForeground(new Color(215, 205, 255));
        f.setCaretColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(75, 55, 140), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        f.setFont(new Font("Monospaced", Font.PLAIN, 13));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        f.setAlignmentX(LEFT_ALIGNMENT);
        return f;
    }

    private JButton btn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Monospaced", Font.BOLD, 13));
        b.setBorder(BorderFactory.createEmptyBorder(9, 22, 9, 22));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        return b;
    }
}
