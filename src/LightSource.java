import java.awt.*;
import java.awt.geom.Point2D;

/**
 * LightSource - Represents a dynamic light in the game world.
 * Supports different colors, radii, intensities, and flickering effects.
 */
public class LightSource {
    // Core properties
    public float x, y;           // World position
    public float radius;          // Light radius in pixels
    public Color coreColor;       // Color at center
    public Color edgeColor;       // Color at edge (usually transparent)
    public float intensity;       // 0.0 - 1.0, overall brightness multiplier

    // Animation properties
    public boolean flicker;       // Whether light flickers
    public float flickerSpeed;    // Speed of flicker
    public float flickerIntensity; // How much intensity varies
    private float flickerTimer = 0;
    private float currentIntensity;

    // Optional: pulsing effect
    public boolean pulse;         // Whether light pulses rhythmically
    public float pulseSpeed;      // Speed of pulse
    public float pulseAmount;     // How much radius varies

    // Type identification (for debugging/optimization)
    public enum LightType {
        PLAYER, ENEMY, PROJECTILE, TORCH, EXPLOSION, PICKUP
    }
    public LightType type;

    /**
     * Creates a new light source
     */
    public LightSource(float x, float y, float radius, Color coreColor, Color edgeColor) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.coreColor = coreColor;
        this.edgeColor = edgeColor;
        this.intensity = 1.0f;
        this.currentIntensity = 1.0f;
        this.type = LightType.TORCH;
    }

    /**
     * Creates a player light with default settings
     */
    public static LightSource createPlayerLight(float x, float y) {
        LightSource light = new LightSource(x, y, 280,
                new Color(80, 100, 255, 180),    // Bluish-purple core
                new Color(40, 30, 80, 0));        // Fades to transparent
        light.type = LightType.PLAYER;
        light.pulse = true;
        light.pulseSpeed = 1.5f;
        light.pulseAmount = 0.05f;
        return light;
    }

    /**
     * Creates an enemy light
     */
    public static LightSource createEnemyLight(float x, float y, Enemy enemy) {
        LightSource light;

        switch (enemy.type) {
            case BOSS:
                light = new LightSource(x, y, 160,
                        new Color(180, 50, 200, 100),
                        new Color(60, 20, 80, 0));
                break;
            case RANGER:
                light = new LightSource(x, y, 100,
                        new Color(200, 60, 60, 80),
                        new Color(80, 20, 20, 0));
                break;
            default: // GRUNT
                light = new LightSource(x, y, 80,
                        new Color(150, 40, 40, 70),
                        new Color(60, 15, 15, 0));
                break;
        }
        light.type = LightType.ENEMY;
        light.flicker = true;
        light.flickerSpeed = 2.0f;
        light.flickerIntensity = 0.15f;
        return light;
    }

    /**
     * Creates a projectile light
     */
    public static LightSource createProjectileLight(float x, float y, Color color) {
        LightSource light = new LightSource(x, y, 45,
                new Color(color.getRed(), color.getGreen(), color.getBlue(), 120),
                new Color(color.getRed()/2, color.getGreen()/2, color.getBlue()/2, 0));
        light.type = LightType.PROJECTILE;
        light.intensity = 0.7f;
        return light;
    }

    /**
     * Creates an explosion flash
     */
    public static LightSource createExplosionFlash(float x, float y) {
        LightSource light = new LightSource(x, y, 150,
                new Color(255, 200, 100, 200),
                new Color(255, 100, 50, 0));
        light.type = LightType.EXPLOSION;
        light.intensity = 1.5f;
        return light;
    }

    /**
     * Updates animation effects
     */
    public void update(float deltaTime) {
        currentIntensity = intensity;

        if (flicker) {
            flickerTimer += deltaTime * flickerSpeed;
            float flickerFactor = 1.0f + (float)Math.sin(flickerTimer * Math.PI * 2) * flickerIntensity;
            currentIntensity *= flickerFactor;
        }

        if (pulse) {
            float pulseFactor = 1.0f + (float)Math.sin(System.currentTimeMillis() * 0.003 * pulseSpeed) * pulseAmount;
            // Note: radius pulsing is handled in renderer
        }
    }

    public float getCurrentIntensity() {
        return currentIntensity;
    }

    public float getPulsedRadius() {
        if (!pulse) return radius;
        float pulseFactor = 1.0f + (float)Math.sin(System.currentTimeMillis() * 0.003 * pulseSpeed) * pulseAmount;
        return radius * pulseFactor;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }
}