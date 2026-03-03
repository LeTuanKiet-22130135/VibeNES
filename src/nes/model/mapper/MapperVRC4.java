package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;
import java.util.Arrays;


public class MapperVRC4 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;
    private byte[] ppuVram;

    private final int mapperId;
    private int prgBank0 = 0; // $8000-$9FFF
    private int prgBank1 = 0; // $A000-$BFFF
    private final int[] chrBanks = new int[8];

    private int mirroringMode = 0;
    private int prgSwapMode = 0;

    // IRQ State
    private int irqLatch = 0;
    private int irqCounter = 0;
    private boolean irqEnable = false;
    private boolean irqEnableAfterAck = false;
    private boolean irqMode = false;
    private int irqPrescaler = 341;
    private boolean irqPending = false;


    private final byte[] prgRam = new byte[8 * 1024];

    public MapperVRC4(int mapperId) {
        this.mapperId = mapperId;
        reset();
    }

    public void setPpuVram(byte[] vram) {
        this.ppuVram = vram;
    }

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        this.bus = bus;
    }

    /**
     * Chuyển đổi địa chỉ CPU thành Internal Offset (0, 1, 2, 3) của chip VRC.
     * Dựa trên cách nối dây vật lý của từng biến thể băng game.
     */
    private int getRegisterOffset(int address) {
        return switch (mapperId) {
            case 21 -> ((address >> 1) & 0x03) | ((address >> 6) & 0x03); // VRC4a/c: Dùng A1, A2 hoặc A6, A7
            case 22 -> ((address & 1) << 1) | ((address >> 1) & 1);       // VRC2a: A0 và A1 bị hoán đổi
            case 23 -> (address & 0x03) | ((address >> 2) & 0x03);        // VRC2b/4e: A0, A1 thẳng mạch hoặc A2, A3 thẳng mạch
            case 25 -> {
                // Mapper 25 (VRC4b/VRC4d): A0 và A1 BỊ HOÁN ĐỔI, hoặc A2 và A3 BỊ HOÁN ĐỔI.
                // Bước 1: Bắt lấy các bit chẵn (A0 hoặc A2)
                int a0_a2 = (address & 1) | ((address >> 2) & 1);
                // Bước 2: Bắt lấy các bit lẻ (A1 hoặc A3)
                int a1_a3 = ((address >> 1) & 1) | ((address >> 3) & 1);

                // Bước 3: Hoán đổi vị trí của chúng (Đẩy bit chẵn lên cao, kéo bit lẻ xuống thấp)
                yield (a0_a2 << 1) | a1_a3;
            }
            default -> (address >> 1) & 0x03;
        };
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x6000 && address <= 0x7FFF) {
            return prgRam[address - 0x6000] & 0xFF;
        }

        if (address >= 0x8000) {
            int offset = address & 0x1FFF;
            int numBanks = cartridge.getPrgRom().length / 8192;
            if (numBanks == 0) return 0;

            int bank;
            if (address < 0xA000) {
                // VRC4 PRG Swap: Nếu prgSwapMode = 1, bank 8000 được cố định là bank áp chót
                bank = (prgSwapMode == 1) ? (numBanks - 2) : prgBank0;
            } else if (address < 0xC000) {
                bank = prgBank1;
            } else if (address < 0xE000) {
                // VRC4 PRG Swap: Nếu prgSwapMode = 1, bank C000 sẽ trỏ vào prgBank0
                bank = (prgSwapMode == 1) ? prgBank0 : (numBanks - 2);
            } else {
                bank = numBanks - 1;
            }

            int finalAddr = (bank % numBanks) * 8192 + offset;
            return cartridge.getPrgRom()[finalAddr] & 0xFF;
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
            prgRam[address - 0x6000] = (byte) value;
            return;
        }

        if (address >= 0x8000) {
            int base = address & 0xF000;
            int offset = getRegisterOffset(address);

            switch (base) {
                case 0x8000:
                    prgBank0 = value & 0x1F;
                    break;
                case 0x9000:
                    if (mapperId == 22) {
                        mirroringMode = value & 0x01;
                    } else {
                        if (offset == 0) {
                            mirroringMode = value & 0x03;
                        } else if (offset == 2) {
                            // Bắt tín hiệu PRG Swap Mode từ VRC4
                            prgSwapMode = (value >> 1) & 1;
                        }
                    }
                    break;
                case 0xA000:
                    prgBank1 = value & 0x1F;
                    break;
                case 0xB000:
                case 0xC000:
                case 0xD000:
                case 0xE000: {
                    int bankIdx = ((base - 0xB000) / 0x1000) * 2 + (offset >> 1);
                    if ((offset & 1) == 0) {
                        chrBanks[bankIdx] = (chrBanks[bankIdx] & 0x1F0) | (value & 0x0F);
                    } else {
                        // Trả lại 5-bit CHR cho Mapper 23 (VRC4e) để hỗ trợ bộ nhớ đồ họa lớn hơn
                        int highMask = (mapperId == 22) ? 0x0F : 0x1F;
                        chrBanks[bankIdx] = (chrBanks[bankIdx] & 0x00F) | ((value & highMask) << 4);
                    }
                    break;
                }
                case 0xF000:
                    // CHÚ Ý: Mở lại IRQ cho Mapper 23! Chỉ Mapper 22 (VRC2a) mới thực sự không có IRQ.
                    if (mapperId == 22) break;

                    if (offset == 0) {
                        irqLatch = (irqLatch & 0xF0) | (value & 0x0F);
                    } else if (offset == 1) {
                        irqLatch = (irqLatch & 0x0F) | ((value & 0x0F) << 4);
                    } else if (offset == 2) {
                        irqEnableAfterAck = (value & 0x01) != 0;
                        irqEnable = (value & 0x02) != 0;
                        irqMode = (value & 0x04) != 0;
                        if (irqEnable) {
                            irqCounter = irqLatch;
                        }
                        irqPrescaler = 341;
                        irqPending = false;
                        if (bus != null) bus.clearIrq();
                    } else if (offset == 3) {
                        irqEnable = irqEnableAfterAck;
                        irqPending = false;
                        if (bus != null) bus.clearIrq();
                    }
                    break;
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            int bankIdx = (address / 1024) % 8;
            int bank = chrBanks[bankIdx];

            if (mapperId == 22) {
                bank >>= 1;
            }

            int offset = address & 0x03FF;
            byte[] chr = cartridge.getChr();
            if (chr.length > 0) {
                return chr[(bank * 1024 + offset) % chr.length] & 0xFF;
            }
        } else if (address < 0x3F00) {
            if (ppuVram != null) {
                return ppuVram[applyMirroring(address & 0x0FFF)] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            if (!cartridge.isChrRam()) return;
            int bankIdx = (address / 1024) % 8;
            int bank = chrBanks[bankIdx];

            if (mapperId == 22) {
                bank >>= 1;
            }

            int offset = address & 0x03FF;
            byte[] chr = cartridge.getChr();
            if (chr.length > 0) {
                chr[(bank * 1024 + offset) % chr.length] = (byte) value;
            }
        } else if (address < 0x3F00) {
            if (ppuVram != null) {
                ppuVram[applyMirroring(address & 0x0FFF)] = (byte) value;
            }
        }
    }

    private int applyMirroring(int addr) {
        return switch (mirroringMode) {
            case 0 -> addr & 0x07FF; // Vertical
            case 1 -> ((addr & 0x800) >> 1) | (addr & 0x3FF); // Horizontal
            case 2 -> addr & 0x3FF; // Single Screen A
            case 3 -> 0x400 | (addr & 0x3FF); // Single Screen B
            default -> addr & 0x07FF;
        };
    }

    @Override
    public void stepCpu() {
        if (irqEnable) {
            if (!irqMode) {
                irqPrescaler -= 3;
                if (irqPrescaler <= 0) {
                    irqPrescaler += 341;
                    clockIrq();
                }
            } else {
                clockIrq();
            }
        }
        if (irqPending && bus != null) bus.requestIrq();
    }

    private void clockIrq() {
        if (irqCounter == 0xFF) {
            irqCounter = irqLatch;
            irqPending = true;
        } else {
            irqCounter++;
        }
    }

    @Override
    public void reset() {
        prgBank0 = 0;
        prgBank1 = 0;
        Arrays.fill(chrBanks, 0);
        mirroringMode = 0;
        irqLatch = 0;
        irqCounter = 0;
        irqEnable = false;
        irqEnableAfterAck = false;
        irqMode = false;
        irqPrescaler = 341;
        irqPending = false;
        prgSwapMode = 0;
    }

    public int getMirroringMode() {
        return mirroringMode;
    }
}
