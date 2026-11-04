package com.artacademy.media;

import com.artacademy.common.ApiException;
import com.artacademy.common.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {
	private final MediaUrlSigner signer;
	private final Path uploadDir;
	public MediaController(MediaUrlSigner signer, @Value("${app.upload.dir:uploads}") String uploadDir) {
		this.signer = signer; this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
	}
	@GetMapping("/media/{fileName}")
	public ResponseEntity<?> serve(@PathVariable String fileName, @RequestParam(required = false) Long exp,
			@RequestParam(required = false) String sig, HttpServletRequest request) {
		if (!signer.verify(fileName, exp, sig)) throw new ApiException(HttpStatus.FORBIDDEN, "Media link is invalid or expired");
		Path file = uploadDir.resolve(fileName).normalize();
		if (!file.startsWith(uploadDir) || !Files.isRegularFile(file)) throw new NotFoundException("Media file was not found");
		MediaType type = "pdf".equals(MediaUrlSigner.extensionOf(fileName)) ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("video/mp4");
		FileSystemResource resource = new FileSystemResource(file);
		var response = ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore().cachePrivate())
				.header("X-Content-Type-Options", "nosniff");
		String rangeHeader = request.getHeader(HttpHeaders.RANGE);
		if (rangeHeader == null || rangeHeader.isBlank()) return response.body(resource);
		try {
			List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
			if (ranges.size() != 1) throw new IllegalArgumentException("Only one byte range is supported");
			ResourceRegion region = ranges.get(0).toResourceRegion(resource);
			long total = resource.contentLength();
			long start = region.getPosition();
			long end = start + region.getCount() - 1;
			return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).contentType(type)
					.cacheControl(CacheControl.noStore().cachePrivate()).header("X-Content-Type-Options", "nosniff")
					.header(HttpHeaders.ACCEPT_RANGES, "bytes")
					.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
					.contentLength(region.getCount()).body(region);
		} catch (IllegalArgumentException | java.io.IOException ex) {
			return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header(HttpHeaders.CONTENT_RANGE,
					"bytes */" + safeLength(resource)).build();
		}
	}

	private static long safeLength(Resource resource) {
		try { return resource.contentLength(); } catch (java.io.IOException ex) { return 0; }
	}
}
