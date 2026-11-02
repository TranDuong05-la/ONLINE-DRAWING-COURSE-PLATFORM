package com.artacademy.learning.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.artacademy.common.ApiException;
import com.artacademy.learning.Errors;

/**
 * Accepts only real JPEG/PNG images within the configured size. Checks content, not just the file name.
 *
 * <p>GHI CHÚ HỌC: không thể tin tên file (.png) hay Content-Type vì người dùng tự đặt được.
 * Nên ta đọc vài byte đầu ("magic bytes") và thử đọc header ảnh để chắc chắn đó là ảnh thật,
 * đồng thời giới hạn dung lượng và số điểm ảnh (chống file nén nhỏ nhưng giải ra khổng lồ).
 */
@Component
public class ImageValidator {
	public record ValidImage(byte[] bytes, String extension) {
	}

	private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");
	private static final int MAX_DIMENSION = 8000;

	private final long maxBytes;

	public ImageValidator(@Value("${app.upload.max-image-bytes}") long maxBytes) {
		this.maxBytes = maxBytes;
	}

	public ValidImage validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw Errors.badRequest("Vui lòng chọn ảnh bài làm");
		}
		if (file.getSize() > maxBytes) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
					"Ảnh vượt quá " + (maxBytes / 1024 / 1024) + "MB");
		}
		String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
		if (!ALLOWED_TYPES.contains(declared)) {
			throw Errors.badRequest("Chỉ chấp nhận ảnh JPEG hoặc PNG");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw Errors.badRequest("Không đọc được tệp");
		}
		String ext = sniff(bytes);
		if (ext == null || !isReadableImage(bytes)) {
			throw Errors.badRequest("Nội dung tệp không phải ảnh JPEG/PNG hợp lệ");
		}
		return new ValidImage(bytes, ext);
	}

	private static String sniff(byte[] b) {
		if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return "jpg";
		}
		if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
			return "png";
		}
		return null;
	}

	/** Reads only the header (no full decode), so oversized pixel dimensions are rejected cheaply. */
	private static boolean isReadableImage(byte[] bytes) {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				return false;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in);
				return reader.getWidth(0) > 0 && reader.getHeight(0) > 0 && reader.getWidth(0) <= MAX_DIMENSION
						&& reader.getHeight(0) <= MAX_DIMENSION;
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
