package lab.webapp;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class AppIntegrationTest {
    @TempDir Path directory;
    private Tomcat tomcat;
    private int port;
    private Path web;
    private Context context;
    private void start() throws Exception {
        if (web == null) { web = directory.resolve("web"); Files.createDirectories(web.resolve("WEB-INF/db")); }
        tomcat = new Tomcat(); tomcat.setBaseDir(directory.resolve("server").toString());
        tomcat.setPort(0); tomcat.getConnector();
        context = tomcat.addContext("/taskpebble", web.toString());
        context.setUseHttpOnly(true);
        Tomcat.addServlet(context, "app", new AppServlet()).setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "app");
        tomcat.start(); port = tomcat.getConnector().getLocalPort();
    }
    private void stop() throws Exception { tomcat.stop(); tomcat.destroy(); }
    private final class Client {
        String cookie, csrf;
        String call(String method, String path, String body, int expected) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/taskpebble" + path).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(60000); c.setRequestMethod(method);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            if (body != null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream stream = c.getOutputStream()) { stream.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            try {
                assertEquals(expected, c.getResponseCode(), method + " " + path);
                String set = c.getHeaderField("Set-Cookie");
                if (set != null) { assertTrue(set.toLowerCase().contains("httponly")); cookie = set.split(";")[0]; }
                InputStream source = expected >= 400 ? c.getErrorStream() : c.getInputStream();
                if (source == null) return "";
                try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    Matcher token = Pattern.compile("name='csrf' value='([^']+)'").matcher(html);
                    if (token.find()) csrf = token.group(1);
                    return html;
                }
            } finally { c.disconnect(); }
        }
        String post(String path, String body, int expected) throws Exception { return call("POST", path, body + "&csrf=" + csrf, expected); }
        void account(String path, String name) throws Exception {
            call("GET", "/login", null, 200); String old = cookie;
            post(path, "username=" + name + "&password=correct-password-123", 303);
            assertNotEquals(old, cookie); call("GET", "/", null, 200);
        }
    }
    @Test void sessionsIsolateUsersAndPersistDataAcrossRestart() throws Exception {
        start();
        Client alice = new Client(), bob = new Client(), guest = new Client();
        try {
            guest.call("GET", "/", null, 303);
            guest.call("POST", "/create", "title=bad&detail=TODO", 401);
            guest.call("POST", "/delete", "id=1", 401);
            assertTrue(guest.call("GET", "/health", null, 200).contains("\"database\":\"UP\""));
            guest.call("GET", "/login", null, 200);
            guest.call("POST", "/register", "username=alice&password=correct-password-123", 403);
            alice.account("/register", "alice");
            bob.account("/register", "bob");
            guest.post("/register", "username=alice&password=correct-password-123", 409);
            guest.post("/login", "username=alice&password=wrong-password-123", 401);
            guest.post("/register", "username=bad&password=short", 400);
            alice.call("POST", "/create", "title=bad&detail=TODO&csrf=wrong", 403);
            alice.post("/create", "title=%3Cscript%3E%ED%85%8C%EC%8A%A4%ED%8A%B8&detail=TODO&userId=2", 303);
            String html = alice.call("GET", "/", null, 200);
            assertTrue(html.contains("&lt;script&gt;테스트")); assertFalse(html.contains("<script>테스트"));
            assertFalse(bob.call("GET", "/", null, 200).contains("&lt;script&gt;테스트"));
            bob.post("/delete", "id=1", 404);
            assertTrue(alice.call("GET", "/", null, 200).contains("&lt;script&gt;테스트"));
            bob.post("/create", "title=bob-only&detail=TODO", 303);
            assertFalse(alice.call("GET", "/", null, 200).contains("bob-only"));
            alice.post("/create", "title=&detail=x", 400);
            alice.post("/delete", "id=invalid", 400);
            alice.call("GET", "/unknown", null, 404);
            alice.call("GET", "/WEB-INF/db/taskpebble.sqlite", null, 404);
            alice.call("GET", "/.env", null, 404);
            String stale = alice.cookie;
            alice.post("/logout", "", 303);
            alice.cookie = stale; alice.call("GET", "/", null, 303);
            alice.call("POST", "/delete", "id=1", 401);
            // Existing unowned rows remain in the DB and never appear for new accounts.
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + web.resolve("WEB-INF/db/taskpebble.sqlite")); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO entries(title,detail) VALUES ('legacy-private','TODO')");
                try (ResultSet rs = s.executeQuery("SELECT password_hash FROM app_users")) {
                    while (rs.next()) { assertTrue(rs.getString(1).startsWith("600000$")); assertFalse(rs.getString(1).contains("correct-password")); }
                }
            }
        } finally { stop(); }
        start();
        try {
            alice.account("/login", "alice"); bob.account("/login", "bob");
            String html = alice.call("GET", "/", null, 200);
            assertTrue(html.contains("&lt;script&gt;테스트")); assertFalse(html.contains("legacy-private"));
            assertFalse(bob.call("GET", "/", null, 200).contains("legacy-private"));
            alice.post("/delete", "id=1", 303);
            assertFalse(alice.call("GET", "/", null, 200).contains("&lt;script&gt;테스트"));
            assertTrue(bob.call("GET", "/", null, 200).contains("bob-only"));
            String sessionId = bob.cookie.substring(bob.cookie.indexOf('=') + 1);
            org.apache.catalina.Session session = context.getManager().findSession(sessionId);
            assertEquals(1800, session.getMaxInactiveInterval());
            session.expire();
            bob.call("GET", "/", null, 303);
            bob.call("POST", "/delete", "id=2", 401);
        } finally { stop(); }
    }
}
