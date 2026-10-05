package lab.webapp;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.UUID;

@WebServlet(value = "/", loadOnStartup = 1)
public class AppServlet extends HttpServlet {
    private String jdbcUrl;
    private String instance;

    @Override public void init() throws ServletException {
        try {
            String directory = getServletContext().getRealPath("/WEB-INF/db");
            if (directory == null) throw new ServletException("Tomcat unpackWARs=true is required");
            Path dbDirectory = Paths.get(directory);
            Files.createDirectories(dbDirectory);
            jdbcUrl = "jdbc:sqlite:" + dbDirectory.resolve("taskpebble.sqlite").toAbsolutePath();
            String configured = System.getenv("APM_INSTANCE_NAME");
            instance = configured == null || configured.trim().isEmpty()
                ? System.getProperty("catalina.base", "local") : configured;
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS entries (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, detail TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                try (PreparedStatement seed = connection.prepareStatement("INSERT INTO entries(title, detail) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM entries)")) {
                    seed.setString(1, "Deploy webapp"); seed.setString(2, "TODO"); seed.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new ServletException("TaskPebble database initialization failed", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 3000");
        } catch (SQLException e) { connection.close(); throw e; }
        return connection;
    }

    private void begin(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Request-ID", UUID.randomUUID().toString());
    }

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        begin(response);
        String path = request.getServletPath();
        if ("/health".equals(path)) {
            response.setContentType("application/json");
            try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT 1")) {
                if (!rs.next()) throw new SQLException("Database health query returned no row");
                response.getWriter().write("{\"app\":\"TaskPebble\",\"status\":\"UP\",\"database\":\"UP\"}");
            } catch (SQLException e) {
                getServletContext().log("Health check failed", e);
                response.setStatus(503);
                response.getWriter().write("{\"app\":\"TaskPebble\",\"status\":\"DOWN\",\"database\":\"DOWN\"}");
            }
            return;
        }
        if (!"/".equals(path) && !path.isEmpty()) { response.sendError(404); return; }
        response.setContentType("text/html");
        // Complete SQL work before writing a successful response.
        StringBuilder rows = new StringBuilder();
        try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT id,title,detail,created_at FROM entries ORDER BY id DESC LIMIT 100")) {
            while (rs.next()) {
                rows.append("<tr><td>").append(rs.getLong("id")).append("</td><td>").append(escape(rs.getString("title")))
                    .append("</td><td class='detail'>").append(escape(rs.getString("detail")))
                    .append("</td><td>").append(escape(rs.getString("created_at")))
                    .append("</td><td><form method='post' action='").append(escape(request.getContextPath())).append("/delete'>")
                    .append("<input type='hidden' name='id' value='").append(rs.getLong("id"))
                    .append("'><button class='delete'>삭제</button></form></td></tr>");
            }
        } catch (SQLException e) { fail(response, e); return; }
        PrintWriter out = response.getWriter();
        out.println("<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>TaskPebble</title>");
        out.println("<style>body{font:16px system-ui,sans-serif;background:#f3f6fa;color:#203048;margin:0}main{max-width:960px;margin:48px auto;padding:24px}h1{margin-bottom:8px}section{background:white;padding:24px;border-radius:12px;margin:24px 0}label{display:block;margin:12px 0}input,textarea,select{box-sizing:border-box;width:100%;padding:10px;border:1px solid #c9d2df;border-radius:5px}button{background:#2463ac;color:white;border:0;padding:10px 18px;border-radius:5px;cursor:pointer}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:12px;border-bottom:1px solid #dde4ee;overflow-wrap:anywhere}.detail{white-space:pre-wrap}.delete{background:#52647a}small{color:#627187}a{color:#2463ac}</style></head><body><main>");
        out.println("<small>APM TESTBED · PROJECT C</small><h1>TaskPebble</h1><p>독립형 작업 기록</p><small>Instance: " + escape(instance) + "</small>");
        out.println("<section><h2>등록</h2><form method='post' action='" + escape(request.getContextPath()) + "/create'><label>작업명<input name='title' maxlength='120' required></label><label>상태<select name='detail'><option>TODO</option><option>DONE</option></select></label><button>등록</button></form></section>");
        out.println("<section><h2>최근 100건</h2><table><thead><tr><th>ID</th><th>작업명</th><th>상태</th><th>등록 시간 (UTC)</th><th></th></tr></thead><tbody>" + rows + "</tbody></table></section>");
        out.println("<a href='" + escape(request.getContextPath()) + "/health'>DB 상태 확인</a></main></body></html>");
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8"); begin(response);
        String path = request.getServletPath();
        try {
            if ("/create".equals(path)) {
                String title = request.getParameter("title"), detail = request.getParameter("detail");
                if (title == null || title.trim().isEmpty() || title.length() > 120 || detail == null || detail.trim().isEmpty() || detail.length() > 1000 || (!"TODO".equals(detail) && !"DONE".equals(detail))) {
                    response.sendError(400, "Invalid title or detail"); return;
                }
                try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("INSERT INTO entries(title,detail) VALUES (?,?)")) {
                    statement.setString(1, title.trim()); statement.setString(2, detail.trim()); statement.executeUpdate();
                }
            } else if ("/delete".equals(path)) {
                long id;
                try { id = Long.parseLong(request.getParameter("id")); }
                catch (NumberFormatException e) { response.sendError(400, "Invalid id"); return; }
                if (id <= 0) { response.sendError(400, "Invalid id"); return; }
                try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("DELETE FROM entries WHERE id=?")) {
                    statement.setLong(1, id);
                    if (statement.executeUpdate() == 0) { response.sendError(404); return; }
                }
            } else { response.sendError(404); return; }
            response.setStatus(303); response.setHeader("Location", request.getContextPath() + "/");
        } catch (SQLException e) { fail(response, e); }
    }

    private void fail(HttpServletResponse response, SQLException e) throws IOException {
        getServletContext().log("TaskPebble database operation failed", e);
        response.sendError(500, "Database operation failed");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
