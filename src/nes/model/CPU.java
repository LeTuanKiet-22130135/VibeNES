package nes.model;

/**
 * Ricoh 2A03/2A07 CPU (6502 derivative)
 * This version includes more accurate cycle counting for addressing modes.
 */
public class CPU {
    // Registers
    private int a, x, y, pc, sp, p;

    // Status flag masks
    private static final int N = 0b1000_0000, V = 0b0100_0000, U = 0b0010_0000;
    private static final int B = 0b0001_0000, D = 0b0000_1000, I = 0b0000_0100;
    private static final int Z = 0b0000_0010, C = 0b0000_0001;

    private Bus bus;
    private long cycles;
    private boolean nmiDetected = false, irqDetected = false;
    private int stallCycles = 0;

    // Temp variables for instruction execution
    private int fetchedAddr;
    private int fetchedValue;
    private int cyclesThisInstruction;
    private boolean isAccumulatorMode;
    private int debugFrameCounter = 0;

    public CPU() {
        // Do not call reset() here because bus is not attached yet.
        // Initialize registers to safe defaults.
        a = x = y = 0;
        sp = 0xFD;
        p = U | I;
        pc = 0x0000;
        cycles = 0;
    }
    
    void attachBus(Bus bus) { this.bus = bus; }
    public long getCycles() { return cycles; }

    public void reset() {
        a = x = y = 0;
        sp = 0xFD;
        p = U | I;
        // Read reset vector
        pc = read16(0xFFFC);
        cycles = 0;
        nmiDetected = false;
        irqDetected = false;
        stallCycles = 0;
    }

    public void nmi() { nmiDetected = true; }
    public void irq() { irqDetected = true; }
    public void stall(int cycles) { this.stallCycles += cycles; }

    public int stepInstruction() {
        cyclesThisInstruction = 0;
        isAccumulatorMode = false; // Reset mode flag for each instruction

        if (nmiDetected) {
            nmiDetected = false;
            push16(pc); push((p & ~B) | U); setFlag(I, true);
            pc = read16(0xFFFA);
            cyclesThisInstruction += 2; // 2 internal cycles for interrupt
        } else if (irqDetected && !flag(I)) {
            irqDetected = false;
            push16(pc); push((p & ~B) | U); setFlag(I, true);
            pc = read16(0xFFFE);
            cyclesThisInstruction += 2; // 2 internal cycles for interrupt
        } else {
            int opcode = read8(pc++);
            executeOpcode(opcode);
        }
        
        cycles += cyclesThisInstruction + stallCycles;
        int totalCycles = cyclesThisInstruction + stallCycles;
        stallCycles = 0;
        return totalCycles;
    }

