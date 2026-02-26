package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;
import java.util.Arrays;

/**
 * Mapper 19: Namco 163
 * - Sửa lỗi: Cấu hình CIRAM và CHR-RAM ẩn (dành riêng cho Megami Tensei II).
 */
public class Mapper19 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    private final byte[] wram = new byte[8192];
    private final byte[] ciram = new byte[2048]; // NES 2KB CIRAM

    // Thêm mảng 8KB CHR-RAM phụ dành riêng cho băng vừa có CHR-ROM vừa có CHR-RAM
    private final byte[] chrRam = new byte[8192];

    // Các cờ cấu hình ghi từ thanh ghi $E800
    private boolean disableChrRam0000_0FFF = false;
    private boolean disableChrRam1000_1FFF = false;

    private final int[] prgBanks = new int[4];
    private final int[] chrBanks = new int[12];

    private final Namco163Audio audio = new Namco163Audio();

    private int irqCounter = 0;
    private boolean irqEnabled = false;
    private boolean irqPending = false;

    public Mapper19() {
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

        if (address >= 0x4800 && address <= 0x4FFF) {
            return audio.readRegister(address);
        }

        if (address >= 0x5000 && address <= 0x57FF) {
            int val = irqCounter & 0xFF;
            irqPending = false;
            if (bus != null) bus.clearIrq();
            return val;
        }
        if (address >= 0x5800 && address <= 0x5FFF) {
            int val = ((irqCounter >> 8) & 0x7F) | (irqEnabled ? 0x80 : 0);
            irqPending = false;
            if (bus != null) bus.clearIrq();
            return val;
        }

        if (address >= 0x6000 && address <= 0x7FFF) {
            return wram[address - 0x6000] & 0xFF;
        }
        if (address >= 0x8000) {
            int bank = prgBanks[(address - 0x8000) / 0x2000];
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

        if (address >= 0x4800 && address <= 0x4FFF) {
            audio.writeRegister(address, value);
        }
        else if (address >= 0x5000 && address <= 0x57FF) {
            irqCounter = (irqCounter & 0x7F00) | value;
            irqPending = false;
            if (bus != null) bus.clearIrq();
        } else if (address >= 0x5800 && address <= 0x5FFF) {
            irqCounter = (irqCounter & 0x00FF) | ((value & 0x7F) << 8);
            irqEnabled = (value & 0x80) != 0;
            irqPending = false;
            if (bus != null) bus.clearIrq();
        }
        else if (address >= 0x6000 && address <= 0x7FFF) {
            wram[address - 0x6000] = (byte) value;
        } else if (address >= 0x8000 && address <= 0xDFFF) {
            int registerIndex = (address - 0x8000) / 0x0800;
            chrBanks[registerIndex] = value;
        } else if (address >= 0xE000 && address <= 0xE7FF) {
            prgBanks[0] = value & 0x3F;
        } else if (address >= 0xE800 && address <= 0xEFFF) {
            // Thanh ghi $E800 chứa trạng thái ánh xạ CIRAM ở bits 6 và 7
            prgBanks[1] = value & 0x3F;
            disableChrRam0000_0FFF = (value & 0x40) != 0;
            disableChrRam1000_1FFF = (value & 0x80) != 0;
        } else if (address >= 0xF000 && address <= 0xF7FF) {
            prgBanks[2] = value & 0x3F;
        } else if (address >= 0xF800 && address <= 0xFFFF) {
            audio.writeAddress(value);
        }
    }

    @Override
    public void cpuWrite(int address, int value) { cpuWrite(address, value, 0); }

    @Override
    public void stepCpu() {
        if (irqEnabled) {
            if (irqCounter < 0x7FFF) {
                irqCounter++;
                if (irqCounter == 0x7FFF) {
                    irqPending = true;
                }
            }
        }

        if (irqPending && bus != null) {
            bus.requestIrq();
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            int bank = chrBanks[address / 1024];
            return readChr(address, bank, address % 1024);
        } else if (address < 0x3F00) {
            int mirrorAddr = address & 0x2FFF;
            int bank = chrBanks[8 + ((mirrorAddr - 0x2000) / 1024)];
            return readChr(address, bank, mirrorAddr % 1024);
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            int bank = chrBanks[address / 1024];
            writeChr(address, bank, address % 1024, value);
        } else if (address < 0x3F00) {
            int mirrorAddr = address & 0x2FFF;
            int bank = chrBanks[8 + ((mirrorAddr - 0x2000) / 1024)];
            writeChr(address, bank, mirrorAddr % 1024, value);
        }
    }

    private int readChr(int address, int bank, int offset) {
        boolean useCiram = (bank >= 0xE0);

        // Kiểm tra xem game có đang vô hiệu hóa CIRAM thông qua cờ $E800 hay không
        if (useCiram) {
            if (address < 0x1000 && disableChrRam0000_0FFF) useCiram = false;
            if (address >= 0x1000 && address < 0x2000 && disableChrRam1000_1FFF) useCiram = false;
        }

        if (useCiram) {
            return ciram[((bank & 1) * 1024) + offset] & 0xFF;
        } else if (bank >= 0x80 && !cartridge.isChrRam() && cartridge.getChr().length <= 128 * 1024) {
            // Định tuyến bank >= 0x80 vào mảng CHR-RAM ẩn của Megami Tensei II
            return chrRam[((bank & 7) * 1024) + offset] & 0xFF;
        } else {
            // Định tuyến vào CHR-ROM (hoặc CHR-RAM duy nhất nếu băng chỉ có RAM)
            int numChrBanks = cartridge.getChr().length / 1024;
            if (numChrBanks > 0) return cartridge.getChr()[((bank % numChrBanks) * 1024) + offset] & 0xFF;
        }
        return 0;
    }

    private void writeChr(int address, int bank, int offset, int value) {
        boolean useCiram = (bank >= 0xE0);

        if (useCiram) {
            if (address < 0x1000 && disableChrRam0000_0FFF) useCiram = false;
            if (address >= 0x1000 && address < 0x2000 && disableChrRam1000_1FFF) useCiram = false;
        }

        if (useCiram) {
            ciram[((bank & 1) * 1024) + offset] = (byte) value;
        } else if (bank >= 0x80 && !cartridge.isChrRam() && cartridge.getChr().length <= 128 * 1024) {
            // Ghi dữ liệu đồ họa vào CHR-RAM ẩn
            chrRam[((bank & 7) * 1024) + offset] = (byte) value;
        } else if (cartridge.isChrRam()) {
            int numChrBanks = cartridge.getChr().length / 1024;
            if (numChrBanks > 0) cartridge.getChr()[((bank % numChrBanks) * 1024) + offset] = (byte) value;
        }
    }

    @Override
    public void reset() {
        Arrays.fill(prgBanks, 0);
        Arrays.fill(chrBanks, 0);
        Arrays.fill(wram, (byte) 0);
        Arrays.fill(ciram, (byte) 0);
        Arrays.fill(chrRam, (byte) 0);
        audio.reset();
        updateFixedBanks();

        irqCounter = 0;
        irqEnabled = false;
        irqPending = false;
        disableChrRam0000_0FFF = false;
        disableChrRam1000_1FFF = false;
        if (bus != null) bus.clearIrq();
    }

    @Override
    public void stepAudio(int cpuCycles) { audio.step(cpuCycles); }

    @Override
    public float getAudioSample() { return audio.getSample(); }

    private static class Namco163Audio {
        private final byte[] registers = new byte[0x80];
        private int currentAddress = 0;
        private boolean autoIncrement = false;

        // Quản lý bộ đếm chu kỳ để cập nhật kênh
        private int cycleCounter = 0;
        private int currentChannel = 7;
        private final int[] output = new int[8];

        void writeAddress(int value) {
            currentAddress = value & 0x7F;
            autoIncrement = (value & 0x80) != 0;
        }

        void writeRegister(int address, int value) {
            if (address >= 0x4800 && address <= 0x4FFF) {
                registers[currentAddress] = (byte) value;
                if (autoIncrement) currentAddress = (currentAddress + 1) & 0x7F;
            }
        }

        int readRegister(int address) {
            if (address >= 0x4800 && address <= 0x4FFF) {
                int value = registers[currentAddress] & 0xFF;
                if (autoIncrement) currentAddress = (currentAddress + 1) & 0x7F;
                return value;
            }
            return 0;
        }

        // Hàm này được APU gọi thông qua mapper.stepAudio()
        void step(int cpuCycles) {
            cycleCounter += cpuCycles;

            // Namco 163 xử lý 1 kênh mỗi 15 chu kỳ CPU
            while (cycleCounter >= 15) {
                cycleCounter -= 15;

                // Thanh ghi 0x7F (Volume của kênh 7) lưu số lượng kênh đang hoạt động ở 3 bit cao
                int activeChannels = ((registers[0x7F] >> 4) & 7) + 1;
                int startChannel = 8 - activeChannels;

                processChannel(currentChannel);

                // Lùi xuống kênh tiếp theo, nếu vượt quá số kênh active thì quay lại kênh 7
                currentChannel--;
                if (currentChannel < startChannel) {
                    currentChannel = 7;
                }
            }
        }

        private void processChannel(int ch) {
            int base = 0x40 + (ch * 8);

            // Đọc 8 byte cấu hình của kênh (phải & 0xFF để tránh lỗi số âm trong Java)
            int f0 = registers[base + 0] & 0xFF;
            int p0 = registers[base + 1] & 0xFF;
            int f1 = registers[base + 2] & 0xFF;
            int p1 = registers[base + 3] & 0xFF;
            int f2 = registers[base + 4] & 0xFF;
            int p2 = registers[base + 5] & 0xFF;
            int offset = registers[base + 6] & 0xFF;
            int volReg = registers[base + 7] & 0xFF;

            // Tần số (18-bit) và Pha (24-bit)
            int freq = ((f2 & 0x03) << 16) | (f1 << 8) | f0;
            int phase = (p2 << 16) | (p1 << 8) | p0;

            // Độ dài sóng được lưu dưới dạng (256 - Length) ở 6 bit cao của f2
            int waveStart = f2 & 0xFC;
            int waveLength = 256 - waveStart;

            // Cộng dồn Pha bằng Tần số
            phase += freq;
            int hi = phase >> 16;

            // Xử lý vòng lặp sóng (Wrap-around)
            if (hi >= 256) {
                int overflow = hi - 256;
                hi = waveStart + (overflow % waveLength);
                phase = (hi << 16) | (phase & 0xFFFF);
            }

            // Ghi ngược Phase mới vào lại RAM (Đúng với cách phần cứng thật hoạt động)
            registers[base + 1] = (byte) (phase & 0xFF);
            registers[base + 3] = (byte) ((phase >> 8) & 0xFF);
            registers[base + 5] = (byte) ((phase >> 16) & 0xFF);

            // Đọc mẫu âm thanh (Sample)
            int sampleIndex = hi;
            int byteAddr = (offset + (sampleIndex / 2)) & 0x7F;
            int data = registers[byteAddr] & 0xFF;

            // Mỗi byte chứa 2 mẫu 4-bit. Chỉ mục chẵn dùng 4-bit thấp, lẻ dùng 4-bit cao.
            int sample = ((sampleIndex & 1) != 0) ? (data >> 4) : (data & 0x0F);
            int vol = volReg & 0x0F;

            // Đưa mẫu âm thanh (0 -> 15) về điểm giữa 0 (thành -8 -> 7), rồi nhân với âm lượng
            output[ch] = (sample - 8) * vol;
        }

        // Lấy mẫu âm thanh cuối cùng để đẩy ra APU
        float getSample() {
            int activeChannels = ((registers[0x7F] >> 4) & 7) + 1;
            int startChannel = 8 - activeChannels;

            float total = 0;
            for (int i = startChannel; i <= 7; i++) {
                total += output[i];
            }

            // Tính trung bình cộng các kênh đang hoạt động (do hiệu ứng Multiplexing)
            // Nhân hệ số 0.004f để cân bằng âm lượng với các kênh mặc định của NES
            return (total / activeChannels) * 0.004f;
        }

        void reset() {
            Arrays.fill(registers, (byte) 0);
            currentAddress = 0;
            autoIncrement = false;
            cycleCounter = 0;
            currentChannel = 7;
            Arrays.fill(output, 0);
        }
    }
}