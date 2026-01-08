package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 1: MMC1
 *
 * A common mapper used in many popular games (e.g., Zelda, Metroid).
 * It uses a serial shift register to configure bank switching and mirroring.
 */
public class Mapper1 implements Mapper {

    private Cartridge cartridge;

    // Shift register (5 writes)
    private int shiftRegister;
    private int shiftCount;

    // MMC1 registers
    private int control;
    private int chrBank0;
    private int chrBank1;
    private int prgBank;

    private long lastWriteCycle = -1;

    private final byte[] prgRam = new byte[8 * 1024];
    private boolean prgRamEnabled = true; // always enabled (bit 4 of reg3 ignored here)

    public Mapper1() {
        reset();
    }

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 1 does not use IRQs, so we don't need to store the bus.
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (prgRamEnabled) return prgRam[address - 0x6000] & 0xFF;
            return 0;
        }

        if (address >= 0x8000) {
            int prgMode = (control >> 2) & 0x03;
            int bank = 0;
            int offset;

            int numBanks = cartridge.getPrgRom().length / 16384;
            if (numBanks <= 0) numBanks = 1;

            switch (prgMode) {
                case 0:
                case 1: { // 32 KB mode (ignore bit 0)
                    bank = (prgBank & 0x0E); // Ignore lowest bit
                    bank %= numBanks; // Wrap to available banks
                    offset = address - 0x8000;
                    break;
                }
                case 2: { // First bank fixed at $8000, switch bank at $C000
                    if (address < 0xC000) {
                        bank = 0; // Fixed to first bank
                        offset = address - 0x8000;
                    } else {
                        bank = prgBank & 0x0F; // Switchable bank
                        bank %= numBanks; // Wrap to available banks
                        offset = address - 0xC000;
                    }
                    break;
                }
                case 3:
                default: { // Last bank fixed at $C000, switch bank at $8000
                    if (address < 0xC000) {
                        bank = prgBank & 0x0F; // Switchable bank
                        bank %= numBanks; // Wrap to available banks
                        offset = address - 0x8000;
                    } else {
                        bank = numBanks - 1; // Fixed to last bank
                        offset = address - 0xC000;
                    }
                    break;
                }
            }

            int finalAddr = bank * 16384 + (offset & 0x3FFF);
            if (finalAddr < cartridge.getPrgRom().length) {
                return cartridge.getPrgRom()[finalAddr] & 0xFF;
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
        value &= 0xFF;

        // Writes below $8000: only PRG RAM region honored
        if (address >= 0x6000 && address <= 0x7FFF) {
            if (prgRamEnabled) prgRam[address - 0x6000] = (byte) value;
            return;
        }
        if (address < 0x8000) return;

        // *** MMC1 timing quirk: ignore writes that are too close together ***
        if (lastWriteCycle >= 0 && (cycles - lastWriteCycle) < 2) {
            return; // ignore this write
        }
        lastWriteCycle = cycles;

        // Reset command
        if ((value & 0x80) != 0) {
            resetShiftRegister();
            control |= 0x0C; // force 16 KB, fix last bank
            return;
        }

        // Shift in bit (LSB first), commit on 5th write
        shiftRegister = (shiftRegister >> 1) | ((value & 1) << 4);
        shiftCount++;

        if (shiftCount == 5) {
            int data = shiftRegister & 0x1F;
            int reg = (address >> 13) & 0x03;
            switch (reg) {
                case 0: // Control
                    control = data;
                    break;
                case 1: // CHR bank 0
                    chrBank0 = data;
                    break;
                case 2: // CHR bank 1
                    chrBank1 = data;
                    break;
                case 3: // PRG bank (bit 4 would disable PRG RAM; we keep RAM enabled)
                    prgBank = data & 0x0F;
                    prgRamEnabled = (data & 0x10) == 0; // Honor PRG RAM disable
                    break;
            }
            resetShiftRegister();
        }
    }

    private void resetShiftRegister() {
        shiftRegister = 0x00; // Clear shift register
        shiftCount = 0;
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        int bank;
        int offset;

        byte[] chr = cartridge.getChr();
        int chr4kBanks = (chr.length / 4096);
        if (chr4kBanks <= 0) chr4kBanks = 1;

        boolean chr8k = (control & 0x10) == 0;
        int finalAddr;
        if (chr8k) {
            int pairCount = Math.max(1, chr4kBanks / 2);
            int pair = (chrBank0 >> 1) % pairCount;
            int base4k = pair * 2;
            int off = address & 0x1FFF; // within 8KB window
            finalAddr = base4k * 4096 + off;
        } else {
            if (address < 0x1000) {
                bank = ((chrBank0 % chr4kBanks) + chr4kBanks) % chr4kBanks;
                finalAddr = bank * 4096 + (address & 0x0FFF);
            } else {
                bank = ((chrBank1 % chr4kBanks) + chr4kBanks) % chr4kBanks;
                finalAddr = bank * 4096 + ((address - 0x1000) & 0x0FFF);
            }
        }

        if (finalAddr >= 0 && finalAddr < chr.length) {
            return chr[finalAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x1FFF;
        int bank;
        int offset;

        byte[] chr = cartridge.getChr();
        int chr4kBanks = (chr.length / 4096);
        if (chr4kBanks <= 0) chr4kBanks = 1;

        boolean chr8k = (control & 0x10) == 0;
        int finalAddr;
        if (chr8k) {
            int pairCount = Math.max(1, chr4kBanks / 2);
            int pair = (chrBank0 >> 1) % pairCount;
            int base4k = pair * 2;
            int off = address & 0x1FFF;
            finalAddr = base4k * 4096 + off;
        } else {
            if (address < 0x1000) {
                bank = ((chrBank0 % chr4kBanks) + chr4kBanks) % chr4kBanks;
                finalAddr = bank * 4096 + (address & 0x0FFF);
            } else {
                bank = ((chrBank1 % chr4kBanks) + chr4kBanks) % chr4kBanks;
                finalAddr = bank * 4096 + ((address - 0x1000) & 0x0FFF);
            }
        }

        if (cartridge.isChrRam() && chr.length > 0 && finalAddr >= 0 && finalAddr < chr.length) {
            chr[finalAddr] = (byte) value;
        }
    }

    @Override
    public void reset() {
        resetShiftRegister();
        control = 0x0C; // Power-on default: PRG mode 3 (fix last bank), mirroring 00 (Single Screen A)
        chrBank0 = 0;
        chrBank1 = 0;
        prgBank = 0;
        prgRamEnabled = true; // keep PRG RAM enabled
        lastWriteCycle = -1;
    }
    
    public int getMirroringMode() {
        return control & 0x03;
    }
}
