import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LightingEngine - Advanced dynamic lighting system for VoidWalker.
 */
public class LightingEngine {

    private static final int SW = 960;
    private static final int SH = 640;

    private static final Color AMBIENT_DARKNESS = new Color(8, 6, 18, 220);
    private static final Color FOG_COLOR = new Color(15, 10, 25, 40);

    private static BufferedImage lightMap = null;
    private static Graphics2D lightMapG = null;

    private static final Map<String, RadialGradientPaint> gradientCache = new ConcurrentHashMap<>();

    private static List<LightSource> activeLights = new ArrayList<>();
    private static List<LightSource> flashLights = new ArrayList<>();

    public static void clearLights() {
        activeLights.clear();
    }

    public static void addLight(LightSource light) {
        if (light != null) {
            activeLights.add(light);
        }
    }

    public static void addFlash(LightSource flash) {
        if (flash != null) {
            flashLights.add(flash);
        }
    }

    public static void applyLighting(Graphics2D g, int camX, int camY, Quality quality) {
        if (quality == Quality.LOW) {
            applySimpleDarkness(g);
            return;
        }

        ensureLightMap();
        clearLightMap();
        renderAllLights(camX, camY, quality);
        applyFogEffect();
        blendLightMap(g);
        addVignetteEffect(g);
    }

    private static void ensureLightMap() {
        if (lightMap == null || lightMap.getWidth() != SW || lightMap.getHeight() != SH) {
            if (lightMap != null) {
                lightMap.flush();
                if (lightMapG != null) lightMapG.dispose();
            }
            lightMap = new BufferedImage(SW, SH, BufferedImage.TYPE_INT_ARGB);
            lightMapG = lightMap.createGraphics();
            lightMapG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            lightMapG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        }
    }

    private static void clearLightMap() {
        lightMapG.setComposite(AlphaComposite.Clear);
        lightMapG.fillRect(0, 0, SW, SH);
        lightMapG.setComposite(AlphaComposite.SrcOver);
    }

    private static void renderAllLights(int camX, int camY, Quality quality) {
        List<LightSource> allLights = new ArrayList<>(activeLights);
        allLights.addAll(flashLights);
        flashLights.clear();

        allLights.sort((a, b) -> Float.compare(b.radius, a.radius));

        for (LightSource light : allLights) {
            renderLight(light, camX, camY, quality);
        }
    }

    private static void renderLight(LightSource light, int camX, int camY, Quality quality) {
        int screenX = (int)(light.x - camX);
        int screenY = (int)(light.y - camY);

        float radius = light.getPulsedRadius();
        float intensity = light.getCurrentIntensity();

        if (screenX + radius < -50 || screenX - radius > SW + 50 ||
                screenY + radius < -50 || screenY - radius > SH + 50) {
            return;
        }

        int coreAlpha = Math.min(255, (int)(light.coreColor.getAlpha() * intensity));
        int edgeAlpha = Math.min(255, (int)(light.edgeColor.getAlpha() * intensity));

        Color adjustedCore = new Color(
                light.coreColor.getRed(),
                light.coreColor.getGreen(),
                light.coreColor.getBlue(),
                coreAlpha
        );
        Color adjustedEdge = new Color(
                light.edgeColor.getRed(),
                light.edgeColor.getGreen(),
                light.edgeColor.getBlue(),
                edgeAlpha
        );

        // Fixed: Removed the problematic String.format with missing arguments
        // Using a simpler cache key
        String cacheKey = adjustedCore.getRGB() + "_" + adjustedEdge.getRGB() + "_" + (int)radius;

        RadialGradientPaint gradient = gradientCache.get(cacheKey);
        if (gradient == null) {
            float[] fractions = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
            Color[] colors = {
                    adjustedCore,
                    new Color(adjustedCore.getRed(), adjustedCore.getGreen(), adjustedCore.getBlue(),
                            (int)(adjustedCore.getAlpha() * 0.8f)),
                    new Color(adjustedEdge.getRed(), adjustedEdge.getGreen(), adjustedEdge.getBlue(),
                            (int)(adjustedEdge.getAlpha() * 0.5f)),
                    new Color(adjustedEdge.getRed(), adjustedEdge.getGreen(), adjustedEdge.getBlue(),
                            (int)(adjustedEdge.getAlpha() * 0.25f)),
                    new Color(adjustedEdge.getRed(), adjustedEdge.getGreen(), adjustedEdge.getBlue(),
                            (int)(adjustedEdge.getAlpha() * 0.1f)),
                    adjustedEdge
            };
            gradient = new RadialGradientPaint(
                    new Point2D.Float(screenX, screenY), radius,
                    fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE
            );

            if (gradientCache.size() < 200) {
                gradientCache.put(cacheKey, gradient);
            }
        }

        lightMapG.setPaint(gradient);
        lightMapG.setComposite(AlphaComposite.SrcOver);
        lightMapG.fillOval((int)(screenX - radius), (int)(screenY - radius),
                (int)(radius * 2), (int)(radius * 2));
    }

    private static void applyFogEffect() {
        lightMapG.setColor(FOG_COLOR);
        lightMapG.setComposite(AlphaComposite.SrcAtop);
        lightMapG.fillRect(0, 0, SW, SH);
    }

    private static void blendLightMap(Graphics2D g) {
        g.setColor(AMBIENT_DARKNESS);
        g.fillRect(0, 0, SW, SH);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
        g.drawImage(lightMap, 0, 0, null);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
    }

    private static void addVignetteEffect(Graphics2D g) {
        int vignetteRadius = 400;
        int centerX = SW / 2;
        int centerY = SH / 2;

        RadialGradientPaint vignette = new RadialGradientPaint(
                new Point2D.Float(centerX, centerY), vignetteRadius,
                new float[]{0f, 0.6f, 1f},
                new Color[]{
                        new Color(0, 0, 0, 0),
                        new Color(0, 0, 0, 20),
                        new Color(0, 0, 0, 60)
                }
        );
        g.setPaint(vignette);
        g.fillRect(0, 0, SW, SH);
    }

    private static void applySimpleDarkness(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, SW, SH);
    }

    public static void createDamageFlash(float x, float y) {
        LightSource flash = new LightSource(x, y, 180,
                new Color(255, 100, 100, 180),
                new Color(150, 50, 50, 0));
        flash.intensity = 1.2f;
        flashLights.add(flash);
    }

    public static void createExplosionFlash(float x, float y) {
        LightSource explosion = LightSource.createExplosionFlash(x, y);
        flashLights.add(explosion);
    }

    public static void clearCache() {
        gradientCache.clear();
        if (lightMapG != null) {
            lightMapG.dispose();
            lightMap = null;
            lightMapG = null;
        }
    }

    public static int getActiveLightCount() {
        return activeLights.size();
    }
}