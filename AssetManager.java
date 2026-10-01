// IT Asset Management Web App - Java 17+, no external libraries.
// Run: java AssetManager.java  ->  http://localhost:8080   (data saved in assets.csv)
import com.sun.net.httpserver.*;
import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets;
import java.nio.file.*; import java.util.*; import java.util.stream.Collectors;

public class AssetManager {
    static final Path DB = Path.of("assets.csv");
    static final String HDR = "id,type,name,serial,assignedTo,status,nextMaintenance";

    static String clean(String s) { return s == null ? "" : s.replaceAll("[,\\r\\n\"\\\\]", " ").trim(); }

    static synchronized List<String[]> load() throws IOException {
        if (!Files.exists(DB)) Files.writeString(DB, HDR + "\n");
        return Files.readAllLines(DB).stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split(",", -1)).collect(Collectors.toList());
    }
    static synchronized void save(List<String[]> rows) throws IOException {
        List<String> out = new ArrayList<>(List.of(HDR)); rows.forEach(r -> out.add(String.join(",", r))); Files.write(DB, out);
    }
    static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) { String[] p = kv.split("=", 2); if (p.length == 2) m.put(p[0], clean(URLDecoder.decode(p[1], StandardCharsets.UTF_8))); }
        return m;
    }
    static void send(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type + "; charset=utf-8"); x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress(8080), 0);
        srv.createContext("/", x -> send(x, 200, "text/html", PAGE));
        srv.createContext("/api/assets", x -> {
            try {
                String q = Optional.ofNullable(x.getRequestURI().getQuery()).orElse("");
                List<String[]> rows = load();
                switch (x.getRequestMethod()) {
                    case "POST" -> {
                        Map<String, String> f = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        int id = rows.stream().mapToInt(r -> Integer.parseInt(r[0])).max().orElse(0) + 1;
                        rows.add(new String[]{"" + id, f.getOrDefault("type", ""), f.getOrDefault("name", ""), f.getOrDefault("serial", ""),
                                f.getOrDefault("assignedTo", ""), f.getOrDefault("status", "In use"), f.getOrDefault("nextMaintenance", "")});
                        save(rows);
                    }
                    case "DELETE" -> { String id = q.replace("id=", ""); rows.removeIf(r -> r[0].equals(id)); save(rows); }
                    default -> {}
                }
                String json = load().stream().map(r -> "{\"id\":" + r[0] + ",\"type\":\"" + r[1] + "\",\"name\":\"" + r[2] + "\",\"serial\":\"" + r[3]
                        + "\",\"assignedTo\":\"" + r[4] + "\",\"status\":\"" + r[5] + "\",\"nextMaintenance\":\"" + r[6] + "\"}").collect(Collectors.joining(",", "[", "]"));
                send(x, 200, "application/json", json);
            } catch (Exception e) { send(x, 500, "text/plain", "Error: " + e.getMessage()); }
        });
        srv.start(); System.out.println("Running on http://localhost:8080");
    }

    static final String PAGE = """
<!DOCTYPE html><html><head><meta charset="utf-8"><title>IT Asset Manager</title><style>
body{font-family:system-ui;max-width:1000px;margin:30px auto;padding:0 16px;background:#f4f7f6}
form{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px;margin-bottom:20px}
input,select,button{padding:8px;border:1px solid #bbb;border-radius:6px}button{background:#2c3e50;color:#fff;cursor:pointer}
table{width:100%;border-collapse:collapse;background:#fff}td,th{padding:8px;border-bottom:1px solid #ddd;text-align:left}
.due{background:#ffe3e3}</style></head><body><h1>IT Asset Manager</h1>
<form id="f"><select name="type"><option>Workstation</option><option>Router</option><option>Switch</option><option>Server</option><option>Software license</option></select>
<input name="name" placeholder="Name / model" required><input name="serial" placeholder="Serial / key"><input name="assignedTo" placeholder="Assigned to">
<select name="status"><option>In use</option><option>In stock</option><option>Under repair</option><option>Retired</option></select>
<input type="date" name="nextMaintenance" title="Next maintenance"><button>Add asset</button></form>
<table><thead><tr><th>Type<th>Name<th>Serial<th>Assigned to<th>Status<th>Next maintenance<th></thead><tbody id="t"></tbody></table>
<script>
const esc=s=>s.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
async function load(){const r=await (await fetch('/api/assets')).json(),today=new Date().toISOString().slice(0,10);
t.innerHTML=r.map(a=>`<tr class="${a.nextMaintenance&&a.nextMaintenance<=today?'due':''}"><td>${esc(a.type)}<td>${esc(a.name)}<td>${esc(a.serial)}<td>${esc(a.assignedTo)}<td>${esc(a.status)}<td>${a.nextMaintenance}<td><button onclick="del(${a.id})">Delete</button>`).join('')}
async function del(id){await fetch('/api/assets?id='+id,{method:'DELETE'});load()}
f.onsubmit=async e=>{e.preventDefault();await fetch('/api/assets',{method:'POST',body:new URLSearchParams(new FormData(f))});f.reset();load()};load();
</script></body></html>""";
}
