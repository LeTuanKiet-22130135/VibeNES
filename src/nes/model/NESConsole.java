package nes.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * NESConsole is the central Model in the MVC design.
 * It aggregates all core hardware components (CPU, PPU, APU, Bus, RAM, Cartridge, ControllerPorts)
 * and exposes a simple, high-level API for the Controller and View layers.
 */
public class NESConsole {
    public static final int SCREEN_WIDTH = 256;
    public static final int SCREEN_HEIGHT = 240;
    public static final int NTSC_CPU_CYCLES_PER_FRAME = 29_780;

    private long frameCounter = 0;
    private int cpuCyclesPerFrame = NTSC_CPU_CYCLES_PER_FRAME;

    private final CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private final Bus bus;
    private final RAM ram;
    private Cartridge cartridge;
    private final ControllerPort controller1;
    private final ControllerPort controller2;

    private final Map<ControllerButton, Boolean> controller1State = new EnumMap<>(ControllerButton.class);

    public NESConsole() {
        this.cpu = new CPU();
        this.ppu = new PPU();
        this.apu = new APU();
        this.ram = new RAM(2 * 1024);
        this.bus = new Bus(cpu, ppu, apu, ram);
        this.controller1 = new ControllerPort();
        this.controller2 = new ControllerPort();
        this.bus.setControllers(controller1, controller2);

        for (ControllerButton b : ControllerButton.values()) {
            controller1State.put(b, false);
        }
        
        // Reset the CPU now that the bus is attached
        this.cpu.reset();
    }

    public void nextFrame() {
        frameCounter++;
        if (cpuCyclesPerFrame > 0 && cartridge != null) {
            int cyclesRemaining = cpuCyclesPerFrame;
            while (cyclesRemaining > 0) {
                int cycles = cpu.stepInstruction();
                apu.stepCpuCycles(cycles);
                cyclesRemaining -= cycles;
            }
        }
    }

    // The HLE/LLE mode distinction is removed, as rendering is now always LLE.
    // Kept for compatibility with Main.java CLI flags, but has no effect.
    public enum EmulationMode { LLE, HLE }
    public void setEmulationMode(EmulationMode mode) { /* No-op */ }
    public EmulationMode getEmulationMode() { return EmulationMode.LLE; }

    public int[] getFrameBuffer() {
        return ppu.getFrameBuffer();
    }

    public int drainApuSamples(float[] buffer) {
        return apu.drainSamples(buffer);
    }

    public int getScreenWidth() { return SCREEN_WIDTH; }
    public int getScreenHeight() { return SCREEN_HEIGHT; }

    public void pressButton(ControllerButton button) {
        controller1State.put(button, true);
        controller1.setButtonState(button, true);
    }

    public void releaseButton(ControllerButton button) {
        controller1State.put(button, false);
        controller1.setButtonState(button, false);
    }

    public boolean isButtonPressed(ControllerButton button) {
        return controller1State.getOrDefault(button, false);
    }

    public void insertCartridge(Cartridge cart) {
        this.cartridge = cart;
        this.bus.setCartridge(cart);
        this.ppu.setCartridge(cart);
        if (cart != null) {
            cart.reset();   // reset mapper (MMC1) state
        }
        this.ppu.reset();    // reset PPU state
        this.cpu.reset();    // reset CPU
    }

    public void setCpuCyclesPerFrame(int cycles) {
        this.cpuCyclesPerFrame = Math.max(0, cycles);
    }
}
