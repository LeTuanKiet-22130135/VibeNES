package nes.model.mapper;

import nes.model.Bus;
import nes.model.Cartridge;

/**
 * Mapper 206: DxROM (Namco 118 / Tengen MIMIC-1)
 * Đây là phiên bản rút gọn của MMC3. Không có IRQ và không điều khiển Mirroring.
 * Sử dụng thanh ghi tại $8000 để chọn Bank và $8001 để gán dữ liệu Bank.
 */
public class Mapper206 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;

    // Các thanh ghi chuyển Bank (giống MMC3)
    private int bankSelect = 0; // Thanh ghi chỉ mục ($8000)
    private final int[] registers = new int[8]; // Thanh ghi dữ liệu ($8001)

    // PRG Banks (Mỗi bank 8KB)
    private int prgBank0; // Trỏ tới CPU $8000-$9FFF
    private int prgBank1; // Trỏ tới CPU $A000-$BFFF
    private int prgBank2; // Trỏ tới CPU $C000-$DFFF (Cố định bank kế cuối)
    private int prgBank3; // Trỏ tới CPU $E000-$FFFF (Cố định bank cuối)

    // CHR Banks (Chia thành 8 bank 1KB cho dễ quản lý)
    private final int[] chrBanks = new int[8];

    public Mapper206() {
        reset();
    }

    @Override
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        updateBanks();
    }

    @Override
    public void setBus(Bus bus) {
        this.bus = bus;
    }

    /**
     * Hàm này tính toán lại xem các bank PRG và CHR sẽ trỏ tới vị trí nào
     * trong mảng byte của Cartridge dựa trên các thanh ghi hiện tại.
     */
    private void updateBanks() {
        if (cartridge == null) return;

        // Cập nhật PRG Banks (Tính theo đơn vị 8KB)
        int prgBanksTotal = cartridge.getPrgRom().length / 8192;
        if (prgBanksTotal == 0) prgBanksTotal = 1;

        prgBank0 = registers[6] % prgBanksTotal;
        prgBank1 = registers[7] % prgBanksTotal;
        prgBank2 = prgBanksTotal - 2; // Luôn là bank kế cuối
        prgBank3 = prgBanksTotal - 1; // Luôn là bank cuối cùng

        // Cập nhật CHR Banks (Tính theo đơn vị 1KB)
        int chrBanksTotal = cartridge.getChr().length / 1024;
        if (chrBanksTotal == 0) chrBanksTotal = 1;

        // Lệnh 0: 2KB CHR bank (bỏ qua bit thấp nhất nên ta mask với 0xFE)
        chrBanks[0] = (registers[0] & 0xFE) % chrBanksTotal;
        chrBanks[1] = (chrBanks[0] + 1) % chrBanksTotal;

        // Lệnh 1: 2KB CHR bank
        chrBanks[2] = (registers[1] & 0xFE) % chrBanksTotal;
        chrBanks[3] = (chrBanks[2] + 1) % chrBanksTotal;

        // Lệnh 2, 3, 4, 5: 1KB CHR bank
        chrBanks[4] = registers[2] % chrBanksTotal;
        chrBanks[5] = registers[3] % chrBanksTotal;
        chrBanks[6] = registers[4] % chrBanksTotal;
        chrBanks[7] = registers[5] % chrBanksTotal;
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x8000) {
            int bank;
            if (address < 0xA000) {
                bank = prgBank0;
            } else if (address < 0xC000) {
                bank = prgBank1;
            } else if (address < 0xE000) {
                bank = prgBank2;
            } else {
                bank = prgBank3;
            }

            int finalAddr = (bank * 8192) + (address & 0x1FFF);
            if (finalAddr < cartridge.getPrgRom().length) {
                return cartridge.getPrgRom()[finalAddr] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;

        // Mapper 206 chỉ quan tâm đến các địa chỉ ghi từ $8000 đến $9FFF
        if (address >= 0x8000 && address <= 0x9FFF) {
            if ((address & 1) == 0) {
                // Địa chỉ chẵn ($8000): Chọn lệnh (0-7)
                bankSelect = value & 0x07;
            } else {
                // Địa chỉ lẻ ($8001): Ghi dữ liệu vào lệnh đang được chọn
                registers[bankSelect] = value;
                updateBanks();
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x1FFF;

        // Tính toán xem địa chỉ này thuộc bank 1KB thứ mấy (0 tới 7)
        int bankNum = address / 1024;
        int offset = address % 1024;

        int finalAddr = (chrBanks[bankNum] * 1024) + offset;
        if (finalAddr < cartridge.getChr().length) {
            return cartridge.getChr()[finalAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x1FFF;

        // Chỉ cho phép ghi nếu Cartridge dùng CHR RAM
        if (cartridge.isChrRam()) {
            int bankNum = address / 1024;
            int offset = address % 1024;
            int finalAddr = (chrBanks[bankNum] * 1024) + offset;

            if (finalAddr < cartridge.getChr().length) {
                cartridge.getChr()[finalAddr] = (byte) value;
            }
        }
    }

    @Override
    public void reset() {
        bankSelect = 0;
        for (int i = 0; i < 8; i++) {
            registers[i] = 0;
        }
        if (cartridge != null) {
            updateBanks();
        }
    }
}