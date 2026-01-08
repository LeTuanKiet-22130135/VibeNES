package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 3: CNROM
 *
 * This mapper is simple, with no PRG banking and simple CHR bank switching.
 *
 * PRG:
 *  - Up to 32KB of PRG ROM, mapped to $8000-$FFFF. If 16KB, it's mirrored.
 *
 * CHR:
 *  - 8KB of CHR ROM, switchable in 8KB banks.
 */
public class Mapper3 implements Mapper {

    private Cartridge cartridge;
    private int chrBankSelect = 0;

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 3 does not use IRQs, so we don't need to store the bus.
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x8000) {
            // PRG ROM (16KB or 32KB)
            int prgRomSize = cartridge.getPrgRom().length;
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
        if (address >= 0x8000) {
            // Any write to the PRG ROM area selects the CHR bank
            chrBankSelect = value & 0x03; // Lower 2 bits select the bank
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        int bank = chrBankSelect;
        int offset = address;
        int finalAddr = (bank * 8192) + offset;
        return cartridge.getChr()[finalAddr % cartridge.getChr().length] & 0xFF;
    }

    @Override
    public void ppuWrite(int address, int value) {
        // CNROM uses CHR ROM, so writes are ignored.
    }

    @Override
    public void reset() {
        chrBankSelect = 0;
    }
}
