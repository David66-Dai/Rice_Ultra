package com.smartrice.server.auth;

import com.smartrice.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 系统不开放注册：用户表为空时按配置创建初始管理员，之后不再干预。 */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AppProperties props;

	public AdminBootstrap(UserAccountRepository users, PasswordEncoder passwordEncoder, AppProperties props) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.props = props;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		AppProperties.BootstrapAdmin admin = props.getAuth().getBootstrapAdmin();
		if (!admin.isEnabled() || users.count() > 0) {
			return;
		}
		if (admin.getUsername() == null || admin.getUsername().isBlank()
				|| admin.getPassword() == null || admin.getPassword().isBlank()) {
			log.warn("用户表为空，但 app.auth.bootstrap-admin 未配置账号/密码，跳过初始化管理员");
			return;
		}

		UserAccount user = new UserAccount();
		user.setUsername(admin.getUsername().trim());
		user.setPasswordHash(passwordEncoder.encode(admin.getPassword()));
		user.setDisplayName(admin.getDisplayName() == null || admin.getDisplayName().isBlank()
			? admin.getUsername() : admin.getDisplayName());
		user.setRole("ADMIN");
		user.setEnabled(true);
		users.save(user);

		log.warn("已创建初始管理员账号 [{}]，请尽快修改 app.auth.bootstrap-admin.password 或数据库中的密码", user.getUsername());
	}
}
