package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;
import java.util.Arrays;

/**
 * MapperVRC6 handles both iNES Mapper 24 (VRC6a) and Mapper 26 (VRC6b).
 */
public class MapperVRC6 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;
    private final boolean isMapper26;
    private byte[] ppuVram;

    private final byte[] prgRam = new byte[8 * 1024];

    private int prgBank16 = 0;
    private int prgBank8 = 0;
    private final int[] chrBanks = new int[8];

    private int mirroringMode = 0;
    private boolean ppuBankingMode = false;
    private boolean wramEnable = false;

    // IRQ State
    private int irqLatch = 0;
    private int irqCounter = 0;
    private boolean irqEnable = false;
    private boolean irqEnableAfterAck = false;
    private boolean irqMode = false;
    private int irqPrescaler = 341;
    private boolean irqPending = false;

    // Audio Core
    private final Vrc6Audio audioCore = new Vrc6Audio();

    public MapperVRC6(boolean isMapper26) {
        this.isMapper26 = isMapper26;
        Arrays.fill(prgRam, (byte) 0);
        reset();
    }

    public void setPpuVram(byte[] vram) {
        this.ppuVram = vram;
    }

    @Override
    public void setCartridge(Cartridge cartridge) { this.cartridge = cartridge; }

    @Override
    public void setBus(Bus bus) { this.bus = bus; }

    // Xử lý đảo chân A0/A1 cực kỳ an toàn
    private int getOffset(int address) {
        int offset = address & 0x03;
        if (isMapper26) {
            if (offset == 1) return 2;
            if (offset == 2) return 1;
        }
        return offset;
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x6000 && address <= 0x7FFF) {
            return wramEnable ? (prgRam[address - 0x6000] & 0xFF) : 0;
        }

        if (address >= 0x8000) {
            int bank;
            int offset = address & 0x1FFF;

            if (address < 0xC000) {
                int numBanks16 = cartridge.getPrgRom().length / 16384;
                bank = (numBanks16 > 0) ? (prgBank16 % numBanks16) : 0;
                offset = address & 0x3FFF;
                int finalAddr = (bank * 16384) + offset;
                if (finalAddr < cartridge.getPrgRom().length) return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
            else if (address < 0xE000) {
                int numBanks8 = cartridge.getPrgRom().length / 8192;
                bank = (numBanks8 > 0) ? (prgBank8 % numBanks8) : 0;
                int finalAddr = (bank * 8192) + offset;
                if (finalAddr < cartridge.getPrgRom().length) return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
            else {
                int numBanks8 = cartridge.getPrgRom().length / 8192;
                bank = Math.max(0, numBanks8 - 1);
                int finalAddr = (bank * 8192) + offset;
                if (finalAddr < cartridge.getPrgRom().length) return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value, long cycles) {
        address &= 0xFFFF;
        value &= 0xFF;

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (wramEnable) prgRam[address - 0x6000] = (byte) value;
            return;
        }

        if (address >= 0x8000) {
            int base = address & 0xF000;
            int offset = getOffset(address);

            if (base == 0x8000) {
                prgBank16 = value & 0xFF;
            }
            else if (base == 0x9000 || base == 0xA000 || base == 0xB000) {
                if (base == 0x9000 && offset == 3) {
                    audioCore.writeGlobalControl(value);
                }
                else if (base == 0xB000 && offset == 3) {
                    wramEnable = (value & 0x80) != 0;

                    // Cấu trúc chuẩn NESdev VRC6: W.PN MMDD
                    ppuBankingMode = (value & 0x10) != 0; // Bit 4 (N): Nguồn Nametable

                    // LẤY CHÍNH XÁC BIT 2 VÀ 3 CHO MIRRORING
                    mirroringMode = (value >> 2) & 0x03;
                } else {
                    audioCore.writeRegister(base, offset, value);
                }
            }
            else if (base == 0xC000) {
                prgBank8 = value & 0xFF;
            }
            else if (base == 0xD000) {
                chrBanks[offset] = value & 0xFF;
            }
            else if (base == 0xE000) {
                chrBanks[4 + offset] = value & 0xFF;
            }
            else if (base == 0xF000) {
                if (offset == 0) irqLatch = value;
                else if (offset == 1) {
                    irqEnableAfterAck = (value & 0x01) != 0;
                    irqEnable = (value & 0x02) != 0;
                    irqMode = (value & 0x04) != 0;
                    if (irqEnable) { irqCounter = irqLatch; }
                    irqPrescaler = 341;
                    irqPending = false;
                    if (bus != null) bus.clearIrq();
                } else if (offset == 2) {
                    irqEnable = irqEnableAfterAck;
                    irqPending = false;
                    if (bus != null) bus.clearIrq();
                }
            }
        }
    }

    @Override
    public void cpuWrite(int address, int value) { cpuWrite(address, value, 0); }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            int bankIdx = chrBanks[(address / 1024) % 8] & 0xFF;
            int offset = address & 0x03FF;
            byte[] chr = cartridge.getChr();
            if (chr.length > 0) return chr[(bankIdx * 1024 + offset) % chr.length] & 0xFF;
        }
        else if (address < 0x3F00) {
            int ntAddr = address & 0x0FFF;

            if (ppuBankingMode) {
                int bankIdx = chrBanks[6 + ((ntAddr >> 10) & 1)] & 0xFF;
                int offset = ntAddr & 0x03FF;
                byte[] chr = cartridge.getChr();
                if (chr.length > 0) return chr[(bankIdx * 1024 + offset) % chr.length] & 0xFF;
            } else {
                if (ppuVram != null) return ppuVram[applyMirroring(ntAddr)] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            if (!cartridge.isChrRam()) return;
            int bankIdx = chrBanks[(address / 1024) % 8] & 0xFF;
            int offset = address & 0x03FF;
            byte[] chr = cartridge.getChr();
            if (chr.length > 0) chr[(bankIdx * 1024 + offset) % chr.length] = (byte) value;
        }
        else if (address < 0x3F00) {
            int ntAddr = address & 0x0FFF;

            if (ppuBankingMode) {
                if (!cartridge.isChrRam()) return;
                int bankIdx = chrBanks[6 + ((ntAddr >> 10) & 1)] & 0xFF;
                int offset = ntAddr & 0x03FF;
                byte[] chr = cartridge.getChr();
                if (chr.length > 0) chr[(bankIdx * 1024 + offset) % chr.length] = (byte) value;
            } else {
                if (ppuVram != null) ppuVram[applyMirroring(ntAddr)] = (byte) value;
            }
        }
    }

    private int applyMirroring(int addr) {
        return switch (mirroringMode) {
            case 0 -> addr & 0x07FF; // 0: Vertical (Khi game gửi $20)
            case 1 -> ((addr & 0x800) >> 1) | (addr & 0x3FF); // 1: Horizontal (Khi game gửi $24)
            case 2 -> addr & 0x3FF; // 2: Single Screen A
            case 3 -> 0x400 | (addr & 0x3FF); // 3: Single Screen B
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
    public void stepAudio(int cycles) {
        for (int i = 0; i < cycles; i++) audioCore.step();
    }

    @Override
    public float getAudioSample() { return audioCore.getSample(); }

    @Override
    public void reset() {
        prgBank16 = 0; prgBank8 = 0; Arrays.fill(chrBanks, 0);
        mirroringMode = 0; ppuBankingMode = false; wramEnable = false;
        irqLatch = irqCounter = 0; irqEnable = irqEnableAfterAck = irqMode = irqPending = false;
        irqPrescaler = 341; audioCore.reset();
    }

    public int getMirroringMode() { return 0; }

    /**
     * Konami VRC6 Expansion Audio Synthesizer
     */
    private static class Vrc6Audio {
        private final int[] pulseCtrl = new int[2];
        private final int[] pulseFreq = new int[2];
        private final int[] pulseDivider = new int[2];
        private final int[] pulseStep = new int[2];

        private int sawRate = 0, sawFreq = 0, sawDivider = 0, sawStep = 0, sawAccumulator = 0;
        private boolean halt = false;

        public void reset() {
            Arrays.fill(pulseCtrl, 0); Arrays.fill(pulseFreq, 0);
            Arrays.fill(pulseDivider, 0); Arrays.fill(pulseStep, 0);
            sawRate = sawFreq = sawDivider = sawStep = sawAccumulator = 0;
            halt = false;
        }

        public void writeGlobalControl(int value) {
            halt = (value & 0x01) != 0;
        }

        public void writeRegister(int base, int offset, int value) {
            if (offset == 3) return;

            if (base == 0x9000 || base == 0xA000) {
                int ch = (base == 0x9000) ? 0 : 1;
                if (offset == 0) pulseCtrl[ch] = value;
                else if (offset == 1) pulseFreq[ch] = (pulseFreq[ch] & 0xFF00) | value;
                else if (offset == 2) pulseFreq[ch] = (pulseFreq[ch] & 0x00FF) | ((value & 0x8F) << 8);
            }
            else if (base == 0xB000) {
                if (offset == 0) sawRate = value & 0x3F;
                else if (offset == 1) sawFreq = (sawFreq & 0xFF00) | value;
                else if (offset == 2) sawFreq = (sawFreq & 0x00FF) | ((value & 0x8F) << 8);
            }
        }

        public void step() {
            if (halt) return;

            for (int ch = 0; ch < 2; ch++) {
                if ((pulseFreq[ch] & 0x8000) != 0) {
                    pulseDivider[ch]--;
                    if (pulseDivider[ch] < 0) {
                        pulseDivider[ch] = pulseFreq[ch] & 0x0FFF;
                        pulseStep[ch] = (pulseStep[ch] + 1) & 15;
                    }
                }
            }

            if ((sawFreq & 0x8000) != 0) {
                sawDivider--;
                if (sawDivider < 0) {
                    sawDivider = sawFreq & 0x0FFF;
                    sawStep++;
                    if (sawStep >= 14) {
                        sawStep = 0;
                        sawAccumulator = 0;
                    } else if ((sawStep % 2) == 0) {
                        sawAccumulator = (sawAccumulator + sawRate) & 0xFF;
                    }
                }
            } else {
                sawAccumulator = 0;
            }
        }

        public float getSample() {
            float mixed = 0.0f;
            for (int ch = 0; ch < 2; ch++) {
                if ((pulseFreq[ch] & 0x8000) != 0) {
                    int mode = (pulseCtrl[ch] >> 7) & 1;
                    int duty = (pulseCtrl[ch] >> 4) & 7;
                    int vol = pulseCtrl[ch] & 15;
                    if (mode == 1 || pulseStep[ch] <= duty) mixed += vol;
                }
            }
            if ((sawFreq & 0x8000) != 0) mixed += (sawAccumulator >> 3) & 0x1F;
            return (mixed / 61.0f) * 0.25f;
        }
    }
}