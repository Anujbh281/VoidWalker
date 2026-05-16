import javax.swing.*;
import java.awt.Component;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/**
 * LobbyScreen.java
 * Shows who is connected and lets the host start the game.
 * Works for both hosting and joining.
 *
 * USAGE:
 *   LobbyScreen lobby = new LobbyScreen(client, isHost, username);
 *   lobby.setOnGameStart(() -> switchToGameScreen());
 *   // Show it in your JFrame
 */
public class LobbyScreen extends JPanel {

    private static final Color BG        = new Color(8,   6,  18);
    private static final Color PANEL_BG  = new Color(15, 12, 30, 220);
    private static final Color ACCENT    = new Color(120, 80, 255);
    private static final Color TEXT      = new Color(200, 190, 255);
    private static final Color GOLD      = new Color(255, 200,  60);
    private static final Color GREEN     = new Color( 80, 220, 120);

    private final GameClient  client;
    private final boolean     isHost;
    private final String      username;

    // UI
    private JPanel  playerListPanel;
    private JLabel  statusLabel;
    private JLabel  ipLabel;
    private JButton startBtn;
    private JTextArea chatArea;
    private JTextField chatField;

    // Callback when game actually starts
    private Runnable onGameStart;

    // ── Constructor ───────────────────────────────────────────
    public LobbyScreen(GameClient client, boolean isHost, String username) {
        this.client   = client;
        this.isHost   = isHost;
        this.username = username;
        buildUI();
        setupCallbacks();
    }

    public void setOnGameStart(Runnable r) { this.onGameStart = r; }

    // ── Build UI ──────────────────────────────────────────────
    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        // ── TOP: title + IP ───────────────────────────────────
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JLabel title = label("VOIDWALKER  —  LOBBY", 22, ACCENT);
        String role  = isHost ? "HOST" : "GUEST";
        JLabel roleL = label("[" + role + "] " + username, 13, GOLD);
        top.add(title, BorderLayout.WEST);
        top.add(roleL, BorderLayout.EAST);

        // Show local IP so others know where to connect
        ipLabel = label("Your IP: " + getLocalIP() +
                "   |   Port: " + GameClient.PORT, 11, new Color(150,140,200));
        JPanel topBox = new JPanel(new BorderLayout(0,4));
        topBox.setOpaque(false);
        topBox.add(top,     BorderLayout.NORTH);
        topBox.add(ipLabel, BorderLayout.SOUTH);

        // ── CENTRE: players + chat ────────────────────────────
        JPanel centre = new JPanel(new GridLayout(1, 2, 16, 0));
        centre.setOpaque(false);

        // Left: player slots
        JPanel leftBox = darkPanel("PLAYERS (0/4)");
        playerListPanel = new JPanel(new GridLayout(4, 1, 0, 8));
        playerListPanel.setOpaque(false);
        for (int i = 1; i <= 4; i++) playerListPanel.add(emptySlot(i));
        leftBox.add(playerListPanel, BorderLayout.CENTER);

        // Right: chat
        JPanel rightBox = darkPanel("CHAT");
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setBackground(new Color(10, 8, 20));
        chatArea.setForeground(new Color(180, 170, 220));
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setBorder(null);
        scroll.setBackground(new Color(10, 8, 20));

        chatField = new JTextField();
        chatField.setBackground(new Color(20, 15, 40));
        chatField.setForeground(Color.WHITE);
        chatField.setCaretColor(Color.WHITE);
        chatField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatField.setBorder(BorderFactory.createLineBorder(new Color(80, 60, 140)));
        chatField.setToolTipText("Press ENTER to send");
        chatField.addActionListener(e -> sendChat());

        rightBox.add(scroll,    BorderLayout.CENTER);
        rightBox.add(chatField, BorderLayout.SOUTH);

        centre.add(leftBox);
        centre.add(rightBox);

        // ── BOTTOM: status + buttons ──────────────────────────
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setOpaque(false);

