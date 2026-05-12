public class Camera {
    float x, y;
    float targetX, targetY;
    float shake = 0;

    static final int SCREEN_W = 960;
    static final int SCREEN_H = 640;

    void follow(float px, float py, Level level) {
        targetX = px - SCREEN_W / 2f;
        targetY = py - SCREEN_H / 2f;
        targetX = Math.max(0, Math.min(level.widthPx() - SCREEN_W, targetX));
        targetY = Math.max(0, Math.min(level.heightPx() - SCREEN_H, targetY));
        x += (targetX - x) * 0.1f;
        y += (targetY - y) * 0.1f;
        if (shake > 0) shake -= 0.5f;
    }

    int getX() { return (int)(x + (shake > 0 ? (Math.random()-0.5)*shake : 0)); }
    int getY() { return (int)(y + (shake > 0 ? (Math.random()-0.5)*shake : 0)); }
    void shake(float amt) { shake = Math.max(shake, amt); }
}
