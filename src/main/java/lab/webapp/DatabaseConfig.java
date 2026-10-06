package lab.webapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reads an external .env at startup; credentials are never bundled in the WAR. */
final class DatabaseConfig {
    final String url, user, password;
    final boolean maria;

    private DatabaseConfig(String url, String user, String password, boolean maria) {
        this.url = url; this.user = user; this.password = password; this.maria = maria;
    }

    static DatabaseConfig load(Path webRoot, String explicitEnv) throws IOException {
        boolean explicit = explicitEnv != null && !explicitEnv.trim().isEmpty();
        Path env = explicit ? Paths.get(explicitEnv).toAbsolutePath() : webRoot.resolve(".env");
        if (!Files.exists(env)) {
            if (explicit) throw new IOException("Configured .env file does not exist");
            Path db = webRoot.resolve("WEB-INF/db"); Files.createDirectories(db);
            return new DatabaseConfig("jdbc:sqlite:" + db.resolve("taskpebble.sqlite").toAbsolutePath(), "", "", false);
        }
        Map<String, String> values = new HashMap<>(); int number = 0;
        for (String raw : Files.readAllLines(env, StandardCharsets.UTF_8)) {
            number++;
            String line = raw.trim();
            if (number == 1 && line.startsWith("\uFEFF")) line = line.substring(1).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int separator = line.indexOf('=');
            if (separator < 1) throw invalidLine(number);
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || values.containsKey(key)) throw invalidLine(number);
            if (value.startsWith("\"") || value.startsWith("'")) {
                if (value.length() < 2 || value.charAt(value.length() - 1) != value.charAt(0)) throw invalidLine(number);
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        String url = required(values, "DB_URL", false);
        String user = required(values, "DB_USER", false);
        String password = required(values, "DB_PASSWORD", true);
        if (!url.startsWith("jdbc:mariadb://")) throw new IOException("DB_URL must use jdbc:mariadb://");
        return new DatabaseConfig(url, user, password, true);
    }

    private static String required(Map<String, String> values, String key, boolean allowEmpty) throws IOException {
        String value = values.get(key);
        if (value == null || (!allowEmpty && value.isEmpty())) throw new IOException("Missing .env key: " + key);
        return value;
    }

    private static IOException invalidLine(int number) {
        return new IOException("Invalid .env syntax at line " + number);
    }
}
