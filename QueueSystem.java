// Digital Queue Management System - Algeria Post
// Compile: javac QueueSystem.java | Run: java -cp .:mysql-connector-j.jar QueueSystem
// SQL:  CREATE DATABASE queue_db; USE queue_db;
//       CREATE TABLE tickets(id INT AUTO_INCREMENT PRIMARY KEY, service CHAR(1), num INT,
//         status ENUM('WAITING','SERVING','DONE') DEFAULT 'WAITING',
//         created TIMESTAMP DEFAULT NOW(), called TIMESTAMP NULL, finished TIMESTAMP NULL);
import javax.swing.*; import java.awt.*; import java.sql.*;

public class QueueSystem extends JFrame {
    static final String URL = "jdbc:mysql://localhost:3306/queue_db", USER = "root", PASS = "";
    static final String[][] SERVICES = {{"A", "Postal / Colis"}, {"B", "CCP / Financial"}, {"C", "Money Transfer"}};
    final JLabel now = new JLabel("Now serving: -", SwingConstants.CENTER), info = new JLabel(" ", SwingConstants.CENTER);
    final DefaultListModel<String> waiting = new DefaultListModel<>();

    QueueSystem() {
        super("Algeria Post - Queue Manager");
        setDefaultCloseOperation(EXIT_ON_CLOSE); setSize(760, 460); setLocationRelativeTo(null);
        JPanel kiosk = new JPanel(new GridLayout(0, 1, 8, 8)); kiosk.setBorder(BorderFactory.createTitledBorder("Customer kiosk"));
        for (String[] s : SERVICES) {
            JButton b = new JButton("Take ticket - " + s[1]); b.setFont(b.getFont().deriveFont(15f));
            b.addActionListener(e -> run(() -> issue(s[0])));
            kiosk.add(b);
        }
        JPanel desk = new JPanel(new BorderLayout(8, 8)); desk.setBorder(BorderFactory.createTitledBorder("Counter"));
        now.setFont(now.getFont().deriveFont(Font.BOLD, 28f));
        JButton next = new JButton("Call next"), done = new JButton("Finish current");
        next.addActionListener(e -> run(this::callNext)); done.addActionListener(e -> run(this::finish));
        JPanel btns = new JPanel(new GridLayout(1, 2, 8, 8)); btns.add(next); btns.add(done);
        desk.add(now, BorderLayout.NORTH); desk.add(new JScrollPane(new JList<>(waiting)), BorderLayout.CENTER);
        JPanel south = new JPanel(new GridLayout(2, 1)); south.add(info); south.add(btns); desk.add(south, BorderLayout.SOUTH);
        setLayout(new GridLayout(1, 2, 10, 10)); add(kiosk); add(desk);
        run(this::refresh);
        new Timer(5000, e -> run(this::refresh)).start();
    }

    Connection db() throws SQLException { return DriverManager.getConnection(URL, USER, PASS); }

    void issue(String svc) throws SQLException {
        try (Connection c = db()) {
            int n; try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(MAX(num),0)+1 FROM tickets WHERE service=? AND DATE(created)=CURDATE()")) {
                p.setString(1, svc); ResultSet r = p.executeQuery(); r.next(); n = r.getInt(1); }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO tickets(service,num) VALUES(?,?)")) {
                p.setString(1, svc); p.setInt(2, n); p.executeUpdate(); }
            int ahead; try (Statement s = c.createStatement()) { ResultSet r = s.executeQuery("SELECT COUNT(*) FROM tickets WHERE status='WAITING' AND DATE(created)=CURDATE()"); r.next(); ahead = r.getInt(1) - 1; }
            JOptionPane.showMessageDialog(this, "Your ticket: " + svc + String.format("%03d", n) + "\nPeople ahead: " + ahead + "\nEstimated wait: ~" + ahead * avgMinutes(c) + " min", "Ticket", JOptionPane.INFORMATION_MESSAGE);
        }
        refresh();
    }

    int avgMinutes(Connection c) throws SQLException {   // average service time, default 4 min
        try (Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("SELECT AVG(TIMESTAMPDIFF(MINUTE,called,finished)) FROM tickets WHERE status='DONE' AND DATE(created)=CURDATE()");
            r.next(); int v = (int) Math.ceil(r.getDouble(1)); return v > 0 ? v : 4;
        }
    }

    void callNext() throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE tickets SET status='DONE', finished=NOW() WHERE status='SERVING'");
            s.executeUpdate("UPDATE tickets SET status='SERVING', called=NOW() WHERE id=(SELECT id FROM (SELECT id FROM tickets WHERE status='WAITING' AND DATE(created)=CURDATE() ORDER BY id LIMIT 1) t)");
        }
        refresh();
    }

    void finish() throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement()) { s.executeUpdate("UPDATE tickets SET status='DONE', finished=NOW() WHERE status='SERVING'"); }
        refresh();
    }

    void refresh() throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("SELECT service,num FROM tickets WHERE status='SERVING' LIMIT 1");
            String cur = r.next() ? r.getString(1) + String.format("%03d", r.getInt(2)) : "-";
            waiting.clear(); r = s.executeQuery("SELECT service,num FROM tickets WHERE status='WAITING' AND DATE(created)=CURDATE() ORDER BY id");
            while (r.next()) waiting.addElement(r.getString(1) + String.format("%03d", r.getInt(2)));
            SwingUtilities.invokeLater(() -> { now.setText("Now serving: " + cur); info.setText(waiting.size() + " waiting"); });
        }
    }

    interface Job { void go() throws Exception; }
    void run(Job j) { new Thread(() -> { try { j.go(); } catch (Exception e) { JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage()); } }).start(); }

    public static void main(String[] a) { SwingUtilities.invokeLater(() -> new QueueSystem().setVisible(true)); }
}
