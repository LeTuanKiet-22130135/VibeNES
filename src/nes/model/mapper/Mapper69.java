package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;
import java.util.Arrays;

/**
 * Mapper 69: Sunsoft FME-7 / 5A / 5B
 */
public class Mapper69 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    private int commandRegister = 0;
    private final int[] registers = new int[16];

    private final byte[] prgRam = new byte[8 * 1024];

    private int irqCounter = 0;
    private boolean irqEnabled = false;
    private boolean irqCounterEnabled = false;
    private boolean irqPending = false;

    public Mapper69() {
        // Gimmick! and several other FME-7 games require PRG RAM to be initialized to 0xFF.
        Arrays.fill(prgRam, (byte) 0xFF);
        reset();
    }

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        this.bus = bus;
    }

    private int getPrgBank(int bankRegister) {
        int bank = bankRegister & 0x3F;
        int numBanks = cartridge.getPrgRom().length / 8192;
        if (numBanks == 0) return 0;
        return bank % numBanks;  // Safe for all sizes including 384KB
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x6000 && address <= 0x7FFF) {
            int reg8 = registers[0x08];
            boolean enabled = (reg8 & 0x80) != 0;
            boolean isRam = (reg8 & 0x40) != 0;

            if (!enabled) return 0; // Open bus behavior

            if (isRam) {
                return prgRam[address - 0x6000] & 0xFF;
            } else {
                int bank = getPrgBank(reg8);
                int finalAddr = (bank * 8192) + (address & 0x1FFF);
                return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
        }

        if (address >= 0x8000) {
            int bank = 0;
            int offset = address & 0x1FFF;

            if (address < 0xA000) bank = getPrgBank(registers[0x09]);
            else if (address < 0xC000) bank = getPrgBank(registers[0x0A]);
            else if (address < 0xE000) bank = getPrgBank(registers[0x0B]);
            else {
                // The last PRG bank is always hardwired to the final physical bank
                int numBanks = cartridge.getPrgRom().length / 8192;
                bank = (numBanks > 0) ? numBanks - 1 : 0;
            }

            int finalAddr = (bank * 8192) + offset;
            if (finalAddr < cartridge.getPrgRom().length) {
                return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value, long cycles) {
        address &= 0xFFFF;
        value &= 0xFF;

        if (address >= 0x6000 && address <= 0x7FFF) {
            int reg8 = registers[0x08];
            boolean enabled = (reg8 & 0x80) != 0;
            boolean isRam = (reg8 & 0x40) != 0;

            if (enabled && isRam) {
                prgRam[address - 0x6000] = (byte) value;
            }
            return;
        }

        if (address >= 0x8000 && address < 0xA000) {
            commandRegister = value & 0x0F;
        }
        else if (address >= 0xA000 && address < 0xC000) {
            registers[commandRegister] = value;

            if (commandRegister == 0x0D) {
                irqEnabled = (value & 0x01) != 0;
                irqCounterEnabled = (value & 0x80) != 0;
                irqPending = false; // Writing to 0x0D acknowledges the IRQ
                if (bus != null) bus.clearIrq();
            } else if (commandRegister == 0x0E) {
                irqCounter = (irqCounter & 0xFF00) | value;
            } else if (commandRegister == 0x0F) {
                irqCounter = (irqCounter & 0x00FF) | (value << 8);
            }
        }
    }

    @Override
    public void cpuWrite(int address, int value) {
        cpuWrite(address, value, 0);
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        int bank = 0;
        int offset = address & 0x03FF;

        if (address < 0x0400) bank = registers[0];
        else if (address < 0x0800) bank = registers[1];
        else if (address < 0x0C00) bank = registers[2];
        else if (address < 0x1000) bank = registers[3];
        else if (address < 0x1400) bank = registers[4];
        else if (address < 0x1800) bank = registers[5];
        else if (address < 0x1C00) bank = registers[6];
        else bank = registers[7];

        byte[] chr = cartridge.getChr();
        if (chr.length > 0) {
            bank &= 0xFF; // Constrain to 8-bit bank index
            int finalAddr = (bank * 1024) + offset;
            return chr[finalAddr % chr.length] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        if (!cartridge.isChrRam()) return;

        address &= 0x1FFF;
        int bank = 0;
        int offset = address & 0x03FF;

        if (address < 0x0400) bank = registers[0];
        else if (address < 0x0800) bank = registers[1];
        else if (address < 0x0C00) bank = registers[2];
        else if (address < 0x1000) bank = registers[3];
        else if (address < 0x1400) bank = registers[4];
        else if (address < 0x1800) bank = registers[5];
        else if (address < 0x1C00) bank = registers[6];
        else bank = registers[7];

        byte[] chr = cartridge.getChr();
        if (chr.length > 0) {
            bank &= 0xFF;
            int finalAddr = (bank * 1024) + offset;
            chr[finalAddr % chr.length] = (byte) value;
        }
    }

    @Override
    public void stepCpu() {
        if (irqCounterEnabled) {
            irqCounter--;
            if (irqCounter < 0) {
                irqCounter = 0xFFFF;
                irqPending = true;
            }
        }

        if (irqPending && irqEnabled && bus != null) {
            bus.requestIrq();
        }
    }

    @Override
    public void reset() {
        commandRegister = 0;
        Arrays.fill(registers, 0);
        irqCounter = 0;
        irqEnabled = false;
        irqCounterEnabled = false;
        irqPending = false;
    }

    public int getMirroringMode() {
        return registers[0x0C] & 0x03;
    }
}