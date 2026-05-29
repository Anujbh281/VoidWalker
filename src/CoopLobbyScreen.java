import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * CoopLobbyScreen — Pre-game lobby for co-op multiplayer.
 *
 * Shows connected players, chat, and host start controls.
 * Uses CoopGameClient v2 public-field callbacks (no setters).
 */
public class CoopLobbyScreen extends JPanel {

    private static final Color BG       = new Color(8,   6,  18);
    private static final Color PANEL_BG = new Color(14, 11,  28, 225);
    private static final Color ACCENT   = new Color(110, 70, 255);
    private static final Color TEXT     = new Color(200, 190, 255);
    private static final Color GOLD     = new Color(255, 200,  55);
    private static final Color GREEN    = new Color( 75, 220, 120);

    private final CoopGameClient client;
    private final boolean        isHost;
    private final String         username;

    private JPanel    playerListPanel;
    private JLabel    statusLabel;
    private JTextArea chatArea;
    private JTextField chatField;
    private Runnable  onGameStart;

    public CoopLobbyScreen(CoopGameClient client, boolean isHost, String username) {
        this.client   = client;
        this.isHost   = isHost;
        this.username = username;
        buildUI();
        wireCallbacks();
    }

    public void setOnGameStart(Runnable r) { this.onGameStart = r; }

