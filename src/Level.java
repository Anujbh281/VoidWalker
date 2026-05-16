import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Level — tile map, BSP dungeon generator, arena generator.
 *
 * OPTIMISATION: pre-renders the entire tile map into a single BufferedImage
 * (tileCanvas) at level-load time. Each frame we just do ONE drawImage() call
 * instead of hundreds of individual tile draws. This is the single biggest
 * performance win for a tile-based game.
 */
public class Level {

    int[][] tiles;
    int cols, rows;
    int levelNum;
    List<Enemy>  enemies = new ArrayList<>();
    List<Pickup> pickups = new ArrayList<>();
    float spawnX, spawnY, exitX, exitY;
    boolean bossLevel;
    boolean exitReached = false;
    boolean isEndless   = false;

    static final int VOID  = 0;
    static final int FLOOR = 1;
    static final int WALL  = 2;
    static final int TILE  = 64;
    static final int SW    = 960;
    static final int SH    = 640;

    // Pre-rendered tile canvas — drawn ONCE at level load
    private BufferedImage tileCanvas;
    // Background image shown in void areas
    private static BufferedImage bgImage = null;

    static void loadBackground() {
        if (bgImage != null) return; // only load once
        try {
            java.io.File f = new java.io.File("assets/background.png");
            if (f.exists()) {
                bgImage = javax.imageio.ImageIO.read(f);
                // Convert to INT_RGB for fastest blitting
                BufferedImage fast = new BufferedImage(bgImage.getWidth(),
                        bgImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = fast.createGraphics();
                g.drawImage(bgImage, 0, 0, null);
                g.dispose();
                bgImage = fast;
            }
        } catch (Exception ignored) {}
    }
    private int canvasW, canvasH;

    // Cached exit pulse state (updated once per frame)
    private long lastPulseTime = 0;
    private float lastPulse    = 1f;

    // ── Constructors ─────────────────────────────────────────────
    Level(int num, SaveData save) {
        this.levelNum  = num;
        this.bossLevel = (num % 5 == 0);
        generate();
        bakeCanvas();
    }

    Level(boolean endless) {
        this.isEndless = true;
        generateArena();
        bakeCanvas();
    }

    // ── CRITICAL OPTIMISATION: bake entire map to one image ──────
    /**
     * Renders ALL tiles into a single BufferedImage at startup.
     * During gameplay, drawGame() does exactly ONE g.drawImage() for the
     * entire map instead of N*M individual tile draws.
     */
    void bakeCanvas() {
        canvasW = cols * TILE;
        canvasH = rows * TILE;
        // ARGB so void = transparent (background shows through)
        tileCanvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tileCanvas.createGraphics();

        // Clear everything to transparent first
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, canvasW, canvasH);
        g.setComposite(java.awt.AlphaComposite.SrcOver);

        // Render hints for crisp pixel art tiles
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);

        BufferedImage texFloor = TextureFactory.get("tile_floor", TILE, TILE);
        BufferedImage texWall  = TextureFactory.get("tile_wall",  TILE, TILE);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int t  = tiles[row][col];
                int sx = col * TILE;
                int sy = row * TILE;

