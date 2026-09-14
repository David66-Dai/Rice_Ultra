package com.smartrice.server.camera;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 一条允许访问的网段，例如 192.168.1.0/24；不带掩码时表示单台设备。 */
public final class CidrRange {

	private final byte[] network;

	private final int prefixBits;

	private CidrRange(byte[] network, int prefixBits) {
		this.network = network;
		this.prefixBits = prefixBits;
	}

	public static CidrRange parse(String text) {
		int slash = text.indexOf('/');
		String host = slash < 0 ? text : text.substring(0, slash);
		byte[] network = literalAddress(host);
		int bits = network.length * 8;
		if (slash >= 0) {
			try {
				bits = Integer.parseInt(text.substring(slash + 1).trim());
			}
			catch (NumberFormatException ex) {
				throw new IllegalArgumentException("网段格式不正确：" + text);
			}
			if (bits < 0 || bits > network.length * 8) {
				throw new IllegalArgumentException("网段掩码超出范围：" + text);
			}
		}
		return new CidrRange(network, bits);
	}

	public boolean contains(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length != network.length) {
			return false;
		}
		int fullBytes = prefixBits / 8;
		for (int index = 0; index < fullBytes; index++) {
			if (bytes[index] != network[index]) {
				return false;
			}
		}
		int remaining = prefixBits % 8;
		if (remaining == 0) {
			return true;
		}
		int mask = 0xFF << (8 - remaining);
		return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
	}

	/** 只接受字面量地址：纯数字与冒号的写法不会触发 DNS 查询。 */
	private static byte[] literalAddress(String host) {
		String text = host.trim();
		if (!text.matches("[0-9.]+") && !text.matches("\\[?[0-9A-Fa-f:.]+]?")) {
			throw new IllegalArgumentException("网段必须是 IP 字面量：" + host);
		}
		try {
			return InetAddress.getByName(text).getAddress();
		}
		catch (UnknownHostException ex) {
			throw new IllegalArgumentException("网段必须是 IP 字面量：" + host, ex);
		}
	}
}
