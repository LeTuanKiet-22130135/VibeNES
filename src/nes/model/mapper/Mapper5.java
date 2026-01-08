package nes.model. mapper;

import nes.model.Bus;
import nes.model. Cartridge;

import java.util.Arrays;

/**
 * Mapper 5: MMC5 (Nintendo MMC5)
 * Used by Castlevania III, Just Breed, Romance of Three Kingdoms II, etc.
 */
public class Mapper5 implements Mapper {

    private Cartridge cartridge;
    private Bus bus;
    private byte[] ppuVram;

    // PRG Banking
    private int prgMode = 3;
    private final int[] prgBanks = new int[5];
    private int prgRamProtect1 = 0;
    private int prgRamProtect2 = 0;

    // CHR Banking
    private int chrMode = 0;
    private final int[] chrBanksA = new int[8]; // $5120-$5127
    private final int[] chrBanksB = new int[4]; // $5128-$512B
    private int lastChrWrite = 0; // 0 = A, 1 = B
    private int chrUpperBits = 0; // $5130

    // Internal RAM
    private final byte[] prgRam = new byte[64 * 1024];
    private final byte[] exRam = new byte[1024];
    private int exRamMode = 0;

    // Nametable control
    private int nametableMapping = 0;
    private int fillModeTile = 0;
    private int fillModeAttr = 0;

    // IRQ
    private int irqScanline = 0;
    private boolean irqEnabled = false;
    private boolean irqPending = false;
    private int scanlineCounter = 0;
    private boolean inFrame = false;

    // Multiplier
    private int multiplicand = 0xFF;
    private int multiplier = 0xFF;

    // Sprite size tracking
    private boolean spriteSize8x16 = false;
    private boolean inSpriteFetch = false;

    // For EXRAM extended attribute mode - stores the EXRAM byte for current tile
    private int exRamTileAttr = 0;

    public Mapper5() {
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

    public void setPpuVram(byte[] vram) {
        this.ppuVram = vram;
    }

    @Override
    public void reset() {
        prgMode = 3;
        chrMode = 0;
        exRamMode = 0;
        nametableMapping = 0;
        fillModeTile = 0;
        fillModeAttr = 0;
        irqScanline = 0;
        irqEnabled = false;
        irqPending = false;
        scanlineCounter = 0;
        inFrame = false;
        multiplicand = 0xFF;
        multiplier = 0xFF;
        prgRamProtect1 = 0;
        prgRamProtect2 = 0;
        chrUpperBits = 0;
        spriteSize8x16 = false;
        lastChrWrite = 0;
        inSpriteFetch = false;
        exRamTileAttr = 0;

        Arrays.fill(prgBanks, 0);
        prgBanks[4] = 0xFF;

        Arrays.fill(chrBanksA, 0);
        Arrays.fill(chrBanksB, 0);
        Arrays.fill(prgRam, (byte) 0);
        Arrays.fill(exRam, (byte) 0);
    }

    // ==================== CPU Memory Access ====================

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;

        if (address >= 0x5000 && address < 0x5C00) {
            return readRegister(address);
        }

        if (address >= 0x5C00 && address <= 0x5FFF) {
            if (exRamMode >= 2) {
                return exRam[address - 0x5C00] & 0xFF;
            }
            return 0xFF;
        }

        if (address >= 0x6000 && address <= 0x7FFF) {
            int bank = prgBanks[0] & 0x7F;
            int offset = (bank * 0x2000) + (address & 0x1FFF);
            return prgRam[offset % prgRam.length] & 0xFF;
        }

        if (address >= 0x8000) {
            return readPrg(address);
        }

        return 0;
    }

    private int readRegister(int address) {
        switch (address) {
            case 0x5204:
                int status = (irqPending ? 0x80 : 0) | (inFrame ? 0x40 :  0);
                irqPending = false;
                return status;

            case 0x5205:
                return (multiplicand * multiplier) & 0xFF;

            case 0x5206:
                return ((multiplicand * multiplier) >> 8) & 0xFF;

            default:
                return 0;
        }
    }

