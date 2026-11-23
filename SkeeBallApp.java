import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;


//atp im coding like its C++ and hoping to god it works
class SkeeBallApp {

    private static final String ARDUINO_PORT = "COM7";//change this
    private static final int BAUD = 9600;

    enum EventType { START, END, SCORE }
    static final class GameEvent {
        final EventType type;
        final int hole;
        GameEvent(EventType type, int hole) { this.type = type; this.hole = hole; }
    }
    interface GameEventObserver { void onGameEvent(GameEvent event); }

    static final class EventDecoder {
        static GameEvent decode(int b) {
            if (b == 0x01) return new GameEvent(EventType.START, 0);
            if (b == 0x02) return new GameEvent(EventType.END, 0);
            if (b >= 0x11 && b <= 0x15) return new GameEvent(EventType.SCORE, b - 0x10);
            return null;
        }
    }

    static final class SerialEventReader implements AutoCloseable {
        private final String portName;
        private final List<GameEventObserver> observers = new ArrayList<GameEventObserver>();
        private volatile boolean running;
        private Process process;

        SerialEventReader(String portName) { this.portName = portName; }
        void addObserver(GameEventObserver observer) { observers.add(observer); }

        void start() throws IOException {
            if (running) return;
            ProcessBuilder builder = buildCommand(portName);
            process = builder.start();
            running = true;

            final Process current = process;
            final StringBuilder errors = new StringBuilder();
            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println("Arduino USB serial: " + line);
                        synchronized (errors) {
                            if (errors.length() < 2000) errors.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) { }
            }, "arduino-errors");
            errorThread.setDaemon(true);
            errorThread.start();

            Thread readThread = new Thread(() -> {
                try (InputStream input = current.getInputStream()) {
                    int raw;
                    while (running && (raw = input.read()) != -1) {
                        System.out.printf("Arduino byte: 0x%02X%n", raw);
                        GameEvent event = EventDecoder.decode(raw);
                        if (event != null) {
                            for (GameEventObserver observer : observers) {
                                SwingUtilities.invokeLater(() -> observer.onGameEvent(event));
                            }
                        }
                    }
                    if (running) {
                        String detail;
                        synchronized (errors) { detail = errors.toString().trim(); }
                        showError("Serial receiver stopped on " + portName
                                + (detail.isEmpty() ? ". Check Arduino USB port and close the Arduino Serial Monitor." : ":\n" + detail));
                    }
                } catch (IOException ex) {
                    if (running) showError("Serial read failed: " + ex.getMessage());
                } finally {
                    running = false;
                    current.destroy();
                }
            }, "arduino-reader");
            readThread.setDaemon(true);
            readThread.start();
        }

        private static void showError(String message) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null, message, "Arduino USB serial error", JOptionPane.ERROR_MESSAGE));
        }

        private static ProcessBuilder buildCommand(String port) {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                String script =
                        "$ErrorActionPreference='Stop'; " +
                        "$p=[System.IO.Ports.SerialPort]::new($env:ARDUINO_PORT," + BAUD + ",[System.IO.Ports.Parity]::None,8,[System.IO.Ports.StopBits]::One); " +
                        "try { $p.Open(); $out=[Console]::OpenStandardOutput(); " +
                        "while ($true) { $b=$p.ReadByte(); $out.WriteByte([byte]$b); $out.Flush() } } " +
                        "finally { if ($p.IsOpen) { $p.Close() }; $p.Dispose() }";
                String encoded = Base64.getEncoder().encodeToString(
                        script.getBytes(StandardCharsets.UTF_16LE));
                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-EncodedCommand", encoded);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("sh", "-c",
                        "stty -f \"$ARDUINO_PORT\" " + BAUD + " raw -echo || exit 1; exec cat \"$ARDUINO_PORT\"");
            } else {
                pb = new ProcessBuilder("sh", "-c",
                        "stty -F \"$ARDUINO_PORT\" " + BAUD + " raw -echo || exit 1; exec cat \"$ARDUINO_PORT\"");
            }
            pb.environment().put("ARDUINO_PORT", port);
            return pb;
        }

        @Override public void close() {
            running = false;
            if (process != null) process.destroy();
        }
    }

    interface GameState {
        void handle(GameController controller, GameEvent event);
        String label();
    }
    static final class WaitingState implements GameState {
        public void handle(GameController c, GameEvent e) {
            if (e.type == EventType.START) c.beginGame();
        }
        public String label() { return "WAITING FOR START BUTTON"; }
    }
    static final class PlayingState implements GameState {
        public void handle(GameController c, GameEvent e) {
            if (e.type == EventType.SCORE) c.addScore(e.hole);
            else if (e.type == EventType.END) c.finishGame();
            else if (e.type == EventType.START) c.beginGame();
        }
        public String label() { return "GAME IN PROGRESS"; }
    }
    static final class FinishedState implements GameState {
        public void handle(GameController c, GameEvent e) {
            if (e.type == EventType.START) c.beginGame();
        }
        public String label() { return "GAME OVER"; }
    }

    static final class GameController implements GameEventObserver {
        private final Scoreboard view;
        private GameState state = new WaitingState();
        private int score, balls;
        GameController(Scoreboard view) {
            this.view = view;
            update("Press the button on the Arduino to begin");
        }
        @Override public void onGameEvent(GameEvent event) { state.handle(this, event); }
        void beginGame() {
            score = 0;
            balls = 0;
            state = new PlayingState();
            update("Game started! Roll the balls!");
        }
        void addScore(int hole) {
            int points = hole * 10; // Adjust if scores dont work
            score += points;
            balls++;
            update("Hole " + hole + " - +" + points + " points");
        }
        void finishGame() {
            state = new FinishedState();
            update("FINAL SCORE: " + score + " - press the button to play again");
        }
        void update(String message) { view.showState(state.label(), score, balls, message); }
    }

    static final class Scoreboard extends JFrame {
        private final JLabel state = new JLabel("", SwingConstants.CENTER);
        private final JLabel score = new JLabel("0", SwingConstants.CENTER);
        private final JLabel balls = new JLabel("", SwingConstants.CENTER);
        private final JLabel message = new JLabel("", SwingConstants.CENTER);
        Scoreboard() {
            super("Skee-Ball Scoreboard - Arduino USB on " + ARDUINO_PORT + " (" + BAUD + " baud)");
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            setSize(760, 360);
            setLocationRelativeTo(null);
            setLayout(new GridLayout(4, 1, 0, 6));
            state.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
            score.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 92));
            balls.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            message.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            add(state); add(score); add(balls); add(message);
        }
        void showState(String status, int points, int ballCount, String detail) {
            state.setText(status);
            score.setText(Integer.toString(points));
            balls.setText("Scored balls: " + ballCount);
            message.setText(detail);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Scoreboard window = new Scoreboard();
            GameController controller = new GameController(window);
            SerialEventReader receiver = new SerialEventReader(ARDUINO_PORT);
            receiver.addObserver(controller);
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) { receiver.close(); }
            });
            window.setVisible(true);
            try {
                receiver.start();
                System.out.println("Listening for raw Arduino bytes on " + ARDUINO_PORT
                        + " at " + BAUD + " baud");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(window, "Cannot start receiver: " + ex.getMessage(),
                        "Serial error", JOptionPane.ERROR_MESSAGE);
                receiver.close();
            }
        });
    }
}
