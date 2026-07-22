package me.kirug.flatfilestorage.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for automatic serialization.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Serialize {

    /**
     * A permanent, unique field id (like a protobuf field number). Pick a number and never change or
     * reuse it: the id is what lets you add, remove, or reorder fields without breaking old data.
     * Removing a field frees nothing - just don't reuse its id for something else. Must be >= 1.
     */
    int order();

    /**
     * @deprecated No longer used. The tagged format skips unknown/absent fields automatically, so a
     * "since version" is unnecessary. Kept only for source compatibility.
     */
    @Deprecated
    int since() default 0;
}
