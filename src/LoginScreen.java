import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * LoginScreen.java
 * A Swing screen that lets players log in, register, or play as guest.
 * Integrate this into your MenuSystem by showing it before the main menu.
 *
 * USAGE:
 *   LoginScreen screen = new LoginScreen(db, result -> {
 *       // result.user != null means logged in
 *       // result.isGuest = true means playing as guest
 *       startGame(result);
 *   });
 *   screen.show();
 */
public class LoginScreen extends JPanel {

    private static final Color BG       = new Color(8,   6,  18);
    private static final Color PANEL_BG = new Color(15, 12, 30, 230);
    private static final Color ACCENT   = new Color(120, 80, 255);
    private static final Color TEXT     = new Color(200, 190, 255);
    private static final Color ERROR    = new Color(255,  80,  80);
    private static final Color SUCCESS  = new Color( 80, 255, 130);

    private final DatabaseManager          db;
    private final java.util.function.Consumer<LoginResult> onResult;

    // ── UI Components ─────────────────────────────────────────
    private JTextField     usernameField;
    private JPasswordField passwordField;
    private JLabel         statusLabel;
    private JPanel         formPanel;
    private boolean        dbAvailable;

    // ── Result ────────────────────────────────────────────────
    public static class LoginResult {
        public DatabaseManager.UserRecord user;     // null if guest
        public boolean isGuest;
        public String  guestName;
    }

    public LoginScreen(DatabaseManager db,
                       java.util.function.Consumer<LoginResult> onResult) {
        this.db       = db;
        this.onResult = onResult;
        this.dbAvailable = db != null && db.isConnected();
        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout());
        setBackground(BG);
        setPreferredSize(new Dimension(960, 640));

        // Centre panel
        JPanel centre = new JPanel(new GridBagLayout());
        centre.setOpaque(false);

        formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.setBackground(PANEL_BG);
        formPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 2, true),
                BorderFactory.createEmptyBorder(30, 40, 30, 40)
        ));
        formPanel.setMaximumSize(new Dimension(380, 500));

        // Title
        JLabel title = styledLabel("VOIDWALKER", 28, new Color(180, 140, 255));
        title.setAlignmentX(CENTER_ALIGNMENT);
        JLabel sub   = styledLabel("Login to save your progress", 13, TEXT);
        sub.setAlignmentX(CENTER_ALIGNMENT);

        // Username
        JLabel userLbl = styledLabel("Username", 12, TEXT);
        userLbl.setAlignmentX(LEFT_ALIGNMENT);
        usernameField  = styledField();

        // Password
        JLabel passLbl = styledLabel("Password", 12, TEXT);
        passLbl.setAlignmentX(LEFT_ALIGNMENT);
        passwordField  = new JPasswordField();
        styleField(passwordField);

        // Status label
        statusLabel = styledLabel("", 12, ERROR);
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);

        // Buttons
        JButton loginBtn    = styledButton("LOGIN",       ACCENT);
        JButton registerBtn = styledButton("REGISTER",    new Color(40, 100, 60));
        JButton guestBtn    = styledButton("PLAY AS GUEST", new Color(50, 50, 80));

        loginBtn.setAlignmentX(CENTER_ALIGNMENT);
        registerBtn.setAlignmentX(CENTER_ALIGNMENT);
        guestBtn.setAlignmentX(CENTER_ALIGNMENT);

        // Offline notice
        if (!dbAvailable) {
            JLabel offline = styledLabel("⚠ Database offline — guest only", 11, new Color(255, 180, 80));
            offline.setAlignmentX(CENTER_ALIGNMENT);
            formPanel.add(offline);
            formPanel.add(Box.createVerticalStrut(10));
            loginBtn.setEnabled(false);
            registerBtn.setEnabled(false);
        }

        // Actions
        loginBtn.addActionListener(e -> doLogin());
        registerBtn.addActionListener(e -> doRegister());
        guestBtn.addActionListener(e -> doGuest());

        // Enter key on password field triggers login
        passwordField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) doLogin();
            }
        });

        // Assemble form
        formPanel.add(title);
        formPanel.add(Box.createVerticalStrut(4));
        formPanel.add(sub);
        formPanel.add(Box.createVerticalStrut(24));
        formPanel.add(userLbl);
        formPanel.add(Box.createVerticalStrut(4));
        formPanel.add(usernameField);
        formPanel.add(Box.createVerticalStrut(14));
        formPanel.add(passLbl);
        formPanel.add(Box.createVerticalStrut(4));
        formPanel.add(passwordField);
        formPanel.add(Box.createVerticalStrut(10));
        formPanel.add(statusLabel);
        formPanel.add(Box.createVerticalStrut(20));
        formPanel.add(loginBtn);
        formPanel.add(Box.createVerticalStrut(8));
        formPanel.add(registerBtn);
        formPanel.add(Box.createVerticalStrut(8));
        formPanel.add(guestBtn);

        centre.add(formPanel);
        add(centre, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        // Dark gradient background
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        // Subtle purple glow at centre
        g.setColor(new Color(80, 40, 180, 30));
        g.fillOval(getWidth()/2-200, getHeight()/2-200, 400, 400);
    }

    // ── Actions ───────────────────────────────────────────────
    private void doLogin() {
        String user = usernameField.getText().trim();
        String pass = new String(passwordField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            setStatus("Please enter username and password.", false);
            return;
        }
        setStatus("Logging in...", true);
        new Thread(() -> {
            DatabaseManager.UserRecord record = db.loginUser(user, pass);
            SwingUtilities.invokeLater(() -> {
                if (record == null) {
                    setStatus("Invalid username or password.", false);
                } else {
                    setStatus("Welcome back, " + record.username + "!", true);
                    LoginResult r = new LoginResult();
                    r.user    = record;
                    r.isGuest = false;
                    SwingUtilities.invokeLater(() -> onResult.accept(r));
                }
            });
        }).start();
    }

    private void doRegister() {
        String user = usernameField.getText().trim();
        String pass = new String(passwordField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            setStatus("Please enter username and password.", false);
            return;
        }
        setStatus("Registering...", true);
        new Thread(() -> {
            String result = db.registerUser(user, pass);
            SwingUtilities.invokeLater(() -> {
                if ("ok".equals(result)) {
                    setStatus("Account created! Logging in...", true);
                    doLogin();
                } else {
                    setStatus(result, false);
                }
            });
        }).start();
    }

    private void doGuest() {
        String name = usernameField.getText().trim();
        if (name.isEmpty()) name = "Guest" + (int)(Math.random() * 9000 + 1000);
        LoginResult r  = new LoginResult();
        r.isGuest      = true;
        r.guestName    = name;
        onResult.accept(r);
    }

    // ── Styling helpers ───────────────────────────────────────
    private void setStatus(String msg, boolean good) {
        statusLabel.setText(msg);
        statusLabel.setForeground(good ? SUCCESS : ERROR);
    }

    private JLabel styledLabel(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.BOLD, size));
        l.setForeground(color);
        return l;
    }

    private JTextField styledField() {
        JTextField f = new JTextField();
        styleField(f);
        return f;
    }

    private void styleField(JTextField f) {
        f.setBackground(new Color(20, 15, 40));
        f.setForeground(new Color(220, 210, 255));
        f.setCaretColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 60, 140), 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        f.setFont(new Font("Monospaced", Font.PLAIN, 14));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        f.setAlignmentX(LEFT_ALIGNMENT);
    }

    private JButton styledButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Monospaced", Font.BOLD, 13));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bg.brighter(), 1),
                BorderFactory.createEmptyBorder(8, 20, 8, 20)
        ));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(bg.brighter()); }
            @Override public void mouseExited(MouseEvent e)  { b.setBackground(bg); }
        });
        return b;
    }
}
