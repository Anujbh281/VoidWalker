import java.io.*;

public class SaveData implements Serializable {
    int level            = 1;
    int score            = 0;
    int highScore        = 0;
    int totalKills       = 0;
    boolean hasUnlockedBoss = false;

    static SaveData load() {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream("voidwalker_save.dat"))) {
            return (SaveData) ois.readObject();
        } catch (Exception e) {
            return new SaveData();
        }
    }

    void save() {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream("voidwalker_save.dat"))) {
            oos.writeObject(this);
        } catch (Exception ignored) {}
    }
}
