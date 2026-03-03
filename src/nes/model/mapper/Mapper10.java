package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 10: MMC4
 * Used in Famicom Wars and Fire Emblem.
 * Similar to MMC2 (Mapper 9), but with 16KB PRG banking and 8KB PRG RAM.
 */
public class Mapper10 implements Mapper {

    private Cartridge cartridge;

    // 8KB PRG RAM for save data (Crucial for Fire Emblem)
    private final byte[] prgRam = new byte[8192];

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
        // Mapper 10 does not use IRQs
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        // Read from PRG RAM ($6000 - $7FFF)
        if (address >= 0x6000 && address <= 0x7FFF) {
            return prgRam[address - 0x6000] & 0xFF;
        }

        if (address >= 0x8000) {
            int offset = address & 0x3FFF;
            int bank;
            if (address < 0xC000) {
                // Switchable 16KB PRG bank at $8000-$BFFF
                bank = prgBankSelect;
            } else {
                // Fixed last 16KB PRG bank at $C000-$FFFF
                int numBanks = cartridge.getPrgRom().length / 16384;
                bank = numBanks - 1;
            }
            int finalAddr = (bank * 16384) + offset;
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
        value &= 0xFF;

        // Write to PRG RAM ($6000 - $7FFF)
        if (address >= 0x6000 && address <= 0x7FFF) {
            prgRam[address - 0x6000] = (byte) value;
            return;
        }

        // PRG bank selection ($A000-$AFFF), uses low 4 bits
        if (address >= 0xA000 && address <= 0xAFFF) {
            prgBankSelect = value & 0x0F;
        }
        // CHR bank selection ($B000-$EFFF), uses low 5 bits
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
        else if (address >= 0xF000) {
            mirroring = value & 1;
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;

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

        updateLatches(address);

        return data;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x1FFF;
        updateLatches(address);
    }

    private void updateLatches(int address) {
        // MMC4 latches are triggered by reading ranges, not just single addresses.
        if (address >= 0x0FD8 && address <= 0x0FDF) {
            chrLatch0 = 0;
        } else if (address >= 0x0FE8 && address <= 0x0FEF) {
            chrLatch0 = 1;
        } else if (address >= 0x1FD8 && address <= 0x1FDF) {
            chrLatch1 = 0;
        } else if (address >= 0x1FE8 && address <= 0x1FEF) {
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
        // Optional: you can clear PRG RAM here, but battery-backed RAM usually persists.
    }

    public int getMirroringMode() {
        return mirroring; // 0=Vertical, 1=Horizontal
    }
}