        statusLabel = label("Waiting for players...", 12, TEXT);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);

        if (isHost) {
            // Mode buttons
            JButton storyBtn   = actionButton("START  STORY",   new Color(60, 40, 140));
            JButton endlessBtn = actionButton("START  ENDLESS", new Color(40, 80, 40));
            storyBtn.addActionListener(e   -> hostStart("story"));
            endlessBtn.addActionListener(e -> hostStart("endless"));
            startBtn = storyBtn;
            btnRow.add(endlessBtn);
            btnRow.add(storyBtn);
        }

        JButton leaveBtn = actionButton("LEAVE", new Color(80, 20, 20));
        leaveBtn.addActionListener(e -> {
            client.disconnect();
            // Return to menu — handled by parent
        });
        btnRow.add(leaveBtn);

        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btnRow,      BorderLayout.EAST);

        // Assemble
        add(topBox,  BorderLayout.NORTH);
        add(centre,  BorderLayout.CENTER);
        add(bottom,  BorderLayout.SOUTH);

        appendChat("System", "Welcome to the lobby! Share your IP with friends.");
        if (isHost) appendChat("System", "Press START when everyone is ready.");
        else        appendChat("System", "Waiting for the host to start...");
    }

    // ── Wire up GameClient callbacks ──────────────────────────
    private void setupCallbacks() {
        client.setOnLobbyUpdate(pkt -> SwingUtilities.invokeLater(() -> {
            updatePlayerList(pkt);
            if (pkt.message != null) statusLabel.setText(pkt.message);
        }));

        client.setOnChatReceived(pkt -> SwingUtilities.invokeLater(() ->
                appendChat(pkt.username, pkt.message)
        ));

        client.setOnGameStart(pkt -> SwingUtilities.invokeLater(() -> {
            appendChat("System", "Game starting! Mode: " + pkt.gameMode);
            if (onGameStart != null) onGameStart.run();
        }));

        client.setOnError(msg -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(255, 80, 80));
            statusLabel.setText("Error: " + msg);
        }));
    }

    // ── Update player slots ───────────────────────────────────
    private void updatePlayerList(GamePacket pkt) {
        playerListPanel.removeAll();
        GamePacket.PlayerState[] players = pkt.players;

        int count = players != null ? players.length : 0;

        // Update title safely
        try {
            ((TitledPanel) playerListPanel.getParent().getParent())
                    .setTitle("PLAYERS (" + count + "/4)");
        } catch (Exception ignored) {}

        for (int i = 0; i < 4; i++) {
            if (players != null && i < players.length) {
                GamePacket.PlayerState p = players[i];
                boolean isMe = p.playerId == client.getPlayerId();
                playerListPanel.add(filledSlot(p.playerId, p.username, isMe));
            } else {
                playerListPanel.add(emptySlot(i + 1));
            }
        }
        playerListPanel.revalidate();
        playerListPanel.repaint();
    }

    // ── Send chat ─────────────────────────────────────────────
    private void sendChat() {
        String msg = chatField.getText().trim();
        if (msg.isEmpty()) return;
        client.sendChat(msg);
        appendChat(username, msg);
        chatField.setText("");
    }

    private void appendChat(String who, String msg) {
        chatArea.append("[" + who + "] " + msg + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // ── Host starts game ──────────────────────────────────────
    private void hostStart(String mode) {
        if (!client.isConnected()) {
            statusLabel.setForeground(new Color(255, 80, 80));
            statusLabel.setText("Not connected to server!");
            return;
        }
        if (client.getPlayerId() != 1) {
            statusLabel.setForeground(new Color(255, 80, 80));
            statusLabel.setText("Only the host (Player 1) can start!");
            return;
        }
        System.out.println("[Lobby] Host sending start request: " + mode);
        statusLabel.setForeground(new Color(80, 255, 130));
        statusLabel.setText("Starting " + mode + " game...");
        // Disable both start buttons
        for (Component c : ((JPanel)statusLabel.getParent()
                .getComponent(1)).getComponents()) {
            if (c instanceof JButton) c.setEnabled(false);
        }
        client.hostStartGame(mode);
    }

    // ── Helpers ───────────────────────────────────────────────
    private JPanel emptySlot(int num) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(15, 12, 28));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 40, 80), 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        JLabel l = label("Slot " + num + "  —  Empty", 13, new Color(80, 70, 120));
        p.add(l);
        return p;
    }

    private JPanel filledSlot(int id, String name, boolean isMe) {
        JPanel p = new JPanel(new BorderLayout());
        Color border = isMe ? ACCENT : GREEN;
        p.setBackground(new Color(20, 16, 36));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, isMe ? 2 : 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        String tag = (id == 1 ? " [HOST]" : "") + (isMe ? " [YOU]" : "");
        JLabel l = label("P" + id + "  " + name + tag, 13, isMe ? GOLD : TEXT);
        p.add(l);
        return p;
    }

    private JPanel darkPanel(String title) {
        TitledPanel p = new TitledPanel(title);
        p.setLayout(new BorderLayout(0, 8));
        p.setBackground(PANEL_BG);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 50, 100), 1),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        return p;
    }

    private JLabel label(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.BOLD, size));
        l.setForeground(color);
        return l;
    }

    private JButton actionButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Monospaced", Font.BOLD, 13));
        b.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private String getLocalIP() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) { return "127.0.0.1"; }
    }

    // ── TitledPanel helper ────────────────────────────────────
    static class TitledPanel extends JPanel {
        private String title;
        TitledPanel(String t) { this.title = t; setOpaque(true); }
        void setTitle(String t) { this.title = t; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(new Color(120, 80, 255));
            g.setFont(new Font("Monospaced", Font.BOLD, 11));
            g.drawString(title, 12, 14);
            g.setColor(new Color(60, 50, 100));
            g.drawLine(12, 18, getWidth() - 12, 18);
        }
    }
}
