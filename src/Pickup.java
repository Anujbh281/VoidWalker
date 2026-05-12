import java.awt.*;

public class Pickup {
    float x, y;
    String type;           // "health", "score", "shield"
    boolean collected = false;
    int bobTimer = 0;

    Pickup(float x, float y, String type) {
        this.x = x; this.y = y; this.type = type;
    }

    void update() { bobTimer++; }

    void draw(Graphics2D g) {
        float bob = (float) Math.sin(bobTimer * 0.1f) * 3;
        Color c = switch (type) {
            case "health" -> new Color(220, 60, 60);
            case "shield" -> new Color(60, 120, 220);
            default       -> new Color(255, 200, 0);
        };
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
        g.fillOval((int)x - 14, (int)(y - 14 + bob), 28, 28);
        g.setColor(c);
        g.fillOval((int)x - 8, (int)(y - 8 + bob), 16, 16);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval((int)x - 8, (int)(y - 8 + bob), 16, 16);
    }

    Rectangle getBounds() { return new Rectangle((int)x - 10, (int)y - 10, 20, 20); }
}
