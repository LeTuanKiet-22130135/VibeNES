package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 16 (Bandai FCG series)
 */
public class Mapper16 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    private int prgBank = 0;
    private final int[] chrBanks = new int[8];
    private int mirroring = 0;

    // Bộ đếm IRQ trực tiếp 16-bit
    private int irqCounter = 0;
    private boolean irqEnabled = false;

    public Mapper16() {
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

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x8000 && address <= 0xBFFF) {
            int prgBanks = cartridge.getPrgRom().length / 16384;
            int bank = prgBank % prgBanks;
            return cartridge.getPrgRom()[bank * 16384 + (address - 0x8000)] & 0xFF;
        } else if (address >= 0xC000 && address <= 0xFFFF) {
            int prgBanks = cartridge.getPrgRom().length / 16384;
            return cartridge.getPrgRom()[(prgBanks - 1) * 16384 + (address - 0xC000)] & 0xFF;
        } else if (address >= 0x6000 && address <= 0x7FFF) {
            // Trả về 0 để tránh việc trò chơi bị treo khi cố đọc dải địa chỉ này
            return 0x00;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;

        // Bắt các lệnh ghi từ $6000 trở lên.
        // FCG-1/2 dùng dải $6000-$7FFF, LZ93D50 dùng dải $8000-$FFFF.
        if (address >= 0x6000) {
            int reg = address & 0x0F; // Lấy 4 bit cuối để xác định thanh ghi
            switch (reg) {
                case 0x0: case 0x1: case 0x2: case 0x3:
                case 0x4: case 0x5: case 0x6: case 0x7:
                    chrBanks[reg] = value;
                    break;
                case 0x8:
                    prgBank = value;
                    break;
                case 0x9:
                    mirroring = value & 0x03;
                    break;
                case 0xA:
                    irqEnabled = (value & 0x01) != 0;
                    // Ghi vào thanh ghi này sẽ xác nhận và xóa cờ ngắt IRQ
                    if (bus != null) {
                        bus.clearIrq();
                    }
                    break;
                case 0xB:
                    // Ghi vào 8 byte thấp của bộ đếm
                    irqCounter = (irqCounter & 0xFF00) | (value & 0xFF);
                    break;
                case 0xC:
                    // Ghi vào 8 byte cao của bộ đếm
                    irqCounter = (irqCounter & 0x00FF) | ((value & 0xFF) << 8);
                    break;
                case 0xD:
                    // Bỏ qua: Thanh ghi điều khiển EEPROM để lưu game
                    break;
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            int bank = address / 1024;
            int offset = address % 1024;
            int chrBank = chrBanks[bank];
            int numChrBanks = cartridge.getChr().length / 1024;
            if (numChrBanks > 0) {
                return cartridge.getChr()[(chrBank % numChrBanks) * 1024 + offset] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        if (cartridge != null && cartridge.isChrRam() && address >= 0x0000 && address <= 0x1FFF) {
            int bank = address / 1024;
            int offset = address % 1024;
            int chrBank = chrBanks[bank];
            int numChrBanks = cartridge.getChr().length / 1024;
            if (numChrBanks > 0) {
                cartridge.getChr()[(chrBank % numChrBanks) * 1024 + offset] = (byte) value;
            }
        }
    }

    @Override
    public void stepCpu() {
        if (irqEnabled) {
            if (irqCounter > 0) {
                irqCounter--;
                if (irqCounter == 0) {
                    if (bus != null) {
                        bus.requestIrq();
                    }
                }
            }
        }
    }

    @Override
    public void reset() {
        prgBank = 0;
        for (int i = 0; i < 8; i++) chrBanks[i] = 0;
        mirroring = 0;
        irqEnabled = false;
        irqCounter = 0;
    }

    public int getMirroringMode() {
        return mirroring;
    }
}