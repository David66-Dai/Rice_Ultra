package com.smartrice.server.tools;

import com.smartrice.server.config.RiceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 离线创建 / 更新登录账号：BCrypt 哈希入库（带 {@code {bcrypt}} 前缀），不启动 Web 服务。
 *
 * <pre>
 *   .\create-user.ps1 -Username zhangsan -Password "Secret#123" -DisplayName "张三"
 *   .\mvnw.cmd -q -DskipTests compile exec:java "-Dexec.args=--username zhangsan --password Secret#123"
 * </pre>
 */
public final class CreateUserTool {

	private static final PasswordEncoder PASSWORD_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	private CreateUserTool() {
	}

	public static void main(String[] args) {
		int code = 1;
		try {
			code = run(args);
		} catch (IllegalArgumentException ex) {
			System.err.println(ex.getMessage());
			code = 2;
		} catch (Exception ex) {
			System.err.println("创建账号失败，请检查数据库连接、表结构和统一配置。");
			code = 1;
		}
		System.exit(code);
	}

	static int run(String[] args) throws Exception {
		Request request = Request.parse(args);
		if (request.help) {
			printHelp();
			return 0;
		}
		request.validate();

		DbConfig db = DbConfig.load(RiceConfiguration.loadEnvironment(request.configurationArgs.toArray(String[]::new)),
			System.getenv());
		try (Connection conn = db.open()) {
			conn.setAutoCommit(false);
			Long existingId = findUserId(conn, request.username);
			if (existingId != null && !request.update) {
				throw new IllegalArgumentException(
					"账号已存在：" + request.username + "。若要改密码或资料，请加 --update / -Update");
			}

			String hash = PASSWORD_ENCODER.encode(request.password);
			if (existingId == null) {
				insertUser(conn, request, hash);
				conn.commit();
				System.out.printf("已创建账号 %s（%s，角色 %s）%n",
					request.username, request.displayName, request.role);
			} else {
				updateUser(conn, existingId, request, hash);
				conn.commit();
				System.out.printf("已更新账号 %s（%s，角色 %s）%n",
					request.username, request.displayName, request.role);
			}
			return 0;
		}
	}

