package nes.controller;

import nes.model.ControllerButton;
import nes.model.NESConsole;

import javax.swing.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * KeyboardController maps keyboard events to NES controller buttons (Controller in MVC).
 */
public class KeyboardController {
    private final NESConsole console;
    private final Map<Integer, ControllerButton> keyMap = new HashMap<>();

    public KeyboardController(NESConsole console, JComponent listenOn) {
        this.console = console;
        initDefaultMapping();
        listenOn.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                ControllerButton b = keyMap.get(e.getKeyCode());
                if (b != null) {
                    console.pressButton(b);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                ControllerButton b = keyMap.get(e.getKeyCode());
                if (b != null) {
                    console.releaseButton(b);
                }
            }
        });
    }

    private void initDefaultMapping() {
        // Arrow keys for D-pad
        keyMap.put(KeyEvent.VK_UP, ControllerButton.UP);
        keyMap.put(KeyEvent.VK_DOWN, ControllerButton.DOWN);
        keyMap.put(KeyEvent.VK_LEFT, ControllerButton.LEFT);
        keyMap.put(KeyEvent.VK_RIGHT, ControllerButton.RIGHT);

        // A/S for A/B, Enter for Start, Shift for Select
        keyMap.put(KeyEvent.VK_A, ControllerButton.A);
        keyMap.put(KeyEvent.VK_S, ControllerButton.B);
        keyMap.put(KeyEvent.VK_ENTER, ControllerButton.START);
        keyMap.put(KeyEvent.VK_SHIFT, ControllerButton.SELECT);
    }
}
