package com.smartrice.server.camera;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 浏览器抓拍网络摄像头时会被同源策略挡住（画布被污染），由服务端代取一帧再交给前端。
 * 代理任意地址存在 SSRF 风险，因此只放行 {@link CameraProxyProperties} 配置的网段。
 */
@Service
public class CameraSnapshotService {

	private static final byte[] JPEG_START = { (byte) 0xFF, (byte) 0xD8 };

	private static final byte[] JPEG_END = { (byte) 0xFF, (byte) 0xD9 };

	private final CameraProxyProperties props;

	public CameraSnapshotService(CameraProxyProperties props) {
		this.props = props;
	}

	public CameraSnapshot fetch(String rawUrl) {
		URI uri = verify(rawUrl);
		HttpURLConnection conn = null;
		try {
			conn = open(uri);
			int status = conn.getResponseCode();
			if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"摄像头要求登录，请在地址中带上用户名密码，例如 http://admin:123456@192.168.1.64/snapshot.jpg");
			}
			if (status / 100 == 3) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "摄像头返回了跳转，请直接填写快照或 MJPEG 地址");
			}
			if (status != HttpURLConnection.HTTP_OK) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "摄像头返回 HTTP " + status + "，请检查抓拍地址");
			}
			String contentType = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase(Locale.ROOT);
			try (InputStream in = conn.getInputStream()) {
				if (contentType.startsWith("multipart/")) {
					return new CameraSnapshot(firstJpegFrame(in), "image/jpeg");
				}
				byte[] bytes = read(in, props.getMaxImageBytes());
				if (!contentType.startsWith("image/") && !startsWith(bytes, JPEG_START)) {
					throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "该地址返回的不是图片，请填写摄像头的快照或 MJPEG 地址");
				}
				return new CameraSnapshot(bytes, contentType.startsWith("image/") ? conn.getContentType() : "image/jpeg");
			}
		}
		catch (ResponseStatusException ex) {
			throw ex;
		}
		catch (IOException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "连接摄像头失败，请确认设备在线且服务器能访问该地址");
		}
		finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/** 解析并校验目标地址：协议、网段都要在允许范围内。 */
	URI verify(String rawUrl) {
		String text = rawUrl == null ? "" : rawUrl.trim();
		if (text.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供摄像头抓拍地址");
		}
		URI uri;
		try {
			uri = new URI(text);
		}
		catch (URISyntaxException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "摄像头地址格式不正确");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 http/https 的摄像头地址");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "摄像头地址缺少主机名");
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (UnknownHostException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法解析摄像头地址：" + uri.getHost());
		}
		List<CidrRange> allowed = props.getAllowedRanges();
		for (InetAddress address : addresses) {
			if (allowed.stream().noneMatch(range -> range.contains(address))) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"该地址不在允许抓拍的网段内（" + props.getAllowedCidrs() + "），请在 app.camera.allowed-cidrs 中配置");
			}
		}
		return uri;
	}

	private HttpURLConnection open(URI uri) throws IOException {
		URL url = uri.toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(props.getConnectTimeoutMs());
		conn.setReadTimeout(props.getReadTimeoutMs());
		conn.setRequestMethod("GET");
		conn.setUseCaches(false);
		// 跳转可能指向允许网段之外的地址，一律不跟随。
		conn.setInstanceFollowRedirects(false);
		conn.setRequestProperty("Accept", "image/jpeg,image/*;q=0.9,*/*;q=0.1");
		String userInfo = uri.getUserInfo();
		if (userInfo != null && !userInfo.isBlank()) {
			// 浏览器会丢弃 <img src> 里的账号密码，这里补回摄像头常用的 Basic 认证。
			String token = Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
			conn.setRequestProperty("Authorization", "Basic " + token);
		}
		return conn;
	}

	private byte[] read(InputStream in, int limit) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int count;
		while ((count = in.read(chunk)) > 0) {
			if (buffer.size() + count > limit) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "摄像头画面超过 " + (limit / 1024 / 1024) + "MB，已停止读取");
			}
			buffer.write(chunk, 0, count);
		}
		return buffer.toByteArray();
	}

	/** MJPEG 是一条不会结束的流，读到第一帧完整 JPEG 就断开。 */
	byte[] firstJpegFrame(InputStream in) throws IOException {
		ByteArrayOutputStream frame = new ByteArrayOutputStream();
		boolean started = false;
		int previous = -1;
		int limit = props.getMaxImageBytes();
		int current;
		while ((current = in.read()) >= 0) {
			if (!started) {
				if (previous == (JPEG_START[0] & 0xFF) && current == (JPEG_START[1] & 0xFF)) {
					started = true;
					frame.write(JPEG_START[0]);
					frame.write(JPEG_START[1]);
				}
				previous = current;
				continue;
			}
			frame.write(current);
			if (frame.size() > limit) {
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "摄像头画面超过 " + (limit / 1024 / 1024) + "MB，已停止读取");
			}
			if (previous == (JPEG_END[0] & 0xFF) && current == (JPEG_END[1] & 0xFF)) {
				return frame.toByteArray();
			}
			previous = current;
		}
		throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "未能从视频流中读到完整画面，请稍后重试");
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if (bytes[index] != prefix[index]) {
				return false;
			}
		}
		return true;
	}
}
