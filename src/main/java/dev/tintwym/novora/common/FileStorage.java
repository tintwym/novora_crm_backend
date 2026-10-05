package dev.tintwym.novora.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Stores uploaded files on local disk under keys like {@code companyId/2026/10/<cuid>}. */
@Component
public class FileStorage {

	private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

	private final Path root;

	public FileStorage(@Value("${novora.storage.dir}") String dir) {
		this.root = Path.of(dir).toAbsolutePath().normalize();
	}

	public String save(String companyId, InputStream in) {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		String key = companyId + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue()) + "/"
				+ Cuid.generate();
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			log.error("Failed to store upload at {}", target, e);
			throw new AppException("Couldn't save the file. Please try again.", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		return key;
	}

	public Path path(String key) {
		Path p = resolve(key);
		if (!Files.isRegularFile(p)) {
			throw new AppException("File is missing from storage", HttpStatus.NOT_FOUND);
		}
		return p;
	}

	public void delete(String key) {
		if (key == null) return;
		try {
			Files.deleteIfExists(resolve(key));
		} catch (IOException | AppException e) {
			log.warn("Failed to delete stored file {}", key, e);
		}
	}

	private Path resolve(String key) {
		Path p = root.resolve(key).normalize();
		if (!p.startsWith(root)) {
			throw new AppException("Invalid file key", HttpStatus.BAD_REQUEST);
		}
		return p;
	}
}
