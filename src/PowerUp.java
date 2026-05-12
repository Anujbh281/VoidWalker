import java.awt.*;

/**
 * Represents one selectable power-up upgrade.
 * The Runnable effect is applied once when the player picks it up.
 */
public class PowerUp {

    // ── Identity ─────────────────────────────────────────────────
    public final String   name;
    public final String   description;
    public final Color    color;       // accent colour for the card UI
    public final Runnable applyEffect; // called once on selection

    // ── World pickup position (used in ENDLESS arena) ─────────────
    public float   worldX, worldY;
    public boolean collected = false;
    private int    bobTimer  = 0;

    public PowerUp(String name, String description, Color color, Runnable applyEffect) {
        this.name        = name;
        this.description = description;
        this.color       = color;
        this.applyEffect = applyEffect;
    }

    /** Apply the effect immediately. */
    public void apply() {
        if (applyEffect != null) applyEffect.run();
        collected = true;
    }

    // ── Animated world pickup ────────────────────────────────────
    public void update() { bobTimer++; }

    public void draw(Graphics2D g) {
        if (collected) return;
        float bob = (float) Math.sin(bobTimer * 0.08f) * 4f;
        int cx = (int) worldX, cy = (int)(worldY + bob);

        // Glow
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 50));
        g.fillOval(cx - 20, cy - 20, 40, 40);

        // Body
        g.setColor(color);
        g.fillOval(cx - 10, cy - 10, 20, 20);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(cx - 10, cy - 10, 20, 20);

        // Star icon
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        g.setColor(Color.WHITE);
        g.drawString("★", cx - 5, cy + 5);
    }

    public Rectangle getBounds() {
        return new Rectangle((int)worldX - 12, (int)worldY - 12, 24, 24);
    }
}
