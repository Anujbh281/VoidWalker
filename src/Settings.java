import java.awt.event.KeyEvent;
import java.io.*;

public class Settings implements Serializable {
    Quality quality    = Quality.MEDIUM;
    int musicVolume    = 70;
    int sfxVolume      = 80;
    int upKey          = KeyEvent.VK_W;
    int downKey        = KeyEvent.VK_S;
    int leftKey        = KeyEvent.VK_A;
    int rightKey       = KeyEvent.VK_D;
    int attackKey      = KeyEvent.VK_SPACE;
    boolean fullScreen = false;
    int dashKey        = KeyEvent.VK_SHIFT;
    int interactKey    = KeyEvent.VK_E;
    int pauseKey       = KeyEvent.VK_ESCAPE;

    static Settings load() {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream("voidwalker_settings.dat"))) {
            return (Settings) ois.readObject();
        } catch (Exception e) {
            return new Settings();
        }
    }

    void save() {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream("voidwalker_settings.dat"))) {
            oos.writeObject(this);
        } catch (Exception ignored) {}
    }
}
