package lab.webapp;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AppIntegrationTest {
    @TempDir Path directory;
    private Tomcat tomcat;
    private int port;
    private Path web;

    private void start() throws Exception {
        if (web == null) { web = directory.resolve("web"); Files.createDirectories(web.resolve("WEB-INF/db")); }
        tomcat = new Tomcat();
        tomcat.setBaseDir(directory.resolve("server").toString());
        tomcat.setPort(0); tomcat.getConnector();
        Context context = tomcat.addContext("/taskpebble", web.toString());
        Tomcat.addServlet(context, "app", new AppServlet()).setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "app");
        tomcat.start(); port = tomcat.getConnector().getLocalPort();
    }

    private void stop() throws Exception { tomcat.stop(); tomcat.destroy(); }

    private String call(String method, String path, String body, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/taskpebble" + path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream stream = connection.getOutputStream()) { stream.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            assertEquals(expected, connection.getResponseCode(), method + " " + path);
            InputStream source = expected >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (source == null) return "";
            try (InputStream stream = source; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int n;
                while ((n = stream.read(bytes)) != -1) buffer.write(bytes, 0, n);
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }

    @Test void requestsUseSqliteAndPersistAcrossRestart() throws Exception {
        start();
        try {
            assertTrue(call("GET", "/", null, 200).contains("TaskPebble"));
            assertTrue(call("GET", "/health", null, 200).contains("\"database\":\"UP\""));
            call("POST", "/create", "title=%3Cscript%3E%ED%85%8C%EC%8A%A4%ED%8A%B8&detail=TODO", 303);
            String html = call("GET", "/", null, 200);
            assertTrue(html.contains("&lt;script&gt;테스트")); assertFalse(html.contains("<script>테스트"));
            call("POST", "/create", "title=&detail=x", 400);
            call("POST", "/delete", "id=invalid", 400);
            call("GET", "/unknown", null, 404);
            call("GET", "/WEB-INF/db/taskpebble.sqlite", null, 404);
            assertTrue(Files.size(web.resolve("WEB-INF/db/taskpebble.sqlite")) > 0);
        } finally { stop(); }
        start();
        try {
            assertTrue(call("GET", "/", null, 200).contains("&lt;script&gt;테스트"));
            call("POST", "/delete", "id=2", 303);
            assertFalse(call("GET", "/", null, 200).contains("&lt;script&gt;테스트"));
        } finally { stop(); }
    }
}
