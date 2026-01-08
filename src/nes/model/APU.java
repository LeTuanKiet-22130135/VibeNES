package nes.model;

import java.util.LinkedList;
import java.util.Queue;

/**
 * A more complete implementation of the NES Audio Processing Unit (APU).
 *
 * This class simulates the two Pulse channels, the Triangle channel, and the Noise channel.
 * It uses a Frame Counter to drive the length counters and envelope units, which is
 * essential for correct note duration and volume decay.
 *
 * The DMC channel is not implemented.
 */
public class APU {

    private final Pulse pulse1 = new Pulse(true); // true for pulse 1 (extra sweep behavior)
    private final Pulse pulse2 = new Pulse(false);
    private final Triangle triangle = new Triangle();
    private final Noise noise = new Noise();

    private int cpuCycleCounter = 0;
    private int frameCounter = 0;
    private int frameCounterMode = 0; // 0 for 4-step, 1 for 5-step

    private final Queue<Float> sampleBuffer = new LinkedList<>();
    
    // Audio timing
    private static final double CYCLES_PER_SAMPLE = 1789773.0 / 44100.0; // ~40.58
    private double sampleTimer = 0.0;
    
    // High-Pass Filter state for DC offset removal
    private float filterPrevSample = 0.0f;
    private float filterPrevOutput = 0.0f;

