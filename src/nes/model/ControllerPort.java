package nes.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * NES controller port with standard shift-register readout.
 *
 * Behavior mirrors the original pad connected to $4016/$4017:
 * - CPU writes to $4016 bit0 (strobe). When strobe=1, the controller latches the
 *   current button states and read() returns the A button repeatedly.
 * - When strobe transitions to 0, each read() shifts to the next button bit in
 *   the order: A, B, SELECT, START, UP, DOWN, LEFT, RIGHT.
 * - After 8 reads, further reads return 1.
 */
public class ControllerPort {
    private final Map<ControllerButton, Boolean> state = new EnumMap<>(ControllerButton.class);

    // Shift-register emulation
    private boolean strobe;          // when true, always report A and keep relatching
    private int latchedBits;         // bit0..bit7 = A..RIGHT
    private int shiftIndex;          // 0..8 (>=8 returns 1)

    public ControllerPort() {
        for (ControllerButton b : ControllerButton.values()) {
            state.put(b, false);
        }
        strobe = false;
        latchedBits = 0;
        shiftIndex = 0;
    }

    public void setButtonState(ControllerButton button, boolean pressed) {
        state.put(button, pressed);
    }

    public boolean isPressed(ControllerButton button) {
        return state.getOrDefault(button, false);
    }

    /** Called by Bus when $4016 bit0 is written. */
    public void setStrobe(boolean enable) {
        this.strobe = enable;
        if (enable) {
            // On strobe=1, latch immediately and reset index
            latchButtons();
        }
    }

    /** Latch current button states into the internal shift register. */
    public void latchButtons() {
        latchedBits = 0;
        // Order: A, B, SELECT, START, UP, DOWN, LEFT, RIGHT
        setBit(0, isPressed(ControllerButton.A));
        setBit(1, isPressed(ControllerButton.B));
        setBit(2, isPressed(ControllerButton.SELECT));
        setBit(3, isPressed(ControllerButton.START));
        setBit(4, isPressed(ControllerButton.UP));
        setBit(5, isPressed(ControllerButton.DOWN));
        setBit(6, isPressed(ControllerButton.LEFT));
        setBit(7, isPressed(ControllerButton.RIGHT));
        shiftIndex = 0;
    }

    private void setBit(int bit, boolean value) {
        if (value) latchedBits |= (1 << bit); else latchedBits &= ~(1 << bit);
    }

    /**
     * Return the next serial bit (bit0) per read semantics.
     * If strobe is high, this returns the A button bit without advancing.
     * If strobe is low, this returns the current bit and advances the index.
     * After 8 bits have been read, this returns 1.
     */
    public int readSerial() {
        if (strobe) {
            // While strobe=1, read always returns A (bit0) and controller is continually latched.
            // Many docs indicate that while strobe=1 the controller does not shift.
            // We return the latest latched A bit.
            return (latchedBits & 1) != 0 ? 1 : 0;
        }
        int bit;
        if (shiftIndex < 8) {
            bit = ((latchedBits >> shiftIndex) & 1);
        } else {
            bit = 1; // after 8 reads, pads typically return 1
        }
        if (shiftIndex < 8) shiftIndex++;
        return bit;
    }
}
