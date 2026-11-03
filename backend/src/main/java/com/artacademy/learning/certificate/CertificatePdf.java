package com.artacademy.learning.certificate;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Chứng nhận hoàn thành một trang A4 ngang, viết bằng PDFBox.
 *
 * <p>Nhúng font Be Vietnam Pro (SIL Open Font License, xem resources/fonts/OFL.txt) để tên học viên và tên khóa học
 * hiện đủ dấu tiếng Việt. Font được nhúng dạng subset nên file chỉ chứa các ký tự thực sự dùng.
 */
public final class CertificatePdf {
	private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
	private static final float W = PAGE.getWidth();
	private static final float H = PAGE.getHeight();
	private static final float MAX_TEXT_WIDTH = W - 140;
	private static final Color PRIMARY = new Color(0x1B, 0x43, 0x32);
	private static final Color ACCENT = new Color(0xE9, 0xC4, 0x6A);
	private static final Color MUTED = new Color(0x64, 0x74, 0x8B);

	private CertificatePdf() {
	}

	public static byte[] generate(String learnerName, String courseTitle, String code, LocalDateTime issuedAt,
			String signatureHash) {
		try (PDDocument doc = new PDDocument()) {
			PDFont regular = loadFont(doc, "fonts/BeVietnamPro-Regular.ttf");
			PDFont bold = loadFont(doc, "fonts/BeVietnamPro-Bold.ttf");
			PDPage page = new PDPage(PAGE);
			doc.addPage(page);
			try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
				frame(c);
				center(c, bold, 34, 465, "CHỨNG NHẬN HOÀN THÀNH", PRIMARY);
				center(c, regular, 12, 440, "CERTIFICATE OF COMPLETION", MUTED);
				center(c, regular, 15, 400, "Chứng nhận học viên", Color.DARK_GRAY);
				center(c, bold, 30, 355, safe(learnerName), PRIMARY);
				center(c, regular, 15, 315, "đã hoàn thành khóa học vẽ trực tuyến", Color.DARK_GRAY);
				center(c, bold, 22, 278, safe(courseTitle), PRIMARY);
				center(c, regular, 12, 228,
						"Ngày cấp: " + issuedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), Color.DARK_GRAY);
				center(c, bold, 12, 208, "Mã chứng nhận: " + safe(code), Color.DARK_GRAY);
				center(c, regular, 8, 80, "Mã xác thực: " + safe(signatureHash), MUTED);
				center(c, regular, 8, 65,
						"Chứng nhận hoàn thành do hệ thống khóa học vẽ ArtNest cấp, không phải văn bằng được công nhận bên ngoài.",
						MUTED);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("Không tạo được file PDF chứng nhận", e);
		}
	}

	private static PDFont loadFont(PDDocument doc, String resource) throws IOException {
		try (InputStream in = CertificatePdf.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Thiếu font " + resource);
			}
			return PDType0Font.load(doc, in);
		}
	}

	private static void frame(PDPageContentStream c) throws IOException {
		c.setStrokingColor(PRIMARY);
		c.setLineWidth(3);
		c.addRect(28, 28, W - 56, H - 56);
		c.stroke();
		c.setStrokingColor(ACCENT);
		c.setLineWidth(1.2f);
		c.addRect(38, 38, W - 76, H - 76);
		c.stroke();
	}

	/** Canh giữa; chữ quá dài thì tự thu nhỏ cỡ chữ để không tràn khung. */
	private static void center(PDPageContentStream c, PDFont font, float size, float y, String text, Color color)
			throws IOException {
		float width = font.getStringWidth(text) / 1000 * size;
		if (width > MAX_TEXT_WIDTH) {
			size = size * MAX_TEXT_WIDTH / width;
			width = MAX_TEXT_WIDTH;
		}
		c.beginText();
		c.setNonStrokingColor(color);
		c.setFont(font, size);
		c.newLineAtOffset((W - width) / 2, y);
		c.showText(text);
		c.endText();
	}

	/** Bỏ ký tự điều khiển (xuống dòng, tab...) mà PDF không vẽ được. */
	static String safe(String s) {
		return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").strip();
	}
}
