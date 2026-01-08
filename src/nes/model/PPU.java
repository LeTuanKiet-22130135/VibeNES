package nes.model;

import nes.model.mapper.Mapper5;

import java.util.Arrays;

/**
 * NES Picture Processing Unit (PPU)
 * This implementation includes a scanline-based renderer with accurate
 * sprite fetching timing for proper MMC3 A12 IRQ detection.
 */
public class PPU {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 240;

    private final int[] frameBuffer = new int[WIDTH * HEIGHT];

    private final byte[] vram = new byte[0x800];
    private final byte[] palette = new byte[0x20];
    private final byte[] oam = new byte[256];
    private Cartridge cartridge;

    // Registers/state
    private boolean ctrlNmiEnable;
    private boolean ctrlSpriteSize8x16;
    private boolean ctrlBgTableHigh;
    private boolean ctrlSprTableHigh;
    private boolean ctrlAddrInc32;
    private int ctrlNametableSelect;

    private int maskReg;

    private boolean statusVBlank;
    private boolean statusSpriteZeroHit;
    private boolean statusSpriteOverflow;

    private int oamAddr;
    // Loopy scrolling registers
    private boolean writeToggleW;
    private int t; // Temporary VRAM address (15 bits)
    private int v; // Current VRAM address (15 bits)
    private int xFine; // Fine X scroll (3 bits)

    private int ppuDataReadBuffer;
    private int openBus; // Last value written to the PPU bus

    // Timing
    private int scanline;
    private int dot;
    private boolean oddFrame;

    // Background rendering shifters
    private int bg_next_tile_id;
    private int bg_next_tile_attrib;
    private int bg_next_tile_lsb;
    private int bg_next_tile_msb;
    private long bg_shifter_pattern_lo;
    private long bg_shifter_pattern_hi;
    private long bg_shifter_attrib_lo;
    private long bg_shifter_attrib_hi;

    // Sprite evaluation state
    private final int[] spriteScanline = new int[8]; // OAM indices of sprites on current line
    private int spriteCount = 0;

    // Pre-fetched sprite data (fetched during dots 257-320)
    private final int[] spritePatternLo = new int[8];
    private final int[] spritePatternHi = new int[8];
    private final int[] spriteXPos = new int[8];
    private final int[] spriteAttrs = new int[8];
    private final boolean[] spriteIsZero = new boolean[8];

    private Bus bus;

    public PPU() {
        reset();
    }

