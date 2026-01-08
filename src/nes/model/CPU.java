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
    public void clearIrq() { irqDetected = false; }
    public void stall(int cycles) { this.stallCycles += cycles; }

    private void tick() {
        cyclesThisInstruction++;
        if (bus != null) bus.onCpuCycle();
    }

    public int stepInstruction() {
        cyclesThisInstruction = 0;
        isAccumulatorMode = false; // Reset mode flag for each instruction

        if (nmiDetected) {
            nmiDetected = false;
            push16(pc); 
            push((p & ~B) | U); 
            setFlag(I, true);
            pc = read16(0xFFFA);
            tick(); tick(); // 2 internal cycles for interrupt
        } else if (irqDetected && !flag(I)) {
            irqDetected = false;
            push16(pc); 
            push((p & ~B) | U); 
            setFlag(I, true);
            pc = read16(0xFFFE);
            tick(); tick(); // 2 internal cycles for interrupt
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
            case 0x7D: absX(true); ADC(); break; case 0x79: absY(true); ADC(); break;
            case 0x61: indX(); ADC(); break; case 0x71: indY(true); ADC(); break;
            // AND
            case 0x29: immediate(); AND(); break; case 0x25: zp(); AND(); break;
            case 0x35: zpX(); AND(); break; case 0x2D: abs(); AND(); break;
            case 0x3D: absX(true); AND(); break; case 0x39: absY(true); AND(); break;
            case 0x21: indX(); AND(); break; case 0x31: indY(true); AND(); break;
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
            case 0xDD: absX(true); CMP(); break; case 0xD9: absY(true); CMP(); break;
            case 0xC1: indX(); CMP(); break; case 0xD1: indY(true); CMP(); break;
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
            case 0x5D: absX(true); EOR(); break; case 0x59: absY(true); EOR(); break;
            case 0x41: indX(); EOR(); break; case 0x51: indY(true); EOR(); break;
            // INC
            case 0xE6: zp(); INC(); break; case 0xF6: zpX(); INC(); break;
            case 0xEE: abs(); INC(); break; case 0xFE: absX(false); INC(); break;
            // Jumps
            case 0x4C: JMP_abs(); break; case 0x6C: JMP_ind(); break;
            case 0x20: JSR(); break; case 0x60: RTS(); break; case 0x40: RTI(); break;
            // LDA
            case 0xA9: immediate(); LDA(); break; case 0xA5: zp(); LDA(); break;
            case 0xB5: zpX(); LDA(); break; case 0xAD: abs(); LDA(); break;
            case 0xBD: absX(true); LDA(); break; case 0xB9: absY(true); LDA(); break;
            case 0xA1: indX(); LDA(); break; case 0xB1: indY(true); LDA(); break;
            // LDX
            case 0xA2: immediate(); LDX(); break; case 0xA6: zp(); LDX(); break;
            case 0xB6: zpY(); LDX(); break; case 0xAE: abs(); LDX(); break;
            case 0xBE: absY(true); LDX(); break;
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
            case 0x1D: absX(true); ORA(); break; case 0x19: absY(true); ORA(); break;
            case 0x01: indX(); ORA(); break; case 0x11: indY(true); ORA(); break;
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
            case 0xFD: absX(true); SBC(); break; case 0xF9: absY(true); SBC(); break;
            case 0xE1: indX(); SBC(); break; case 0xF1: indY(true); SBC(); break;
            // STA
            case 0x85: zpAddr(); STA(); break; case 0x95: zpXAddr(); STA(); break;
            case 0x8D: absAddr(); STA(); break; case 0x9D: absXAddr(true); STA(); break;
            case 0x99: absYAddr(true); STA(); break; case 0x81: indXAddr(); STA(); break;
            case 0x91: indYAddr(true); STA(); break;
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
            default: break; // Unofficial opcodes are ignored for now
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
    private void absX(boolean extraCycle) { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + x) & 0xFFFF; fetchedValue = read8(fetchedAddr); if (extraCycle && (base & 0xFF00) != (fetchedAddr & 0xFF00)) tick(); }
    private void absY(boolean extraCycle) { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF; fetchedValue = read8(fetchedAddr); if (extraCycle && (base & 0xFF00) != (fetchedAddr & 0xFF00)) tick(); }
    private void indX() { int zp = read8(pc++); read8(zp); int addr = (zp + x) & 0xFF; int lo = read8(addr); int hi = read8((addr + 1) & 0xFF); fetchedAddr = (hi << 8) | lo; fetchedValue = read8(fetchedAddr); }
    private void indY(boolean extraCycle) { int zp = read8(pc++); int lo = read8(zp); int hi = read8((zp + 1) & 0xFF); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF; fetchedValue = read8(fetchedAddr); if (extraCycle && (base & 0xFF00) != (fetchedAddr & 0xFF00)) tick(); }
    
    private void zpAddr() { fetchedAddr = read8(pc++); }
    private void zpXAddr() { fetchedAddr = (read8(pc++) + x) & 0xFF; read8(fetchedAddr); }
    private void zpYAddr() { fetchedAddr = (read8(pc++) + y) & 0xFF; read8(fetchedAddr); }
    private void absAddr() { int lo = read8(pc++); int hi = read8(pc++); fetchedAddr = (hi << 8) | lo; }
    private void absXAddr(boolean dummyRead) { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + x) & 0xFFFF; if (dummyRead || (base & 0xFF00) != (fetchedAddr & 0xFF00)) read8(fetchedAddr); }
    private void absYAddr(boolean dummyRead) { int lo = read8(pc++); int hi = read8(pc++); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF; if (dummyRead || (base & 0xFF00) != (fetchedAddr & 0xFF00)) read8(fetchedAddr); }
    private void indXAddr() { int zp = read8(pc++); read8(zp); int addr = (zp + x) & 0xFF; int lo = read8(addr); int hi = read8((addr + 1) & 0xFF); fetchedAddr = (hi << 8) | lo; }
    private void indYAddr(boolean dummyRead) { int zp = read8(pc++); int lo = read8(zp); int hi = read8((zp + 1) & 0xFF); int base = (hi << 8) | lo; fetchedAddr = (base + y) & 0xFFFF; if (dummyRead || (base & 0xFF00) != (fetchedAddr & 0xFF00)) read8(fetchedAddr); }

    // --- Instructions ---
    private void ADC() { int sum = a + fetchedValue + (flag(C) ? 1 : 0); setFlag(C, sum > 0xFF); setFlag(V, (~(a ^ fetchedValue) & (a ^ sum) & 0x80) != 0); a = sum & 0xFF; setZN(a); }
    private void AND() { a &= fetchedValue; setZN(a); }
    private void ASL() { tick(); if (isAccumulatorMode) { setFlag(C, (a & 0x80) != 0); a = (a << 1) & 0xFF; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 0x80) != 0); val = (val << 1) & 0xFF; write8(fetchedAddr, val); setZN(val); } }
    private void branch(boolean cond) { if (cond) { tick(); int offset = (byte)read8(pc++); int oldPc = pc; pc = (pc + offset) & 0xFFFF; if ((oldPc & 0xFF00) != (pc & 0xFF00)) tick(); } else { read8(pc++); } }
    private void BIT() { setFlag(Z, (a & fetchedValue) == 0); setFlag(V, (fetchedValue & V) != 0); setFlag(N, (fetchedValue & N) != 0); }
    private void BRK() { pc++; push16(pc); push(p | B | U); setFlag(I, true); pc = read16(0xFFFE); }
    private void CLC() { tick(); setFlag(C, false); } private void CLD() { tick(); setFlag(D, false); }
    private void CLI() { tick(); setFlag(I, false); } private void CLV() { tick(); setFlag(V, false); }
    private void CMP() { int cmp = (a - fetchedValue) & 0x1FF; setFlag(C, cmp < 0x100); setZN(cmp & 0xFF); }
    private void CPX() { int cmp = (x - fetchedValue) & 0x1FF; setFlag(C, cmp < 0x100); setZN(cmp & 0xFF); }
    private void CPY() { int cmp = (y - fetchedValue) & 0x1FF; setFlag(C, cmp < 0x100); setZN(cmp & 0xFF); }
    private void DEC() { int val = (fetchedValue - 1) & 0xFF; write8(fetchedAddr, val); setZN(val); }
    private void EOR() { a ^= fetchedValue; setZN(a); }
    private void INC() { int val = (fetchedValue + 1) & 0xFF; write8(fetchedAddr, val); setZN(val); }
    private void JMP_abs() { int lo = read8(pc++); int hi = read8(pc++); pc = (hi << 8) | lo; }
    private void JMP_ind() { int lo = read8(pc++); int hi = read8(pc++); int addr = (hi << 8) | lo; pc = read16Bug(addr); }
    private void JSR() { int lo = read8(pc++); int hi = read8(pc++); push16(pc - 1); pc = (hi << 8) | lo; tick(); }
    private void LDA() { a = fetchedValue; setZN(a); }
    private void LDX() { x = fetchedValue; setZN(x); }
    private void LDY() { y = fetchedValue; setZN(y); }
    private void LSR() { tick(); if (isAccumulatorMode) { setFlag(C, (a & 1) != 0); a >>= 1; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 1) != 0); val >>= 1; write8(fetchedAddr, val); setZN(val); } }
    private void NOP() { tick(); }
    private void ORA() { a |= fetchedValue; setZN(a); }
    private void PHA() { push(a); tick(); }
    private void PLA() { tick(); tick(); a = pop(); setZN(a); }
    private void PHP() { push(p | B | U); tick(); }
    private void PLP() { tick(); tick(); p = (pop() & ~B) | U; }
    private void ROL() { tick(); int c = flag(C) ? 1 : 0; if (isAccumulatorMode) { setFlag(C, (a & 0x80) != 0); a = ((a << 1) | c) & 0xFF; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 0x80) != 0); val = ((val << 1) | c) & 0xFF; write8(fetchedAddr, val); setZN(val); } }
    private void ROR() { tick(); int c = flag(C) ? 0x80 : 0; if (isAccumulatorMode) { setFlag(C, (a & 1) != 0); a = (a >> 1) | c; setZN(a); } else { int val = fetchedValue; setFlag(C, (val & 1) != 0); val = (val >> 1) | c; write8(fetchedAddr, val); setZN(val); } }
    private void RTI() { p = (pop() & ~B) | U; pc = pop16(); tick(); tick(); }
    private void RTS() { pc = (pop16() + 1) & 0xFFFF; tick(); tick(); tick(); }
    private void SBC() { int val = fetchedValue ^ 0xFF; int sum = a + val + (flag(C) ? 1 : 0); setFlag(C, sum > 0xFF); setFlag(V, (~(a ^ val) & (a ^ sum) & 0x80) != 0); a = sum & 0xFF; setZN(a); }
    private void SEC() { tick(); setFlag(C, true); } private void SED() { tick(); setFlag(D, true); }
    private void SEI() { tick(); setFlag(I, true); }
    private void STA() { write8(fetchedAddr, a); }
    private void STX() { write8(fetchedAddr, x); }
    private void STY() { write8(fetchedAddr, y); }
    private void TAX() { x = a; setZN(x); tick(); }
    private void TAY() { y = a; setZN(y); tick(); }
    private void TXA() { a = x; setZN(a); tick(); }
    private void TYA() { a = y; setZN(a); tick(); }
    private void TSX() { x = sp; setZN(x); tick(); }
    private void TXS() { sp = x; tick(); }
    private void INX() { x = (x + 1) & 0xFF; setZN(x); tick(); }
    private void INY() { y = (y + 1) & 0xFF; setZN(y); tick(); }
    private void DEX() { x = (x - 1) & 0xFF; setZN(x); tick(); }
    private void DEY() { y = (y - 1) & 0xFF; setZN(y); tick(); }
}
