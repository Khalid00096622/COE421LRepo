package com.skeeball;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
class GrandPrixScoreboard {
   // Set this to the port shown in Arduino IDE > Tools > Port.
   private static final String PORT = "COM10";
   private static final int BAUD = 9600;
   enum Phase { WAITING, COUNTDOWN, PLAYING, FINISHED }
   enum Kind { COUNTDOWN, START, CLOCK, HIT, FINISH, DISTANCE }
   static final class Event {
       final Kind kind;
       final int value;
       Event(Kind kind, int value) {
           this.kind = kind;
           this.value = value;
       }
   }
   static Event decode(int code) {
       if (code == 0x01) return new Event(Kind.START, 60);
       if (code == 0x02) return new Event(Kind.FINISH, 0);
       if (code >= 0x11 && code <= 0x15) return new Event(Kind.HIT, code - 0x10);
       if (code >= 0x21 && code <= 0x25) return new Event(Kind.COUNTDOWN, code - 0x20);
       if (code >= 0x40 && code <= 0x7C) return new Event(Kind.CLOCK, code - 0x40);
       return null;
   }
   static final class Round {
       Phase phase = Phase.WAITING;
       int points, balls, seconds = 60, lastHole, countdown;
       final int[] distance = {-2, -2, -2, -2, -2};
       final int[] nearest = {-2, -2, -2, -2, -2};
       boolean showSensors;
       String connection = "Opening " + PORT + "...";
       void accept(Event event) {
           switch (event.kind) {
               case COUNTDOWN:
                   if (phase != Phase.COUNTDOWN) reset();
                   phase = Phase.COUNTDOWN;
                   countdown = event.value;
                   break;
               case START:
                   reset();
                   phase = Phase.PLAYING;
                   break;
               case CLOCK:
                   if (phase == Phase.PLAYING) seconds = event.value;
                   break;
               case HIT:
                   if (phase == Phase.PLAYING) {
                       lastHole = event.value;
                       points += lastHole * 10;
                       balls++;
                   }
                   break;
               case DISTANCE:
                   int hole = event.value / 64;
                   int cm = event.value % 64;
                   distance[hole] = cm == 63 ? -1 : cm;
                   if (cm < 63 && (nearest[hole] < 0 || cm < nearest[hole])) nearest[hole] = cm;
                   break;
               case FINISH:
                   if (phase == Phase.PLAYING) {
                       phase = Phase.FINISHED;
                       seconds = 0;
                   }
                   break;
           }
       }
       private void reset() {
           java.util.Arrays.fill(distance, -2);
           java.util.Arrays.fill(nearest, -2);
           points = balls = lastHole = 0;
           seconds = 60;
       }
   }
   static final class Decoder {
       private int pendingHole = -1;
       Event receive(int code) {
           if (code >= 0x81 && code <= 0x85) {
               pendingHole = code - 0x81;
               return null;
           }
           int hole = pendingHole;
           pendingHole = -1;
           if (hole >= 0 && code >= 0xA0 && code <= 0xDF) {
               return new Event(Kind.DISTANCE, hole * 64 + code - 0xA0);
           }
           return decode(code);
       }
   }
   static final class SerialLink implements AutoCloseable {
       private final Process process;
       private volatile boolean closed;
       SerialLink(String port, Consumer<Event> observer, Consumer<String> status) throws IOException {
           ProcessBuilder builder = command();
           builder.environment().put("SKEEBALL_PORT", port);
           builder.redirectError(ProcessBuilder.Redirect.INHERIT);
           process = builder.start();
           Thread reader = new Thread(() -> read(observer, status), "skeeball-serial");
           reader.setDaemon(true);
           reader.start();
       }
       private void read(Consumer<Event> observer, Consumer<String> status) {
           try (InputStream input = process.getInputStream()) {
               Decoder decoder = new Decoder();
               int code;
               while (!closed && (code = input.read()) != -1) {
                   if (code == 0x7F) {
                       SwingUtilities.invokeLater(() -> status.accept("USB connected  /  " + PORT));
                       continue;
                   }
                   Event event = decoder.receive(code);
                   if (event != null) {
                       if (event.kind != Kind.DISTANCE) {
                           System.out.printf("Arduino 0x%02X  %s %d%n", code, event.kind, event.value);
                       }
                       SwingUtilities.invokeLater(() -> {
                           status.accept("USB connected  /  " + PORT);
                           observer.accept(event);
                       });
                   }
               }
               if (!closed) {
                   SwingUtilities.invokeLater(() -> status.accept("Serial closed  /  check " + PORT + " and Serial Monitor"));
               }
           } catch (IOException error) {
               if (!closed) SwingUtilities.invokeLater(() -> status.accept("USB error  /  " + error.getMessage()));
           }
       }
       private static ProcessBuilder command() {
           String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
           if (os.startsWith("win")) {
               String ps = "$ErrorActionPreference='Stop'; "
                       + "p=[System.IO.Ports.SerialPort]::new(env:SKEEBALL_PORT," + BAUD + "); "
                       + "try { $p.Open(); $out=[Console]::OpenStandardOutput(); "
                       + "$out.WriteByte(127); $out.Flush(); "
                       + "while ($true) { b=p.ReadByte(); out.WriteByte([byte]b); $out.Flush() } } "
                       + "finally { $p.Dispose() }";
               String encoded = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
               return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
           }
           String flag = os.contains("mac") ? "-f" : "-F";
           return new ProcessBuilder("sh", "-c", "stty " + flag + " \"$SKEEBALL_PORT\" "
                   + BAUD + " raw -echo -ixoff || exit 1; printf '\\177'; exec cat \"$SKEEBALL_PORT\"");
       }
       @Override
       public void close() {
           closed = true;
           process.destroy();
       }
   }
   static final class Scoreboard extends JPanel {
       static final int WIDTH = 1080, HEIGHT = 660;
       private static final Color WHITE = new Color(248, 248, 239);
       private static final Color MUTED = new Color(157, 174, 191);
       private static final Color LIME = new Color(254, 230, 93);
       private static final Color ORANGE = new Color(255, 72, 115);
       private static final Color PANEL = new Color(37, 28, 67);
       private static final Color BORDER = new Color(86, 67, 127);
       private final Round round;
       Scoreboard(Round round) {
           this.round = round;
           setPreferredSize(new Dimension(WIDTH, HEIGHT));
           setBackground(new Color(17, 11, 34));
       }
       @Override
       protected void paintComponent(Graphics graphics) {
           super.paintComponent(graphics);
           Graphics2D g = (Graphics2D) graphics.create();
           double scale = Math.min(getWidth() / (double) WIDTH, getHeight() / (double) HEIGHT);
           g.translate((getWidth() - WIDTH * scale) / 2, (getHeight() - HEIGHT * scale) / 2);
           g.scale(scale, scale);
           g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
           g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
           g.setPaint(new GradientPaint(0, 0, new Color(72, 25, 103), 850, 580, new Color(17, 11, 34)));
           g.fillRect(0, 0, WIDTH, HEIGHT);
           for (int row = 0; row < 3; row++) {
               for (int col = 0; col < 14; col++) {
                   g.setColor((row + col) % 2 == 0 ? WHITE : PANEL);
                   g.fillRect(52 + col * 10, 34 + row * 10, 10, 10);
               }
           }
           text(g, "SKEE-BALL GRAND PRIX", 52, 123, 46, WHITE, true);
           drawRaceCar(g, 708, 28);
           String phaseText;
           switch (round.phase) {
               case COUNTDOWN: phaseText = "GET READY"; break;
               case PLAYING: phaseText = "RACE IN PROGRESS"; break;
               case FINISHED: phaseText = "RACE COMPLETE"; break;
               default: phaseText = "WAITING TO START";
           }
           panel(g, 814, 78, 214, 46, PANEL);
           centered(g, phaseText, 921, 107, 16, LIME, true);
           panel(g, 52, 158, 644, 284, PANEL);
           panel(g, 716, 158, 312, 284, PANEL);
           boolean finished = round.phase == Phase.FINISHED;
           boolean countdown = round.phase == Phase.COUNTDOWN;
           text(g, finished ? "FINAL SCORE" : "PLAYER SCORE", 80, 202, 18, MUTED, true);
           centered(g, Integer.toString(round.points), 374, 344, 142, LIME, true);
           text(g, "BALLS SCORED   " + round.balls, 80, 409, 17, WHITE, true);
           text(g, countdown ? "STARTING IN" : "TIME LEFT", 744, 202, 18, MUTED, true);
           String time = countdown ? Integer.toString(round.countdown)
                   : String.format(java.util.Locale.ROOT, "%d:%02d", round.seconds / 60, round.seconds % 60);
           Color clock = round.seconds <= 10 && !finished ? ORANGE : WHITE;
           centered(g, time, 872, 318, 78, clock, true);
           g.setColor(BORDER);
           g.fillRoundRect(744, 381, 256, 10, 10, 10);
           int bar = countdown ? round.countdown * 256 / 5 : round.seconds * 256 / 60;
           g.setColor(LIME);
           g.fillRoundRect(744, 381, bar, 10, 10, 10);
           Color[] laneColors = {new Color(255, 90, 125), new Color(80, 235, 182),
                   new Color(91, 206, 255), LIME, new Color(229, 148, 255)};
           for (int hole = 1; hole <= 5; hole++) {
               int x = 52 + (hole - 1) * 198;
               boolean recent = hole == round.lastHole && round.phase == Phase.PLAYING;
               Color lane = laneColors[hole - 1];
               panel(g, x, 462, 184, 79, recent ? lane : PANEL);
               g.setColor(lane);
               g.fillRoundRect(x + 12, 475, 5, 50, 4, 4);
               int cm = round.distance[hole - 1];
               int low = round.nearest[hole - 1];
               Color ink = recent ? new Color(18, 17, 35) : lane;
               centered(g, "+" + hole * 10, x + 92, round.showSensors ? 496 : 512, 31, ink, true);
               if (round.showSensors) {
                   String reading = cm == -2 ? "Waiting for data" : cm == -1 ? "No echo"
                           : cm >= 62 ? "62+ cm" : cm + " cm";
                   if (low >= 0 && low < 62) reading += " / min " + low;
                   centered(g, reading, x + 92, 522, 13, recent ? ink : WHITE, false);
               }
           }
           String prompt;
           if (finished) prompt = "Press the start button to race again";
           else if (countdown) prompt = "Get your first ball ready";
           else if (round.phase == Phase.WAITING) prompt = "Press the start button on the machine";
           else prompt = round.lastHole == 0 ? "Roll your first ball" : "Last hit  +" + round.lastHole * 10 + " points";
           text(g, prompt, 52, 585, 22, WHITE, true);
           text(g, round.connection, 52, 625, 15, MUTED, false);
           text(g, round.showSensors ? "F2  Hide sensor readings" : "F2  Live sensor readings",
                   814, 625, 15, MUTED, true);
           g.dispose();
       }
       private static void drawRaceCar(Graphics2D g, int x, int y) {
           g.setColor(new Color(13, 10, 29));
           g.fillRoundRect(x + 16, y, 32, 15, 5, 5);
           g.fillRoundRect(x + 16, y + 27, 32, 15, 5, 5);
           g.fillRoundRect(x + 114, y + 2, 25, 13, 5, 5);
           g.fillRoundRect(x + 114, y + 27, 25, 13, 5, 5);
           g.setColor(ORANGE);
           g.fillPolygon(new int[] {x + 12, x + 49, x + 83, x + 112, x + 158, x + 158, x + 112, x + 81, x + 49, x + 12},
                         new int[] {y + 13, y + 8, y + 12, y + 16, y + 18, y + 24, y + 26, y + 32, y + 34, y + 28}, 10);
           g.fillRoundRect(x + 7, y + 1, 12, 40, 3, 3);
           g.fillRoundRect(x + 148, y + 8, 12, 26, 3, 3);
           g.setColor(LIME);
           g.fillRoundRect(x + 60, y + 16, 23, 11, 8, 8);
           g.fillRect(x + 100, y + 19, 43, 4);
       }
       private static void panel(Graphics2D g, int x, int y, int width, int height, Color fill) {
           g.setColor(fill);
           g.fillRoundRect(x, y, width, height, 22, 22);
           g.setColor(BORDER);
           g.setStroke(new BasicStroke(1));
           g.drawRoundRect(x, y, width, height, 22, 22);
       }
       private static void centered(Graphics2D g, String label, int x, int y, int size, Color color, boolean bold) {
           g.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
           FontMetrics metrics = g.getFontMetrics();
           text(g, label, x - metrics.stringWidth(label) / 2, y, size, color, bold);
       }
       private static void text(Graphics2D g, String label, int x, int y, int size, Color color, boolean bold) {
           g.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
           g.setColor(color);
           g.drawString(label, x, y);
       }
   }
   static void preview(String path, boolean finished) throws IOException {
       Round round = new Round();
       round.connection = "USB connected  /  " + PORT;
       round.accept(decode(0x01));
       for (int scoreByte : new int[] {0x13, 0x15, 0x11, 0x13, 0x14, 0x15}) {
           round.accept(decode(scoreByte));
       }
       round.accept(decode(finished ? 0x02 : 0x40 + 26));
       Scoreboard view = new Scoreboard(round);
       view.setSize(Scoreboard.WIDTH, Scoreboard.HEIGHT);
       BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
       Graphics2D graphics = image.createGraphics();
       view.paint(graphics);
       graphics.dispose();
       ImageIO.write(image, "png", new File(path));
   }
   public static void main(String[] args) throws IOException {
       if (args.length == 2 && ("--preview".equals(args[0]) || "--preview-final".equals(args[0]))) {
           preview(args[1], "--preview-final".equals(args[0]));
           return;
       }
       SwingUtilities.invokeLater(() -> {
           Round round = new Round();
           Scoreboard board = new Scoreboard(round);
           JFrame frame = new JFrame("Skee-Ball Grand Prix");
           frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
           frame.add(board);
           board.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
                   javax.swing.KeyStroke.getKeyStroke("F2"), "sensors");
           board.getActionMap().put("sensors", new javax.swing.AbstractAction() {
               @Override
               public void actionPerformed(java.awt.event.ActionEvent event) {
                   round.showSensors = !round.showSensors;
                   board.repaint();
               }
           });
           frame.pack();
           frame.setMinimumSize(new Dimension(650, 420));
           frame.setLocationRelativeTo(null);
           frame.setVisible(true);
           Consumer<String> status = message -> {
               round.connection = message;
               board.repaint();
           };
           try {
               SerialLink link = new SerialLink(PORT, event -> {
                   round.accept(event);
                   board.repaint();
               }, status);
               frame.addWindowListener(new WindowAdapter() {
                   @Override public void windowClosed(WindowEvent event) { link.close(); }
               });
           } catch (IOException error) {
               status.accept("USB error  /  " + error.getMessage());
           }
       });
   }
}

