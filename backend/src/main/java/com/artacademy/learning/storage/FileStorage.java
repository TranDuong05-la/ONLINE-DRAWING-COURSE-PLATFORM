package com.artacademy.learning.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.artacademy.common.ApiException;
import com.artacademy.learning.Errors;

/**
 * Local private storage for submission images and certificate PDFs. Files are never served statically;
 * they are only reachable through endpoints that check ownership (or a short-lived signed token).
 * Swap this class for the shared media provider (e.g. Cloudinary authenticated assets) when member B exposes it.
 */
@Service
public class FileStorage {
	private final Path root;

	public FileStorage(@Value("${app.storage.root}") String root) {
		this.root = Path.of(root).toAbsolutePath().normalize();
	}

	public String save(String dir, byte[] data, String extension) {
		String key = dir + "/" + UUID.randomUUID() + "." + extension;
		try {
			Path target = resolve(key);
			Files.createDirectories(target.getParent());
			Files.write(target, data);
			return key;
		} catch (IOException e) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể lưu tệp");
		}
	}

	public byte[] read(String key) {
		try {
			return Files.readAllBytes(resolve(key));
		} catch (IOException e) {
			throw Errors.notFound("Không tìm thấy tệp");
		}
	}

	public void deleteQuietly(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		} catch (IOException | RuntimeException ignored) {
			// best effort cleanup
		}
	}

	private Path resolve(String key) {
		Path p = root.resolve(key).normalize();
		if (!p.startsWith(root)) {
			throw Errors.badRequest("Đường dẫn tệp không hợp lệ");
		}
		return p;
	}
}
