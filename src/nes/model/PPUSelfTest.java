package nes.model;

/**
 * Tiny PPU self-tests to validate critical register semantics.
 */
public final class PPUSelfTest {
    private PPUSelfTest() {}

    /**
     * Verify that PPUDATA ($2007) increments the VRAM address by 1 or 32
     * depending solely on PPUCTRL bit 2, regardless of the current address
     * range (nametable vs palette).
     */
    public static boolean runVramIncrementTest() {
        CPU cpu = new CPU();
        PPU ppu = new PPU();
        APU apu = new APU();
        RAM ram = new RAM(2 * 1024);
        Bus bus = new Bus(cpu, ppu, apu, ram);

        // Ensure clean state
        ppu.reset();

        // Helper lambdas for brevity
        java.util.function.BiConsumer<Integer, Integer> W = (addr, val) -> ppu.writeRegister(addr, val);

        // Case 1: Increment = 1 at palette address
        // PPUCTRL: bit2 = 0 (inc by 1)
        W.accept(0x2000, 0x00);
        // Set PPUADDR = $3F00
        W.accept(0x2006, 0x3F);
        W.accept(0x2006, 0x00);
        if ((ppu.getV() & 0x3FFF) != 0x3F00) return false;
        // Write PPUDATA -> expect v = $3F01
        W.accept(0x2007, 0x12);
        if ((ppu.getV() & 0x3FFF) != 0x3F01) return false;

        // Case 2: Increment = 32 at palette address
        // PPUCTRL: bit2 = 1 (inc by 32)
        W.accept(0x2000, 0x04);
        // Set PPUADDR = $3F04
        W.accept(0x2006, 0x3F);
        W.accept(0x2006, 0x04);
        if ((ppu.getV() & 0x3FFF) != 0x3F04) return false;
        // Write PPUDATA -> expect v = $3F24
        W.accept(0x2007, 0x34);
        if ((ppu.getV() & 0x3FFF) != 0x3F24) return false;

        // Case 3: Increment = 32 at nametable address
        W.accept(0x2000, 0x04);
        // Set PPUADDR = $2000
        W.accept(0x2006, 0x20);
        W.accept(0x2006, 0x00);
        if ((ppu.getV() & 0x3FFF) != 0x2000) return false;
        W.accept(0x2007, 0xAA);
        if ((ppu.getV() & 0x3FFF) != 0x2020) return false;

        // Case 4: Increment = 1 at nametable address
        W.accept(0x2000, 0x00);
        // Set PPUADDR = $23FF
        W.accept(0x2006, 0x23);
        W.accept(0x2006, 0xFF);
        if ((ppu.getV() & 0x3FFF) != 0x23FF) return false;
        W.accept(0x2007, 0x55);
        if ((ppu.getV() & 0x3FFF) != 0x2400) return false;

        return true;
    }

    /**
     * Verify nametable mirroring mapping for Horizontal and Vertical modes.
     * Horizontal: NT0<->NT1 (0x2000<->0x2400), NT2<->NT3 (0x2800<->0x2C00)
     * Vertical:   NT0<->NT2 (0x2000<->0x2800), NT1<->NT3 (0x2400<->0x2C00)
     */
    public static boolean runMirroringTest() {
        // Build minimal iNES ROMs for Mapper 0 with requested mirroring
        java.util.function.Function<Boolean, Cartridge> makeCart = (vertical) -> {
            // Header (16 bytes)
            byte[] header = new byte[16];
            header[0] = 'N'; header[1] = 'E'; header[2] = 'S'; header[3] = 0x1A;
            header[4] = 1; // 1x16KB PRG
            header[5] = 1; // 1x8KB CHR
            int flags6 = 0;
            if (vertical) flags6 |= 0x01; // vertical mirroring flag
            header[6] = (byte) flags6;
            header[7] = 0; // mapper upper nibble
            byte[] prg = new byte[16 * 1024];
            byte[] chr = new byte[8 * 1024];
            byte[] rom = new byte[16 + prg.length + chr.length];
            System.arraycopy(header, 0, rom, 0, 16);
            System.arraycopy(prg, 0, rom, 16, prg.length);
            System.arraycopy(chr, 0, rom, 16 + prg.length, chr.length);
            return Cartridge.loadFromBytes(rom, vertical ? "TEST_VERT" : "TEST_HORZ");
        };

        // Helper to write a byte to PPU VRAM via registers
        java.util.function.BiConsumer<PPUWriteCtx, Integer> write2007 = (ctx, value) -> {
            ctx.ppu.writeRegister(0x2006, (ctx.addr >> 8) & 0xFF);
            ctx.ppu.writeRegister(0x2006, ctx.addr & 0xFF);
            ctx.ppu.writeRegister(0x2007, value & 0xFF);
        };

        // Horizontal mirroring
        {
            CPU cpu = new CPU(); PPU ppu = new PPU(); APU apu = new APU(); RAM ram = new RAM(2 * 1024); Bus bus = new Bus(cpu, ppu, apu, ram);
            Cartridge cartH = makeCart.apply(false); // horizontal
            bus.setCartridge(cartH); ppu.setCartridge(cartH); ppu.reset();

            // Write to $2000 and expect mirror at $2400
            PPUWriteCtx ctx = new PPUWriteCtx(ppu, 0x2000);
            write2007.accept(ctx, 0x12);
            int r1 = ppu.getVramByteForAddr(0x2400) & 0xFF;
            if (r1 != 0x12) { System.out.println("[DEBUG_LOG] H-Mirror fail: $2000->$2400 got="+r1); return false; }

            // Write to $2C10 and expect mirror at $2810
            ctx = new PPUWriteCtx(ppu, 0x2C10);
            write2007.accept(ctx, 0x34);
            int r2 = ppu.getVramByteForAddr(0x2810) & 0xFF;
            if (r2 != 0x34) { System.out.println("[DEBUG_LOG] H-Mirror fail: $2C10->$2810 got="+r2); return false; }
        }

        // Vertical mirroring
        {
            CPU cpu = new CPU(); PPU ppu = new PPU(); APU apu = new APU(); RAM ram = new RAM(2 * 1024); Bus bus = new Bus(cpu, ppu, apu, ram);
            Cartridge cartV = makeCart.apply(true); // vertical
            bus.setCartridge(cartV); ppu.setCartridge(cartV); ppu.reset();

            // Write to $2000 and expect mirror at $2800
            PPUWriteCtx ctx = new PPUWriteCtx(ppu, 0x2000);
            write2007.accept(ctx, 0x56);
            int r3 = ppu.getVramByteForAddr(0x2800) & 0xFF;
            if (r3 != 0x56) { System.out.println("[DEBUG_LOG] V-Mirror fail: $2000->$2800 got="+r3); return false; }

            // Write to $2410 and expect mirror at $2C10
            ctx = new PPUWriteCtx(ppu, 0x2410);
            write2007.accept(ctx, 0x78);
            int r4 = ppu.getVramByteForAddr(0x2C10) & 0xFF;
            if (r4 != 0x78) { System.out.println("[DEBUG_LOG] V-Mirror fail: $2410->$2C10 got="+r4); return false; }
        }

        return true;
    }

    private static final class PPUWriteCtx {
        final PPU ppu; final int addr;
        PPUWriteCtx(PPU ppu, int addr) { this.ppu = ppu; this.addr = addr; }
    }

    /** Run all available PPU self-tests. */
    public static boolean runAll() {
        return runVramIncrementTest() && runMirroringTest();
    }
}
