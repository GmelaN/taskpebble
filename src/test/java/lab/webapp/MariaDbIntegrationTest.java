package lab.webapp;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** CI provisions a real MariaDB; no embedded database substitutes for this test. */
class MariaDbIntegrationTest {
    @TempDir Path root;

    private final class Replica implements AutoCloseable {
        private final Tomcat server;
        private final int port;
        private final Path web;
        private String cookie, csrf;
        Replica(String name) throws Exception {
            web = root.resolve(name); Files.createDirectories(web.resolve("WEB-INF"));
            Path env = root.resolve(name + ".env");
            Files.write(env, ("DB_URL=" + System.getenv("MARIADB_TEST_URL") + "\nDB_USER=" + System.getenv("MARIADB_TEST_USER")
                + "\nDB_PASSWORD='" + System.getenv("MARIADB_TEST_PASSWORD") + "'\n").getBytes(StandardCharsets.UTF_8));
            server = new Tomcat(); server.setBaseDir(root.resolve(name + "-server").toString());
            server.setPort(0); server.getConnector();
            Context context = server.addContext("/taskpebble", web.toString());
            Wrapper servlet = Tomcat.addServlet(context, "app", new AppServlet());
            servlet.setLoadOnStartup(1); servlet.addInitParameter("envFile", env.toString());
            context.addServletMappingDecoded("/", "app");
            server.start(); port = server.getConnector().getLocalPort();
        }
        String call(String path, String body, int expected) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/taskpebble" + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(60000);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (body != null) {
                body += "&csrf=" + csrf;
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            try {
                assertEquals(expected, connection.getResponseCode());
                String set = connection.getHeaderField("Set-Cookie");
                if (set != null) cookie = set.split(";")[0];
                try (InputStream in = expected >= 400 ? connection.getErrorStream() : connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                    String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    java.util.regex.Matcher token = java.util.regex.Pattern.compile("name='csrf' value='([^']+)'").matcher(html);
                    if (token.find()) csrf = token.group(1);
                    return html;
                }
            } finally { connection.disconnect(); }
        }
        void account(String path, String name) throws Exception {
            call("/login", null, 200);
            call(path, "username=" + name + "&password=correct-password-123", 303);
            call("/", null, 200);
        }
        public void close() throws Exception { server.stop(); server.destroy(); }
    }

    @Test void twoTomcatsShareMariaDbAndSurviveRestart() throws Exception {
        assumeTrue(System.getenv("MARIADB_TEST_URL") != null, "Set MARIADB_TEST_* to run the real MariaDB test");
        String username = "u" + UUID.randomUUID().toString().replace("-", "");
        String marker = "replica-" + UUID.randomUUID();
        try (Replica first = new Replica("first"); Replica second = new Replica("second")) {
            assertTrue(first.call("/health", null, 200).contains("\"backend\":\"MariaDB\""));
            first.account("/register", username);
            second.account("/login", username);
            first.call("/create", "title=" + marker + "&detail=TODO", 303);
            assertTrue(second.call("/", null, 200).contains(marker));
            second.call("/create", "title=" + marker + "-reverse&detail=TODO", 303);
            assertTrue(first.call("/", null, 200).contains(marker + "-reverse"));
            try (Replica other = new Replica("other")) {
                other.account("/register", "v" + UUID.randomUUID().toString().replace("-", ""));
                assertFalse(other.call("/", null, 200).contains(marker));
                java.util.regex.Matcher id = java.util.regex.Pattern.compile("name='id' value='(\\d+)'").matcher(first.call("/", null, 200));
                assertTrue(id.find()); other.call("/delete", "id=" + id.group(1), 404);
            }
            assertFalse(Files.exists(first.web.resolve("WEB-INF/db/taskpebble.sqlite")));
            assertFalse(Files.exists(second.web.resolve("WEB-INF/db/taskpebble.sqlite")));
        }
        try (Replica restarted = new Replica("restarted")) {
            restarted.account("/login", username);
            assertTrue(restarted.call("/", null, 200).contains(marker));
        }
    }
}