    private int readPrg(int address) {
        byte[] prg = cartridge.getPrgRom();
        if (prg == null || prg.length == 0) return 0;

        int bank;
        int offset;

        switch (prgMode) {
            case 0: // 32KB mode
                bank = (prgBanks[4] & 0x7C) >> 2;
                offset = address & 0x7FFF;
                return prg[(bank * 0x8000 + offset) % prg.length] & 0xFF;

            case 1: // 16KB + 16KB mode
                if (address < 0xC000) {
                    bank = (prgBanks[2] & 0x7E) >> 1;
                    offset = address & 0x3FFF;
                } else {
                    bank = (prgBanks[4] & 0x7E) >> 1;
                    offset = address & 0x3FFF;
                }
                return prg[(bank * 0x4000 + offset) % prg.length] & 0xFF;

            case 2: // 16KB + 8KB + 8KB mode
                if (address < 0xC000) {
                    bank = (prgBanks[2] & 0x7E) >> 1;
                    offset = address & 0x3FFF;
                    return prg[(bank * 0x4000 + offset) % prg.length] & 0xFF;
                } else if (address < 0xE000) {
                    bank = prgBanks[3] & 0x7F;
                    offset = address & 0x1FFF;
                } else {
                    bank = prgBanks[4] & 0x7F;
                    offset = address & 0x1FFF;
                }
                return prg[(bank * 0x2000 + offset) % prg.length] & 0xFF;

            case 3: // 8KB x 4 mode
            default:
                if (address < 0xA000) {
                    int reg = prgBanks[1];
                    if ((reg & 0x80) == 0) {
                        bank = reg & 0x07;
                        offset = address & 0x1FFF;
                        return prgRam[(bank * 0x2000 + offset) % prgRam.length] & 0xFF;
                    }
                    bank = reg & 0x7F;
                    offset = address & 0x1FFF;
                } else if (address < 0xC000) {
                    int reg = prgBanks[2];
                    if ((reg & 0x80) == 0) {
                        bank = reg & 0x07;
                        offset = address & 0x1FFF;
                        return prgRam[(bank * 0x2000 + offset) % prgRam.length] & 0xFF;
                    }
                    bank = reg & 0x7F;
                    offset = address & 0x1FFF;
                } else if (address < 0xE000) {
                    int reg = prgBanks[3];
                    if ((reg & 0x80) == 0) {
                        bank = reg & 0x07;
                        offset = address & 0x1FFF;
                        return prgRam[(bank * 0x2000 + offset) % prgRam.length] & 0xFF;
                    }
                    bank = reg & 0x7F;
                    offset = address & 0x1FFF;
                } else {
                    bank = prgBanks[4] & 0x7F;
                    offset = address & 0x1FFF;
                }
                return prg[(bank * 0x2000 + offset) % prg.length] & 0xFF;
        }
    }

    @Override
    public void cpuWrite(int address, int value) {
        cpuWrite(address, value, 0);
    }

    @Override
    public void cpuWrite(int address, int value, long cycles) {
        address &= 0xFFFF;
        value &= 0xFF;

        if (address >= 0x5000 && address < 0x5C00) {
            writeRegister(address, value);
            return;
        }

        if (address >= 0x5C00 && address <= 0x5FFF) {
            if (exRamMode != 3) {
                exRam[address - 0x5C00] = (byte) value;
            }
            return;
        }

        if (address >= 0x6000 && address <= 0x7FFF) {
            if (prgRamProtect1 == 0x02 && prgRamProtect2 == 0x01) {
                int bank = prgBanks[0] & 0x07;
                int offset = (bank * 0x2000) + (address & 0x1FFF);
                prgRam[offset % prgRam. length] = (byte) value;
            }
            return;
        }

        if (address >= 0x8000) {
            writePrgRam(address, value);
        }
    }

