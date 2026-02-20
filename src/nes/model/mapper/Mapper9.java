package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 9: MMC2
 *
 * Exclusive to Mike Tyson's Punch-Out!!
 * Automatically switches CHR banks based on the address being accessed by the PPU.
 */
public class Mapper9 implements Mapper {

    private Cartridge cartridge;
    private int prgBankSelect = 0;

    // CHR bank selection registers
    private int chrBank0Select0 = 0;
    private int chrBank0Select1 = 0;
    private int chrBank1Select0 = 0;
    private int chrBank1Select1 = 0;

    // Latch state: Initialized to $FE8 state (value 1)
    private int chrLatch0 = 1;
    private int chrLatch1 = 1;

    private int mirroring = 0; // 0=Vertical, 1=Horizontal

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 9 does not use IRQs
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x8000) {
            if (address < 0xA000) {
                // Switchable 8KB PRG bank
                int bank = prgBankSelect;
                int offset = address - 0x8000;
                int finalAddr = (bank * 8192) + offset;
                return cartridge.getPrgRom()[finalAddr % cartridge.getPrgRom().length] & 0xFF;
            } else {
                // Fixed last 24KB of PRG ROM
                int numBanks = cartridge.getPrgRom().length / 8192;
                int bank = numBanks - 3;
                int offset = address - 0xA000;
                int finalAddr = (bank * 8192) + offset;
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

        // PRG bank selection ($A000-$AFFF), uses low 4 bits
        if (address >= 0xA000 && address <= 0xAFFF) {
            prgBankSelect = value & 0x0F;
        }
        // CHR bank selection ($B000-$EFFF), uses low 5 bits for up to 128KB ROM
        else if (address >= 0xB000 && address <= 0xBFFF) {
            chrBank0Select0 = value & 0x1F;
        } else if (address >= 0xC000 && address <= 0xCFFF) {
            chrBank0Select1 = value & 0x1F;
        } else if (address >= 0xD000 && address <= 0xDFFF) {
            chrBank1Select0 = value & 0x1F;
        } else if (address >= 0xE000 && address <= 0xEFFF) {
            chrBank1Select1 = value & 0x1F;
        }
        // Mirroring selection ($F000-$FFFF), uses bit 0
        else if (address >= 0xF000 && address <= 0xFFFF) {
            mirroring = value & 1;
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;

        // STEP 1: Calculate current bank and read data BEFORE updating latch.
        int bank;
        int offset;
        if (address < 0x1000) {
            bank = (chrLatch0 == 0) ? chrBank0Select0 : chrBank0Select1;
            offset = address & 0x0FFF;
        } else {
            bank = (chrLatch1 == 0) ? chrBank1Select0 : chrBank1Select1;
            offset = (address - 0x1000) & 0x0FFF;
        }

        int finalAddr = (bank * 4096) + offset;
        int data = cartridge.getChr()[finalAddr % cartridge.getChr().length] & 0xFF;

        // STEP 2: Update Latch AFTER data has been fetched
        updateLatches(address);

        return data;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x1FFF;
        updateLatches(address);
    }

    private void updateLatches(int address) {
        int match = address & 0x1FF8;

        if (match == 0x0FD8) {
            chrLatch0 = 0;
        } else if (match == 0x0FE8) {
            chrLatch0 = 1;
        } else if (match == 0x1FD8) {
            chrLatch1 = 0;
        } else if (match == 0x1FE8) {
            chrLatch1 = 1;
        }
    }

    @Override
    public void reset() {
        prgBankSelect = 0;
        chrBank0Select0 = 0;
        chrBank0Select1 = 0;
        chrBank1Select0 = 0;
        chrBank1Select1 = 0;
        chrLatch0 = 1; // Power-on state $FE8
        chrLatch1 = 1;
        mirroring = 0;
    }

    public int getMirroringMode() {
        return mirroring; // 0=Vertical, 1=Horizontal
    }
}