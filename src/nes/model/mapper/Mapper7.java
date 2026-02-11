package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

public class Mapper7 implements Mapper {
    private Cartridge cartridge;
    private int prgBankSelect = 0;
    private int mirroringSelect = 0;
    private int prgBankMask = 0x07;

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        int numBanks = cartridge.getPrgRom().length / 0x8000;
        if (numBanks == 0) numBanks = 1;
        prgBankMask = 1;
        while (prgBankMask < numBanks) prgBankMask <<= 1;
        prgBankMask--;
        // Don't call reset() here - let the console control reset timing
        prgBankSelect = 0;
        mirroringSelect = 0;
    }

    @Override
    public void reset() {
        prgBankSelect = 0;
        mirroringSelect = 0;
        // Do NOT clear CHR-RAM here - the game populates it
    }

    @Override
    public void setBus(Bus bus) { }

    @Override
    public int cpuRead(int address) {
        if (address >= 0x8000) {
            int bank = prgBankSelect & prgBankMask;
            int offset = address & 0x7FFF;
            int prgAddr = (bank * 0x8000) + offset;
            byte[] prg = cartridge.getPrgRom();
            if (prgAddr < prg.length) {
                return prg[prgAddr] & 0xFF;
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
            prgBankSelect = value & 0x07;  // AxROM uses bits 0-2 for bank
            mirroringSelect = (value >> 4) & 1;
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
        address &= 0x1FFF;
        byte[] chr = cartridge.getChr();
        // CHR-RAM: always writable (Mapper 7 uses CHR-RAM)
        if (chr != null && address < chr.length) {
            chr[address] = (byte) value;
        }
    }

    public int getMirroringMode() {
        return mirroringSelect;
    }
}