    private void executeOpcode(int opcode) {
        switch (opcode) {
            // ADC
            case 0x69: immediate(); ADC(); break; case 0x65: zp(); ADC(); break;
            case 0x75: zpX(); ADC(); break; case 0x6D: abs(); ADC(); break;
            case 0x7D: absX(true); ADC(); break; case 0x79: absY(); ADC(); break;
            case 0x61: indX(); ADC(); break; case 0x71: indY(); ADC(); break;
            // AND
            case 0x29: immediate(); AND(); break; case 0x25: zp(); AND(); break;
            case 0x35: zpX(); AND(); break; case 0x2D: abs(); AND(); break;
            case 0x3D: absX(true); AND(); break; case 0x39: absY(); AND(); break;
            case 0x21: indX(); AND(); break; case 0x31: indY(); AND(); break;
            // ASL
            case 0x0A: accumulator(); ASL(); break;
            case 0x06: zp(); ASL(); break;
            case 0x16: zpX(); ASL(); break; case 0x0E: abs(); ASL(); break;
            case 0x1E: absX(false); ASL(); break;
            // Branches
            case 0x90: branch(!flag(C)); break; case 0xB0: branch(flag(C)); break;
            case 0xF0: branch(flag(Z)); break; case 0x30: branch(flag(N)); break;
            case 0xD0: branch(!flag(Z)); break; case 0x10: branch(!flag(N)); break;
            case 0x50: branch(!flag(V)); break; case 0x70: branch(flag(V)); break;
            // BIT
            case 0x24: zp(); BIT(); break; case 0x2C: abs(); BIT(); break;
            // BRK
            case 0x00: BRK(); break;
            // Flags
            case 0x18: CLC(); break; case 0xD8: CLD(); break; 
            case 0x58: CLI(); break; case 0xB8: CLV(); break; 
            case 0x38: SEC(); break; case 0xF8: SED(); break;
            case 0x78: SEI(); break;
            // CMP
            case 0xC9: immediate(); CMP(); break; case 0xC5: zp(); CMP(); break;
            case 0xD5: zpX(); CMP(); break; case 0xCD: abs(); CMP(); break;
            case 0xDD: absX(true); CMP(); break; case 0xD9: absY(); CMP(); break;
            case 0xC1: indX(); CMP(); break; case 0xD1: indY(); CMP(); break;
            // CPX
            case 0xE0: immediate(); CPX(); break; case 0xE4: zp(); CPX(); break;
            case 0xEC: abs(); CPX(); break;
            // CPY
            case 0xC0: immediate(); CPY(); break; case 0xC4: zp(); CPY(); break;
            case 0xCC: abs(); CPY(); break;
            // DEC
            case 0xC6: zp(); DEC(); break; case 0xD6: zpX(); DEC(); break;
            case 0xCE: abs(); DEC(); break; case 0xDE: absX(false); DEC(); break;
            // EOR
            case 0x49: immediate(); EOR(); break; case 0x45: zp(); EOR(); break;
            case 0x55: zpX(); EOR(); break; case 0x4D: abs(); EOR(); break;
            case 0x5D: absX(true); EOR(); break; case 0x59: absY(); EOR(); break;
            case 0x41: indX(); EOR(); break; case 0x51: indY(); EOR(); break;
            // INC
            case 0xE6: zp(); INC(); break; case 0xF6: zpX(); INC(); break;
            case 0xEE: abs(); INC(); break; case 0xFE: absX(false); INC(); break;
            // Jumps
            case 0x4C: JMP_abs(); break; case 0x6C: JMP_ind(); break;
            case 0x20: JSR(); break; case 0x60: RTS(); break; case 0x40: RTI(); break;
            // LDA
            case 0xA9: immediate(); LDA(); break; case 0xA5: zp(); LDA(); break;
            case 0xB5: zpX(); LDA(); break; case 0xAD: abs(); LDA(); break;
            case 0xBD: absX(true); LDA(); break; case 0xB9: absY(); LDA(); break;
            case 0xA1: indX(); LDA(); break; case 0xB1: indY(); LDA(); break;
            // LDX
            case 0xA2: immediate(); LDX(); break; case 0xA6: zp(); LDX(); break;
            case 0xB6: zpY(); LDX(); break; case 0xAE: abs(); LDX(); break;
            case 0xBE: absY(); LDX(); break;
            // LDY
            case 0xA0: immediate(); LDY(); break; case 0xA4: zp(); LDY(); break;
            case 0xB4: zpX(); LDY(); break; case 0xAC: abs(); LDY(); break;
            case 0xBC: absX(true); LDY(); break;
            // LSR
            case 0x4A: accumulator(); LSR(); break;
            case 0x46: zp(); LSR(); break;
            case 0x56: zpX(); LSR(); break; case 0x4E: abs(); LSR(); break;
            case 0x5E: absX(false); LSR(); break;
            // NOP
            case 0xEA: NOP(); break;
            // ORA
            case 0x09: immediate(); ORA(); break; case 0x05: zp(); ORA(); break;
            case 0x15: zpX(); ORA(); break; case 0x0D: abs(); ORA(); break;
            case 0x1D: absX(true); ORA(); break; case 0x19: absY(); ORA(); break;
            case 0x01: indX(); ORA(); break; case 0x11: indY(); ORA(); break;
            // Stack
            case 0x48: PHA(); break; case 0x68: PLA(); break;
            case 0x08: PHP(); break; case 0x28: PLP(); break;
            // ROL
            case 0x2A: accumulator(); ROL(); break;
            case 0x26: zp(); ROL(); break;
            case 0x36: zpX(); ROL(); break; case 0x2E: abs(); ROL(); break;
            case 0x3E: absX(false); ROL(); break;
            // ROR
            case 0x6A: accumulator(); ROR(); break;
            case 0x66: zp(); ROR(); break;
            case 0x76: zpX(); ROR(); break; case 0x6E: abs(); ROR(); break;
            case 0x7E: absX(false); ROR(); break;
            // SBC
            case 0xE9: case 0xEB: immediate(); SBC(); break; case 0xE5: zp(); SBC(); break;
            case 0xF5: zpX(); SBC(); break; case 0xED: abs(); SBC(); break;
            case 0xFD: absX(true); SBC(); break; case 0xF9: absY(); SBC(); break;
            case 0xE1: indX(); SBC(); break; case 0xF1: indY(); SBC(); break;
            // STA
            case 0x85: zpAddr(); STA(); break; case 0x95: zpXAddr(); STA(); break;
            case 0x8D: absAddr(); STA(); break; case 0x9D: absXAddr(); STA(); break;
            case 0x99: absYAddr(); STA(); break; case 0x81: indXAddr(); STA(); break;
            case 0x91: indYAddr(); STA(); break;
            // STX
            case 0x86: zpAddr(); STX(); break; case 0x96: zpYAddr(); STX(); break;
            case 0x8E: absAddr(); STX(); break;
            // STY
            case 0x84: zpAddr(); STY(); break; case 0x94: zpXAddr(); STY(); break;
            case 0x8C: absAddr(); STY(); break;
            // Transfers
            case 0xAA: TAX(); break; case 0xA8: TAY(); break; 
            case 0x8A: TXA(); break; case 0x98: TYA(); break; 
            case 0xBA: TSX(); break; case 0x9A: TXS(); break;
            // Increments/Decrements
            case 0xE8: INX(); break; case 0xC8: INY(); break;
            case 0xCA: DEX(); break; case 0x88: DEY(); break;

            // === UNOFFICIAL OPCODES USED BY BATTLETOADS ===

            // LAX - Load A and X with memory (like LDA + LDX)
            case 0xA7: zp(); LAX(); break;
            case 0xB7: zpY(); LAX(); break;
            case 0xAF: abs(); LAX(); break;
            case 0xBF: absY(); LAX(); break;
            case 0xA3: indX(); LAX(); break;
            case 0xB3: indY(); LAX(); break;

            // SAX - Store A AND X (A & X -> memory)
            case 0x87: zpAddr(); SAX(); break;
            case 0x97: zpYAddr(); SAX(); break;
            case 0x8F: absAddr(); SAX(); break;
            case 0x83: indXAddr(); SAX(); break;

            // DCP - Decrement memory then compare with A (DEC + CMP)
            case 0xC7: zp(); DCP(); break;
            case 0xD7: zpX(); DCP(); break;
            case 0xCF: abs(); DCP(); break;
            case 0xDF: absX(false); DCP(); break;
            case 0xDB: absYRMW(); DCP(); break;  // was absY()
            case 0xC3: indX(); DCP(); break;
            case 0xD3: indYRMW(); DCP(); break;  // was indY()

            // ISB/ISC - Increment memory then subtract from A (INC + SBC)
            case 0xE7: zp(); ISB(); break;
            case 0xF7: zpX(); ISB(); break;
            case 0xEF: abs(); ISB(); break;
            case 0xFF: absX(false); ISB(); break;
            case 0xFB: absYRMW(); ISB(); break;  // was absY()
            case 0xE3: indX(); ISB(); break;
            case 0xF3: indYRMW(); ISB(); break;  // was indY()

            // SLO - Shift left then OR with A (ASL + ORA)
            case 0x07: zp(); SLO(); break;
            case 0x17: zpX(); SLO(); break;
            case 0x0F: abs(); SLO(); break;
            case 0x1F: absX(false); SLO(); break;
            case 0x1B: absYRMW(); SLO(); break;  // was absY()
            case 0x03: indX(); SLO(); break;
            case 0x13: indYRMW(); SLO(); break;  // was indY()

            // RLA - Rotate left then AND with A (ROL + AND)
            case 0x27: zp(); RLA(); break;
            case 0x37: zpX(); RLA(); break;
            case 0x2F: abs(); RLA(); break;
            case 0x3F: absX(false); RLA(); break;
            case 0x3B: absYRMW(); RLA(); break;  // was absY()
            case 0x23: indX(); RLA(); break;
            case 0x33: indYRMW(); RLA(); break;  // was indY()

            // SRE - Shift right then XOR with A (LSR + EOR)
            case 0x47: zp(); SRE(); break;
            case 0x57: zpX(); SRE(); break;
            case 0x4F: abs(); SRE(); break;
            case 0x5F: absX(false); SRE(); break;
            case 0x5B: absYRMW(); SRE(); break;  // was absY()
            case 0x43: indX(); SRE(); break;
            case 0x53: indYRMW(); SRE(); break;  // was indY()

            // RRA - Rotate right then add to A (ROR + ADC)
            case 0x67: zp(); RRA(); break;
            case 0x77: zpX(); RRA(); break;
            case 0x6F: abs(); RRA(); break;
            case 0x7F: absX(false); RRA(); break;
            case 0x7B: absYRMW(); RRA(); break;  // was absY()
            case 0x63: indX(); RRA(); break;
            case 0x73: indYRMW(); RRA(); break;  // was indY()

            // NOP variants (single and multi-byte)
            case 0x1A: case 0x3A: case 0x5A: case 0x7A:  case 0xDA: case 0xFA:
                NOP(); break; // Implied NOP
            case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2:
                immediate(); NOP(); break; // 2-byte NOP (immediate)
            case 0x04: case 0x44: case 0x64:
                zp(); NOP(); break; // 2-byte NOP (zp)
            case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4:
                zpX(); NOP(); break; // 2-byte NOP (zp,X)
            case 0x0C:
                abs(); NOP(); break; // 3-byte NOP (abs)
            case 0x1C:  case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:
                absX(true); NOP(); break; // 3-byte NOP (abs,X)

            default:
                // Unknown opcode - treat as NOP but log for debugging
                cyclesThisInstruction++;
                break;
        }
    }