    // ── Build UI ──────────────────────────────────────────────────
    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(18, 28, 18, 28));

        // ── TOP ──────────────────────────────────────────────────
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel title = lbl("VOIDWALKER  ─  CO-OP LOBBY", 21, ACCENT);
        JLabel role  = lbl("[" + (isHost ? "HOST" : "GUEST") + "]  " + username, 12, GOLD);
        top.add(title, BorderLayout.WEST);
        top.add(role,  BorderLayout.EAST);

        JLabel ipLine = lbl("Your IP: " + getLocalIP()
                        + "   │   Port: " + CoopGameClient.PORT, 10,
                new Color(145, 135, 195));
        JPanel topBox = new JPanel(new BorderLayout(0, 3));
        topBox.setOpaque(false);
        topBox.add(top,    BorderLayout.NORTH);
        topBox.add(ipLine, BorderLayout.SOUTH);

        // ── CENTRE ───────────────────────────────────────────────
        JPanel centre = new JPanel(new GridLayout(1, 2, 14, 0));
        centre.setOpaque(false);

        // Left: player slots
        JPanel leftBox  = darkPanel("PLAYERS  (0 / 4)");
        playerListPanel = new JPanel(new GridLayout(4, 1, 0, 7));
        playerListPanel.setOpaque(false);
        for (int i = 1; i <= 4; i++) playerListPanel.add(emptySlot(i));
        leftBox.add(playerListPanel, BorderLayout.CENTER);

        // Right: chat
        JPanel rightBox = darkPanel("CHAT");
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setBackground(new Color(9, 7, 18));
        chatArea.setForeground(new Color(175, 165, 215));
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setBorder(null);
        scroll.setBackground(chatArea.getBackground());

        chatField = new JTextField();
        chatField.setBackground(new Color(18, 14, 36));
        chatField.setForeground(Color.WHITE);
        chatField.setCaretColor(Color.WHITE);
        chatField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatField.setBorder(BorderFactory.createLineBorder(new Color(75, 55, 130)));
        chatField.setToolTipText("Press ENTER to send");
        chatField.addActionListener(e -> sendChat());

        rightBox.add(scroll,    BorderLayout.CENTER);
        rightBox.add(chatField, BorderLayout.SOUTH);

        centre.add(leftBox);
        centre.add(rightBox);

        // ── BOTTOM ───────────────────────────────────────────────
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setOpaque(false);
        statusLabel = lbl("Waiting for players...", 12, TEXT);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);

        if (isHost) {
            JButton storyBtn   = actionBtn("▶  START  STORY",   new Color(50, 35, 130));
            JButton endlessBtn = actionBtn("▶  START  ENDLESS", new Color(30, 75,  40));
            storyBtn.addActionListener(e   -> hostStart("story"));
            endlessBtn.addActionListener(e -> hostStart("endless"));
            btnRow.add(endlessBtn);
            btnRow.add(storyBtn);
        }

        JButton leaveBtn = actionBtn("LEAVE", new Color(80, 20, 20));
        leaveBtn.addActionListener(e -> {
            client.disconnect();
            Container p = getParent();
            if (p != null) { p.remove(this); p.revalidate(); p.repaint(); }
        });
        btnRow.add(leaveBtn);

        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btnRow,      BorderLayout.EAST);

        add(topBox,  BorderLayout.NORTH);
        add(centre,  BorderLayout.CENTER);
        add(bottom,  BorderLayout.SOUTH);

        appendChat("System", "Welcome! Share your IP with friends to play co-op.");
        if (isHost) appendChat("System", "Press START when everyone is ready.");
        else        appendChat("System", "Waiting for the host to start...");
    }

    // ── Wire CoopGameClient callbacks ─────────────────────────────
    private void wireCallbacks() {
        // Lobby update — player list changed
        client.onLobbyUpdate = pkt -> SwingUtilities.invokeLater(() -> {
            updatePlayerList(pkt);
            if (pkt.message != null) statusLabel.setText(pkt.message);
        });

        // Chat message received
        client.onChat = pkt -> SwingUtilities.invokeLater(() ->
                appendChat(pkt.username != null ? pkt.username : "?", pkt.message)
        );

        // Game starting
        client.onGameStart = pkt -> SwingUtilities.invokeLater(() -> {
            appendChat("System", "Game starting! Mode: " + pkt.gameMode);
            if (onGameStart != null) onGameStart.run();
        });

        // Error
        client.onError = pkt -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(220, 60, 60));
            statusLabel.setText("Error: " + pkt.message);
        });

        // Disconnected
        client.onDisconnected = () -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(220, 60, 60));
            statusLabel.setText("Disconnected from server.");
        });
    }

    // ── Update player list ────────────────────────────────────────
    private void updatePlayerList(GamePacket pkt) {
        playerListPanel.removeAll();
        GamePacket.PlayerState[] players = pkt.players;
        int count = players != null ? players.length : 0;

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

        // Update panel title with count
        Component parent = playerListPanel.getParent();
        if (parent instanceof TitledPanel tp)
            tp.setTitle("PLAYERS  (" + count + " / 4)");
    }

    // ── Send chat ─────────────────────────────────────────────────
    private void sendChat() {
        String msg = chatField.getText().trim();
        if (msg.isEmpty()) return;
        GamePacket pkt  = new GamePacket();
        pkt.type        = GamePacket.TYPE_CHAT;
        pkt.username    = username;
        pkt.message     = msg;
        client.send(pkt);
        appendChat(username, msg);
        chatField.setText("");
    }

    private void appendChat(String who, String msg) {
        chatArea.append("[" + who + "] " + msg + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // ── Host start ────────────────────────────────────────────────
    private void hostStart(String mode) {
        if (!client.isConnected()) {
            statusLabel.setForeground(new Color(220, 60, 60));
            statusLabel.setText("Not connected!"); return;
        }
        if (client.getPlayerId() != 1) {
            statusLabel.setForeground(new Color(220, 60, 60));
            statusLabel.setText("Only the host can start!"); return;
        }
        statusLabel.setForeground(GREEN);
        statusLabel.setText("Starting " + mode + "...");
        client.hostStartGame(mode);
    }

    // ── UI helpers ────────────────────────────────────────────────
    private JPanel emptySlot(int num) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(14, 11, 26));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(48, 38, 76), 1),
                BorderFactory.createEmptyBorder(7, 11, 7, 11)));
        p.add(lbl("Slot " + num + "  —  Empty", 12, new Color(72, 62, 110)));
        return p;
    }

    private JPanel filledSlot(int id, String name, boolean isMe) {
        JPanel p = new JPanel(new BorderLayout());
        Color  border = isMe ? ACCENT : GREEN;
        p.setBackground(new Color(18, 15, 34));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, isMe ? 2 : 1),
                BorderFactory.createEmptyBorder(7, 11, 7, 11)));
        String tag = (id == 1 ? " [HOST]" : "") + (isMe ? " [YOU]" : "");
        p.add(lbl("P" + id + "  " + name + tag, 12, isMe ? GOLD : TEXT));
        return p;
    }

    private JPanel darkPanel(String title) {
        TitledPanel p = new TitledPanel(title);
        p.setLayout(new BorderLayout(0, 7));
        p.setBackground(PANEL_BG);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(58, 47, 96), 1),
                BorderFactory.createEmptyBorder(11, 11, 11, 11)));
        return p;
    }

    private JLabel lbl(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.BOLD, size));
        l.setForeground(color);
        return l;
    }

    private JButton actionBtn(String text, Color bg) {
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
        try { return java.net.InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { return "127.0.0.1"; }
    }

    // ── TitledPanel ───────────────────────────────────────────────
    static class TitledPanel extends JPanel {
        private String title;
        TitledPanel(String t) { this.title = t; setOpaque(true); }
        void setTitle(String t) { this.title = t; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(new Color(110, 70, 255));
            g.setFont(new Font("Monospaced", Font.BOLD, 11));
            g.drawString(title, 11, 13);
            g.setColor(new Color(58, 47, 96));
            g.drawLine(11, 17, getWidth() - 11, 17);
        }
    }
}
