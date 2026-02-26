package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;
import java.util.Arrays;

/**
 * Mapper 85: Konami VRC7
 * - Hỗ trợ PRG 8KB x 4 (Bank cuối cố định).
 * - Hỗ trợ CHR 1KB x 8.
 * - Hỗ trợ IRQ đếm chu kỳ CPU chính xác (Prescaler 341).
 */
public class Mapper85 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    private final int[] prgBanks = new int[4];
    private final int[] chrBanks = new int[8];

    private final byte[] wram = new byte[8192];
    private boolean wramEnabled = false;

    private int mirroringMode = 0; // 0=V, 1=H, 2=1A, 3=1B

    // Trạng thái IRQ
    private int irqLatch = 0;
    private int irqCounter = 0;
    private boolean irqEnable = false;
    private boolean irqEnableOnAck = false;
    private boolean irqMode = false; // true = cycle, false = scanline
    private int prescaler = 341;

    // Hệ thống âm thanh (Stub)
    private final VRC7Audio audio = new VRC7Audio();

    public Mapper85() {
        reset();
    }

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        updateFixedBanks();
    }

    private void updateFixedBanks() {
        if (cartridge != null) {
            int numPrgBanks = cartridge.getPrgRom().length / 8192;
            if (numPrgBanks > 0) {
                // Bank PRG cuối cùng luôn cố định ở cuối ROM
                prgBanks[3] = numPrgBanks - 1;
            }
        }
    }

    @Override
    public void setBus(Bus bus) {
        this.bus = bus;
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (wramEnabled) {
                return wram[address - 0x6000] & 0xFF;
            }
            return 0; // Đang bảo vệ WRAM, không cho đọc
        }

        if (address >= 0x8000) {
            int bankIndex = (address - 0x8000) / 0x2000;
            int bank = prgBanks[bankIndex];
            int numBanks = cartridge.getPrgRom().length / 8192;
            if (numBanks == 0) numBanks = 1;
            int finalAddr = ((bank % numBanks) * 8192) + (address & 0x1FFF);
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
            if (wramEnabled) {
                wram[address - 0x6000] = (byte) value;
            }
            return;
        }

        if (address >= 0x8000) {
            // Giải mã địa chỉ. Bắt các bit A4, A5, A6 để hỗ trợ cả 2 biến thể VRC7.
            int base = address & 0xF000;
            int sub = address & 0x0038;

            switch (base) {
                case 0x8000:
                    if (sub == 0x0000) prgBanks[0] = value & 0x3F;
                    else if (sub == 0x0008 || sub == 0x0010) prgBanks[1] = value & 0x3F;
                    break;
                case 0x9000:
                    if (sub == 0x0000) prgBanks[2] = value & 0x3F;
                    else if (sub == 0x0010) audio.writeRegisterSelect(value);
                    else if (sub == 0x0030) audio.writeRegisterData(value);
                    break;
                case 0xA000:
                    if (sub == 0x0000) chrBanks[0] = value;
                    else if (sub == 0x0008 || sub == 0x0010) chrBanks[1] = value;
                    break;
                case 0xB000:
                    if (sub == 0x0000) chrBanks[2] = value;
                    else if (sub == 0x0008 || sub == 0x0010) chrBanks[3] = value;
                    break;
                case 0xC000:
                    if (sub == 0x0000) chrBanks[4] = value;
                    else if (sub == 0x0008 || sub == 0x0010) chrBanks[5] = value;
                    break;
                case 0xD000:
                    if (sub == 0x0000) chrBanks[6] = value;
                    else if (sub == 0x0008 || sub == 0x0010) chrBanks[7] = value;
                    break;
                case 0xE000:
                    if (sub == 0x0000) {
                        mirroringMode = value & 0x03;
                        wramEnabled = (value & 0x80) != 0;
                    } else if (sub == 0x0008 || sub == 0x0010) {
                        irqLatch = value;
                    }
                    break;
                case 0xF000:
                    if (sub == 0x0000) {
                        irqEnableOnAck = (value & 0x01) != 0;
                        irqEnable = (value & 0x02) != 0;
                        irqMode = (value & 0x04) != 0;

                        // Ghi vào thanh ghi này sẽ Acknowledge IRQ hiện tại
                        if (bus != null) bus.clearIrq();
                        prescaler = 341;

                        if (irqEnable) {
                            irqCounter = irqLatch;
                        }
                    } else if (sub == 0x0008 || sub == 0x0010) {
                        // Acknowledge IRQ
                        if (bus != null) bus.clearIrq();
                        irqEnable = irqEnableOnAck;
                    }
                    break;
            }
        }
    }

    @Override
    public void cpuWrite(int address, int value) {
        cpuWrite(address, value, 0);
    }

    @Override
    public void stepCpu() {
        if (irqEnable) {
            if (irqMode) {
                // Chế độ đếm chu kỳ (Cycle Mode)
                clockIrq();
            } else {
                // Chế độ đếm Scanline (Giả lập qua prescaler -3)
                prescaler -= 3;
                if (prescaler <= 0) {
                    prescaler += 341;
                    clockIrq();
                }
            }
        }
    }

    private void clockIrq() {
        if (irqCounter == 0xFF) {
            irqCounter = irqLatch;
            if (bus != null) bus.requestIrq();
        } else {
            irqCounter++;
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            int bank = chrBanks[address / 1024];
            int numChrBanks = cartridge.getChr().length / 1024;
            if (numChrBanks > 0) {
                return cartridge.getChr()[((bank % numChrBanks) * 1024) + (address % 1024)] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000 && cartridge.isChrRam()) {
            int bank = chrBanks[address / 1024];
            int numChrBanks = cartridge.getChr().length / 1024;
            if (numChrBanks > 0) {
                cartridge.getChr()[((bank % numChrBanks) * 1024) + (address % 1024)] = (byte) value;
            }
        }
    }

    @Override
    public void reset() {
        Arrays.fill(prgBanks, 0);
        Arrays.fill(chrBanks, 0);
        Arrays.fill(wram, (byte) 0);
        wramEnabled = false;
        mirroringMode = 0;

        irqLatch = 0;
        irqCounter = 0;
        irqEnable = false;
        irqEnableOnAck = false;
        irqMode = false;
        prescaler = 341;

        if (bus != null) bus.clearIrq();
        audio.reset();

        updateFixedBanks();
    }

    public int getMirroringMode() {
        return mirroringMode;
    }

    // --- Audio System ---
    @Override
    public void stepAudio(int cpuCycles) {
        audio.step(cpuCycles);
    }

    @Override
    public float getAudioSample() {
        return audio.getSample();
    }

    // Hệ thống âm thanh VRC7 (Bản nâng cấp 3: Bộ tạo Đường bao ADSR - Envelope Generator)
    private static class VRC7Audio {
        private int address = 0;
        private final int[] regs = new int[0x40];

        // Mảng trạng thái cơ bản
        private final double[] phase = new double[6];
        private final boolean[] keyOn = new boolean[6];
        private final int[] fNumber = new int[6];
        private final int[] block = new int[6];
        private final int[] instrument = new int[6];
        private final int[] volume = new int[6];

        // ADSR State Machine
        private enum EnvState { OFF, ATTACK, DECAY, SUSTAIN, RELEASE }
        private final EnvState[] envState = new EnvState[6];
        private final double[] envelopeLevel = new double[6]; // Từ 0.0 (im lặng) đến 1.0 (to nhất)

        private final int[][] patches = {
                {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, // 0: Custom
                {0x03, 0x21, 0x05, 0x06, 0xB8, 0x82, 0x42, 0x27}, // 1: BASS
                {0x13, 0x41, 0x13, 0x0D, 0xD8, 0xD6, 0x23, 0x12}, // 2: GUITAR
                {0x31, 0x11, 0x08, 0x08, 0xFA, 0x9A, 0x22, 0x02}, // 3: PIANO
                {0x31, 0x61, 0x18, 0x07, 0x78, 0x64, 0x30, 0x27}, // 4: FLUTE
                {0x22, 0x21, 0x1E, 0x06, 0xF0, 0x76, 0x08, 0x28}, // 5: CLARINET
                {0x02, 0x01, 0x06, 0x00, 0xF0, 0xF2, 0x03, 0xF5}, // 6: HALO PAD
                {0x21, 0x61, 0x1D, 0x07, 0x82, 0x81, 0x10, 0x07}, // 7: TRUMPET
                {0x23, 0x21, 0x22, 0x17, 0xA0, 0x72, 0x01, 0x17}, // 8: SYNTH BASS
                {0x21, 0x21, 0x18, 0x0F, 0x50, 0x51, 0x70, 0x02}, // 9: TUBULAR BELL
                {0x31, 0x61, 0x0C, 0x07, 0x98, 0x64, 0x20, 0x27}, // A: BRASS
                {0x21, 0x21, 0x0A, 0x09, 0x50, 0x01, 0x10, 0x02}, // B: VIBRAPHONE
                {0x23, 0x21, 0x12, 0x07, 0xB0, 0x23, 0x71, 0x27}, // C: ORGAN
                {0x21, 0x21, 0x14, 0x03, 0xC0, 0x70, 0x07, 0x07}, // D: SYNTH BRASS
                {0x23, 0x21, 0x0A, 0x02, 0xE0, 0x71, 0x31, 0x26}, // E: SYNTH STRINGS
                {0x31, 0x31, 0x08, 0x08, 0xF0, 0xCD, 0x04, 0x32}  // F: SYNTH STRINGS 2
        };

        private static final double VRC7_CLOCK = 49716.0;

        public VRC7Audio() {
            reset();
        }

        void writeRegisterSelect(int value) {
            address = value & 0x3F;
        }

        void writeRegisterData(int value) {
            regs[address] = value & 0xFF;

            if (address >= 0x00 && address <= 0x07) {
                patches[0][address] = regs[address];
            }
            else if (address >= 0x10 && address <= 0x15) {
                int ch = address - 0x10;
                fNumber[ch] = (fNumber[ch] & 0x100) | regs[address];
            }
            else if (address >= 0x20 && address <= 0x25) {
                int ch = address - 0x20;
                fNumber[ch] = (fNumber[ch] & 0xFF) | ((regs[address] & 0x01) << 8);
                block[ch] = (regs[address] >> 1) & 0x07;

                boolean newKeyOn = (regs[address] & 0x10) != 0;
                if (newKeyOn && !keyOn[ch]) {
                    // Trigger Note (Bấm phím)
                    phase[ch] = 0;
                    envState[ch] = EnvState.ATTACK;
                } else if (!newKeyOn && keyOn[ch]) {
                    // Release Note (Nhả phím)
                    envState[ch] = EnvState.RELEASE;
                }
                keyOn[ch] = newKeyOn;
            }
            else if (address >= 0x30 && address <= 0x35) {
                int ch = address - 0x30;
                volume[ch] = regs[address] & 0x0F;
                instrument[ch] = (regs[address] >> 4) & 0x0F;
            }
        }

        void step(int cpuCycles) {
            double timeStep = cpuCycles / 1789773.0;

            for (int i = 0; i < 6; i++) {
                // Tính toán tần số sóng
                double freq = (VRC7_CLOCK * fNumber[i]) / Math.pow(2, 19 - block[i]);
                phase[i] += 2 * Math.PI * freq * timeStep;
                if (phase[i] > 2 * Math.PI) {
                    phase[i] -= 2 * Math.PI;
                }

                // Cập nhật ADSR Envelope
                if (envState[i] != EnvState.OFF) {
                    int[] patch = patches[instrument[i]];

                    // Lấy các thông số ADSR của Sóng mang (Carrier) từ Byte 5 và 7
                    int attackRate = (patch[5] >> 4) & 0x0F;
                    int decayRate = patch[5] & 0x0F;
                    int sustainLevel = (patch[7] >> 4) & 0x0F;
                    int releaseRate = patch[7] & 0x0F;

                    // Chuyển đổi Rate (0-15) thành tốc độ thay đổi (tùy chỉnh cho giả lập này)
                    double aStep = (attackRate == 0) ? 0 : (attackRate * attackRate * 0.5) * timeStep;
                    double dStep = (decayRate == 0) ? 0 : (decayRate * decayRate * 0.1) * timeStep;
                    double rStep = (releaseRate == 0) ? 0 : (releaseRate * releaseRate * 0.1) * timeStep;
                    double slTarget = 1.0 - (sustainLevel / 15.0); // 0 = to nhất, 15 = nhỏ nhất

                    switch (envState[i]) {
                        case ATTACK:
                            envelopeLevel[i] += aStep;
                            if (envelopeLevel[i] >= 1.0) {
                                envelopeLevel[i] = 1.0;
                                envState[i] = EnvState.DECAY;
                            }
                            break;
                        case DECAY:
                            envelopeLevel[i] -= dStep;
                            if (envelopeLevel[i] <= slTarget) {
                                envelopeLevel[i] = slTarget;
                                envState[i] = EnvState.SUSTAIN;
                            }
                            break;
                        case SUSTAIN:
                            // Giữ nguyên mức âm lượng
                            break;
                        case RELEASE:
                            envelopeLevel[i] -= rStep;
                            if (envelopeLevel[i] <= 0) {
                                envelopeLevel[i] = 0;
                                envState[i] = EnvState.OFF;
                            }
                            break;
                        case OFF:
                            break;
                    }
                }
            }
        }

        float getSample() {
            float total = 0;
            int activeChannels = 0;

            for (int i = 0; i < 6; i++) {
                if (envState[i] != EnvState.OFF) {
                    float v = 15 - volume[i];
                    // Nhân sóng Sin với Envelope Level để tạo ra hình dạng âm thanh
                    float sample = (float) (Math.sin(phase[i]) * envelopeLevel[i] * (v / 15.0f));
                    total += sample;
                    activeChannels++;
                }
            }
            return activeChannels > 0 ? (total / 6.0f) * 0.5f : 0.0f;
        }

        void reset() {
            address = 0;
            Arrays.fill(regs, 0);
            Arrays.fill(phase, 0);
            Arrays.fill(keyOn, false);
            Arrays.fill(fNumber, 0);
            Arrays.fill(block, 0);
            Arrays.fill(instrument, 0);
            Arrays.fill(volume, 0);
            Arrays.fill(envState, EnvState.OFF);
            Arrays.fill(envelopeLevel, 0.0);
            for (int i = 0; i < 8; i++) patches[0][i] = 0;
        }
    }
}