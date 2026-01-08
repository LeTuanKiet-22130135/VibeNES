package nes.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Small IO utilities shared across the project.
 */
public final class IOUtil {
    private IOUtil() {}

    /** Read all bytes from a file path into a byte array. */
    public static byte[] readAllBytes(String path) throws IOException {
        Path p = Paths.get(path);
        return Files.readAllBytes(p);
    }
}
