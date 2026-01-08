package nes.model;

/**
 * The system bus that connects CPU, PPU, APU, RAM, and the cartridge mapper.
 */
public class Bus {
    private final CPU cpu;
    private final PPU ppu;
    private final APU apu;
    private final RAM ram;
    private Cartridge cartridge;
    private ControllerPort controller1;
    private ControllerPort controller2;

    public Bus(CPU cpu, PPU ppu, APU apu, RAM ram) {
        this.cpu = cpu;
        this.ppu = ppu;
        this.apu = apu;
        this.ram = ram;
        if (this.cpu != null) this.cpu.attachBus(this);
        if (this.ppu != null) this.ppu.attachBus(this);
        if (this.apu != null) this.apu.attachBus(this);
    }

    public int read(int address) {
        address &= 0xFFFF;
        if (address < 0x2000) {
            return ram.read(address & 0x07FF) & 0xFF;
        }
        if (address < 0x4000) {
            return ppu.readRegister(address) & 0xFF;
        }
        if (address < 0x4020) {
            if (address == 0x4015) return apu.readRegister(address) & 0xFF;
            if (address == 0x4016) return (controller1 != null) ? controller1.readSerial() & 1 : 0;
            if (address == 0x4017) return (controller2 != null) ? controller2.readSerial() & 1 : 0;
            return 0;
        }
        if (cartridge != null) {
            return cartridge.getMapper().cpuRead(address) & 0xFF;
        }
        return 0;
    }

    public void write(int address, int value) {
        // Default write without cycles (should not be used by CPU for mapper writes ideally)
        write(address, value, 0);
    }

    public void write(int address, int value, long cycles) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x2000) {
            ram.write(address & 0x07FF, value);
            return;
        }
        if (address < 0x4000) {
            ppu.writeRegister(address, value);
            return;
        }
        if (address < 0x4020) {
            if (address == 0x4014) {
                int base = value << 8;
                for (int i = 0; i < 256; i++) {
                    ppu.oamDmaWrite(read((base + i) & 0xFFFF));
                }
                ppu.stepCpuCycles(513);
                if (apu != null) apu.stepCpuCycles(513);
                if (cpu != null) cpu.stall(513);
                return;
            }
            if (address == 0x4016) {
                boolean strobe = (value & 1) != 0;
                if (controller1 != null) controller1.setStrobe(strobe);
                if (controller2 != null) controller2.setStrobe(strobe);
                return;
            }
            if (address <= 0x4017) {
                apu.writeRegister(address, value);
                return;
            }
            return;
        }
        if (cartridge != null) {
            cartridge.getMapper().cpuWrite(address, value, cycles);
        }
    }

    public void requestNmi() {
        if (cpu != null) cpu.nmi();
    }
    
    public void requestIrq() {
        if (cpu != null) cpu.irq();
    }

    public void clearIrq() {
        if (cpu != null) cpu.clearIrq();
    }

    public void setCartridge(Cartridge cart) {
        this.cartridge = cart;
        if (cart != null) {
            cart.getMapper().setBus(this);
        }
    }

    public void setControllers(ControllerPort c1, ControllerPort c2) {
        this.controller1 = c1;
        this.controller2 = c2;
    }

    public void onCpuCycle() {
        if (ppu != null) {
            ppu.stepCpuCycles(1);
        }
        if (apu != null) {
            apu.stepCpuCycles(1);
        }
    }
}
