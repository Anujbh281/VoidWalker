import javax.sound.sampled.*;
import java.io.*;

/**
 * AudioManager — WAV-based audio (fully compatible with standard Java, no plugins needed).
 *
 * Asset mapping (place in assets/audio/ folder):
 *   menu_music.wav   → main menu loop
 *   shoot.wav        → player fires
 *   level_music.wav  → in-level background loop
 */
public class AudioManager {

    boolean enabled = true;

    // Active clips (package-private so GamePanel can check state)
    Clip menuMusicClip  = null;
    Clip levelMusicClip = null;
    Clip shootClip      = null;
    Clip currentLoop    = null;

    public AudioManager() {
        menuMusicClip  = loadClip("assets/audio/menu_music.wav");
        levelMusicClip = loadClip("assets/audio/level_music.wav");
        shootClip      = loadClip("assets/audio/shoot.wav");
    }

    /** Load a WAV file into a Clip. Returns null silently if file missing. */
    private Clip loadClip(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            AudioInputStream ais = AudioSystem.getAudioInputStream(f);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            return clip;
        } catch (Exception e) {
            return null;
        }
    }

    private void setVolume(Clip clip, float gain) {
        if (clip == null) return;
        try {
            FloatControl fc = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            // Convert 0.0–1.0 linear to dB
            float dB = (float)(Math.log10(Math.max(gain, 0.0001)) * 20);
            dB = Math.max(fc.getMinimum(), Math.min(fc.getMaximum(), dB));
            fc.setValue(dB);
        } catch (Exception ignored) {}
    }

    private void stopLoop() {
        if (currentLoop != null && currentLoop.isRunning()) {
            currentLoop.stop();
            currentLoop.setFramePosition(0);
        }
        currentLoop = null;
    }

    // ── Public API ────────────────────────────────────────────────

    public void playMenuMusic() {
        if (!enabled) return;
        if (currentLoop == menuMusicClip && menuMusicClip != null
                && menuMusicClip.isRunning()) return;
        stopLoop();
        if (menuMusicClip != null) {
            setVolume(menuMusicClip, 0.6f);
            menuMusicClip.setFramePosition(0);
            menuMusicClip.loop(Clip.LOOP_CONTINUOUSLY);
            currentLoop = menuMusicClip;
        }
    }

    public void playLevelMusic() {
        if (!enabled) return;
        if (currentLoop == levelMusicClip && levelMusicClip != null
                && levelMusicClip.isRunning()) return;
        stopLoop();
        if (levelMusicClip != null) {
            setVolume(levelMusicClip, 0.55f);
            levelMusicClip.setFramePosition(0);
            levelMusicClip.loop(Clip.LOOP_CONTINUOUSLY);
            currentLoop = levelMusicClip;
        }
    }

    public void stopMusic() { stopLoop(); }

    public void shoot() {
        if (!enabled) return;
        if (shootClip != null) {
            shootClip.stop();
            shootClip.setFramePosition(0);
            setVolume(shootClip, 0.7f);
            shootClip.start();
        } else {
            playTone(800, 60, 0.12f);
        }
    }

    // ── Synthesized fallback SFX ──────────────────────────────────
    public void hit()       { playTone(200,  80,  0.15f); }
    public void death()     { playTone(150,  200, 0.18f); }
    public void pickup()    { playTone(1200, 100, 0.10f); }
    public void dash()      { playTone(600,  60,  0.08f); }
    public void menuClick() { playTone(500,  80,  0.08f); }
    public void levelUp() {
        playTone(880, 300, 0.15f);
        new Thread(() -> {
            try { Thread.sleep(200); } catch (Exception ignored) {}
            playTone(1100, 300, 0.15f);
        }).start();
    }

    private void playTone(float freq, int durationMs, float vol) {
        if (!enabled) return;
        new Thread(() -> {
            try {
                AudioFormat fmt = new AudioFormat(44100, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) return;
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(fmt); line.start();
                int samples = (int)(44100 * durationMs / 1000.0);
                byte[] buf = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    double angle    = 2.0 * Math.PI * freq * i / 44100;
                    double envelope = i < 200 ? i/200.0
                            : i > samples-200 ? (samples-i)/200.0 : 1.0;
                    short val = (short)(Math.sin(angle) * 32767 * vol * envelope);
                    buf[i*2]   = (byte)(val & 0xFF);
                    buf[i*2+1] = (byte)((val >> 8) & 0xFF);
                }
                line.write(buf, 0, buf.length);
                line.drain(); line.close();
            } catch (Exception ignored) {}
        }).start();
    }
}
