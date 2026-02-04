package me.kirug.flatfilestorage.api;

/**
 * Base class for Serializable Objects providing convenient "Dirty" state tracking.
 * Users should extend this to get free optimization checks.
 */
public abstract class AbstractSerializable implements SerializableObject {
    
    /**
     * Internal: Transient flag to track changes.
     */
    private transient boolean dirty = true; 

    /**
     * Marks the object as changed.
     * Call this whenever you modify a field if you want it to be saved.
     */
    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Internal: Checks if the object needs saving.
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Internal: Resets dirty state after successful save.
     */
    public void markSaved() {
        this.dirty = false;
    }
    
    @Override
    public int getVersion() {
        return 1;
    }
}
