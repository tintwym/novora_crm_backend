package dev.tintwym.novora.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonMaps {

	private JsonMaps() {}

	public static Map<String, Object> map() {
		return new LinkedHashMap<>();
	}

	public static Map<String, Object> of(Object... kv) {
		Map<String, Object> m = map();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return m;
	}

	public static Double num(BigDecimal v) {
		return v == null ? null : v.doubleValue();
	}

	public static String iso(Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