    // --- Memory Helpers ---
    private int read8(int addr) {
        cyclesThisInstruction++;
        bus.onCpuCycle();
        return bus.read(addr & 0xFFFF);
    }
    private void write8(int addr, int value) {
        cyclesThisInstruction++;
        bus.onCpuCycle();
        bus.write(addr & 0xFFFF, value, cycles + cyclesThisInstruction);
    }
    private int read16(int addr) { int lo = read8(addr); int hi = read8(addr + 1); return (hi << 8) | lo; }
    private int read16Bug(int addr) { int lo = read8(addr); int hi = read8((addr & 0xFF00) | ((addr + 1) & 0xFF)); return (hi << 8) | lo; }
    private void push(int val) { write8(0x100 | sp, val); sp = (sp - 1) & 0xFF; }
    private int pop() { sp = (sp + 1) & 0xFF; return read8(0x100 | sp); }
    private void push16(int val) { push(val >> 8); push(val & 0xFF); }
    private int pop16() { int lo = pop(); int hi = pop(); return (hi << 8) | lo; }

    // --- Flag helpers ---
    private boolean flag(int mask) { return (p & mask) != 0; }
    private void setFlag(int mask, boolean v) { p = v ? (p | mask) : (p & ~mask); }
    private void setZN(int value) {
        setFlag(Z, (value & 0xFF) == 0);
        setFlag(N, (value & 0x80) != 0);
    }

