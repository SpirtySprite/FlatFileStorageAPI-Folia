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
     * The order in which this field should be written/read.
     * Must be unique per class.
     * Use 1, 2, 3...
     */
    int order();

    /**
     * The data version this field was introduced in.
     * If loading a file with a lower version, this field is skipped (left as default).
     */
    int since() default 0;
}