	private static Long findUserId(Connection conn, String username) throws SQLException {
		String sql = "SELECT id FROM user_account WHERE LOWER(username) = LOWER(?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, username);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : null;
			}
		}
	}

	private static void insertUser(Connection conn, Request request, String hash) throws SQLException {
		String sql = """
			INSERT INTO user_account
			  (username, password_hash, display_name, role, enabled, failed_attempts, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, 0, NOW(6), NOW(6))
			""";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, request.username);
			ps.setString(2, hash);
			ps.setString(3, request.displayName);
			ps.setString(4, request.role);
			ps.setBoolean(5, request.enabled);
			ps.executeUpdate();
		}
	}

	private static void updateUser(Connection conn, long id, Request request, String hash) throws SQLException {
		String sql = """
			UPDATE user_account
			SET password_hash = ?, display_name = ?, role = ?, enabled = ?,
			    failed_attempts = 0, locked_until = NULL, updated_at = NOW(6)
			WHERE id = ?
			""";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, hash);
			ps.setString(2, request.displayName);
			ps.setString(3, request.role);
			ps.setBoolean(4, request.enabled);
			ps.setLong(5, id);
			ps.executeUpdate();
		}
	}

	private static void printHelp() {
		System.out.println("""
			创建或更新数智稻安登录账号（写入 MySQL user_account，密码只存 BCrypt 哈希）

			用法：
			  .\\create-user.cmd -Username <账号> [-Password <密码>] [-DisplayName <显示名>] [-Role ADMIN] [-Update] [-Disabled]
			  .\\mvnw.cmd -q -DskipTests compile exec:java "-Dexec.args=--username <账号> --password <密码>"

			参数：
			  --username, -u       登录名（必填，1–64 字符）
			  --password, -p       明文密码（必填；也可设环境变量 CREATE_USER_PASSWORD）
			  --display-name, -n   显示名，默认与账号相同
			  --role, -r           角色，默认 ADMIN
			  --update             账号已存在时改为更新密码/资料
			  --disabled           创建为禁用状态
			  --help, -h           显示本说明

			配置：从当前目录向上查找 conf/config.yaml；RICE_CONFIG_PATH 可指定文件。
			支持 --rice.config.path=<文件>、--spring.profiles.active=dev 及 --spring.* 配置覆盖。
			也可用环境变量覆盖：CREATE_USER_DB_URL / CREATE_USER_DB_USERNAME / CREATE_USER_DB_PASSWORD
			""");
	}

	static final class Request {
		boolean help;
		boolean update;
		boolean enabled = true;
		String username;
		String password;
		String displayName;
		String role = "ADMIN";
		final List<String> configurationArgs = new ArrayList<>();

		static Request parse(String[] args) {
			Request req = fromEnv();
			int i = 0;
			while (i < args.length) {
				String arg = args[i];
				if (arg == null || arg.isBlank()) {
					i++;
					continue;
				}
				if (arg.startsWith("--spring.") || arg.equals("--rice.config.path") || arg.startsWith("--rice.config.path=")) {
					if (arg.contains("=")) {
						req.configurationArgs.add(arg);
					} else {
						req.configurationArgs.add(arg + "=" + requireValue(args, ++i, arg));
					}
					i++;
					continue;
				}
				switch (arg) {
					case "--help", "-h" -> req.help = true;
					case "--update" -> req.update = true;
					case "--disabled" -> req.enabled = false;
					case "--username", "-u" -> req.username = requireValue(args, ++i, arg);
					case "--password", "-p" -> req.password = requireValue(args, ++i, arg);
					case "--display-name", "-n" -> req.displayName = requireValue(args, ++i, arg);
					case "--role", "-r" -> req.role = requireValue(args, ++i, arg);
					default -> {
						if (arg.startsWith("-")) {
							throw new IllegalArgumentException("未知参数：" + arg + "。使用 --help 查看用法");
						}
						if (req.username == null) {
							req.username = arg;
						} else if (req.password == null) {
							req.password = arg;
						} else if (req.displayName == null) {
							req.displayName = arg;
						} else if ("ADMIN".equals(req.role)) {
							req.role = arg;
						} else {
							throw new IllegalArgumentException("多余参数：" + arg);
						}
					}
				}
				i++;
			}
			return req;
		}

		private static Request fromEnv() {
			Request req = new Request();
			req.username = env("CREATE_USER_USERNAME");
			String password = System.getenv("CREATE_USER_PASSWORD");
			req.password = (password == null || password.isEmpty()) ? null : password;
			req.displayName = env("CREATE_USER_DISPLAY_NAME");
			String role = env("CREATE_USER_ROLE");
			if (role != null) {
				req.role = role;
			}
			String update = env("CREATE_USER_UPDATE");
			if (update != null) {
				req.update = Boolean.parseBoolean(update);
			}
			String enabled = env("CREATE_USER_ENABLED");
			if (enabled != null) {
				req.enabled = Boolean.parseBoolean(enabled);
			}
			return req;
		}

		void validate() {
			username = trimToNull(username);
			displayName = trimToNull(displayName);
			role = trimToNull(role);
			if (username == null) {
				throw new IllegalArgumentException("请指定 --username 登录名");
			}
			if (username.length() > 64) {
				throw new IllegalArgumentException("登录名最长 64 个字符");
			}
			if (username.chars().anyMatch(Character::isWhitespace)) {
				throw new IllegalArgumentException("登录名不能包含空白");
			}
			if (password == null || password.isEmpty()) {
				throw new IllegalArgumentException("请指定 --password，或设置环境变量 CREATE_USER_PASSWORD");
			}
			if (password.length() < 8) {
				throw new IllegalArgumentException("密码至少 8 位");
			}
			if (password.length() > 128) {
				throw new IllegalArgumentException("密码最长 128 个字符");
			}
			if (displayName == null) {
				displayName = username;
			}
			if (displayName.length() > 64) {
				throw new IllegalArgumentException("显示名最长 64 个字符");
			}
			if (role == null) {
				role = "ADMIN";
			}
			role = role.toUpperCase(Locale.ROOT);
			if (role.length() > 32) {
				throw new IllegalArgumentException("角色最长 32 个字符");
			}
		}

		private static String requireValue(String[] args, int index, String flag) {
			if (index >= args.length) {
				throw new IllegalArgumentException(flag + " 需要一个值");
			}
			return args[index];
		}

		private static String env(String name) {
			return trimToNull(System.getenv(name));
		}

		private static String trimToNull(String value) {
			if (value == null) {
				return null;
			}
			String trimmed = value.trim();
			return trimmed.isEmpty() ? null : trimmed;
		}
	}

	static final class DbConfig {
		final String url;
		final String username;
		final String password;

		DbConfig(String url, String username, String password) {
			this.url = url;
			this.username = username;
			this.password = password;
		}

		Connection open() throws SQLException {
			Properties props = new Properties();
			props.setProperty("user", username);
			props.setProperty("password", password == null ? "" : password);
			props.setProperty("characterEncoding", "utf8");
			props.setProperty("connectionTimeZone", "Asia/Shanghai");
			try {
				return DriverManager.getConnection(url, props);
			} catch (SQLException ex) {
				throw new SQLException("无法连接数据库，请检查统一配置及数据库服务，并确认已初始化账号表。");
			}
		}

		static DbConfig load(Environment environment, Map<String, String> overrides) {
			String url = trimToNull(overrides.get("CREATE_USER_DB_URL"));
			String username = trimToNull(overrides.get("CREATE_USER_DB_USERNAME"));
			String password = overrides.get("CREATE_USER_DB_PASSWORD");
			try {
				if (url == null) {
					url = trimToNull(environment.getProperty("spring.datasource.url"));
				}
				if (username == null) {
					username = trimToNull(environment.getProperty("spring.datasource.username"));
				}
				if (password == null) {
					password = environment.getProperty("spring.datasource.password");
				}
			}
			catch (RuntimeException ex) {
				// Placeholder errors can include the full configured URL or password.
				throw new IllegalArgumentException("无法解析数据库配置，请检查统一配置中的占位符和环境变量。");
			}
			if (url == null || username == null) {
				throw new IllegalArgumentException(
					"统一配置缺少数据库连接信息，请检查当前 profile 或 CREATE_USER_DB_URL / CREATE_USER_DB_USERNAME。");
			}
			return new DbConfig(url, username, password == null ? "" : password);
		}

		private static String trimToNull(String value) {
			if (value == null) {
				return null;
			}
			String trimmed = value.trim();
			return trimmed.isEmpty() ? null : trimmed;
		}
	}
}
