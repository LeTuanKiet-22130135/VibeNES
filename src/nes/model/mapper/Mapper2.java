package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 2: UxROM
 *
 * This is a common mapper that uses a single register to switch PRG ROM banks.
 *
 * PRG:
 *  - $8000-$BFFF: 16 KB switchable PRG ROM bank.
 *  - $C000-$FFFF: 16 KB PRG ROM bank, fixed to the last bank.
 *
 * CHR:
 *  - 8 KB of CHR RAM or ROM, with no banking.
 */
public class Mapper2 implements Mapper {

    private Cartridge cartridge;
    private int prgBankSelect = 0;

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 2 does not use IRQs, so we don't need to store the bus.
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x8000) {
            if (address < 0xC000) {
                // Switchable bank
                int bank = prgBankSelect;
                int offset = address - 0x8000;
                int finalAddr = (bank * 16384) + offset;
                return cartridge.getPrgRom()[finalAddr % cartridge.getPrgRom().length] & 0xFF;
            } else {
                // Fixed to last bank
                int numBanks = cartridge.getPrgRom().length / 16384;
                int bank = numBanks - 1;
                int offset = address - 0xC000;
                int finalAddr = (bank * 16384) + offset;
                return cartridge.getPrgRom()[finalAddr % cartridge.getPrgRom().length] & 0xFF;
            }
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
            // Any write to the PRG ROM area selects the bank
            prgBankSelect = value & 0x0F; // Lower 4 bits select the bank
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        return cartridge.getChr()[address] & 0xFF;
    }

    @Override
    public void ppuWrite(int address, int value) {
        if (cartridge.isChrRam()) {
            address &= 0x1FFF;
            cartridge.getChr()[address] = (byte) value;
        }
    }

    @Override
    public void reset() {
        prgBankSelect = 0;
    }
}
