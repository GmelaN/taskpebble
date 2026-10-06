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
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            try {
                assertEquals(expected, connection.getResponseCode());
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
        public void close() throws Exception { server.stop(); server.destroy(); }
    }

    @Test void twoTomcatsShareMariaDbAndSurviveRestart() throws Exception {
        assumeTrue(System.getenv("MARIADB_TEST_URL") != null, "Set MARIADB_TEST_* to run the real MariaDB test");
        String marker = "replica-" + UUID.randomUUID();
        try (Replica first = new Replica("first"); Replica second = new Replica("second")) {
            assertTrue(first.call("/health", null, 200).contains("\"backend\":\"MariaDB\""));
            first.call("/create", "title=" + marker + "&detail=TODO", 303);
            assertTrue(second.call("/", null, 200).contains(marker));
            second.call("/create", "title=" + marker + "-reverse&detail=TODO", 303);
            assertTrue(first.call("/", null, 200).contains(marker + "-reverse"));
            assertFalse(Files.exists(first.web.resolve("WEB-INF/db/taskpebble.sqlite")));
            assertFalse(Files.exists(second.web.resolve("WEB-INF/db/taskpebble.sqlite")));
        }
        try (Replica restarted = new Replica("restarted")) {
            assertTrue(restarted.call("/", null, 200).contains(marker));
        }
    }
}
