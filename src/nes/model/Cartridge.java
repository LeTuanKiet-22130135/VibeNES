package nes.model;

import nes.model.mapper.*;
import nes.util.IOUtil;

import java.io.IOException;
import java.util.Arrays;

/**
 * Represents an NES cartridge, containing PRG and CHR data, and an assigned memory mapper.
 */
public class Cartridge {
    public enum Mirroring { HORIZONTAL, VERTICAL, FOUR_SCREEN, SINGLE_SCREEN_A, SINGLE_SCREEN_B }

    private final byte[] prgRom;
    private final byte[] chr;
    private final boolean chrIsRam;
    private final int mapperId;
    private final Mirroring staticMirroring;
    private final String title;

    private final Mapper mapper;

    private Cartridge(byte[] prgRom, byte[] chr, boolean chrIsRam, int mapperId,
                      Mirroring staticMirroring, String title) {
        this.prgRom = prgRom;
        this.chr = chr;
        this.chrIsRam = chrIsRam;
        this.mapperId = mapperId;
        this.staticMirroring = staticMirroring;
        this.title = title;

        // Instantiate the correct mapper
        switch (this.mapperId) {
            case 0:
                this.mapper = new Mapper0();
                break;
            case 1:
                this.mapper = new Mapper1();
                break;
            case 2:
                this.mapper = new Mapper2();
                break;
            case 3:
                this.mapper = new Mapper3();
                break;
            case 4:
                this.mapper = new Mapper4();
                break;
            case 5:
                this.mapper = new Mapper5();
                break;
            case 7:
                this.mapper = new Mapper7();
                break;
            case 9:
                this.mapper = new Mapper9();
                break;
            case 10:
                this.mapper = new Mapper10();
                break;
            case 16:
                this.mapper = new Mapper16();
                break;
            case 19:
                this.mapper = new Mapper19();
                break;
            case 21, 22, 23, 25:
                this.mapper = new MapperVRC4(this.mapperId);
                break;
            case 24:
                this.mapper = new MapperVRC6(false);
                break;
            case 26:
                this.mapper = new MapperVRC6(true);
                break;
            case 34:
                this.mapper = new Mapper34();
                break;
            case 66:
                this.mapper = new Mapper66();
                break;
            case 69:
                this.mapper = new Mapper69();
                break;
            case 85:
                this.mapper = new Mapper85();
                break;
            case 118:
                this.mapper = new Mapper118();
                break;
            case 119:
                this.mapper = new Mapper119();
                break;
            case 206:
                this.mapper = new Mapper206();
                break;
            default:
                throw new UnsupportedOperationException("Unsupported mapper: " + this.mapperId);
        }
        this.mapper.setCartridge(this);
    }

    public String getName() { return title; }
    public int getMapperId() { return mapperId; }
    public byte[] getChr() { return chr; }
    public byte[] getPrgRom() { return prgRom; }
    public boolean isChrRam() { return chrIsRam; }
    public Mapper getMapper() { return mapper; }

