package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 7: AxROM
 *
 * - 32KB switchable PRG ROM at $8000-$FFFF
 * - 8KB CHR-RAM at PPU $0000-$1FFF
 * - Single-screen mirroring (switchable between nametable A and B)
 *
 * Note: AxROM has bus conflicts, but they are typically handled by the game
 * writing to an address that contains the same value being written.
 */
public class Mapper7 implements Mapper {

    private Cartridge cartridge;
    private int prgBankSelect = 0;
    private int mirroringSelect = 0;
    private int prgBankMask = 0x07; // Will be calculated based on ROM size

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        // Calculate the bank mask based on actual PRG ROM size
        // PRG ROM is in 32KB banks for AxROM
        int numBanks = cartridge.getPrgRom().length / 0x8000;
        if (numBanks == 0) numBanks = 1;
        // Calculate mask (next power of 2 minus 1)
        prgBankMask = 1;
        while (prgBankMask < numBanks) {
            prgBankMask <<= 1;
        }
        prgBankMask--; // Convert to mask (e.g., 8 banks -> mask of 0x07)
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 7 does not use IRQs
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x8000) {
            byte[] prg = cartridge. getPrgRom();
            int numBanks = prg. length / 0x8000;
            if (numBanks == 0) numBanks = 1;

            int bank = prgBankSelect % numBanks;
            int offset = address & 0x7FFF;
            return prg[(bank * 0x8000) + offset] & 0xFF;
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
            // FIX: Disable bus conflict emulation.
            // Many ROM dumps are not accurate enough for "value & cpuRead(address)"
            // to work correctly, causing Battletoads to fail nametable switches.
            int resolvedValue = value;

            // Update PRG and Mirroring
            prgBankSelect = resolvedValue & prgBankMask;
            mirroringSelect = (resolvedValue >> 4) & 1;

            // Log state (Optional: keep for debug)
            /*
            System.out.printf(
                    "Mapper 7 Write: Addr=$%04X, Value=$%02X, PRGBank=%d, Mirroring=%d%n",
                    address, resolvedValue, prgBankSelect, mirroringSelect
            );
            */
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        byte[] chr = cartridge.getChr();
        if (chr != null && address < chr.length) {
            return chr[address] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        // CHR-RAM is always writable on Mapper 7
        address &= 0x1FFF;
        byte[] chr = cartridge.getChr();
        if (chr != null && address < chr.length) {
            chr[address] = (byte) value;
        }
    }

    @Override
    public void reset() {
        prgBankSelect = 0;
        mirroringSelect = 0;
    }

    public int getMirroringMode() {
        return mirroringSelect;
    }
}