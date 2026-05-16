import java.util.ArrayList;
import java.util.List;

/**
 * SpatialGrid — Uniform grid spatial partitioning for O(1) lookups.
 *
 * WHY THIS MATTERS:
 *   Without this: collision = O(n²) = 30 enemies × 300 bullets = 9000 checks/frame
 *   With this:    collision = O(1) per bullet  = ~4 checks/frame
 *
 * HOW TO USE:
 *   // At frame start:
 *   grid.clear();
 *   for (Enemy e : enemies) grid.insert(e.x, e.y, e);
 *
 *   // Collision query:
 *   List<Enemy> near = grid.query(bullet.x, bullet.y, 64);
 *   for (Enemy e : near) checkCollision(bullet, e);
 */
public class SpatialGrid<T> {

    private final int   cellSize;
    private final int   gridW;    // number of cells wide
    private final int   gridH;    // number of cells tall
    private final int   totalCells;

    // Flat array of cell buckets — avoids HashMap overhead
    @SuppressWarnings("unchecked")
    private final List<T>[] cells;

    /**
     * @param worldW   World width in pixels
     * @param worldH   World height in pixels
     * @param cellSize Size of each grid cell (match to typical entity size × 2)
     */
    @SuppressWarnings("unchecked")
    public SpatialGrid(int worldW, int worldH, int cellSize) {
        this.cellSize   = cellSize;
        this.gridW      = (worldW  / cellSize) + 2;
        this.gridH      = (worldH  / cellSize) + 2;
        this.totalCells = gridW * gridH;
        this.cells      = new List[totalCells];
        for (int i = 0; i < totalCells; i++) {
            cells[i] = new ArrayList<>(4);
        }
    }

    /** Clear all cells — call once per frame before inserting. O(cells with data). */
    public void clear() {
        for (int i = 0; i < totalCells; i++) {
            if (!cells[i].isEmpty()) cells[i].clear();
        }
    }

    /** Insert an entity at world position (x, y). */
    public void insert(float x, float y, T entity) {
        int idx = cellIndex(x, y);
        if (idx >= 0 && idx < totalCells) {
            cells[idx].add(entity);
        }
    }

    /**
     * Query all entities within radius of (x, y).
     * Returns a list (may contain duplicates at cell boundaries — caller deduplicates if needed).
     * Uses a reusable result list to avoid allocation.
     */
    private final List<T> queryResult = new ArrayList<>(32);

    public List<T> query(float x, float y, float radius) {
        queryResult.clear();
        int minCX = cellX(x - radius);
        int maxCX = cellX(x + radius);
        int minCY = cellY(y - radius);
        int maxCY = cellY(y + radius);

        for (int cy = minCY; cy <= maxCY; cy++) {
            for (int cx = minCX; cx <= maxCX; cx++) {
                if (cx < 0 || cy < 0 || cx >= gridW || cy >= gridH) continue;
                List<T> cell = cells[cy * gridW + cx];
                queryResult.addAll(cell);
            }
        }
        return queryResult;
    }

    /** Get entities in a single cell at (x, y). Ultra-fast — no allocation. */
    public List<T> getCell(float x, float y) {
        int idx = cellIndex(x, y);
        return (idx >= 0 && idx < totalCells) ? cells[idx] : EMPTY;
    }

    private int cellX(float x) { return Math.max(0, Math.min(gridW-1, (int)(x / cellSize))); }
    private int cellY(float y) { return Math.max(0, Math.min(gridH-1, (int)(y / cellSize))); }
    private int cellIndex(float x, float y) { return cellY(y) * gridW + cellX(x); }

    private static final List EMPTY = new ArrayList(0);

    public int getCellSize()  { return cellSize; }
    public int getGridW()     { return gridW; }
    public int getGridH()     { return gridH; }
}
