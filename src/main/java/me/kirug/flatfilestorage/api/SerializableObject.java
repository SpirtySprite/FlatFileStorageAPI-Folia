package me.kirug.flatfilestorage.api;

import java.io.IOException;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

public interface SerializableObject {

    /**
     * Get the current version of this data structure.
     * Use simple integers: 1, 2, 3...
     */
    default int getVersion() { return 1; }

    /**
     * Write this object's fields to the optimized stream.
     */
    void write(VarOutputStream out) throws IOException;

    /**
     * Read data from the stream.
     * @param in The input stream.
     * @param version The version of the data found on disk. Handle legacy data here.
     */
    void read(VarInputStream in, int version) throws IOException;
}