    void attachBus(Bus bus) { this.bus = bus; }
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        if (cartridge != null && cartridge.getMapper() instanceof Mapper5) {
            ((Mapper5) cartridge.getMapper()).setPpuVram(vram);
        }
    }

    public void reset() {
        // Clear all registers and state
        ctrlNmiEnable = false; ctrlSpriteSize8x16 = false; ctrlBgTableHigh = false;
        ctrlSprTableHigh = false; ctrlAddrInc32 = false; ctrlNametableSelect = 0;
        maskReg = 0; statusVBlank = false; statusSpriteZeroHit = false;
        statusSpriteOverflow = false; oamAddr = 0; writeToggleW = false;
        t = 0; v = 0; xFine = 0; ppuDataReadBuffer = 0; openBus = 0;
        scanline = -1; dot = 0; oddFrame = false;
        bg_next_tile_id = 0;
        bg_next_tile_attrib = 0;
        bg_next_tile_lsb = 0;
        bg_next_tile_msb = 0;
        bg_shifter_pattern_lo = 0;
        bg_shifter_pattern_hi = 0;
        bg_shifter_attrib_lo = 0;
        bg_shifter_attrib_hi = 0;

        // Clear memories
        Arrays.fill(vram, (byte) 0);
        Arrays.fill(palette, (byte) 0);
        Arrays.fill(oam, (byte) 0);
        Arrays.fill(frameBuffer, 0);
        Arrays.fill(spriteScanline, -1);
        spriteCount = 0;

        // Clear sprite fetch buffers
        Arrays.fill(spritePatternLo, 0);
        Arrays.fill(spritePatternHi, 0);
        Arrays.fill(spriteXPos, 0);
        Arrays.fill(spriteAttrs, 0);
        Arrays.fill(spriteIsZero, false);
    }

    public void stepCpuCycles(int cpuCycles) {
        if (cpuCycles <= 0) return;
        int ppuCycles = cpuCycles * 3;
        while (ppuCycles-- > 0) {
            tickOneDot();
        }
    }

    private void tickOneDot() {
        // --- Pre-render scanline clear flags ---
        if (scanline == -1 && dot == 1) {
            statusVBlank = false;
            statusSpriteZeroHit = false;
            statusSpriteOverflow = false;
        }

        // --- VBlank start ---
        if (scanline == 241 && dot == 1) {
            statusVBlank = true;
            if (ctrlNmiEnable) {
                bus.requestNmi();
            }
            // Notify MMC5 of vblank
            if (cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                ((Mapper5) cartridge.getMapper()).startVBlank();
            }
        }

        boolean renderingEnabled = isRenderEnabled();

        // --- Visible scanlines and pre-render line ---
        if (scanline >= -1 && scanline < 240) {

            // Sprite evaluation at dot 65 (we do it at dot 1 for simplicity, result is same)
            if (renderingEnabled && dot == 1 && scanline >= 0) {
                evaluateSprites();
            }

            // Background fetching and shifting
            if (renderingEnabled) {

                // *** MMC5:  Signal start of BG fetches at dot 1 ***
                if (dot == 1 && cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                    ((Mapper5) cartridge.getMapper()).setFetchingSprites(false);
                }

                // Shifter update (dots 2-257 and 322-337)
                if ((dot >= 2 && dot <= 257) || (dot >= 322 && dot <= 337)) {
                    bg_shifter_pattern_lo <<= 1;
                    bg_shifter_pattern_hi <<= 1;
                    bg_shifter_attrib_lo <<= 1;
                    bg_shifter_attrib_hi <<= 1;
                }

                // Background tile fetching (dots 1-256 and 321-336)
                if ((dot >= 1 && dot <= 256) || (dot >= 321 && dot <= 336)) {
                    switch ((dot - 1) % 8) {
                        case 0:
                            loadBackgroundShifters();
                            int ntAddr = 0x2000 | (v & 0x0FFF);
                            // Notify MMC5 of nametable fetch for EXRAM mode
                            if (cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                                ((Mapper5) cartridge.getMapper()).notifyNametableFetch(ntAddr);
                            }
                            bg_next_tile_id = readVram(ntAddr);
                            break;
                        case 2:
                            bg_next_tile_attrib = readVram(0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07));
                            if (((v >> 5) & 2) != 0) bg_next_tile_attrib >>= 4;
                            if ((v & 2) != 0) bg_next_tile_attrib >>= 2;
                            bg_next_tile_attrib &= 0x03;
                            break;
                        case 4:
                            bg_next_tile_lsb = readVram((ctrlBgTableHigh ?  0x1000 : 0) + (bg_next_tile_id * 16) + ((v >> 12) & 7));
                            break;
                        case 6:
                            bg_next_tile_msb = readVram((ctrlBgTableHigh ? 0x1000 : 0) + (bg_next_tile_id * 16) + 8 + ((v >> 12) & 7));
                            break;
                        case 7:
                            incrementScrollX();
                            break;
                    }
                }

                // *** MMC5: Signal start of sprite fetches at dot 257 ***
                if (dot == 257 && cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                    ((Mapper5) cartridge.getMapper()).setFetchingSprites(true);
                }

                // *** MMC5: Signal end of sprite fetches AND scanline end at dot 340 ***
                if (dot == 340 && cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                    ((Mapper5) cartridge.getMapper()).setFetchingSprites(false);
                    if (scanline >= 0) {
                        ((Mapper5) cartridge.getMapper()).onScanlineEnd(scanline);
                    }
                }

                // End of visible scanline - increment Y
                if (dot == 256) {
                    incrementScrollY();
                }

                // Copy horizontal bits from t to v
                if (dot == 257) {
                    loadBackgroundShifters();
                    v = (v & ~0x041F) | (t & 0x041F);
                }

                // *** MMC5: Signal start of sprite fetches at dot 257 ***
                if (dot == 257 && cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                    ((Mapper5) cartridge.getMapper()).setFetchingSprites(true);
                }

                // Sprite pattern fetches (dots 257-320)
                if (dot >= 257 && dot <= 320 && scanline >= 0) {
                    fetchSpriteData();
                }

                // *** MMC5: Signal end of sprite fetches at dot 321 ***
                if (dot == 321 && cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                    ((Mapper5) cartridge.getMapper()).setFetchingSprites(false);
                }

                // Pre-render:  copy vertical bits from t to v
                if (scanline == -1 && dot >= 280 && dot <= 304) {
                    v = (v & ~0x7BE0) | (t & 0x7BE0);
                }
            }

            // Pixel output (visible scanlines only, dots 1-256)
            if (scanline >= 0 && dot >= 1 && dot <= 256) {
                renderPixel();
            }
        }

        // --- Advance dot and scanline ---
        dot++;
        if (dot > 340) {
            dot = 0;
            scanline++;
            if (scanline > 260) {
                scanline = -1;
                oddFrame = !oddFrame;
                if (oddFrame && renderingEnabled) {
                    dot = 1;
                }
            }
        }
    }

    /**
     * Fetch sprite pattern data during dots 257-320.
     * Each sprite takes 8 dots:  2 NT garbage, 2 AT garbage, 2 pattern lo, 2 pattern hi.
     * These reads trigger A12 transitions for MMC3 IRQ counting.
     */
    private void fetchSpriteData() {
        int spriteDot = dot - 257; // 0-63
        int spriteIndex = spriteDot / 8; // Which sprite (0-7)
        int cycle = spriteDot % 8; // Which cycle within sprite fetch (0-7)

        if (spriteIndex < spriteCount && spriteScanline[spriteIndex] >= 0) {
            int oamIdx = spriteScanline[spriteIndex] * 4;
            int sprY = oam[oamIdx] & 0xFF;
            int tile = oam[oamIdx + 1] & 0xFF;
            int attr = oam[oamIdx + 2] & 0xFF;
            int sprX = oam[oamIdx + 3] & 0xFF;

            switch (cycle) {
                case 0: // Garbage nametable read
                    readVram(0x2000 | (v & 0x0FFF));
                    break;
                case 2: // Garbage attribute read
                    readVram(0x23C0 | (v & 0x0C00));
                    break;
                case 4: // Pattern table low byte
                    spritePatternLo[spriteIndex] = fetchSpritePattern(sprY, tile, attr, false);
                    break;
                case 6: // Pattern table high byte
                    spritePatternHi[spriteIndex] = fetchSpritePattern(sprY, tile, attr, true);
                    // Store other sprite data for rendering
                    spriteXPos[spriteIndex] = sprX;
                    spriteAttrs[spriteIndex] = attr;
                    spriteIsZero[spriteIndex] = (spriteScanline[spriteIndex] == 0);
                    break;
            }
        } else {
            // No sprite in this slot - fetch from $FF tile (still triggers A12)
            switch (cycle) {
                case 0:
                    readVram(0x2000 | (v & 0x0FFF));
                    break;
                case 2:
                    readVram(0x23C0 | (v & 0x0C00));
                    break;
                case 4:
                    fetchDummySpritePattern(false);
                    // Clear this slot
                    spritePatternLo[spriteIndex] = 0;
                    break;
                case 6:
                    fetchDummySpritePattern(true);
                    spritePatternHi[spriteIndex] = 0;
                    spriteXPos[spriteIndex] = 0xFF;
                    spriteAttrs[spriteIndex] = 0;
                    spriteIsZero[spriteIndex] = false;
                    break;
            }
        }
    }

    private int fetchSpritePattern(int sprY, int tile, int attr, boolean highByte) {
        int height = ctrlSpriteSize8x16 ? 16 :  8;

        // Row within the sprite (0 to height-1)
        // scanline - sprY gives 1 to height, so subtract 1
        int row = scanline - sprY;

        if (row < 0 || row >= height) {
            return 0;
        }

        boolean flipV = (attr & 0x80) != 0;
        if (flipV) {
            row = height - 1 - row;
        }

        int patternAddr;
        if (ctrlSpriteSize8x16) {
            int table = (tile & 1) * 0x1000;
            int baseTile = tile & 0xFE;
            if (row >= 8) {
                baseTile++;
                row -= 8;
            }
            patternAddr = table + baseTile * 16 + row;
        } else {
            int table = ctrlSprTableHigh ?  0x1000 : 0;
            patternAddr = table + tile * 16 + row;
        }

        if (highByte) {
            patternAddr += 8;
        }

        return readVram(patternAddr);
    }

    /**
     * Fetch from tile $FF when sprite slot is empty.
     * Still triggers A12 transitions.
     */
    private void fetchDummySpritePattern(boolean highByte) {
        int table = ctrlSprTableHigh ? 0x1000 :  0;
        int patternAddr = table + (0xFF * 16);
        if (highByte) {
            patternAddr += 8;
        }
        readVram(patternAddr);
    }

    private void evaluateSprites() {
        Arrays.fill(spriteScanline, -1);
        spriteCount = 0;
        int height = ctrlSpriteSize8x16 ? 16 : 8;

        for (int i = 0; i < 64; i++) {
            int spriteY = oam[i * 4] & 0xFF;
            // NES sprites:  Y value is the scanline BEFORE the sprite appears
            // So sprite at Y=0 is displayed starting on scanline 1
            // Sprite at Y=N is displayed on scanlines N+1 through N+height
            // For scanline S, we check if S is in range [Y+1, Y+height]
            // Which means:  (S - 1) - Y must be in range [0, height-1]
            // Or equivalently: S - Y must be in range [1, height]

            int row = scanline - spriteY;

            if (row >= 1 && row <= height) {
                if (spriteCount < 8) {
                    spriteScanline[spriteCount++] = i;
                } else {
                    statusSpriteOverflow = true;
                    break;
                }
            }
        }
    }

    private void loadBackgroundShifters() {
        bg_shifter_pattern_lo = (bg_shifter_pattern_lo & 0xFF00) | (bg_next_tile_lsb & 0xFF);
        bg_shifter_pattern_hi = (bg_shifter_pattern_hi & 0xFF00) | (bg_next_tile_msb & 0xFF);
        bg_shifter_attrib_lo = (bg_shifter_attrib_lo & 0xFF00) | ((bg_next_tile_attrib & 1) != 0 ? 0xFF : 0x00);
        bg_shifter_attrib_hi = (bg_shifter_attrib_hi & 0xFF00) | ((bg_next_tile_attrib & 2) != 0 ? 0xFF : 0x00);
    }

    private void incrementScrollX() {
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;
            v ^= 0x0400;
        } else {
            v++;
        }
    }

    private void incrementScrollY() {
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000;
        } else {
            v &= ~0x7000;
            int y = (v & 0x03E0) >> 5;
            if (y == 29) {
                y = 0;
                v ^= 0x0800;
            } else if (y == 31) {
                y = 0;
            } else {
                y++;
            }
            v = (v & ~0x03E0) | (y << 5);
        }
    }

    private void renderPixel() {
        int x = dot - 1;
        int bgPixel = 0;
        int bgPalette = 0;

        boolean renderBg = (maskReg & 0x08) != 0;
        boolean renderBgLeft = (maskReg & 0x02) != 0;
        boolean renderSpr = (maskReg & 0x10) != 0;
        boolean renderSprLeft = (maskReg & 0x04) != 0;

        // Background pixel from shifters
        if (renderBg && (renderBgLeft || x >= 8)) {
            int bit_mux = 0x8000 >> xFine;
            int p0 = (bg_shifter_pattern_lo & bit_mux) != 0 ? 1 : 0;
            int p1 = (bg_shifter_pattern_hi & bit_mux) != 0 ? 1 : 0;
            bgPixel = (p1 << 1) | p0;

            int pal0 = (bg_shifter_attrib_lo & bit_mux) != 0 ? 1 : 0;
            int pal1 = (bg_shifter_attrib_hi & bit_mux) != 0 ? 1 : 0;
            bgPalette = (pal1 << 1) | pal0;
        }

        // Sprite pixel from pre-fetched data
        int fgPixel = 0;
        int fgPalette = 0;
        boolean fgPriority = false;
        boolean spriteZeroRendered = false;

        if (renderSpr && (renderSprLeft || x >= 8)) {
            for (int i = 0; i < spriteCount; i++) {
                int sprX = spriteXPos[i];

                if (x < sprX || x >= sprX + 8) continue;

                int col = x - sprX;
                int attr = spriteAttrs[i];

                boolean flipH = (attr & 0x40) != 0;
                if (flipH) {
                    col = 7 - col;
                }

                int bit = 7 - col;
                int p0 = (spritePatternLo[i] >> bit) & 1;
                int p1 = (spritePatternHi[i] >> bit) & 1;
                int sprPixel = (p1 << 1) | p0;

                if (sprPixel != 0) {
                    fgPixel = sprPixel;
                    fgPalette = (attr & 3) + 4;
                    fgPriority = (attr & 0x20) == 0;

                    if (spriteIsZero[i]) {
                        spriteZeroRendered = true;
                    }
                    break;
                }
            }
        }

        // Sprite zero hit detection
        if (! statusSpriteZeroHit && spriteZeroRendered && bgPixel != 0) {
            if (renderBg) {
                boolean leftClipped = ! renderBgLeft || ! renderSprLeft;
                if (x >= 8 || ! leftClipped) {
                    if (x < 255) {
                        statusSpriteZeroHit = true;
                    }
                }
            }
        }

        // Priority multiplexer
        int finalPixel;
        int finalPalette;

        if (bgPixel == 0 && fgPixel == 0) {
            finalPixel = 0;
            finalPalette = 0;
        } else if (bgPixel == 0) {
            finalPixel = fgPixel;
            finalPalette = fgPalette;
        } else if (fgPixel == 0) {
            finalPixel = bgPixel;
            finalPalette = bgPalette;
        } else if (fgPriority) {
            finalPixel = fgPixel;
            finalPalette = fgPalette;
        } else {
            finalPixel = bgPixel;
            finalPalette = bgPalette;
        }

        frameBuffer[scanline * WIDTH + x] = getColorFromPalette(finalPalette, finalPixel);
    }

    private int getColorFromPalette(int paletteNum, int pixel) {
        int addr = 0x3F00 + (paletteNum << 2) + pixel;
        if (pixel == 0) addr = 0x3F00;

        int palIndex = readPalette(addr) & 0x3F;

        if ((maskReg & 0x01) != 0) {
            palIndex &= 0x30;
        }

        return NES_PALETTE[palIndex];
    }

    // --- Register Interface & Memory Access ---
    public int readRegister(int address) {
        int reg = 0x2000 | (address & 7);
        switch (reg) {
            case 0x2002:
                int status = (statusVBlank ? 0x80 :  0) | (statusSpriteZeroHit ? 0x40 : 0) | (statusSpriteOverflow ? 0x20 : 0);
                status |= (openBus & 0x1F);
                statusVBlank = false;
                writeToggleW = false;
                openBus = status;
                return status;
            case 0x2004:
                int data = oam[oamAddr & 0xFF] & 0xFF;
                openBus = data;
                return data;
            case 0x2007:
                int addr = v & 0x3FFF;
                int value;
                if (addr >= 0x3F00) {
                    value = readPalette(addr) & 0xFF;
                    ppuDataReadBuffer = readVram(addr - 0x1000);
                } else {
                    value = ppuDataReadBuffer;
                    ppuDataReadBuffer = readVram(addr);
                }
                incrementVramAddr();
                openBus = value;
                return value;
            default:
                return openBus;
        }
    }

    public void writeRegister(int address, int value) {
        openBus = value;
        int reg = 0x2000 | (address & 7);
        value &= 0xFF;
        switch (reg) {
            case 0x2000:
                ctrlNmiEnable = (value & 0x80) != 0;
                ctrlSpriteSize8x16 = (value & 0x20) != 0;
                ctrlBgTableHigh = (value & 0x10) != 0;
                ctrlSprTableHigh = (value & 0x08) != 0;
                ctrlAddrInc32 = (value & 0x04) != 0;
                ctrlNametableSelect = (value & 0x03);
                t = (t & ~0x0C00) | ((value & 0x03) << 10);
                if (cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                    ((Mapper5) cartridge.getMapper()).setSpriteSize8x16(ctrlSpriteSize8x16);
                }
                break;
            case 0x2001:
                maskReg = value;
                break;
            case 0x2003:
                oamAddr = value;
                break;
            case 0x2004:
                oam[oamAddr++ & 0xFF] = (byte) value;
                break;
            case 0x2005:
                if (! writeToggleW) {
                    xFine = value & 0x07;
                    t = (t & ~0x001F) | (value >> 3);
                } else {
                    t = (t & ~0x73E0) | ((value & 0x07) << 12) | ((value & 0xF8) << 2);
                }
                writeToggleW = !writeToggleW;
                break;
            case 0x2006:
                if (!writeToggleW) {
                    t = (t & 0x00FF) | ((value & 0x3F) << 8);
                } else {
                    t = (t & 0xFF00) | value;
                    v = t;
                }
                writeToggleW = !writeToggleW;
                break;
            case 0x2007:
                int addr = v & 0x3FFF;
                if (addr >= 0x3F00) {
                    writePalette(addr, value);
                } else {
                    writeVram(addr, value);
                }
                incrementVramAddr();
                break;
        }
    }

    public void oamDmaWrite(int data) {
        oam[oamAddr++ & 0xFF] = (byte) data;
    }

    private void incrementVramAddr() {
        v = (v + (ctrlAddrInc32 ? 32 : 1)) & 0x7FFF;
    }

    private int readVram(int addr) {
        addr &= 0x3FFF;

        // Pattern tables ($0000-$1FFF) - ALWAYS go through mapper for CHR access
        if (addr < 0x2000) {
            if (cartridge != null) {
                return cartridge.getMapper().ppuRead(addr) & 0xFF;
            }
            return 0;
        }

        // Nametables ($2000-$3EFF)
        if (addr < 0x3F00) {
            // For MMC5, let the mapper handle nametables
            if (cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                return cartridge.getMapper().ppuRead(addr) & 0xFF;
            }
            // Standard mirroring for other mappers
            return vram[applyMirroring(addr & 0x0FFF)] & 0xFF;
        }

        return 0;
    }

    private void writeVram(int addr, int value) {
        addr &= 0x3FFF;

        // Pattern tables ($0000-$1FFF) - ALWAYS go through mapper for CHR-RAM writes
        if (addr < 0x2000) {
            if (cartridge != null) {
                cartridge.getMapper().ppuWrite(addr, value);
            }
            return;
        }

        // Nametables ($2000-$3EFF)
        if (addr < 0x3F00) {
            // For MMC5, let the mapper handle nametables
            if (cartridge != null && cartridge.getMapper() instanceof Mapper5) {
                cartridge.getMapper().ppuWrite(addr, value);
                return;
            }
            // Standard mirroring for other mappers
            vram[applyMirroring(addr & 0x0FFF)] = (byte) value;
        }
    }

    private int applyMirroring(int addr) {
        Cartridge. Mirroring mode = (cartridge != null) ? cartridge.getMirroring() : Cartridge.Mirroring.VERTICAL;
        return switch (mode) {
            case HORIZONTAL -> ((addr >> 1) & 0x400) | (addr & 0x3FF);
            case SINGLE_SCREEN_A -> addr & 0x3FF;           // All map to first 1KB
            case SINGLE_SCREEN_B -> 0x400 | (addr & 0x3FF); // All map to second 1KB
            case FOUR_SCREEN -> addr & 0xFFF;               // Full 4KB (if supported)
            default -> addr & 0x7FF;                        // VERTICAL:  standard 2KB mirroring
        };
    }

    private int readPalette(int addr) {
        addr &= 0x1F;
        if (addr == 0x10 || addr == 0x14 || addr == 0x18 || addr == 0x1C) {
            addr -= 0x10;
        }
        return palette[addr] & 0x3F;
    }

    private void writePalette(int addr, int value) {
        addr &= 0x1F;
        if (addr == 0x10 || addr == 0x14 || addr == 0x18 || addr == 0x1C) {
            addr -= 0x10;
        }
        palette[addr] = (byte) (value & 0x3F);
    }

    // --- Getters ---
    public int[] getFrameBuffer() { return frameBuffer; }
    public boolean isRenderEnabled() { return (maskReg & 0x18) != 0; }
    public int getT() { return t; }
    public int getV() { return v; }
    public int getFineX() { return xFine; }
    public byte[] getOam() { return oam; }
    public boolean isCtrlBgTableHigh() { return ctrlBgTableHigh; }
    public boolean isCtrlSprTableHigh() { return ctrlSprTableHigh; }
    public boolean isCtrlSpriteSize8x16() { return ctrlSpriteSize8x16; }
    public int getVramByteForAddr(int addr) { return readVram(addr); }
    public int getPaletteByteForAddr(int addr) { return readPalette(addr); }

    private static final int[] NES_PALETTE = new int[] {
            0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0700, 0x561D00,
            0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
            0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
            0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
            0xFFFFFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF26AFF, 0xFF6ECC, 0xFF8170, 0xEA9E22,
            0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
            0xFFFFFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFFC4EA, 0xFFCECA, 0xF8D5A9,
            0xE4E594, 0xCFEE96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000
    };
}