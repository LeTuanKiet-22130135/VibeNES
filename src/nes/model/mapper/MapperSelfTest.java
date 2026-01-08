package nes.model.mapper;

import nes.model.*;

/**
 * Small self-tests for mapper behavior, focusing on MMC1 basics.
 */
public final class MapperSelfTest {
    private MapperSelfTest() {}

    /**
     * Construct a tiny iNES image with the given parameters.
     */
    private static Cartridge makeRom(int prgBanks16k, int chrBanks8k, boolean verticalMirroring, int mapperId,
                                     byte[] prgFill, int vectorLo, int vectorHi) {
        int prgSize = prgBanks16k * 16 * 1024;
        int chrSize = chrBanks8k * 8 * 1024;
        byte[] header = new byte[16];
        header[0] = 'N'; header[1] = 'E'; header[2] = 'S'; header[3] = 0x1A;
        header[4] = (byte) prgBanks16k; // PRG banks
        header[5] = (byte) chrBanks8k; // CHR banks
        int flags6 = 0;
        if (verticalMirroring) flags6 |= 0x01;
        flags6 |= ((mapperId & 0x0F) << 4);
        header[6] = (byte) flags6;
        header[7] = (byte) ((mapperId & 0xF0));
        byte[] prg = new byte[prgSize];
        byte[] chr = new byte[chrSize > 0 ? chrSize : 8 * 1024];

        // Fill PRG banks with recognizable patterns
        for (int b = 0; b < prgBanks16k; b++) {
            int base = b * 16 * 1024;
            byte fill = prgFill != null && b < prgFill.length ? prgFill[b] : (byte) (0x10 * (b + 1));
            for (int i = 0; i < 16 * 1024; i++) prg[base + i] = fill;
        }
        // Place a reset vector at the end of last bank ($FFFC-$FFFD)
        if (prgSize >= 0x8000) {
            prg[prgSize - 4] = (byte) vectorLo; // $FFFC
            prg[prgSize - 3] = (byte) vectorHi; // $FFFD
        }

        byte[] rom = new byte[16 + prg.length + (chrBanks8k > 0 ? chr.length : 0)];
        System.arraycopy(header, 0, rom, 0, 16);
        System.arraycopy(prg, 0, rom, 16, prg.length);
        if (chrBanks8k > 0) System.arraycopy(chr, 0, rom, 16 + prg.length, chr.length);
        return Cartridge.loadFromBytes(rom, "MMC1_TEST");
    }

    /** Basic MMC1 sanity checks: reset mapping, PRG bank switching, and initial mirroring. */
    public static boolean runMMC1Basic() {
        // 2x16KB PRG, 1x8KB CHR, mapper 1
        byte[] fills = new byte[] {(byte) 0xA0, (byte) 0xB0};
        Cartridge cart = makeRom(2, 1, true, 1, fills, 0x34, 0x12);

        CPU cpu = new CPU(); PPU ppu = new PPU(); APU apu = new APU(); RAM ram = new RAM(2 * 1024); Bus bus = new Bus(cpu, ppu, apu, ram);
        bus.setCartridge(cart); ppu.setCartridge(cart); cart.reset();

        // 1) On reset, control = 0x0C (mode 3), last bank fixed at $C000-$FFFF.
        int lo = bus.read(0xFFFC) & 0xFF; // should read from last bank
        if (lo != 0x34) return false;

        // 2) In mode 3, $8000-$BFFF maps to selected bank (initial prgBank = 0)
        int v8000 = bus.read(0x8000) & 0xFF;
        if (v8000 != 0xA0) return false; // bank 0 fill

        // 3) Switch PRG bank to 1 via MMC1 register 3 writes ($E000-$FFFF)
        Mapper1 m1 = (Mapper1) cart.getMapper();
        long cyc = 0;
        // Write PRG bank value = 1 (lower 4 bits) LSB-first to $E000 region
        int data = 0x01;
        for (int i = 0; i < 5; i++) {
            int bit = (data >> i) & 1;
            m1.cpuWrite(0xE000, bit, cyc);
            cyc += 2; // ensure spacing
        }

        int v8000b1 = bus.read(0x8000) & 0xFF;
        if (v8000b1 != 0xB0) return false; // bank 1 fill

        // 4) Ensure $C000 still maps to last bank
        int vC000 = bus.read(0xC000) & 0xFF;
        if (vC000 != 0xB0) return false;

        // 5) Verify initial nametable mirroring honors iNES header (VERTICAL in this ROM)
        {
            PPU testPPU = new PPU();
            Bus testBus = new Bus(new CPU(), testPPU, new APU(), new RAM(2 * 1024));
            testBus.setCartridge(cart); testPPU.setCartridge(cart); testPPU.reset();

            // Write to $2000 and verify mirror at $2800 (vertical mirroring)
            testPPU.writeRegister(0x2006, 0x20);
            testPPU.writeRegister(0x2006, 0x00);
            testPPU.writeRegister(0x2007, 0x5A);
            int mirror = testPPU.getVramByteForAddr(0x2800) & 0xFF;
            if (mirror != 0x5A) return false;
        }

        // 6) Repeat with a HORIZONTAL header ROM
        {
            Cartridge cartH = makeRom(2, 1, false, 1, fills, 0x78, 0x56);
            PPU testPPU = new PPU();
            Bus testBus = new Bus(new CPU(), testPPU, new APU(), new RAM(2 * 1024));
            testBus.setCartridge(cartH); testPPU.setCartridge(cartH); cartH.reset(); testPPU.reset();

            // Write to $2000 and verify mirror at $2400 (horizontal mirroring)
            testPPU.writeRegister(0x2006, 0x20);
            testPPU.writeRegister(0x2006, 0x00);
            testPPU.writeRegister(0x2007, 0xA5);
            int mirror = testPPU.getVramByteForAddr(0x2400) & 0xFF;
            if (mirror != 0xA5) return false;
        }

        return true;
    }
}
