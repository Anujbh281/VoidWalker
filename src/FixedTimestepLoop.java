/**
 * FixedTimestepLoop.java
 *
 * Drop-in replacement for Swing Timer-based game loop.
 * Gives stable 60 UPS (updates per second) regardless of render speed.
 *
 * HOW TO INTEGRATE:
 *   1. In GamePanel constructor, REMOVE: gameTimer = new Timer(FRAME_TIME, this);
 *   2. ADD: gameLoop = new FixedTimestepLoop(this::update, this::repaint);
 *   3. In startGame(): CHANGE gameTimer.start() → gameLoop.start();
 *   4. Keep paintComponent() as-is — Swing repaint handles rendering.
 *
 * WHY BETTER THAN SWING TIMER:
 *   - Swing Timer fires every ~16ms but is NOT guaranteed (OS scheduling)
 *   - If a frame takes 20ms, Timer skips the next tick → game slows down
 *   - FixedTimestepLoop catches up missed ticks → stable physics/AI
 */
public class FixedTimestepLoop {

    private static final long NS_PER_UPDATE = 1_000_000_000L / 60; // 60 UPS
    private static final int  MAX_CATCHUP   = 5; // max missed frames to catch up

    private final Runnable updateFn;
    private final Runnable renderFn;
    private       Thread   loopThread;
    private volatile boolean running = false;

    public FixedTimestepLoop(Runnable updateFn, Runnable renderFn) {
        this.updateFn = updateFn;
        this.renderFn = renderFn;
    }

    public void start() {
        if (running) return;
        running    = true;
        loopThread = new Thread(this::loop, "VW-GameLoop");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public void stop() {
        running = false;
        if (loopThread != null) loopThread.interrupt();
    }

    private void loop() {
        long previous   = System.nanoTime();
        long lag        = 0L;

        while (running) {
            long current = System.nanoTime();
            long elapsed = current - previous;
            previous     = current;
            lag         += elapsed;

            // Catch up on missed updates (max MAX_CATCHUP to avoid spiral of death)
            int catchup = 0;
            while (lag >= NS_PER_UPDATE && catchup < MAX_CATCHUP) {
                updateFn.run();
                lag -= NS_PER_UPDATE;
                catchup++;
            }

            // If still behind, drop excess lag
            if (lag > NS_PER_UPDATE * MAX_CATCHUP) lag = 0;

            // Render
            renderFn.run();

            // Sleep remaining time in this frame
            long frameEnd   = System.nanoTime();
            long frameTime  = frameEnd - current;
            long sleepNs    = NS_PER_UPDATE - frameTime;
            if (sleepNs > 1_000_000L) { // only sleep if >1ms remaining
                try {
                    Thread.sleep(sleepNs / 1_000_000L,
                            (int)(sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public boolean isRunning() { return running; }
}