    // --- Addressing Modes ---
    private void accumulator() { fetchedValue = a; isAccumulatorMode = true; }
    private void immediate() { fetchedValue = read8(pc++); }
    private void zp() { fetchedAddr = read8(pc++); fetchedValue = read8(fetchedAddr); }
    private void zpX() { fetchedAddr = (read8(pc++) + x) & 0xFF; read8(fetchedAddr); fetchedValue = read8(fetchedAddr); }
    private void zpY() { fetchedAddr = (read8(pc++) + y) & 0xFF; read8(fetchedAddr); fetchedValue = read8(fetchedAddr); }
    private void abs() { int lo = read8(pc++); int hi = read8(pc++); fetchedAddr = (hi << 8) | lo; fetchedValue = read8(fetchedAddr); }
    private void absX(boolean extraCycle) { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + x) & 0xFFFF; fetchedValue = read8(fetchedAddr); if (extraCycle && (base & 0xFF00) != (fetchedAddr & 0xFF00)) cyclesThisInstruction++; }
    private void absY() { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF; fetchedValue = read8(fetchedAddr); if ((base & 0xFF00) != (fetchedAddr & 0xFF00)) cyclesThisInstruction++; }
    private void indX() { int zp = read8(pc++); read8(zp); int addr = (zp + x) & 0xFF; int lo = read8(addr); int hi = read8((addr + 1) & 0xFF); fetchedAddr = (hi << 8) | lo; fetchedValue = read8(fetchedAddr); }
    private void indY() { int zp = read8(pc++); int lo = read8(zp); int hi = read8((zp + 1) & 0xFF); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF; fetchedValue = read8(fetchedAddr); if ((base & 0xFF00) != (fetchedAddr & 0xFF00)) cyclesThisInstruction++; }
    
    private void zpAddr() { fetchedAddr = read8(pc++); }
    private void zpXAddr() { fetchedAddr = (read8(pc++) + x) & 0xFF; read8(fetchedAddr); }
    private void zpYAddr() { fetchedAddr = (read8(pc++) + y) & 0xFF; read8(fetchedAddr); }
    private void absAddr() { int lo = read8(pc++); int hi = read8(pc++); fetchedAddr = (hi << 8) | lo; }
    private void absXAddr() { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + x) & 0xFFFF;
        read8(fetchedAddr);
    }
    private void absYAddr() { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF;
        read8(fetchedAddr);
    }
    private void indXAddr() { int zp = read8(pc++); read8(zp); int addr = (zp + x) & 0xFF; int lo = read8(addr); int hi = read8((addr + 1) & 0xFF); fetchedAddr = (hi << 8) | lo; }
    private void indYAddr() { int zp = read8(pc++); int lo = read8(zp); int hi = read8((zp + 1) & 0xFF); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF;
        read8(fetchedAddr);
    }

    // --- Instructions ---
    private void ADC() { int sum = a + fetchedValue + (flag(C) ? 1 : 0); setFlag(C, sum > 0xFF); setFlag(V, (~(a ^ fetchedValue) & (a ^ sum) & 0x80) != 0); a = sum & 0xFF; setZN(a); }
    private void AND() { a &= fetchedValue; setZN(a); }
    private void ASL() { cyclesThisInstruction++; if (isAccumulatorMode) { setFlag(C, (a & 0x80) != 0); a = (a << 1) & 0xFF; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 0x80) != 0); val = (val << 1) & 0xFF; write8(fetchedAddr, val); setZN(val); } }
    private void branch(boolean cond) { if (cond) { cyclesThisInstruction++; int offset = (byte)read8(pc++); int oldPc = pc; pc = (pc + offset) & 0xFFFF; if ((oldPc & 0xFF00) != (pc & 0xFF00)) cyclesThisInstruction++; } else { read8(pc++); } }
    private void BIT() { setFlag(Z, (a & fetchedValue) == 0); setFlag(V, (fetchedValue & V) != 0); setFlag(N, (fetchedValue & N) != 0); }
    private void BRK() { pc++; push16(pc); push(p | B | U); setFlag(I, true); pc = read16(0xFFFE); }
    private void CLC() { cyclesThisInstruction++; setFlag(C, false); } private void CLD() { cyclesThisInstruction++; setFlag(D, false); }
    private void CLI() { cyclesThisInstruction++; setFlag(I, false); } private void CLV() { cyclesThisInstruction++; setFlag(V, false); }
    private void CMP() { int cmp = (a - fetchedValue) & 0x1FF; setFlag(C, cmp < 0x100); setZN(cmp & 0xFF); }
    private void CPX() { int cmp = (x - fetchedValue) & 0x1FF; setFlag(C, cmp < 0x100); setZN(cmp & 0xFF); }
    private void CPY() { int cmp = (y - fetchedValue) & 0x1FF; setFlag(C, cmp < 0x100); setZN(cmp & 0xFF); }
    private void DEC() { int val = (fetchedValue - 1) & 0xFF; write8(fetchedAddr, val); setZN(val); }
    private void EOR() { a ^= fetchedValue; setZN(a); }
    private void INC() { int val = (fetchedValue + 1) & 0xFF; write8(fetchedAddr, val); setZN(val); }
    private void JMP_abs() { int lo = read8(pc++); int hi = read8(pc++); pc = (hi << 8) | lo; }
    private void JMP_ind() { int lo = read8(pc++); int hi = read8(pc++); int addr = (hi << 8) | lo; pc = read16Bug(addr); }
    private void JSR() { int lo = read8(pc++); int hi = read8(pc++); push16(pc - 1); pc = (hi << 8) | lo; cyclesThisInstruction++; }
    private void LDA() { a = fetchedValue; setZN(a); }
    private void LDX() { x = fetchedValue; setZN(x); }
    private void LDY() { y = fetchedValue; setZN(y); }
    private void LSR() { cyclesThisInstruction++; if (isAccumulatorMode) { setFlag(C, (a & 1) != 0); a >>= 1; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 1) != 0); val >>= 1; write8(fetchedAddr, val); setZN(val); } }
    private void NOP() { cyclesThisInstruction++; }
    private void ORA() { a |= fetchedValue; setZN(a); }
    private void PHA() { push(a); cyclesThisInstruction++; }
    private void PLA() { cyclesThisInstruction += 2; a = pop(); setZN(a); }
    private void PHP() { push(p | B | U); cyclesThisInstruction++; }
    private void PLP() { cyclesThisInstruction += 2; p = (pop() & ~B) | U; }
    private void ROL() { cyclesThisInstruction++; int c = flag(C) ? 1 : 0; if (isAccumulatorMode) { setFlag(C, (a & 0x80) != 0); a = ((a << 1) | c) & 0xFF; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 0x80) != 0); val = ((val << 1) | c) & 0xFF; write8(fetchedAddr, val); setZN(val); } }
    private void ROR() { cyclesThisInstruction++; int c = flag(C) ? 0x80 : 0; if (isAccumulatorMode) { setFlag(C, (a & 1) != 0); a = (a >> 1) | c; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 1) != 0); val = (val >> 1) | c; write8(fetchedAddr, val); setZN(val); } }
    private void RTI() { p = (pop() & ~B) | U; pc = pop16(); cyclesThisInstruction += 2; }
    private void RTS() { pc = (pop16() + 1) & 0xFFFF; cyclesThisInstruction += 3; }
    private void SBC() { int val = fetchedValue ^ 0xFF; int sum = a + val + (flag(C) ? 1 : 0); setFlag(C, sum > 0xFF); setFlag(V, (~(a ^ val) & (a ^ sum) & 0x80) != 0); a = sum & 0xFF; setZN(a); }
    private void SEC() { cyclesThisInstruction++; setFlag(C, true); } private void SED() { cyclesThisInstruction++; setFlag(D, true); }
    private void SEI() { cyclesThisInstruction++; setFlag(I, true); }
    private void STA() { write8(fetchedAddr, a); }
    private void STX() { write8(fetchedAddr, x); }
    private void STY() { write8(fetchedAddr, y); }
    private void TAX() { x = a; setZN(x); cyclesThisInstruction++; }
    private void TAY() { y = a; setZN(y); cyclesThisInstruction++; }
    private void TXA() { a = x; setZN(a); cyclesThisInstruction++; }
    private void TYA() { a = y; setZN(a); cyclesThisInstruction++; }
    private void TSX() { x = sp; setZN(x); cyclesThisInstruction++; }
    private void TXS() { sp = x; cyclesThisInstruction++; }
    private void INX() { x = (x + 1) & 0xFF; setZN(x); cyclesThisInstruction++; }
    private void INY() { y = (y + 1) & 0xFF; setZN(y); cyclesThisInstruction++; }
    private void DEX() { x = (x - 1) & 0xFF; setZN(x); cyclesThisInstruction++; }
    private void DEY() { y = (y - 1) & 0xFF; setZN(y); cyclesThisInstruction++; }

    private void LAX() {
        a = fetchedValue;
        x = fetchedValue;
        setZN(a);
    }

    private void SAX() {
        write8(fetchedAddr, a & x);
    }

    private void DCP() {
        // DEC then CMP
        int val = (fetchedValue - 1) & 0xFF;
        write8(fetchedAddr, val);
        int cmp = (a - val) & 0x1FF;
        setFlag(C, cmp < 0x100);
        setZN(cmp & 0xFF);
    }

    private void ISB() {
        // INC then SBC
        int val = (fetchedValue + 1) & 0xFF;
        write8(fetchedAddr, val);
        // Now do SBC with the incremented value
        val ^= 0xFF;
        int sum = a + val + (flag(C) ? 1 : 0);
        setFlag(C, sum > 0xFF);
        setFlag(V, (~(a ^ val) & (a ^ sum) & 0x80) != 0);
        a = sum & 0xFF;
        setZN(a);
    }

    private void SLO() {
        // ASL then ORA
        setFlag(C, (fetchedValue & 0x80) != 0);
        int val = (fetchedValue << 1) & 0xFF;
        write8(fetchedAddr, val);
        a |= val;
        setZN(a);
    }

    private void RLA() {
        // ROL then AND
        int c = flag(C) ? 1 : 0;
        setFlag(C, (fetchedValue & 0x80) != 0);
        int val = ((fetchedValue << 1) | c) & 0xFF;
        write8(fetchedAddr, val);
        a &= val;
        setZN(a);
    }

    private void SRE() {
        // LSR then EOR
        setFlag(C, (fetchedValue & 1) != 0);
        int val = fetchedValue >> 1;
        write8(fetchedAddr, val);
        a ^= val;
        setZN(a);
    }

    private void RRA() {
        // ROR then ADC
        int c = flag(C) ? 0x80 : 0;
        setFlag(C, (fetchedValue & 1) != 0);
        int val = (fetchedValue >> 1) | c;
        write8(fetchedAddr, val);
        // Now do ADC with the rotated value
        int sum = a + val + (flag(C) ? 1 : 0);
        setFlag(C, sum > 0xFF);
        setFlag(V, (~(a ^ val) & (a ^ sum) & 0x80) != 0);
        a = sum & 0xFF;
        setZN(a);
    }

    // Add these new addressing mode methods for read-modify-write unofficial opcodes
    private void absYRMW() {
        int lo = read8(pc++);
        int hi = read8(pc++);
        int base = (hi << 8) | lo;
        fetchedAddr = (base + y) & 0xFFFF;
        read8(fetchedAddr); // Always do dummy read for RMW
        fetchedValue = read8(fetchedAddr);
    }

    private void indYRMW() {
        int zp = read8(pc++);
        int lo = read8(zp);
        int hi = read8((zp + 1) & 0xFF);
        int base = (hi << 8) | lo;
        fetchedAddr = (base + y) & 0xFFFF;
        read8(fetchedAddr); // Always do dummy read for RMW
        fetchedValue = read8(fetchedAddr);
    }

    // Helper to advance one CPU cycle and clock the rest of the system
    private void tick() {
        cyclesThisInstruction++;
        if (bus != null) bus.onCpuCycle();
    }
}
