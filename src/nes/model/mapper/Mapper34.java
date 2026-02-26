package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

import java.util.Arrays;

/**
 * Mapper 34: BNROM / NINA-001
 * Hỗ trợ cả 2 loại bo mạch:
 * - BNROM: Chuyển PRG 32KB bằng cách ghi vào $8000-$FFFF.
 * - NINA-001: Chuyển PRG 32KB ($7FFD) và CHR 4KB ($7FFE, $7FFF), có 8KB SRAM tại $6000-$7FFF.
 */
public class Mapper34 implements Mapper {

    private Cartridge cartridge;

    // Quản lý PRG Bank (Khối 32KB)
    private int prgBank = 0;

    // Quản lý CHR Banks (Khối 4KB - dùng cho NINA-001)
    private int chrBank0 = 0; // Trỏ tới PPU $0000-$0FFF
    private int chrBank1 = 1; // Trỏ tới PPU $1000-$1FFF

    // Bộ nhớ RAM bổ sung (SRAM / Work RAM) 8KB cho NINA-001
    private final byte[] sram = new byte[8192];

    public Mapper34() {
        reset();
    }

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void setBus(Bus bus) {
        // Mapper 34 không sử dụng IRQ
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x8000) {
            // Đọc mã lệnh PRG ROM từ $8000-$FFFF
            int offset = address - 0x8000;
            int numBanks = cartridge.getPrgRom().length / 32768;
            if (numBanks == 0) numBanks = 1;

            int bank = prgBank % numBanks;
            int finalAddr = (bank * 32768) + offset;

            if (finalAddr < cartridge.getPrgRom().length) {
                return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
        } else if (address >= 0x6000 && address <= 0x7FFF) {
            // Đọc từ SRAM cho NINA-001
            return sram[address - 0x6000] & 0xFF;
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

        if (address >= 0x8000) {
            // Xử lý ghi cho BNROM
            prgBank = value;
        } else if (address >= 0x6000 && address <= 0x7FFF) {
            // Ghi vào SRAM cho NINA-001
            sram[address - 0x6000] = (byte) value;

            // NINA-001 "nghe lén" các lượt ghi vào 3 địa chỉ cuối của SRAM để chuyển bank
            if (address == 0x7FFD) {
                prgBank = value;
            } else if (address == 0x7FFE) {
                chrBank0 = value;
            } else if (address == 0x7FFF) {
                chrBank1 = value;
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;

        // Xác định xem địa chỉ thuộc khối 4KB đầu tiên hay thứ hai
        int bank;
        int offset = address % 4096;
        if (address < 0x1000) {
            bank = chrBank0;
        } else {
            bank = chrBank1;
        }

        int numBanks = cartridge.getChr().length / 4096;
        if (numBanks == 0) {
            // Trường hợp dùng CHR RAM
            return cartridge.getChr()[address] & 0xFF;
        }

        bank %= numBanks;
        int finalAddr = (bank * 4096) + offset;

        if (finalAddr < cartridge.getChr().length) {
            return cartridge.getChr()[finalAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        // Chỉ cho phép ghi nếu Cartridge dùng CHR RAM
        if (cartridge.isChrRam()) {
            address &= 0x1FFF;
            cartridge.getChr()[address] = (byte) value;
        }
    }

    @Override
    public void reset() {
        prgBank = 0;
        chrBank0 = 0;
        chrBank1 = 1;

        // Xóa sạch SRAM khi reset
        Arrays.fill(sram, (byte) 0);
    }
}