    // Lookup tables
    private static final int[] lengthCounterTable = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    };
    private static final int[] noisePeriodTable = {
            4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    };
    private static final float[] pulseMixTable = new float[31];
    private static final float[] tndMixTable = new float[203];

    static {
        // Pre-calculate mixer lookup tables
        for (int i = 0; i < 31; i++) {
            pulseMixTable[i] = 95.52f / (8128.0f / i + 100);
        }
        for (int i = 0; i < 203; i++) {
            tndMixTable[i] = 163.67f / (24329.0f / i + 100);
        }
    }

    public void reset() {
        pulse1.reset();
        pulse2.reset();
        triangle.reset();
        noise.reset();
        cpuCycleCounter = 0;
        frameCounter = 0;
        sampleTimer = 0.0;
        sampleBuffer.clear();
        filterPrevSample = 0.0f;
        filterPrevOutput = 0.0f;
    }

    public void stepCpuCycles(int cpuCycles) {
        for (int i = 0; i < cpuCycles; i++) {
            cpuCycleCounter++;

            // APU channels are clocked at half CPU speed
            if (cpuCycleCounter % 2 == 0) {
                pulse1.stepTimer();
                pulse2.stepTimer();
                noise.stepTimer();
            }
            // Triangle is clocked at CPU speed
            triangle.stepTimer();

            // Frame Counter logic
            // (Approximate timings for NTSC)
            if (frameCounterMode == 0) { // 4-Step Sequence
                if (cpuCycleCounter == 7457) { clockQuarterFrame(); }
                if (cpuCycleCounter == 14913) { clockQuarterFrame(); clockHalfFrame(); }
                if (cpuCycleCounter == 22371) { clockQuarterFrame(); }
                if (cpuCycleCounter == 29829) { clockQuarterFrame(); clockHalfFrame(); cpuCycleCounter = 0; }
            } else { // 5-Step Sequence
                if (cpuCycleCounter == 7457) { clockQuarterFrame(); }
                if (cpuCycleCounter == 14913) { clockQuarterFrame(); clockHalfFrame(); }
                if (cpuCycleCounter == 22371) { clockQuarterFrame(); }
                if (cpuCycleCounter == 37281) { clockQuarterFrame(); clockHalfFrame(); }
                if (cpuCycleCounter >= 37282) { cpuCycleCounter = 0; }
            }

            // Sample generation with fractional precision
            sampleTimer += 1.0;
            if (sampleTimer >= CYCLES_PER_SAMPLE) {
                sampleTimer -= CYCLES_PER_SAMPLE;
                if (sampleBuffer.size() < 4096) {
                    float rawSample = mixOutput();
                    // High-Pass Filter to remove DC offset
                    // y[n] = x[n] - x[n-1] + 0.996 * y[n-1]
                    float filteredSample = rawSample - filterPrevSample + 0.996f * filterPrevOutput;
                    filterPrevSample = rawSample;
                    filterPrevOutput = filteredSample;
                    
                    sampleBuffer.add(filteredSample);
                }
            }
        }
    }

    private void clockQuarterFrame() {
        pulse1.stepEnvelope();
        pulse2.stepEnvelope();
        triangle.stepLinearCounter();
        noise.stepEnvelope();
    }

    private void clockHalfFrame() {
        pulse1.stepLength();
        pulse1.stepSweep();
        pulse2.stepLength();
        pulse2.stepSweep();
        triangle.stepLength();
        noise.stepLength();
    }

    private float mixOutput() {
        int pulseOut = pulse1.getSample() + pulse2.getSample();
        int tndOut = (3 * triangle.getSample()) + (2 * noise.getSample());
        return pulseMixTable[pulseOut] + tndMixTable[tndOut];
    }

    public int drainSamples(float[] buffer) {
        int count = 0;
        while (!sampleBuffer.isEmpty() && count < buffer.length) {
            buffer[count++] = sampleBuffer.poll();
        }
        return count;
    }

    public void writeRegister(int address, int value) {
        switch (address) {
            // Pulse 1
            case 0x4000: pulse1.writeControl(value); break;
            case 0x4001: pulse1.writeSweep(value); break;
            case 0x4002: pulse1.writeTimerLo(value); break;
            case 0x4003: pulse1.writeTimerHi(value); break;
            // Pulse 2
            case 0x4004: pulse2.writeControl(value); break;
            case 0x4005: pulse2.writeSweep(value); break;
            case 0x4006: pulse2.writeTimerLo(value); break;
            case 0x4007: pulse2.writeTimerHi(value); break;
            // Triangle
            case 0x4008: triangle.writeControl(value); break;
            case 0x400A: triangle.writeTimerLo(value); break;
            case 0x400B: triangle.writeTimerHi(value); break;
            // Noise
            case 0x400C: noise.writeControl(value); break;
            case 0x400E: noise.writePeriod(value); break;
            case 0x400F: noise.writeLength(value); break;
            // Control
            case 0x4015:
                pulse1.setEnabled((value & 1) != 0);
                pulse2.setEnabled((value & 2) != 0);
                triangle.setEnabled((value & 4) != 0);
                noise.setEnabled((value & 8) != 0);
                break;
            case 0x4017:
                frameCounterMode = (value >> 7) & 1;
                cpuCycleCounter = 0; // Reset counter on mode change
                if (frameCounterMode == 1) { // 5-step mode immediately clocks
                    clockQuarterFrame();
                    clockHalfFrame();
                }
                break;
        }
    }

    public int readRegister(int address) {
        if (address == 0x4015) {
            int status = 0;
            if (pulse1.lengthCounter > 0) status |= 1;
            if (pulse2.lengthCounter > 0) status |= 2;
            if (triangle.lengthCounter > 0) status |= 4;
            if (noise.lengthCounter > 0) status |= 8;
            return status;
        }
        return 0;
    }

    // --- Channel Implementations ---

    private static class Pulse {
        private final boolean isPulse1;
        boolean enabled;
        // Registers
        int duty;
        boolean lengthHalt;
        boolean constantVolume;
        int volume;
        boolean sweepEnabled;
        int sweepPeriod;
        boolean sweepNegate;
        int sweepShift;
        int timerPeriod;
        int lengthCounterLoad;
        // Internal state
        int timer;
        int sequenceStep;
        int lengthCounter;
        boolean envelopeStart;
        int envelopeDecay;
        int envelopeCounter;
        boolean sweepReload;
        int sweepCounter;
        int sweepTargetPeriod;

        private static final int[][] dutyCycleTable = {
                {0, 1, 0, 0, 0, 0, 0, 0}, // 12.5%
                {0, 1, 1, 0, 0, 0, 0, 0}, // 25%
                {0, 1, 1, 1, 1, 0, 0, 0}, // 50%
                {1, 0, 0, 1, 1, 1, 1, 1}  // 25% negated
        };

        Pulse(boolean isPulse1) { this.isPulse1 = isPulse1; }

        void reset() { enabled = false; duty = 0; lengthHalt = false; constantVolume = false; volume = 0; sweepEnabled = false; sweepPeriod = 0; sweepNegate = false; sweepShift = 0; timerPeriod = 0; lengthCounterLoad = 0; timer = 0; sequenceStep = 0; lengthCounter = 0; envelopeStart = false; envelopeDecay = 0; envelopeCounter = 0; sweepReload = false; sweepCounter = 0; sweepTargetPeriod = 0; }
        void setEnabled(boolean v) { if (!v) lengthCounter = 0; enabled = v; }

        void writeControl(int v) { duty = (v >> 6) & 3; lengthHalt = (v & 0x20) != 0; constantVolume = (v & 0x10) != 0; volume = v & 0x0F; }
        void writeSweep(int v) { sweepEnabled = (v & 0x80) != 0; sweepPeriod = (v >> 4) & 7; sweepNegate = (v & 8) != 0; sweepShift = v & 7; sweepReload = true; }
        void writeTimerLo(int v) { timerPeriod = (timerPeriod & 0xFF00) | v; }
        void writeTimerHi(int v) { timerPeriod = (timerPeriod & 0x00FF) | ((v & 7) << 8); lengthCounterLoad = (v >> 3) & 0x1F; if (enabled) lengthCounter = lengthCounterTable[lengthCounterLoad]; timer = timerPeriod; envelopeStart = true; }

        void stepTimer() { if (timer == 0) { timer = timerPeriod; sequenceStep = (sequenceStep + 1) & 7; } else { timer--; } }
        void stepLength() { if (!lengthHalt && lengthCounter > 0) lengthCounter--; }
        void stepEnvelope() {
            if (envelopeStart) {
                envelopeStart = false;
                envelopeDecay = 15;
                envelopeCounter = volume;
            } else {
                if (envelopeCounter > 0) {
                    envelopeCounter--;
                } else {
                    envelopeCounter = volume;
                    if (envelopeDecay > 0) envelopeDecay--;
                    else if (lengthHalt) envelopeDecay = 15;
                }
            }
        }
        void stepSweep() {
            sweepTargetPeriod = timerPeriod >> sweepShift;
            if (sweepNegate) {
                sweepTargetPeriod = timerPeriod - sweepTargetPeriod;
                if (isPulse1) sweepTargetPeriod--;
            } else {
                sweepTargetPeriod = timerPeriod + sweepTargetPeriod;
            }
            boolean sweepMuted = timerPeriod < 8 || sweepTargetPeriod > 0x7FF;
            if (sweepReload) {
                sweepCounter = sweepPeriod;
                sweepReload = false;
            } else if (sweepCounter > 0) {
                sweepCounter--;
            } else {
                sweepCounter = sweepPeriod;
                if (sweepEnabled && sweepShift > 0 && !sweepMuted) {
                    timerPeriod = sweepTargetPeriod;
                }
            }
        }
        int getSample() {
            if (!enabled || lengthCounter == 0 || timerPeriod < 8 || sweepTargetPeriod > 0x7FF) return 0;
            if (dutyCycleTable[duty][sequenceStep] == 0) return 0;
            return constantVolume ? volume : envelopeDecay;
        }
    }

    private static class Triangle {
        boolean enabled;
        boolean controlFlag;
        int linearCounterLoad;
        int timerPeriod;
        int lengthCounterLoad;
        int timer;
        int sequenceStep;
        int lengthCounter;
        int linearCounter;
        boolean linearCounterReload;

        private static final int[] sequenceTable = {
                15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
        };

        void reset() { enabled = false; controlFlag = false; linearCounterLoad = 0; timerPeriod = 0; lengthCounterLoad = 0; timer = 0; sequenceStep = 0; lengthCounter = 0; linearCounter = 0; linearCounterReload = false; }
        void setEnabled(boolean v) { if (!v) lengthCounter = 0; enabled = v; }

        void writeControl(int v) { controlFlag = (v & 0x80) != 0; linearCounterLoad = v & 0x7F; }
        void writeTimerLo(int v) { timerPeriod = (timerPeriod & 0xFF00) | v; }
        void writeTimerHi(int v) { timerPeriod = (timerPeriod & 0x00FF) | ((v & 7) << 8); lengthCounterLoad = (v >> 3) & 0x1F; if (enabled) lengthCounter = lengthCounterTable[lengthCounterLoad]; linearCounterReload = true; }

        void stepTimer() { if (timer == 0) { timer = timerPeriod; if (lengthCounter > 0 && linearCounter > 0) sequenceStep = (sequenceStep + 1) & 0x1F; } else { timer--; } }
        void stepLength() { if (!controlFlag && lengthCounter > 0) lengthCounter--; }
        void stepLinearCounter() { if (linearCounterReload) linearCounter = linearCounterLoad; else if (linearCounter > 0) linearCounter--; if (!controlFlag) linearCounterReload = false; }
        int getSample() { return enabled ? sequenceTable[sequenceStep] : 0; }
    }

    private static class Noise {
        boolean enabled;
        boolean lengthHalt;
        boolean constantVolume;
        int volume;
        boolean mode;
        int period;
        int lengthCounterLoad;
        int timer;
        int shiftRegister = 1;
        int lengthCounter;
        boolean envelopeStart;
        int envelopeDecay;
        int envelopeCounter;

        void reset() { enabled = false; lengthHalt = false; constantVolume = false; volume = 0; mode = false; period = 0; lengthCounterLoad = 0; timer = 0; shiftRegister = 1; lengthCounter = 0; envelopeStart = false; envelopeDecay = 0; envelopeCounter = 0; }
        void setEnabled(boolean v) { if (!v) lengthCounter = 0; enabled = v; }

        void writeControl(int v) { lengthHalt = (v & 0x20) != 0; constantVolume = (v & 0x10) != 0; volume = v & 0x0F; }
        void writePeriod(int v) { mode = (v & 0x80) != 0; period = noisePeriodTable[v & 0x0F]; }
        void writeLength(int v) { lengthCounterLoad = (v >> 3) & 0x1F; if (enabled) lengthCounter = lengthCounterTable[lengthCounterLoad]; envelopeStart = true; }

        void stepTimer() {
            if (timer == 0) {
                timer = period;
                int feedbackBit = (shiftRegister & 1) ^ ((mode ? (shiftRegister >> 6) : (shiftRegister >> 1)) & 1);
                shiftRegister = (shiftRegister >> 1) | (feedbackBit << 14);
            } else {
                timer--;
            }
        }
        void stepLength() { if (!lengthHalt && lengthCounter > 0) lengthCounter--; }
        void stepEnvelope() {
            if (envelopeStart) {
                envelopeStart = false;
                envelopeDecay = 15;
                envelopeCounter = volume;
            } else {
                if (envelopeCounter > 0) {
                    envelopeCounter--;
                } else {
                    envelopeCounter = volume;
                    if (envelopeDecay > 0) envelopeDecay--;
                    else if (lengthHalt) envelopeDecay = 15;
                }
            }
        }
        int getSample() {
            if (!enabled || lengthCounter == 0 || (shiftRegister & 1) != 0) return 0;
            return constantVolume ? volume : envelopeDecay;
        }
    }
}
