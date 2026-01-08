package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Interface for memory mappers. Mappers are responsible for handling CPU and PPU
 * memory access to the cartridge, implementing bank switching and other logic.
 */
public interface Mapper {
    void setCartridge(Cartridge cartridge);
    void setBus(Bus bus); // For IRQ signaling
    int cpuRead(int address);
    void cpuWrite(int address, int value);
    // Overload for cycle-aware writes
    default void cpuWrite(int address, int value, long cycles) {
        cpuWrite(address, value);
    }
    int ppuRead(int address);
    void ppuWrite(int address, int value);
    void reset();
}
