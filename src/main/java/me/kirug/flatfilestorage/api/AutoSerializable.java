package me.kirug.flatfilestorage.api;

/**
 * Base class for auto-serialized data. Extend it, annotate fields with {@link Serialize}, and add a
 * public no-args constructor. The storage engine derives a codec from the annotated fields; you write
 * no read/write code.
 *
 * <p>Also provides dirty tracking: {@code save()} skips objects that haven't changed since their last
 * save. Call {@link #markDirty()} after mutating a field (or don't bother and always save).
 */
public abstract class AutoSerializable {

    private transient boolean dirty = true;

    /** Marks the object as changed so the next {@code save()} writes it. */
    public void markDirty() {
        this.dirty = true;
    }

    /** @return whether the object has unsaved changes. */
    public boolean isDirty() {
        return dirty;
    }

    /** Internal: cleared by the engine after a successful save. */
    public void markSaved() {
        this.dirty = false;
    }

    /**
     * Data version, used only for value migrations. With the tagged format, adding or removing fields
     * needs no version bump; override this only when the <i>meaning</i> of stored values changes.
     */
    public int dataVersion() {
        return 1;
    }
}
