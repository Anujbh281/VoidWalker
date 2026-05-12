import java.awt.event.*;
import java.util.HashSet;
import java.util.Set;

public class InputHandler extends KeyAdapter
        implements MouseListener, MouseMotionListener {

    Set<Integer> held = new HashSet<>();
    int mouseX, mouseY;
    boolean mouseClicked = false;
    boolean mouseDown    = false;
    int clickX, clickY;
    Settings settings;

    // Fullscreen coordinate translation
    private float scale = 1f;
    private int   offX  = 0;
    private int   offY  = 0;

    InputHandler(Settings s) { this.settings = s; }

    /** Called by GamePanel each frame when rendering at non-native scale. */
    void setScale(float sc, int ox, int oy) { scale = sc; offX = ox; offY = oy; }

    /** Translate raw screen coordinate to game-space (960x640). */
    private int tx(int x) { return scale == 1f ? x : Math.round((x - offX) / scale); }
    private int ty(int y) { return scale == 1f ? y : Math.round((y - offY) / scale); }

    @Override public void keyPressed(KeyEvent e)  { held.add(e.getKeyCode()); }
    @Override public void keyReleased(KeyEvent e) { held.remove(e.getKeyCode()); }

    boolean isUp()       { return held.contains(settings.upKey); }
    boolean isDown()     { return held.contains(settings.downKey); }
    boolean isLeft()     { return held.contains(settings.leftKey); }
    boolean isRight()    { return held.contains(settings.rightKey); }
    /** LMB held = continuous auto-fire */
    boolean isAttack()   { return mouseDown; }
    boolean isDash()     { return held.contains(settings.dashKey); }
    boolean isInteract() { return held.contains(settings.interactKey); }
    boolean isPause()    { return held.contains(settings.pauseKey); }

    @Override public void mouseClicked(MouseEvent e) {
        mouseClicked = true; clickX = tx(e.getX()); clickY = ty(e.getY());
    }
    @Override public void mousePressed(MouseEvent e) {
        mouseX = tx(e.getX()); mouseY = ty(e.getY());
        if (e.getButton() == MouseEvent.BUTTON1) mouseDown = true;
        mouseClicked = true; clickX = tx(e.getX()); clickY = ty(e.getY());
    }
    @Override public void mouseReleased(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1) mouseDown = false;
    }
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e)  {}
    @Override public void mouseMoved(MouseEvent e)   { mouseX = tx(e.getX()); mouseY = ty(e.getY()); }
    @Override public void mouseDragged(MouseEvent e) { mouseX = tx(e.getX()); mouseY = ty(e.getY()); }

    void consumeClick() { mouseClicked = false; }
}
