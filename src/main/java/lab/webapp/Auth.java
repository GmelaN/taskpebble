package lab.webapp;

import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;

/** App-local accounts; identity is read exclusively from the server session. */
final class Auth {
    private static final SecureRandom RANDOM = new SecureRandom();
    static void initialize(Connection c, boolean maria) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(maria
                ? "CREATE TABLE IF NOT EXISTS app_users (id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE, password_hash VARCHAR(200) NOT NULL) ENGINE=InnoDB"
                : "CREATE TABLE IF NOT EXISTS app_users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL)");
            s.executeUpdate(maria
                ? "CREATE TABLE IF NOT EXISTS entry_owners (entry_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, INDEX owner_lookup(user_id)) ENGINE=InnoDB"
                : "CREATE TABLE IF NOT EXISTS entry_owners (entry_id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL)");
            if (!maria) s.executeUpdate("CREATE INDEX IF NOT EXISTS owner_lookup ON entry_owners(user_id)");
        }
    }
    static Long user(HttpServletRequest r) {
        HttpSession s = r.getSession(false);
        return s == null ? null : (Long) s.getAttribute("userId");
    }
    static String token(HttpServletRequest r) {
        HttpSession s = r.getSession(true);
        s.setMaxInactiveInterval(1800);
        if (s.getAttribute("csrf") == null) s.setAttribute("csrf", random());
        return (String) s.getAttribute("csrf");
    }
    static String field(HttpServletRequest r) {
        return "<input type='hidden' name='csrf' value='" + token(r) + "'>";
    }
    static boolean csrf(HttpServletRequest r, HttpServletResponse p) throws IOException {
        HttpSession s = r.getSession(false);
        String supplied = r.getParameter("csrf");
        if (s == null || supplied == null || !supplied.equals(s.getAttribute("csrf"))) {
            p.sendError(403, "Invalid CSRF token"); return false;
        }
        return true;
    }
    static void page(HttpServletRequest r, HttpServletResponse p, String appName) throws IOException {
        p.setContentType("text/html");
        String base = r.getContextPath();
        p.getWriter().println("<!doctype html><html lang='ko'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>로그인</title><style>body{font:16px system-ui;max-width:480px;margin:60px auto;padding:24px}label{display:block;margin:16px 0}input{display:block;padding:10px}button{padding:10px 20px}</style><h1>" + appName + " 로그인</h1><p>이 앱의 계정으로 로그인하세요. 사용자별 데이터가 따로 저장됩니다.</p>"
            + form(base + "/login", r, "로그인") + "<h2>회원가입</h2><p>아이디: 영문 소문자·숫자·밑줄 3–40자. 비밀번호: 12–128자.</p>" + form(base + "/register", r, "회원가입") + "</html>");
    }
    private static String form(String action, HttpServletRequest r, String label) {
        return "<form method='post' action='" + action + "'>" + field(r)
            + "<label>아이디<input name='username' minlength='3' maxlength='40' pattern='[a-z0-9_]{3,40}' autocomplete='username' required></label><label>비밀번호<input type='password' name='password' minlength='12' maxlength='128' autocomplete='" + ("회원가입".equals(label) ? "new-password" : "current-password") + "' required></label><button>" + label + "</button></form>";
    }
    static void account(Connection c, HttpServletRequest r, HttpServletResponse p, boolean register) throws SQLException, IOException {
        String name = r.getParameter("username"), password = r.getParameter("password");
        if (name == null || !name.matches("[a-z0-9_]{3,40}") || password == null || password.length() < 12 || password.length() > 128) {
            p.sendError(400, "Invalid username or password length"); return;
        }
        long id;
        if (register) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO app_users(username,password_hash) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, name); s.setString(2, hash(password));
                try { s.executeUpdate(); }
                catch (SQLException e) {
                    if (e.getErrorCode() == 19 || "23000".equals(e.getSQLState())) { p.sendError(409, "Username already exists"); return; }
                    throw e;
                }
                try (ResultSet keys = s.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Missing user key"); id = keys.getLong(1); }
            }
        } else {
            try (PreparedStatement s = c.prepareStatement("SELECT id,password_hash FROM app_users WHERE username=?")) {
                s.setString(1, name);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) { hash(password); p.sendError(401, "Invalid credentials"); return; }
                    if (!verify(password, rs.getString(2))) { p.sendError(401, "Invalid credentials"); return; }
                    id = rs.getLong(1);
                }
            }
        }
        r.getSession().invalidate();
        HttpSession session = r.getSession(true);
        session.setMaxInactiveInterval(1800); session.setAttribute("userId", id); session.setAttribute("username", name);
        session.setAttribute("csrf", random());
        redirect(r, p, "/");
    }
    static void redirect(HttpServletRequest r, HttpServletResponse p, String path) {
        p.setStatus(303); p.setHeader("Location", r.getContextPath() + path);
    }
    private static String random() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String hash(String password) {
        byte[] salt = new byte[32]; RANDOM.nextBytes(salt);
        return "600000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, 600000));
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
    private static boolean verify(String password, String hash) {
        String[] parts = hash.split("\\$");
        return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]), derive(password, Base64.getDecoder().decode(parts[1]), Integer.parseInt(parts[0])));
    }
}