                if (t == FLOOR) {
                    // Always use clean floor tile — lighting handled by ShadowRenderer
                    g.drawImage(texFloor, sx, sy, TILE, TILE, null);

                } else if (t == WALL) {
                    // Floor base underneath so wall edges don't float
                    g.drawImage(texFloor, sx, sy, TILE, TILE, null);
                    // Wall texture on top
                    g.drawImage(texWall, sx, sy, TILE, TILE, null);
                    // Soft top-edge highlight (light source from above)
                    g.setColor(new Color(255, 255, 255, 18));
                    g.fillRect(sx, sy, TILE, 3);
                    // Soft bottom-edge shadow (underside in shadow)
                    g.setColor(new Color(0, 0, 0, 55));
                    g.fillRect(sx, sy + TILE - 4, TILE, 4);
                    // Left-edge slight shadow
                    g.setColor(new Color(0, 0, 0, 30));
                    g.fillRect(sx, sy, 2, TILE);
                }
                // VOID = stays transparent — background shows through
            }
        }
        g.dispose();
    }

    // ================================================================
    //  FIX 2: Background jank in fullscreen - REPLACED draw() method
    // ================================================================
    void draw(Graphics2D g, int camX, int camY, Settings settings) {
        // ── Background — tiles to fill entire visible area ────────
        if (bgImage != null) {
            int bgW = bgImage.getWidth();
            int bgH = bgImage.getHeight();

            // Offset background by camera with wrap-around
            int startX = -(camX % bgW);
            int startY = -(camY % bgH);

            // Ensure we start before screen edge
            if (startX > 0) startX -= bgW;
            if (startY > 0) startY -= bgH;

            // Fill extra tiles to cover ANY screen size (fullscreen safe)
            // Use SW+bgW*2 and SH+bgH*2 to guarantee no gaps ever
            for (int ty = startY; ty < SH + bgH; ty += bgH)
                for (int tx = startX; tx < SW + bgW; tx += bgW)
                    g.drawImage(bgImage, tx, ty, bgW, bgH, null);

        } else {
            // No background image — solid dark colour
            g.setColor(new Color(8, 6, 18));
            g.fillRect(0, 0, SW + 100, SH + 100); // extra 100px safety margin
        }

        // ── Tile canvas on top ────────────────────────────────────
        int srcX  = Math.max(0, camX);
        int srcY  = Math.max(0, camY);
        int srcX2 = Math.min(canvasW, camX + SW);
        int srcY2 = Math.min(canvasH, camY + SH);
        if (srcX2 > srcX && srcY2 > srcY) {
            int dstX = srcX - camX;
            int dstY = srcY - camY;
            g.drawImage(tileCanvas,
                    dstX, dstY, dstX + (srcX2-srcX), dstY + (srcY2-srcY),
                    srcX, srcY, srcX2, srcY2, null);
        }

        // ── Exit portal ───────────────────────────────────────────
        if (!isEndless) drawExit(g, camX, camY);
    }

    private void drawExit(Graphics2D g, int camX, int camY) {
        int ex = (int)exitX - camX;
        int ey = (int)exitY - camY;
        if (ex < -40 || ex > SW+40 || ey < -40 || ey > SH+40) return;

        // Cache pulse so we don't call currentTimeMillis every tile
        long now = System.currentTimeMillis();
        if (now - lastPulseTime > 33) { // update ~30fps
            lastPulse     = (float)(Math.sin(now * 0.005) * 0.3 + 0.7);
            lastPulseTime = now;
        }

        g.setColor(new Color(100, 255, 200, (int)(150 * lastPulse)));
        g.fillOval(ex-16, ey-16, 32, 32);
        g.setColor(new Color(150, 255, 220));
        g.setStroke(new BasicStroke(2));
        g.drawOval(ex-16, ey-16, 32, 32);
        g.setFont(new Font("Monospaced", Font.BOLD, 12));
        g.setColor(Color.WHITE);
        g.drawString("EXIT", ex-14, ey+5);
    }

    // ── Tile queries ─────────────────────────────────────────────
    boolean isWall(float px, float py) {
        int tx = (int)(px / TILE), ty = (int)(py / TILE);
        if (tx < 0 || ty < 0 || tx >= cols || ty >= rows) return true;
        return tiles[ty][tx] != FLOOR;
    }

    boolean hasLos(float ax, float ay, float bx, float by) {
        float dx = bx-ax, dy = by-ay;
        float dist = (float)Math.sqrt(dx*dx + dy*dy);
        int steps = Math.max(1, (int)(dist / 12)); // coarser = faster
        for (int i = 1; i < steps; i++) {
            float t = (float)i / steps;
            if (isWall(ax+dx*t, ay+dy*t)) return false;
        }
        return true;
    }

    int widthPx()  { return canvasW; }
    int heightPx() { return canvasH; }
    boolean allEnemiesDead() { return enemies.stream().noneMatch(e -> e.alive); }

    /** Return pixel X at the centre of the room, guaranteed to be a FLOOR tile. */
    float safeFloorX(int[] room) {
        int cx = room[0] + room[2]/2;
        // Scan outward from centre to find a floor tile
        for (int dx = 0; dx <= room[2]/2; dx++) {
            if (cx+dx < cols && tiles[room[1]+room[3]/2][cx+dx] == FLOOR)
                return (cx+dx)*TILE + TILE/2f;
            if (cx-dx >= 0 && tiles[room[1]+room[3]/2][cx-dx] == FLOOR)
                return (cx-dx)*TILE + TILE/2f;
        }
        return cx*TILE + TILE/2f; // fallback
    }

    float safeFloorY(int[] room) {
        int cy = room[1] + room[3]/2;
        for (int dy = 0; dy <= room[3]/2; dy++) {
            if (cy+dy < rows && tiles[cy+dy][room[0]+room[2]/2] == FLOOR)
                return (cy+dy)*TILE + TILE/2f;
            if (cy-dy >= 0 && tiles[cy-dy][room[0]+room[2]/2] == FLOOR)
                return (cy-dy)*TILE + TILE/2f;
        }
        return cy*TILE + TILE/2f; // fallback
    }

    // ── BSP dungeon generation ────────────────────────────────────
    // FIXED: Added deterministic seed using levelNum
    void generate() {
        rows = 17; cols = 24;
        tiles = new int[rows][cols];
        for (int[] row : tiles) Arrays.fill(row, VOID);

        // Use levelNum as seed so each floor is always valid and deterministic
        List<int[]> rooms = new ArrayList<>();
        generateRooms(rooms, new Random(levelNum * 12345L), 1, 1, cols-2, rows-2, 0);

        for (int[] r : rooms)
            for (int y = r[1]; y < r[1]+r[3]; y++)
                for (int x = r[0]; x < r[0]+r[2]; x++)
                    if (y>0&&y<rows-1&&x>0&&x<cols-1) tiles[y][x] = FLOOR;

        for (int i = 0; i < rooms.size()-1; i++) {
            int[] a=rooms.get(i), b=rooms.get(i+1);
            int ax=a[0]+a[2]/2,ay=a[1]+a[3]/2,bx2=b[0]+b[2]/2,by2=b[1]+b[3]/2;
            int cx=ax; while(cx!=bx2){if(cx>0&&cx<cols-1)tiles[ay][cx]=FLOOR;cx+=(bx2>cx)?1:-1;}
            int cy=ay; while(cy!=by2){if(cy>0&&cy<rows-1)tiles[cy][bx2]=FLOOR;cy+=(by2>cy)?1:-1;}
        }

        for (int y=0;y<rows;y++) for (int x=0;x<cols;x++) {
            if (tiles[y][x]==VOID) {
                boolean near=false;
                for (int dy=-1;dy<=1&&!near;dy++) for (int dx=-1;dx<=1&&!near;dx++) {
                    int ny=y+dy,nx=x+dx;
                    if(ny>=0&&ny<rows&&nx>=0&&nx<cols&&tiles[ny][nx]==FLOOR) near=true;
                }
                if (near) tiles[y][x]=WALL;
            }
        }

        // Safety: ensure we have at least 2 rooms
        if (rooms.size() < 2) {
            // Add a fallback room far from first
            rooms.add(new int[]{cols-8, rows-8, 5, 5});
        }

        int[] first = rooms.get(0);
        int[] last  = rooms.get(rooms.size()-1);

        // Spawn point — guaranteed floor tile at centre of first room
        spawnX = safeFloorX(first);
        spawnY = safeFloorY(first);

        // Exit — use last room, guaranteed different from first
        // If rooms only has 1 entry (shouldn't happen but guard anyway)
        if (rooms.size() == 1) {
            // Make exit at opposite corner of same room
            exitX = spawnX + TILE * 3;
            exitY = spawnY + TILE * 3;
        } else {
            exitX = safeFloorX(last);
            exitY = safeFloorY(last);
        }

        // Ensure exit is not coincident with spawn
        if (Math.abs(exitX - spawnX) < TILE && Math.abs(exitY - spawnY) < TILE
                && rooms.size() > 2) {
            int[] mid = rooms.get(rooms.size() / 2);
            exitX = safeFloorX(mid);
            exitY = safeFloorY(mid);
        }

        // Spawn enemies on verified FLOOR tiles only
        for (int i = 1; i < rooms.size(); i++) {
            int[] r = rooms.get(i);

            // Collect floor tiles — scan full room including edges
            List<int[]> floorTiles = new ArrayList<>();
            for (int ry2 = r[1]; ry2 < r[1]+r[3]; ry2++)
                for (int rx2 = r[0]; rx2 < r[0]+r[2]; rx2++)
                    if (ry2 >= 0 && ry2 < rows && rx2 >= 0 && rx2 < cols
                            && tiles[ry2][rx2] == FLOOR)
                        floorTiles.add(new int[]{rx2, ry2});

            // Fallback: scan corridor tiles near room centre
            if (floorTiles.isEmpty()) {
                int cx2 = r[0]+r[2]/2, cy2 = r[1]+r[3]/2;
                for (int dy=-3; dy<=3; dy++)
                    for (int dx=-3; dx<=3; dx++) {
                        int nx=cx2+dx, ny=cy2+dy;
                        if (nx>=0&&nx<cols&&ny>=0&&ny<rows&&tiles[ny][nx]==FLOOR)
                            floorTiles.add(new int[]{nx,ny});
                    }
            }

            // Still empty — skip this room
            if (floorTiles.isEmpty()) continue;

            java.util.Collections.shuffle(floorTiles);
            int count = Math.min(2 + levelNum/2 + (int)(Math.random()*2),
                    Math.max(1, floorTiles.size()));

            for (int j = 0; j < count; j++) {
                int[] ft = floorTiles.get(j % floorTiles.size());
                // Place exactly at tile centre — no random offset to avoid walls
                float ex = ft[0]*TILE + TILE/2f;
                float ey = ft[1]*TILE + TILE/2f;
                EnemyType et = bossLevel && i == rooms.size()-1 ? EnemyType.BOSS
                        : Math.random() < 0.3 ? EnemyType.RANGER : EnemyType.GRUNT;
                enemies.add(new Enemy(ex, ey, et));
            }

            // Pickup
            if (Math.random() < 0.4) {
                int[] ft = floorTiles.get((int)(Math.random()*floorTiles.size()));
                pickups.add(new Pickup(ft[0]*TILE+TILE/2f, ft[1]*TILE+TILE/2f,
                        Math.random()<0.5?"health":"score"));
            }
        }
    }

    // ================================================================
    //  FIXED generateRooms() - Proper BSP room generation
    // ================================================================
    void generateRooms(List<int[]> rooms, Random rng, int x, int y, int w, int h, int depth) {
        int MIN = 5;
        // Base case: too small to split, or max depth reached
        if (w < MIN*2 || h < MIN*2 || depth > 5) {
            int rw = MIN + rng.nextInt(Math.max(1, w - MIN));
            int rh = MIN + rng.nextInt(Math.max(1, h - MIN));
            int rx = x + rng.nextInt(Math.max(1, w - rw));
            int ry = y + rng.nextInt(Math.max(1, h - rh));
            // Clamp to grid bounds
            rw = Math.min(rw, cols - rx - 1);
            rh = Math.min(rh, rows - ry - 1);
            if (rw >= MIN && rh >= MIN) {
                rooms.add(new int[]{rx, ry, rw, rh});
            }
            return;
        }
        if (w > h) {
            // Split horizontally — ensure both halves are at least MIN*2 wide
            int minSplit = MIN * 2;
            int maxSplit = w - MIN * 2;
            if (minSplit >= maxSplit) {
                // Can't split safely — just make a room
                generateRooms(rooms, rng, x, y, w, h, depth+10);
                return;
            }
            int s = minSplit + rng.nextInt(maxSplit - minSplit);
            generateRooms(rooms, rng, x,   y, s,   h, depth+1);
            generateRooms(rooms, rng, x+s, y, w-s, h, depth+1);
        } else {
            int minSplit = MIN * 2;
            int maxSplit = h - MIN * 2;
            if (minSplit >= maxSplit) {
                generateRooms(rooms, rng, x, y, w, h, depth+10);
                return;
            }
            int s = minSplit + rng.nextInt(maxSplit - minSplit);
            generateRooms(rooms, rng, x, y,   w, s,   depth+1);
            generateRooms(rooms, rng, x, y+s, w, h-s, depth+1);
        }
    }

    // ── Arena (endless) ──────────────────────────────────────────
    void generateArena() {
        int SIZE=15; rows=SIZE; cols=SIZE;
        tiles=new int[rows][cols];
        for (int[] row:tiles) Arrays.fill(row,VOID);
        for (int y=1;y<rows-1;y++) for (int x=1;x<cols-1;x++) tiles[y][x]=FLOOR;
        for (int y=0;y<rows;y++){tiles[y][0]=WALL;tiles[y][cols-1]=WALL;}
        for (int x=0;x<cols;x++){tiles[0][x]=WALL;tiles[rows-1][x]=WALL;}
        spawnX=cols/2f*TILE; spawnY=rows/2f*TILE;
        exitX=spawnX; exitY=spawnY;
    }
}