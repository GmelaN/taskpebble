package lab.webapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {
    @TempDir Path root;
    private void env(String content) throws Exception {
        Files.write(root.resolve(".env"), content.getBytes(StandardCharsets.UTF_8));
    }
    @Test void rootEnvPreservesQuotedCredentialCharacters() throws Exception {
        env("\uFEFF# config\nexport DB_URL=jdbc:mariadb://db:3306/taskpebble\nDB_USER=apm\nDB_PASSWORD='sp ace#=한글'\n");
        DatabaseConfig c = DatabaseConfig.load(root, null);
        assertTrue(c.maria); assertEquals("sp ace#=한글", c.password);
        assertFalse(Files.exists(root.resolve("WEB-INF/db")));
    }
    @Test void missingDefaultUsesSqliteButExplicitMissingFails() throws Exception {
        assertFalse(DatabaseConfig.load(root, null).maria);
        assertThrows(IOException.class, () -> DatabaseConfig.load(root, root.resolve("missing.env").toString()));
    }
    @Test void invalidConfigNeverSilentlyFallsBackOrPrintsValues() throws Exception {
        env("DB_URL=jdbc:mariadb://db:3306/test\nDB_USER=user\n");
        assertThrows(IOException.class, () -> DatabaseConfig.load(root, null));
        env("DB_URL=jdbc:h2:mem:test\nDB_USER=user\nDB_PASSWORD=secret\n");
        assertThrows(IOException.class, () -> DatabaseConfig.load(root, null));
        env("DB_URL=jdbc:mariadb://db:3306/test\nDB_USER=user\nDB_PASSWORD=secret\nDB_PASSWORD=other\n");
        IOException error = assertThrows(IOException.class, () -> DatabaseConfig.load(root, null));
        assertFalse(error.getMessage().contains("secret")); assertFalse(error.getMessage().contains("other"));
    }
}
