import java.util.function.Supplier;

/**
 * ObjectPool<T> — Generic zero-GC object pool.
 *
 * HOW IT WORKS:
 *   Objects are pre-allocated in an array. When you need one, call acquire().
 *   When done, call release(obj). No new/GC ever happens after warmup.
 *
 * USAGE:
 *   ObjectPool<Bullet> pool = new ObjectPool<>(Bullet::new, 300);
 *   Bullet b = pool.acquire();
 *   b.init(...);
 *   // later:
 *   pool.release(b);
 */
public class ObjectPool<T extends ObjectPool.Poolable> {

    public interface Poolable {
        void reset();        // called when object is returned to pool
        boolean isActive();  // true = in use, false = available
        void setActive(boolean active);
    }

    private final Object[] pool;
    private final int      capacity;
    private       int      nextFree = 0;   // round-robin pointer
    private       int      activeCount = 0;

    @SuppressWarnings("unchecked")
    public ObjectPool(Supplier<T> factory, int capacity) {
        this.capacity = capacity;
        this.pool     = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            T obj = factory.get();
            obj.setActive(false);
            pool[i] = obj;
        }
    }

    /**
     * Get an available object from the pool.
     * Returns null if pool is exhausted (increase capacity if this happens).
     */
    @SuppressWarnings("unchecked")
    public T acquire() {
        // Scan from nextFree forward (fast for sparse pools)
        for (int i = 0; i < capacity; i++) {
            int idx = (nextFree + i) % capacity;
            T obj = (T) pool[idx];
            if (!obj.isActive()) {
                obj.setActive(true);
                nextFree = (idx + 1) % capacity;
                activeCount++;
                return obj;
            }
        }
        // Pool exhausted — return null (caller should handle gracefully)
        return null;
    }

    /** Return an object to the pool. */
    public void release(T obj) {
        if (obj != null && obj.isActive()) {
            obj.reset();
            obj.setActive(false);
            activeCount--;
        }
    }

    /** Release all objects at once (level reset, wave start, etc.) */
    @SuppressWarnings("unchecked")
    public void releaseAll() {
        for (int i = 0; i < capacity; i++) {
            T obj = (T) pool[i];
            if (obj.isActive()) {
                obj.reset();
                obj.setActive(false);
            }
        }
        activeCount = 0;
        nextFree    = 0;
    }

    /** Iterate all ACTIVE objects. Zero allocation — uses index loop. */
    @SuppressWarnings("unchecked")
    public void forEach(PoolConsumer<T> action) {
        for (int i = 0; i < capacity; i++) {
            T obj = (T) pool[i];
            if (obj.isActive()) action.accept(obj);
        }
    }

    /** Iterate and release in one pass. */
    @SuppressWarnings("unchecked")
    public void forEachAndRelease(PoolConsumer<T> action) {
        for (int i = 0; i < capacity; i++) {
            T obj = (T) pool[i];
            if (obj.isActive()) {
                action.accept(obj);
                if (!obj.isActive()) activeCount--; // action may have deactivated it
            }
        }
    }

    public int getActiveCount()   { return activeCount; }
    public int getCapacity()      { return capacity; }
    public float getUsageRatio()  { return (float)activeCount / capacity; }

    @FunctionalInterface
    public interface PoolConsumer<T> {
        void accept(T obj);
    }
}
