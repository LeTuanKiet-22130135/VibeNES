package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;
import java.util.Arrays;

/**
 * Mapper 69: Sunsoft FME-7 / 5A / 5B
 * Đã tích hợp Sunsoft 5B Expansion Audio (YM2149F core)
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

    // --- Biến cho Âm thanh ---
    private final Sunsoft5BAudio audioCore = new Sunsoft5BAudio();
    private int audioRegisterSelect = 0;

    public Mapper69() {
        Arrays.fill(prgRam, (byte) 0x00);
        reset();
    }

    @Override
    public void setCartridge(Cartridge cartridge) { this.cartridge = cartridge; }

    @Override
    public void setBus(Bus bus) { this.bus = bus; }

    private int getPrgBank(int bankRegister) {
        int bank = bankRegister & 0x3F;
        int numBanks = cartridge.getPrgRom().length / 8192;
        if (numBanks == 0) return 0;
        return bank % numBanks;
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x6000 && address <= 0x7FFF) {
            int reg8 = registers[0x08];
            boolean enabled = (reg8 & 0x80) != 0;
            boolean isRam = (reg8 & 0x40) != 0;

            if (isRam) {
                if (!enabled) return 0;
                return prgRam[address - 0x6000] & 0xFF;
            } else {
                int bank = getPrgBank(reg8);
                int finalAddr = (bank * 8192) + (address & 0x1FFF);
                if (finalAddr < cartridge.getPrgRom().length) {
                    return cartridge.getPrgRom()[finalAddr] & 0xFF;
                }
                return 0;
            }
        }

        if (address >= 0x8000) {
            int bank = 0;
            int offset = address & 0x1FFF;
            if (address < 0xA000) bank = getPrgBank(registers[0x09]);
            else if (address < 0xC000) bank = getPrgBank(registers[0x0A]);
            else if (address < 0xE000) bank = getPrgBank(registers[0x0B]);
            else {
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
            if (isRam && enabled) {
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
                irqPending = false;
                if (bus != null) bus.clearIrq();
            } else if (commandRegister == 0x0E) {
                irqCounter = (irqCounter & 0xFF00) | value;
            } else if (commandRegister == 0x0F) {
                irqCounter = (irqCounter & 0x00FF) | (value << 8);
            }
        }
        // Bắt các lệnh ghi vào địa chỉ âm thanh của Sunsoft 5B
        else if (address >= 0xC000 && address < 0xE000) {
            audioRegisterSelect = value & 0x0F; // Chọn thanh ghi (0-15)
        }
        else if (address >= 0xE000) {
            audioCore.writeRegister(audioRegisterSelect, value); // Ghi dữ liệu vào thanh ghi đã chọn
        }
    }

    @Override
    public void cpuWrite(int address, int value) { cpuWrite(address, value, 0); }

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
            bank &= 0xFF;
            return chr[(bank * 1024 + offset) % chr.length] & 0xFF;
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
            chr[(bank * 1024 + offset) % chr.length] = (byte) value;
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
        if (irqPending && irqEnabled && bus != null) bus.requestIrq();

        // Tiến trình tạo âm thanh chạy theo mỗi chu kỳ CPU
        audioCore.step();
    }

    @Override
    public float getAudioSample() {
        return audioCore.getSample();
    }

    @Override
    public void reset() {
        commandRegister = 0;
        Arrays.fill(registers, 0);
        irqCounter = 0;
        irqEnabled = false;
        irqCounterEnabled = false;
        irqPending = false;
        audioRegisterSelect = 0;
        audioCore.reset();
    }

    public int getMirroringMode() { return registers[0x0C] & 0x03; }

    /**
     * Mô phỏng phần cứng Yamaha YM2149F (Sunsoft 5B Audio Core)
     */
    private static class Sunsoft5BAudio {
        private final int[] regs = new int[16];

        private final int[] period = new int[3];
        private final int[] counter = new int[3];
        private final int[] volume = new int[3];
        private final boolean[] output = new boolean[3];
        private int clockDivider = 0;

        // Bảng quy đổi âm lượng tuyến tính sang logarit (để nghe giống thật hơn)
        private static final float[] VOL_TABLE = new float[16];
        static {
            VOL_TABLE[0] = 0.0f;
            for (int i = 1; i < 16; i++) {
                // Tăng theo hàm mũ để khớp với mạch khuếch đại (amplifer) analog
                VOL_TABLE[i] = (float) Math.pow(10.0, (i - 15) * 0.1) * 0.15f;
            }
        }

        public void reset() {
            Arrays.fill(regs, 0);
            Arrays.fill(period, 0);
            Arrays.fill(counter, 0);
            Arrays.fill(volume, 0);
            Arrays.fill(output, false);
            clockDivider = 0;
        }

        public void writeRegister(int reg, int value) {
            if (reg > 15) return;
            regs[reg] = value;

            if (reg <= 5) {
                int ch = reg / 2;
                period[ch] = (regs[ch * 2 + 1] << 8) | regs[ch * 2];
                period[ch] &= 0x0FFF; // 12-bit period
            }
            else if (reg >= 8 && reg <= 10) {
                int ch = reg - 8;
                volume[ch] = value & 0x0F;
                // Lưu ý: Bit 4 quy định Envelope (chúng ta dùng volume tĩnh cho 90% Gimmick!)
            }
        }

        public void step() {
            // YM2149F chia xung nhịp đầu vào (CPU clock) cho 16
            clockDivider++;
            if (clockDivider >= 16) {
                clockDivider = 0;

                // Clock 3 kênh âm (Tone Generators)
                for (int i = 0; i < 3; i++) {
                    if (period[i] > 0) {
                        counter[i]++;
                        if (counter[i] >= period[i]) {
                            counter[i] = 0;
                            output[i] = !output[i];
                        }
                    }
                }
            }
        }

        public float getSample() {
            float mixedSample = 0.0f;
            int enableReg = regs[7]; // Thanh ghi 7 quản lý Enable (0 = Bật, 1 = Tắt)

            for (int i = 0; i < 3; i++) {
                boolean isToneEnabled = (enableReg & (1 << i)) == 0;
                if (isToneEnabled && output[i]) {
                    mixedSample += VOL_TABLE[volume[i]];
                }
            }
            return mixedSample;
        }
    }
}