    /**
     * Returns the current mirroring mode. For most mappers, this is static.
     * For mappers like MMC1/MMC3/MMC5/AxROM/VRC6, this will delegate to the mapper to get the dynamic mode.
     */
    public Mirroring getMirroring() {
        if (mapper instanceof Mapper1) {
            int mode = ((Mapper1) mapper).getMirroringMode();
            switch (mode) {
                case 0: return Mirroring.SINGLE_SCREEN_A;
                case 1: return Mirroring.SINGLE_SCREEN_B;
                case 2: return Mirroring.VERTICAL;
                case 3: return Mirroring.HORIZONTAL;
            }
        }
        if (mapper instanceof Mapper4) {
            int mode = ((Mapper4) mapper).getMirroringMode();
            return mode == 0 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
        }
        if (mapper instanceof Mapper5) {
            int mode = ((Mapper5) mapper).getMirroringMode();
            // Simplified mapping for now
            return mode == 2 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
        }
        if (mapper instanceof Mapper7) {
            int mode = ((Mapper7) mapper).getMirroringMode();
            return mode == 0 ? Mirroring.SINGLE_SCREEN_A : Mirroring.SINGLE_SCREEN_B;
        }
        if (mapper instanceof Mapper9) {
            int mode = ((Mapper9) mapper).getMirroringMode();
            return mode == 0 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
        }
        if (mapper instanceof Mapper69) {
            int mode = ((Mapper69) mapper).getMirroringMode();
            switch (mode) {
                case 0: return Mirroring.VERTICAL;
                case 1: return Mirroring.HORIZONTAL;
                case 2: return Mirroring.SINGLE_SCREEN_A;
                case 3: return Mirroring.SINGLE_SCREEN_B;
            }
        }
        if (mapper instanceof Mapper118) {
            int mode = ((Mapper118) mapper).getMirroringMode();
            switch (mode) {
                case 0: return Mirroring.VERTICAL;
                case 1: return Mirroring.HORIZONTAL;
                case 2: return Mirroring.SINGLE_SCREEN_A;
                case 3: return Mirroring.SINGLE_SCREEN_B;
            }
        }
        if (mapper instanceof MapperVRC6) {
            int mode = ((MapperVRC6) mapper).getMirroringMode();
            switch (mode) {
                case 0: return Mirroring.VERTICAL;
                case 1: return Mirroring.HORIZONTAL;
                case 2: return Mirroring.SINGLE_SCREEN_A;
                case 3: return Mirroring.SINGLE_SCREEN_B;
            }
        }
        if (mapper instanceof Mapper119) {
            int mode = ((Mapper119) mapper).getMirroringMode();
            return mode == 0 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
        }
        if (mapper instanceof MapperVRC4) {
            int mode = ((MapperVRC4) mapper).getMirroringMode();
            switch (mode) {
                case 0: return Mirroring.VERTICAL;
                case 1: return Mirroring.HORIZONTAL;
                case 2: return Mirroring.SINGLE_SCREEN_A;
                case 3: return Mirroring.SINGLE_SCREEN_B;
            }
        }
        if (mapper instanceof Mapper10) {
            int mode = ((Mapper10) mapper).getMirroringMode();
            return mode == 0 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
        }
        if (mapper instanceof Mapper16) {
            int mode = ((Mapper16) mapper).getMirroringMode();
            switch (mode) {
                case 0: return Mirroring.VERTICAL;
                case 1: return Mirroring.HORIZONTAL;
                case 2: return Mirroring.SINGLE_SCREEN_A;
                case 3: return Mirroring.SINGLE_SCREEN_B;
            }
        }
        return staticMirroring;
    }

    public void reset() {
        if (mapper != null) {
            mapper.reset();
        }
    }

    // ===== Loading iNES (.nes) =====
    public static Cartridge loadFromFile(String path) throws IOException {
        byte[] bytes = IOUtil.readAllBytes(path);
        return loadFromBytes(bytes, path);
    }

    public static Cartridge loadFromBytes(byte[] data, String title) {
        if (data == null || data.length < 16)
            throw new IllegalArgumentException("Data too short for iNES header");
        if (!(data[0] == 'N' && data[1] == 'E' && data[2] == 'S' && (data[3] & 0xFF) == 0x1A)) {
            throw new IllegalArgumentException("Not an iNES file (invalid magic)");
        }
        int prgBanks = data[4] & 0xFF;
        int chrBanks = data[5] & 0xFF;
        int flags6 = data[6] & 0xFF;
        int flags7 = data[7] & 0xFF;
        boolean hasTrainer = (flags6 & 0x04) != 0;
        boolean fourScreen = (flags6 & 0x08) != 0;
        boolean vertical = (flags6 & 0x01) != 0;
        int mapperId = ((flags7 & 0xF0) | ((flags6 >>> 4) & 0x0F)) & 0xFF;

        int offset = 16;
        if (hasTrainer) offset += 512;

        int prgSize = prgBanks * 16 * 1024;
        if (data.length < offset + prgSize) throw new IllegalArgumentException("File truncated (PRG)");
        byte[] prgRom = Arrays.copyOfRange(data, offset, offset + prgSize);
        offset += prgSize;

        boolean chrIsRam = (chrBanks == 0);
        int chrSize = chrIsRam ? 8 * 1024 : chrBanks * 8 * 1024;
        byte[] chr;
        if (chrIsRam) {
            chr = new byte[chrSize]; // Allocate CHR RAM
        } else {
            if (data.length < offset + chrSize) throw new IllegalArgumentException("File truncated (CHR)");
            chr = Arrays.copyOfRange(data, offset, offset + chrSize);
        }

        Mirroring mirroring = fourScreen ? Mirroring.FOUR_SCREEN : (vertical ? Mirroring.VERTICAL : Mirroring.HORIZONTAL);

        return new Cartridge(prgRom, chr, chrIsRam, mapperId, mirroring, title != null ? title : "iNES ROM");
    }
}
