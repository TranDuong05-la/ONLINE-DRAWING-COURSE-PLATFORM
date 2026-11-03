package com.artacademy.learning.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Chứng nhận giữ nguyên dấu tiếng Việt (trước đây font Helvetica làm mất dấu). */
class CertificatePdfTest {

	@Test
	void keepsVietnameseDiacriticsInLearnerNameAndCourseTitle() throws Exception {
		byte[] pdf = CertificatePdf.generate("Nguyễn Thị Hồng Nhung", "Vẽ màu nước cho người mới bắt đầu",
				"ART-CERT-2026-ABCD2345", LocalDateTime.of(2026, 11, 4, 9, 30), "0123456789abcdef01234567");

		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			assertThat(doc.getNumberOfPages()).isEqualTo(1);
			String text = new PDFTextStripper().getText(doc);
			assertThat(text).contains("CHỨNG NHẬN HOÀN THÀNH", "Nguyễn Thị Hồng Nhung",
					"Vẽ màu nước cho người mới bắt đầu", "ART-CERT-2026-ABCD2345", "04/11/2026");
		}
	}

	@Test
	void veryLongTitleStillFitsAndControlCharactersAreRemoved() throws Exception {
		String longTitle = "Khóa học ".repeat(30) + "\nxuống dòng";
		byte[] pdf = CertificatePdf.generate("Lê Văn Đức", longTitle, "ART-CERT-2026-ZZZZ9999",
				LocalDateTime.of(2026, 1, 2, 0, 0), "ffffffffffffffffffffffff");
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			assertThat(new PDFTextStripper().getText(doc)).contains("Lê Văn Đức", "xuống dòng");
		}
	}
}
