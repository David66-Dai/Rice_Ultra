package com.smartrice.server.auth;

import com.smartrice.server.config.AppProperties;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** 签发 HS256 访问令牌。校验由 Spring Security 资源服务器（JwtDecoder）完成。 */
@Service
public class JwtService {

	public static final String ISSUER = "smart-rice-security";
	public static final String CLAIM_UID = "uid";
	public static final String CLAIM_ROLE = "role";
	public static final String CLAIM_NAME = "name";

	private final JwtEncoder encoder;
	private final AppProperties props;

	public JwtService(JwtEncoder encoder, AppProperties props) {
		this.encoder = encoder;
		this.props = props;
	}

	public record IssuedToken(String token, long expiresInSeconds) {
	}

	public IssuedToken issue(UserAccount user) {
		Instant now = Instant.now();
		Instant expiresAt = now.plus(props.getAuth().getAccessTokenTtl());

		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.issuedAt(now)
			.expiresAt(expiresAt)
			.subject(user.getUsername())
			.claim(CLAIM_UID, user.getId())
			.claim(CLAIM_ROLE, user.getRole())
			.claim(CLAIM_NAME, user.getDisplayName())
			.build();

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, expiresAt.getEpochSecond() - now.getEpochSecond());
	}
}
