package nes.model;

import java.util.Arrays;

/**
 * Simple RAM implementation backed by a byte array.
 * Notes:
 * - Addressing wraps within the RAM size (modulo length), so callers can pass
 *   any integer address and it will be normalized into range.
 * - Values are treated as unsigned bytes (0..255) on read/write.
 */
public class RAM {
    private final byte[] data;

    public RAM(int sizeBytes) {
        if (sizeBytes <= 0) throw new IllegalArgumentException("RAM size must be > 0");
        this.data = new byte[sizeBytes];
    }

    /** Return the raw size of this RAM in bytes. */
    public int size() { return data.length; }

    /** Read one byte (unsigned) from RAM. Address wraps within RAM size. */
    public int read(int address) {
        int idx = normalize(address);
        return data[idx] & 0xFF;
    }

    /** Write one byte to RAM. Address wraps; only low 8 bits of value are stored. */
    public void write(int address, int value) {
        int idx = normalize(address);
        data[idx] = (byte) (value & 0xFF);
    }

    /**
     * Read 16-bit little-endian value from RAM at the given address
     * (low byte at addr, high byte at addr+1), with wrap inside RAM.
     */
    public int read16(int address) {
        int lo = read(address);
        int hi = read(address + 1);
        return (hi << 8) | lo;
    }

    /**
     * Write 16-bit little-endian value to RAM at the given address
     * (low byte at addr, high byte at addr+1), with wrap inside RAM.
     */
    public void write16(int address, int value) {
        write(address, value & 0xFF);
        write(address + 1, (value >>> 8) & 0xFF);
    }

    /** Set all bytes to zero. */
    public void clear() { fill(0); }

    /** Fill RAM with a repeated unsigned byte value. */
    public void fill(int value) {
        byte b = (byte) (value & 0xFF);
        Arrays.fill(data, b);
    }

    /**
     * Load a range of bytes into RAM starting at baseAddress. If len exceeds RAM end,
     * it wraps around. Off and len are clamped to the source array bounds.
     */
    public void load(int baseAddress, byte[] src, int off, int len) {
        if (src == null) return;
        int start = Math.max(0, off);
        int end = Math.min(src.length, start + Math.max(0, len));
        for (int i = start, a = baseAddress; i < end; i++, a++) {
            write(a, src[i]);
        }
    }

    private int normalize(int address) {
        int len = data.length;
        int idx = address % len;
        if (idx < 0) idx += len;
        return idx;
    }
}
