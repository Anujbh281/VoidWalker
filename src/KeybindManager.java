import java.awt.event.KeyEvent;
import java.io.*;
import java.util.*;

/**
 * KeybindManager — Fully editable keybind system with auto-save.
 *
 * HOW TO USE:
 *   KeybindManager km = KeybindManager.load();
 *   if (km.isPressed(KeyAction.MOVE_UP, keyCode)) { ... }
 *
 * REBINDING:
 *   km.setKey(KeyAction.DASH, KeyEvent.VK_SHIFT);
 *   km.save();
 */
public class KeybindManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String SAVE_FILE = "voidwalker_keybinds.dat";

    // ── All bindable actions ──────────────────────────────────────
    public enum KeyAction {
        MOVE_UP    ("Move Up",      "Movement"),
        MOVE_DOWN  ("Move Down",    "Movement"),
        MOVE_LEFT  ("Move Left",    "Movement"),
        MOVE_RIGHT ("Move Right",   "Movement"),
        DASH       ("Dash",         "Combat"),
        ABILITY    ("Use Ability",  "Combat"),
        PAUSE      ("Pause",        "System"),
        INTERACT   ("Interact",     "System");

        public final String displayName;
        public final String category;
        KeyAction(String displayName, String category) {
            this.displayName = displayName;
            this.category    = category;
        }
    }

    // ── Key bindings map ──────────────────────────────────────────
    private final Map<KeyAction, Integer> bindings = new HashMap<>();

    // ── Default bindings ──────────────────────────────────────────
    private static final Map<KeyAction, Integer> DEFAULTS = new HashMap<>();
    static {
        DEFAULTS.put(KeyAction.MOVE_UP,    KeyEvent.VK_W);
        DEFAULTS.put(KeyAction.MOVE_DOWN,  KeyEvent.VK_S);
        DEFAULTS.put(KeyAction.MOVE_LEFT,  KeyEvent.VK_A);
        DEFAULTS.put(KeyAction.MOVE_RIGHT, KeyEvent.VK_D);
        DEFAULTS.put(KeyAction.DASH,       KeyEvent.VK_SHIFT);
        DEFAULTS.put(KeyAction.ABILITY,    KeyEvent.VK_E);
        DEFAULTS.put(KeyAction.PAUSE,      KeyEvent.VK_ESCAPE);
        DEFAULTS.put(KeyAction.INTERACT,   KeyEvent.VK_F);
    }

    private KeybindManager() {
        resetToDefaults();
    }

    // ── Load from disk ────────────────────────────────────────────
    public static KeybindManager load() {
        try (ObjectInputStream in = new ObjectInputStream(
                new FileInputStream(SAVE_FILE))) {
            KeybindManager loaded = (KeybindManager) in.readObject();
            // Fill any missing keys with defaults
            for (KeyAction a : KeyAction.values()) {
                loaded.bindings.putIfAbsent(a, DEFAULTS.get(a));
            }
            System.out.println("[Keys] Loaded keybinds from " + SAVE_FILE);
            return loaded;
        } catch (Exception e) {
            System.out.println("[Keys] Using default keybinds.");
            return new KeybindManager();
        }
    }

    // ── Save to disk ──────────────────────────────────────────────
    public void save() {
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(SAVE_FILE))) {
            out.writeObject(this);
        } catch (IOException e) {
            System.err.println("[Keys] Could not save keybinds: " + e.getMessage());
        }
    }

    // ── Query ─────────────────────────────────────────────────────
    public int getKey(KeyAction action) {
        return bindings.getOrDefault(action, DEFAULTS.getOrDefault(action, KeyEvent.VK_UNDEFINED));
    }

    public boolean isPressed(KeyAction action, int keyCode) {
        return getKey(action) == keyCode;
    }

    // ── Rebind ────────────────────────────────────────────────────
    /**
     * Set a new key for an action.
     * Returns false if the key is already used by another action.
     * Use force=true to override duplicates.
     */
    public boolean setKey(KeyAction action, int newKey, boolean force) {
        // Check for duplicate
        for (Map.Entry<KeyAction, Integer> e : bindings.entrySet()) {
            if (e.getKey() != action && e.getValue() == newKey) {
                if (!force) return false;
                // Clear the duplicate
                bindings.put(e.getKey(), KeyEvent.VK_UNDEFINED);
            }
        }
        bindings.put(action, newKey);
        save();
        return true;
    }

    public boolean setKey(KeyAction action, int newKey) {
        return setKey(action, newKey, false);
    }

    // ── Reset ─────────────────────────────────────────────────────
    public void resetToDefaults() {
        bindings.clear();
        bindings.putAll(DEFAULTS);
    }

    public void resetAction(KeyAction action) {
        bindings.put(action, DEFAULTS.get(action));
        save();
    }

    // ── Key name helpers ──────────────────────────────────────────
    public String getKeyName(KeyAction action) {
        int key = getKey(action);
        if (key == KeyEvent.VK_UNDEFINED) return "[UNBOUND]";
        return "[" + KeyEvent.getKeyText(key) + "]";
    }

    public static String keyName(int keyCode) {
        if (keyCode == KeyEvent.VK_UNDEFINED) return "[UNBOUND]";
        return "[" + KeyEvent.getKeyText(keyCode) + "]";
    }

    // ── Conflict detection ────────────────────────────────────────
    public KeyAction getConflict(int keyCode, KeyAction excluding) {
        for (Map.Entry<KeyAction, Integer> e : bindings.entrySet()) {
            if (e.getKey() != excluding && e.getValue() == keyCode) return e.getKey();
        }
        return null;
    }

    public Map<KeyAction, Integer> getBindings() {
        return Collections.unmodifiableMap(bindings);
    }
}