    private void writeRegister(int address, int value) {
        switch (address) {
            case 0x5100:  prgMode = value & 0x03; break;
            case 0x5101: chrMode = value & 0x03; break;
            case 0x5102: prgRamProtect1 = value & 0x03; break;
            case 0x5103: prgRamProtect2 = value & 0x03; break;
            case 0x5104: exRamMode = value & 0x03; break;
            case 0x5105: nametableMapping = value; break;
            case 0x5106: fillModeTile = value; break;
            case 0x5107: fillModeAttr = value & 0x03; break;

            case 0x5113: prgBanks[0] = value & 0x07; break;
            case 0x5114: prgBanks[1] = value; break;
            case 0x5115: prgBanks[2] = value; break;
            case 0x5116: prgBanks[3] = value; break;
            case 0x5117: prgBanks[4] = value | 0x80; break;

            case 0x5120: chrBanksA[0] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5121: chrBanksA[1] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5122: chrBanksA[2] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5123: chrBanksA[3] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5124: chrBanksA[4] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5125: chrBanksA[5] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5126: chrBanksA[6] = value | (chrUpperBits << 8); lastChrWrite = 0; break;
            case 0x5127: chrBanksA[7] = value | (chrUpperBits << 8); lastChrWrite = 0; break;

            case 0x5128: chrBanksB[0] = value | (chrUpperBits << 8); lastChrWrite = 1; break;
            case 0x5129: chrBanksB[1] = value | (chrUpperBits << 8); lastChrWrite = 1; break;
            case 0x512A: chrBanksB[2] = value | (chrUpperBits << 8); lastChrWrite = 1; break;
            case 0x512B: chrBanksB[3] = value | (chrUpperBits << 8); lastChrWrite = 1; break;

            case 0x5130: chrUpperBits = value & 0x03; break;

            case 0x5203: irqScanline = value; break;
            case 0x5204: irqEnabled = (value & 0x80) != 0; break;

            case 0x5205: multiplicand = value; break;
            case 0x5206: multiplier = value; break;
        }
    }

    private void writePrgRam(int address, int value) {
        if (prgRamProtect1 != 0x02 || prgRamProtect2 != 0x01) return;

        int reg;
        if (address < 0xA000) {
            reg = prgBanks[1];
        } else if (address < 0xC000) {
            reg = prgBanks[2];
        } else if (address < 0xE000) {
            reg = prgBanks[3];
        } else {
            return;
        }

        if ((reg & 0x80) == 0) {
            int bank = reg & 0x07;
            int offset = (bank * 0x2000) + (address & 0x1FFF);
            prgRam[offset % prgRam. length] = (byte) value;
        }
    }

    // ==================== PPU Memory Access ====================

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;

        if (address < 0x2000) {
            return readChr(address);
        }

        if (address < 0x3F00) {
            return readNametable(address);
        }

        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;

        if (address < 0x2000) {
            writeChr(address, value);
            return;
        }

