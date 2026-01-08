package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 0: NROM
 *
 * The simplest mapper. It supports one or two banks of PRG ROM, one bank of CHR ROM,
 * and optional PRG RAM.
 */
public class Mapper0 implements Mapper {

    private Cartridge cartridge;

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 0 does not use IRQs, so we don't need to store the bus.
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x6000 && address <= 0x7FFF) {
            // PRG RAM (if available)
            return 0; // Not implemented in this simple version
        }
        if (address >= 0x8000) {
            // PRG ROM
            int prgRomSize = cartridge.getPrgRom().length;
            // If 16KB, mirror $8000-$BFFF to $C000-$FFFF
            int mappedAddr = (address - 0x8000) % prgRomSize;
            return cartridge.getPrgRom()[mappedAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value) {
        cpuWrite(address, value, 0);
    }

    @Override
    public void cpuWrite(int address, int value, long cycles) {
        address &= 0xFFFF;
        if (address >= 0x6000 && address <= 0x7FFF) {
            // PRG RAM (if available)
        }
        // Writes to PRG ROM are ignored
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        byte[] chr = cartridge.getChr();
        if (address < chr.length) {
            return chr[address] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        // NROM typically has CHR ROM, but some variants have CHR RAM.
        // We'll assume CHR RAM is possible.
        address &= 0x1FFF;
        byte[] chr = cartridge.getChr();
        if (cartridge.isChrRam() && address < chr.length) {
            chr[address] = (byte) value;
        }
    }

    @Override
    public void reset() {
        // Mapper 0 has no internal state to reset
    }
}
