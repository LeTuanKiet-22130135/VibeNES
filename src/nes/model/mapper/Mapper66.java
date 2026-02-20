package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 66: GxROM / MHROM
 *
 * A simple mapper that uses a single register to switch both PRG and CHR banks.
 *
 * PRG:
 *  - 32KB switchable PRG ROM bank at $8000.
 *
 * CHR:
 *  - 8KB switchable CHR ROM bank at $0000.
 */
public class Mapper66 implements Mapper {

    private Cartridge cartridge;
    private int prgBankSelect = 0;
    private int chrBankSelect = 0;

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 66 does not use IRQs
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x8000) {
            int bank = prgBankSelect;
            int offset = address - 0x8000;
            int finalAddr = (bank * 32768) + offset;
            return cartridge.getPrgRom()[finalAddr % cartridge.getPrgRom().length] & 0xFF;
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
            // Any write to the PRG ROM area selects the banks
            chrBankSelect = value & 0x03;
            prgBankSelect = (value >> 4) & 0x03;
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
        // GxROM uses CHR ROM, so writes are ignored.
    }

    @Override
    public void reset() {
        prgBankSelect = 0;
        chrBankSelect = 0;
    }
}