        if (address < 0x3F00) {
            writeNametable(address, value);
        }
    }

    /**
     * Read CHR memory with MMC5 banking.
     *
     * EXRAM Mode 1 (Extended Attribute Mode):
     * - Background fetches use EXRAM to provide per-tile CHR bank selection
     * - Bits 5-0 of EXRAM = 4KB CHR bank number
     * - Combined with $5130 upper bits
     * - Sprite fetches use normal bank registers
     */
    private int readChr(int address) {
        byte[] chr = cartridge.getChr();
        if (chr == null || chr.length == 0) return 0;

        int finalAddr;

        // EXRAM mode 1: Extended attribute mode for background fetches ONLY
        // This provides per-tile CHR banking from EXRAM
        if (exRamMode == 1 && ! inSpriteFetch) {
            // In extended attribute mode, EXRAM provides:
            // - Bits 5-0: 4KB CHR bank number
            // - Combined with $5130 for upper bits
            int exRamChrBank = exRamTileAttr & 0x3F;
            int fullBank = exRamChrBank | (chrUpperBits << 6);

            // 4KB bank addressing - use offset within 4KB
            finalAddr = (fullBank * 0x1000) + (address & 0x0FFF);
        } else {
            // Normal banking mode (sprites, or when exRamMode != 1)
            finalAddr = calculateChrAddress(address);
        }

        return chr[finalAddr % chr.length] & 0xFF;
    }

    /**
     * Calculate CHR address using normal bank registers.
     */
    private int calculateChrAddress(int address) {
        // Determine which bank set to use
        boolean useSetA;
        if (spriteSize8x16) {
            // 8x16 sprites:  Set A for sprites, Set B for backgrounds
            useSetA = inSpriteFetch;
        } else {
            // 8x8 sprites: Use whichever set was last written to
            useSetA = (lastChrWrite == 0);
        }

        int region = (address >> 10) & 0x07;

        switch (chrMode) {
            case 0: { // 8KB mode
                int bank = useSetA ? chrBanksA[7] : chrBanksB[3];
                return (bank * 0x2000) + (address & 0x1FFF);
            }

            case 1: { // 4KB mode
                int bank;
                if (address < 0x1000) {
                    bank = useSetA ? chrBanksA[3] : chrBanksB[3];
                } else {
                    bank = useSetA ? chrBanksA[7] :  chrBanksB[3];
                }
                return (bank * 0x1000) + (address & 0x0FFF);
            }

            case 2: { // 2KB mode
                int regIndex = region >> 1; // 0-3
                int bank;
                if (useSetA) {
                    bank = chrBanksA[regIndex * 2 + 1];
                } else {
                    bank = chrBanksB[regIndex & 1 | ((regIndex >> 1) << 1)];
                }
                return (bank * 0x0800) + (address & 0x07FF);
            }

            case 3: // 1KB mode
            default:  {
                int bank;
                if (useSetA) {
                    bank = chrBanksA[region];
                } else {
                    bank = chrBanksB[region & 0x03];
                }
                return (bank * 0x0400) + (address & 0x03FF);
            }
        }
    }

    private void writeChr(int address, int value) {
        if (! cartridge.isChrRam()) return;

        byte[] chr = cartridge.getChr();
        if (chr == null || chr.length == 0) return;

        int finalAddr = calculateChrAddress(address);
        chr[finalAddr % chr. length] = (byte) value;
    }

    private int readNametable(int address) {
        int ntAddr = address & 0x0FFF;
        int ntIndex = (ntAddr >> 10) & 0x03;
        int offset = ntAddr & 0x3FF;
        boolean isAttributeFetch = (offset >= 0x3C0);

        int mode = (nametableMapping >> (ntIndex * 2)) & 0x03;

        // EXRAM Mode 1: Attribute table reads return per-tile palette from EXRAM
        // This applies regardless of which nametable source is used
        if (exRamMode == 1 && isAttributeFetch && ! inSpriteFetch) {
            // Bits 7-6 of the captured EXRAM byte = palette for this tile
            int attr = (exRamTileAttr >> 6) & 0x03;
            // Return this attribute in all 4 positions of the attribute byte
            return attr | (attr << 2) | (attr << 4) | (attr << 6);
        }

        switch (mode) {
            case 0:
                return ppuVram != null ? ppuVram[offset] & 0xFF : 0;
            case 1:
                return ppuVram != null ? ppuVram[0x400 + offset] & 0xFF : 0;
            case 2:
                if (exRamMode == 0 || exRamMode == 1) {
                    return exRam[offset] & 0xFF;
                }
                return 0;
            case 3:
                if (offset < 0x3C0) {
                    return fillModeTile & 0xFF;
                } else {
                    int attr = fillModeAttr & 0x03;
                    return attr | (attr << 2) | (attr << 4) | (attr << 6);
                }
            default:
                return 0;
        }
    }

    private void writeNametable(int address, int value) {
        int ntAddr = address & 0x0FFF;
        int ntIndex = (ntAddr >> 10) & 0x03;
        int offset = ntAddr & 0x3FF;

        int mode = (nametableMapping >> (ntIndex * 2)) & 0x03;

        switch (mode) {
            case 0:
                if (ppuVram != null) ppuVram[offset] = (byte) value;
                break;
            case 1:
                if (ppuVram != null) ppuVram[0x400 + offset] = (byte) value;
                break;
            case 2:
                if (exRamMode != 3) exRam[offset] = (byte) value;
                break;
            case 3:
                break;
        }
    }

    // ==================== PPU Callbacks ====================

    public void setFetchingSprites(boolean fetching) {
        this.inSpriteFetch = fetching;
    }

    public void setSpriteSize8x16(boolean is8x16) {
        this.spriteSize8x16 = is8x16;
    }

    public void onScanlineEnd(int scanline) {
        if (scanline >= 0 && scanline < 240) {
            if (! inFrame) {
                inFrame = true;
                scanlineCounter = 0;
            }
            scanlineCounter++;
            if (scanlineCounter == irqScanline && irqEnabled) {
                irqPending = true;
                if (bus != null) {
                    bus.requestIrq();
                }
            }
        }
    }

    public void startVBlank() {
        inFrame = false;
    }

    public void startFrame() {
        inFrame = false;
        scanlineCounter = 0;
    }

    /**
     * Called by PPU when fetching a nametable byte (tile ID).
     * In EXRAM mode 1, we capture the EXRAM byte at the same tile position
     * for use in extended CHR banking and per-tile palette.
     */
    public void notifyNametableFetch(int address) {
        if (exRamMode == 1) {
            int offset = address & 0x3FF;
            if (offset < 0x3C0) {
                exRamTileAttr = exRam[offset] & 0xFF;
            }
        }
    }

    public int getMirroringMode() {
        return 2;
    }

    public boolean handlesNametables() {
        return true;
    }
}