package dev.tintwym.novora.common;

import java.util.UUID;

public final class Cuid {

	private Cuid() {}

	public static String generate() {
		return "c" + UUID.randomUUID().toString().replace("-", "");
	}
}
