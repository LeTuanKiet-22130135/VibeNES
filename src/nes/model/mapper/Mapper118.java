package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

import java.util.Arrays;

/**
 * Mapper 118: TxSROM (MMC3 Variant)
 *
 * A variant of MMC3 where mirroring is controlled by the CHR bank's MSB.
 * Specifically, the A10 of the nametable is connected to the CHR A17 (or A15/A16/A18 depending on wiring).
 * For TxSROM, it's typically the highest bit of the CHR bank selected for the current nametable region.
 */
public class Mapper118 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    // MMC3 Registers
    private int targetRegister = 0;
    private int prgBankMode = 0;
    private int chrInversion = 0;
    private final int[] registers = new int[8];

    // PRG RAM (though TxSROM usually doesn't have it, MMC3 logic often includes it)
    private boolean prgRamEnabled = true;
    private boolean prgRamWritesEnabled = true;
    private final byte[] prgRam = new byte[8 * 1024];

    // IRQ
    private int irqCounter = 0;
    private int irqLatch = 0;
    private boolean irqReload = false;
    private boolean irqEnabled = false;

    // A12 detection for IRQ
    private int a12Filter = 0;
    private byte[] vram;

    public Mapper118() {
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
        if (a12 == 1) {
            // Chỉ đếm IRQ nếu A12 đã ở mức 0 trong ít nhất 3 chu kỳ PPU liên tiếp
            // Điều này giúp lọc bỏ các nhiễu từ quá trình PPU đọc Sprite
            if (a12Filter >= 8) {
                clockIrqCounter();
            }
            a12Filter = 0; // Reset bộ lọc
        } else {
            a12Filter++;   // Tăng bộ đếm khi A12 ở mức 0
        }
    }

    private void clockIrqCounter() {
        int oldCounter = irqCounter; // Lưu lại giá trị cũ trước khi thay đổi

        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch;
            irqReload = false;
        } else {
            irqCounter--;
        }

        // Chuẩn MMC3B: Chỉ kích hoạt ngắt nếu counter thực sự giảm từ một số > 0 xuống 0
        if (oldCounter != 0 && irqCounter == 0 && irqEnabled) {
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
                    // Mapper 118 mirroring is handled by CHR banks
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
                // Khi game can thiệp bật/tắt IRQ, ta cần clear cờ ngắt để CPU không bị dính vòng lặp
                if (bus != null) {
                    bus.clearIrq();
                }
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        checkA12(address);

        // Chặn quyền đọc Nametable (0x2000 - 0x3EFF) để tự phân trang
        if (address >= 0x2000 && address < 0x3F00) {
            if (vram != null) {
                int bank = getCirBank(address);
                return vram[bank * 0x400 + (address & 0x3FF)] & 0xFF;
            }
            return 0;
        }

        // Đọc CHR ROM (0x0000 - 0x1FFF)
        address &= 0x1FFF;
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
        if (chr.length > 0) {
            return chr[finalAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        checkA12(address);

        // Chặn quyền ghi Nametable (0x2000 - 0x3EFF)
        if (address >= 0x2000 && address < 0x3F00) {
            if (vram != null) {
                int bank = getCirBank(address);
                vram[bank * 0x400 + (address & 0x3FF)] = (byte) value;
            }
            return;
        }

        // Ghi CHR RAM (0x0000 - 0x1FFF)
        address &= 0x1FFF;
        if (cartridge.isChrRam()) {
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
            if (finalAddr < cartridge.getChr().length) {
                cartridge.getChr()[finalAddr] = (byte) value;
            }
        }
    }

    @Override
    public void reset() {
        targetRegister = 0;
        prgBankMode = 0;
        chrInversion = 0;
        Arrays.fill(registers, 0);
        prgRamEnabled = true;
        prgRamWritesEnabled = true;
        irqCounter = 0;
        irqLatch = 0;
        irqReload = false;
        irqEnabled = false;
        a12Filter = 0; // Khởi tạo bộ lọc A12
    }

    public int getMirroringMode() {
        int nt0, nt1, nt2, nt3;

        // Xác định bit cao nhất của các CHR bank đang được gán cho 4 góc Nametable
        if (chrInversion == 0) {
            nt0 = (registers[0] & 0x80) >> 7;
            nt1 = nt0;
            nt2 = (registers[1] & 0x80) >> 7;
            nt3 = nt2;
        } else {
            nt0 = (registers[2] & 0x80) >> 7;
            nt1 = (registers[3] & 0x80) >> 7;
            nt2 = (registers[4] & 0x80) >> 7;
            nt3 = (registers[5] & 0x80) >> 7;
        }

        // Horizontal Mirroring: Nửa trên và nửa dưới dùng các page khác nhau
        if (nt0 == nt1 && nt2 == nt3 && nt0 != nt2) {
            return 1;
        }

        // Vertical Mirroring: Nửa trái và nửa phải dùng các page khác nhau
        if (nt0 == nt2 && nt1 == nt3 && nt0 != nt1) {
            return 0;
        }

        // Single Screen: Cả 4 góc đều trỏ về cùng 1 page
        if (nt0 == nt1 && nt1 == nt2 && nt2 == nt3) {
            return nt0 == 0 ? 2 : 3;
        }

        // Mặc định trả về Vertical nếu cấu hình không khớp chuẩn
        return 0;
    }

    public void setPpuVram(byte[] vram) {
        this.vram = vram;
    }

    private int getCirBank(int address) {
        int ntAddr = address & 0x0FFF;
        int a11 = (ntAddr >> 11) & 1;
        int a10 = (ntAddr >> 10) & 1;

        // Mapper 118 nối thẳng CIRAM A10 vào MSB của CHR bank
        if (chrInversion == 0) {
            if (a11 == 0) return (registers[0] & 0x80) >> 7;
            else return (registers[1] & 0x80) >> 7;
        } else {
            if (a11 == 0 && a10 == 0) return (registers[2] & 0x80) >> 7;
            if (a11 == 0 && a10 == 1) return (registers[3] & 0x80) >> 7;
            if (a11 == 1 && a10 == 0) return (registers[4] & 0x80) >> 7;
            return (registers[5] & 0x80) >> 7;
        }
    }
}
