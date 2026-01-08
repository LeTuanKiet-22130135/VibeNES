//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import nes.controller.KeyboardController;
import nes.model.CPUSelfTest;
import nes.model.NESConsole;
import nes.model.Cartridge;
import nes.view.NESWindow;

import javax.swing.*;
import java.io.IOException;
import javax.sound.sampled.*;
import java.util.Arrays;

public class Main {
    private static final int SAMPLE_RATE = 44100;
    private static final int AUDIO_BUFFER_SAMPLES = 4096; // Increased buffer size

    public static void main(String[] args) {
        // CLI: run a tiny CPU self-test and exit
        for (String a : args) {
            if ("--cpu-self-test".equals(a)) {
                boolean ok = CPUSelfTest.runTiny();
                System.out.println("CPU self-test: " + (ok ? "PASS" : "FAIL"));
                System.exit(ok ? 0 : 1);
                return;
            }
            if ("--ppu-self-test".equals(a)) {
                boolean ok = nes.model.PPUSelfTest.runAll();
                System.out.println("PPU self-test: " + (ok ? "PASS" : "FAIL"));
                System.exit(ok ? 0 : 1);
                return;
            }
            if ("--ppu-mirror-test".equals(a)) {
                boolean ok = nes.model.PPUSelfTest.runMirroringTest();
                System.out.println("PPU mirroring test: " + (ok ? "PASS" : "FAIL"));
                System.exit(ok ? 0 : 1);
                return;
            }
            if ("--mapper-self-test".equals(a)) {
                boolean ok = nes.model.mapper.MapperSelfTest.runMMC1Basic();
                System.out.println("Mapper self-test (MMC1 basic): " + (ok ? "PASS" : "FAIL"));
                System.exit(ok ? 0 : 1);
                return;
            }
        }

        SwingUtilities.invokeLater(() -> {
            // Model
            NESConsole console = new NESConsole();

            // Optional: allow configuring CPU cycles stepped per frame via CLI flag
            for (String a : args) {
                String prefix = "--cpu-cycles-per-frame=";
                if (a.startsWith(prefix)) {
                    try {
                        int cycles = Integer.parseInt(a.substring(prefix.length()));
                        console.setCpuCyclesPerFrame(cycles);
                    } catch (NumberFormatException ignored) { }
                }
            }

            // Optional: choose emulation mode (HLE or LLE)
            for (String a : args) {
                String prefix = "--mode=";
                if (a.startsWith(prefix)) {
                    String m = a.substring(prefix.length()).trim().toUpperCase();
                    try {
                        NESConsole.EmulationMode mode = NESConsole.EmulationMode.valueOf(m);
                        console.setEmulationMode(mode);
                        System.out.println("Emulation mode: " + mode);
                    } catch (IllegalArgumentException ex) {
                        System.err.println("Unknown mode '" + m + "' (use HLE or LLE). Using default.");
                    }
                }
            }

            // Optional: load a ROM if provided via CLI
            boolean romLoaded = false;
            for (String a : args) {
                String prefix = "--load-rom=";
                if (a.startsWith(prefix)) {
                    String path = a.substring(prefix.length());
                    try {
                        Cartridge cart = Cartridge.loadFromFile(path);
                        console.insertCartridge(cart);
                        System.out.println("Loaded ROM: " + cart.getName());
                        romLoaded = true;
                    } catch (IOException | RuntimeException ex) {
                        System.err.println("Failed to load ROM '" + path + "': " + ex.getMessage());
                    }
                }
            }

            if (!romLoaded) {
                try {
                    Cartridge cart = Cartridge.loadFromFile("C:/Users/lekie/IdeaProjects/NES/roms/Super_mario_brothers.nes");
                    console.insertCartridge(cart);
                    System.out.println("Loaded default ROM: " + cart.getName());
                } catch (IOException | RuntimeException ex) {
                    System.err.println("Failed to load default ROM: " + ex.getMessage());
                }
            }

            // View
            NESWindow window = new NESWindow(console);

            // Controller (keyboard -> first controller port)
            new KeyboardController(console, window.getRootPanel());

            window.showWindow();

            // --- Audio Setup ---
            SourceDataLine audioLine = null;
            try {
                // Use Little Endian format, which is standard for most modern systems.
                AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false); // false for Little Endian
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                audioLine = (SourceDataLine) AudioSystem.getLine(info);
                audioLine.open(format, AUDIO_BUFFER_SAMPLES * 2);
                audioLine.start();
                
                // Pre-fill the buffer with silence to prevent initial crackling
                byte[] silence = new byte[AUDIO_BUFFER_SAMPLES];
                Arrays.fill(silence, (byte)0);
                audioLine.write(silence, 0, silence.length);

            } catch (LineUnavailableException e) {
                System.err.println("Audio line unavailable: " + e.getMessage());
                // Exit if audio is critical
                System.exit(1);
            }
            
            final SourceDataLine finalAudioLine = audioLine;
            
            // --- Emulation Thread ---
            Thread emulationThread = new Thread(() -> {
                float[] sampleBuffer = new float[AUDIO_BUFFER_SAMPLES];
                byte[] byteBuffer = new byte[AUDIO_BUFFER_SAMPLES * 2]; // 2 bytes per sample (16-bit)

                long lastFpsTime = System.nanoTime();
                int frameCount = 0;

                while (true) {
                    // Run one frame's worth of emulation
                    console.nextFrame();
                    frameCount++;
                    
                    // Repaint the screen on the EDT
                    SwingUtilities.invokeLater(window::repaintScreen);

                    // Drain and play audio samples
                    int samplesDrained = console.drainApuSamples(sampleBuffer);
                    if (samplesDrained > 0) {
                        // Convert float samples to 16-bit PCM bytes (Little Endian)
                        for (int i = 0; i < samplesDrained; i++) {
                            float sample = Math.max(-1.0f, Math.min(1.0f, sampleBuffer[i]));
                            short pcm = (short) (sample * Short.MAX_VALUE);
                            byteBuffer[i * 2] = (byte) (pcm & 0xFF);      // Low byte
                            byteBuffer[i * 2 + 1] = (byte) ((pcm >> 8) & 0xFF); // High byte
                        }
                        // This write call will block if the audio buffer is full,
                        // effectively synchronizing the emulation speed to the audio output.
                        finalAudioLine.write(byteBuffer, 0, samplesDrained * 2);
                    }

                    // Update FPS counter every second
                    long currentTime = System.nanoTime();
                    if (currentTime - lastFpsTime >= 1_000_000_000) {
                        final int fps = frameCount;
                        SwingUtilities.invokeLater(() -> window.updateFPS(fps));
                        frameCount = 0;
                        lastFpsTime = currentTime;
                    }
                }

            });

            emulationThread.setDaemon(true); // Allow JVM to exit if only this thread is running
            emulationThread.start();


        });

    }
}
