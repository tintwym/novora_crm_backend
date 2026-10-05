package dev.tintwym.novora.common;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {

	private final HttpStatus status;

	public AppException(String message, HttpStatus status) {
		super(message);
		this.status = status;
	}

	public AppException(String message, int status) {
		this(message, HttpStatus.valueOf(status));
	}

	public HttpStatus getStatus() {
		return status;
	}
}
