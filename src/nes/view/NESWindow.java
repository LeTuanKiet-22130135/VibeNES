package nes.view;

import nes.model.NESConsole;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Simple Swing View for the NES emulator (View in MVC).
 * Displays the model's framebuffer and exposes a root component for input listeners.
 */
public class NESWindow {
    private final NESConsole console;
    private final JFrame frame;
    private final ScreenPanel screenPanel;
    private final BufferedImage image;

    private final int scale;
    private int currentFps = 0;

    public NESWindow(NESConsole console) {
        this(console, 3); // default 3x scale
    }

    public NESWindow(NESConsole console, int scale) {
        this.console = console;
        this.scale = Math.max(1, scale);
        this.frame = new JFrame("NES Emulator (MVC Outline)");
        this.image = new BufferedImage(console.getScreenWidth(), console.getScreenHeight(), BufferedImage.TYPE_INT_RGB);
        this.screenPanel = new ScreenPanel();

        screenPanel.setPreferredSize(new Dimension(console.getScreenWidth() * this.scale, console.getScreenHeight() * this.scale));
        screenPanel.setFocusable(true);
        screenPanel.setFocusTraversalKeysEnabled(false);

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(screenPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void showWindow() {
        frame.setVisible(true);
        SwingUtilities.invokeLater(screenPanel::requestFocusInWindow);
    }

    public void repaintScreen() {
        // Copy framebuffer into the image and repaint.
        int[] fb = console.getFrameBuffer();
        image.setRGB(0, 0, console.getScreenWidth(), console.getScreenHeight(), fb, 0, console.getScreenWidth());
        screenPanel.repaint();
    }
    
    public void updateFPS(int fps) {
        this.currentFps = fps;
    }

    public JComponent getRootPanel() {
        return screenPanel;
    }

    private class ScreenPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                int w = console.getScreenWidth();
                int h = console.getScreenHeight();
                g2.drawImage(image, 0, 0, w * scale, h * scale, null);
                
                // Draw FPS Overlay
                g2.setColor(Color.GREEN);
                g2.setFont(new Font("Monospaced", Font.BOLD, 16));
                g2.drawString("FPS: " + currentFps, 10, 20);
            } finally {
                g2.dispose();
            }
        }
    }
}
