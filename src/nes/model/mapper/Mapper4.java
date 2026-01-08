package nes. model. mapper;

import nes.model.Bus;
import nes.model. Cartridge;
import java.util.Arrays;

public class Mapper4 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    private int targetRegister = 0;
    private int prgBankMode = 0;
    private int chrInversion = 0;

    private final int[] registers = new int[8];

    private int mirroring = 0;
    private boolean prgRamEnabled = true;
    private boolean prgRamWritesEnabled = true;

    // IRQ
    private int irqCounter = 0;
    private int irqLatch = 0;
    private boolean irqReload = false;
    private boolean irqEnabled = false;

    // A12 detection
    private int lastA12 = 0;

    private final byte[] prgRam = new byte[8 * 1024];

    public Mapper4() {
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

    private void checkA12(int address) {
        int a12 = (address >> 12) & 1;

        // Clock on rising edge of A12
        if (a12 == 1 && lastA12 == 0) {
            clockIrqCounter();
        }

        lastA12 = a12;
    }

    private void clockIrqCounter() {
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch;
            irqReload = false;
        } else {
            irqCounter--;
        }

        if (irqCounter == 0 && irqEnabled) {
            if (bus != null) {
                bus.requestIrq();
            }
        }
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (prgRamEnabled) return prgRam[address - 0x6000] & 0xFF;
            return 0;
        }

        if (address >= 0x8000) {
            int bank;
            int offset = address & 0x1FFF;
            int numBanks = cartridge.getPrgRom().length / 8192;
            if (numBanks == 0) numBanks = 1;

            if (prgBankMode == 0) {
                if (address < 0xA000) bank = registers[6];
                else if (address < 0xC000) bank = registers[7];
                else if (address < 0xE000) bank = numBanks - 2;
                else bank = numBanks - 1;
            } else {
                if (address < 0xA000) bank = numBanks - 2;
                else if (address < 0xC000) bank = registers[7];
                else if (address < 0xE000) bank = registers[6];
                else bank = numBanks - 1;
            }

            bank %= numBanks;
            int finalAddr = bank * 8192 + offset;
            if (finalAddr < cartridge.getPrgRom().length) {
                return cartridge. getPrgRom()[finalAddr] & 0xFF;
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

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (prgRamEnabled && prgRamWritesEnabled) {
                prgRam[address - 0x6000] = (byte) value;
            }
            return;
        }

        if (address >= 0x8000) {
            boolean even = (address & 1) == 0;

            if (address < 0xA000) {
                if (even) {
                    targetRegister = value & 0x07;
                    prgBankMode = (value >> 6) & 1;
                    chrInversion = (value >> 7) & 1;
                } else {
                    registers[targetRegister] = value;
                }
            } else if (address < 0xC000) {
                if (even) {
                    mirroring = value & 1;
                } else {
                    prgRamEnabled = (value & 0x80) != 0;
                    prgRamWritesEnabled = (value & 0x40) == 0;
                }
            } else if (address < 0xE000) {
                if (even) {
                    irqLatch = value;
                } else {
                    irqCounter = 0;
                    irqReload = true;
                }
            } else {
                irqEnabled = !even;
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;
        checkA12(address);
        return readChr(address);
    }

    private int readChr(int address) {
        int bank;
        int offset;

        int numChrBanks = cartridge. getChr().length / 1024;
        if (numChrBanks == 0) numChrBanks = 1;

        if (chrInversion == 0) {
            if (address < 0x0400) { bank = registers[0] & 0xFE; offset = address & 0x03FF; }
            else if (address < 0x0800) { bank = (registers[0] & 0xFE) | 1; offset = address & 0x03FF; }
            else if (address < 0x0C00) { bank = registers[1] & 0xFE; offset = address & 0x03FF; }
            else if (address < 0x1000) { bank = (registers[1] & 0xFE) | 1; offset = address & 0x03FF; }
            else if (address < 0x1400) { bank = registers[2]; offset = address & 0x03FF; }
            else if (address < 0x1800) { bank = registers[3]; offset = address & 0x03FF; }
            else if (address < 0x1C00) { bank = registers[4]; offset = address & 0x03FF; }
            else { bank = registers[5]; offset = address & 0x03FF; }
        } else {
            if (address < 0x0400) { bank = registers[2]; offset = address & 0x03FF; }
            else if (address < 0x0800) { bank = registers[3]; offset = address & 0x03FF; }
            else if (address < 0x0C00) { bank = registers[4]; offset = address & 0x03FF; }
            else if (address < 0x1000) { bank = registers[5]; offset = address & 0x03FF; }
            else if (address < 0x1400) { bank = registers[0] & 0xFE; offset = address & 0x03FF; }
            else if (address < 0x1800) { bank = (registers[0] & 0xFE) | 1; offset = address & 0x03FF; }
            else if (address < 0x1C00) { bank = registers[1] & 0xFE; offset = address & 0x03FF; }
            else { bank = (registers[1] & 0xFE) | 1; offset = address & 0x03FF; }
        }

        bank %= numChrBanks;
        int finalAddr = bank * 1024 + offset;
        byte[] chr = cartridge.getChr();
        if (chr. length > 0) {
            return chr[finalAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x1FFF;
        checkA12(address);

        int bank;
        int offset;

        int numChrBanks = cartridge.getChr().length / 1024;
        if (numChrBanks == 0) numChrBanks = 1;

        if (chrInversion == 0) {
            if (address < 0x0400) { bank = registers[0] & 0xFE; offset = address & 0x03FF; }
            else if (address < 0x0800) { bank = (registers[0] & 0xFE) | 1; offset = address & 0x03FF; }
            else if (address < 0x0C00) { bank = registers[1] & 0xFE; offset = address & 0x03FF; }
            else if (address < 0x1000) { bank = (registers[1] & 0xFE) | 1; offset = address & 0x03FF; }
            else if (address < 0x1400) { bank = registers[2]; offset = address & 0x03FF; }
            else if (address < 0x1800) { bank = registers[3]; offset = address & 0x03FF; }
            else if (address < 0x1C00) { bank = registers[4]; offset = address & 0x03FF; }
            else { bank = registers[5]; offset = address & 0x03FF; }
        } else {
            if (address < 0x0400) { bank = registers[2]; offset = address & 0x03FF; }
            else if (address < 0x0800) { bank = registers[3]; offset = address & 0x03FF; }
            else if (address < 0x0C00) { bank = registers[4]; offset = address & 0x03FF; }
            else if (address < 0x1000) { bank = registers[5]; offset = address & 0x03FF; }
            else if (address < 0x1400) { bank = registers[0] & 0xFE; offset = address & 0x03FF; }
            else if (address < 0x1800) { bank = (registers[0] & 0xFE) | 1; offset = address & 0x03FF; }
            else if (address < 0x1C00) { bank = registers[1] & 0xFE; offset = address & 0x03FF; }
            else { bank = (registers[1] & 0xFE) | 1; offset = address & 0x03FF; }
        }

        bank %= numChrBanks;
        int finalAddr = bank * 1024 + offset;
        byte[] chr = cartridge.getChr();
        if (cartridge.isChrRam() && chr.length > 0) {
            chr[finalAddr] = (byte) value;
        }
    }

    @Override
    public void reset() {
        targetRegister = 0;
        prgBankMode = 0;
        chrInversion = 0;
        mirroring = 0;
        prgRamEnabled = true;
        prgRamWritesEnabled = true;
        irqCounter = 0;
        irqLatch = 0;
        irqReload = false;
        irqEnabled = false;
        lastA12 = 0;
        Arrays.fill(registers, 0);
    }

    public int getMirroringMode() {
        return mirroring;
    }
}