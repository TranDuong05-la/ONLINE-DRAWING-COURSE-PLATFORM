package com.artacademy.media;

import com.artacademy.common.BadRequestException;
import com.artacademy.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/media")
public class AdminMediaController {
	public record SignedMedia(String url, long expiresInSeconds) { }
	private final MediaUrlSigner signer;
	public AdminMediaController(MediaUrlSigner signer) { this.signer = signer; }
	@GetMapping("/sign")
	public ApiResponse<SignedMedia> sign(@RequestParam String path) {
		if (path == null || path.isBlank()) throw new BadRequestException("Media path is required");
		return ApiResponse.success("OK", new SignedMedia(signer.signIfProtected(path.trim()), signer.ttlSeconds()));
	}
}
