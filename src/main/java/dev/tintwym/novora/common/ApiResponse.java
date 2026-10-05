package dev.tintwym.novora.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, String message, Object errors) {

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data, null, null);
	}

	public static <T> ApiResponse<T> success(T data, String message) {
		return new ApiResponse<>(true, data, message, null);
	}

	public static ApiResponse<Void> error(String message) {
		return new ApiResponse<>(false, null, message, null);
	}

	public static ApiResponse<Void> error(String message, Object errors) {
		return new ApiResponse<>(false, null, message, errors);
	}
}
