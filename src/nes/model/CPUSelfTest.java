package nes.model;

/**
 * Tiny CPU self-test harness. Loads a few instructions into RAM and verifies
 * that memory results match expectations. This is not exhaustive, just a
 * smoke test to catch obvious regressions.
 */
public final class CPUSelfTest {
    private CPUSelfTest() {}

    /**
     * Run a minimal self-test on the CPU core and return true if it passes.
     * The test writes a small program to RAM at $0000 and steps through it.
     */
    public static boolean runTiny() {
        CPU cpu = new CPU();
        PPU ppu = new PPU();
        APU apu = new APU();
        RAM ram = new RAM(2 * 1024);
        Bus bus = new Bus(cpu, ppu, apu, ram);

        // Program at $0000:
        //   A simple sequence to test LDX/TXS, LDA/STA, ADC (with zero-page), INX/STX, and BRK.
        //   LDX #$10        A2 10
        //   TXS             9A
        //   LDA #$05        A9 05
        //   STA $10         85 10
        //   LDA #$03        A9 03
        //   ADC $10         65 10   ; 03 + 05 = 08 (C=0)
        //   STA $11         85 11
        //   INX             E8      ; X becomes 0x11
        //   STX $12         86 12
        //   BRK             00
        int[] program = new int[] {
                0xA2, 0x10,
                0x9A,
                0xA9, 0x05,
                0x85, 0x10,
                0xA9, 0x03,
                0x65, 0x10,
                0x85, 0x11,
                0xE8,
                0x86, 0x12,
                0x00
        };

        // Load program to RAM @ $0000
        for (int i = 0; i < program.length; i++) {
            ram.write(i, program[i]);
        }

        // Clear test output bytes
        ram.write(0x10, 0x00);
        ram.write(0x11, 0x00);
        ram.write(0x12, 0x00);

        // Reset CPU (vectors read as 0 -> start at $0000 in this placeholder bus)
        cpu.reset();

        // Step through all instructions in the program (10 total)
        for (int i = 0; i < 10; i++) {
            cpu.stepInstruction();
        }

        // Validate expected memory results
        int v10 = ram.read(0x10) & 0xFF;
        int v11 = ram.read(0x11) & 0xFF;
        int v12 = ram.read(0x12) & 0xFF;

        boolean ok = (v10 == 0x05) && (v11 == 0x08) && (v12 == 0x11);
        return ok;
    }
}
