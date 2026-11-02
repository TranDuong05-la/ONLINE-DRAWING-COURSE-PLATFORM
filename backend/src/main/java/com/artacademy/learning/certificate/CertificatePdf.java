package com.artacademy.learning.certificate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free single-page PDF. Uses the built-in Helvetica font, which has no Vietnamese glyphs,
 * so text is rendered without diacritics (limitation documented in docs/PHAN_C.md).
 */
public final class CertificatePdf {
	private static final double W = 842; // A4 landscape
	private static final double H = 595;

	private CertificatePdf() {
	}

	public static byte[] generate(String learnerName, String courseTitle, String code, LocalDateTime issuedAt,
			String signatureHash) {
		StringBuilder c = new StringBuilder();
		c.append("2 w 0.2 0.3 0.5 RG 30 30 ").append(W - 60).append(' ').append(H - 60).append(" re S\n");
		center(c, "F2", 34, 470, "CERTIFICATE OF COMPLETION");
		center(c, "F1", 14, 430, "This certifies that");
		center(c, "F2", 28, 380, ascii(learnerName));
		center(c, "F1", 14, 340, "has successfully completed the online drawing course");
		center(c, "F2", 22, 300, ascii(courseTitle));
		center(c, "F1", 12, 250, "Issued on " + issuedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
		center(c, "F1", 12, 230, "Certificate code: " + code);
		center(c, "F1", 8, 80, "Signature hash: " + signatureHash);
		center(c, "F1", 8, 65, "Completion certificate issued by the Drawing Course system. Not an externally accredited credential.");
		return build(c.toString());
	}

	/** Removes diacritics so text fits the standard PDF font; also escapes PDF string delimiters. */
	static String ascii(String s) {
		String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
				.replace('đ', 'd').replace('Đ', 'D');
		StringBuilder out = new StringBuilder();
		for (char ch : n.toCharArray()) {
			out.append(ch >= 32 && ch < 127 ? ch : '?');
		}
		return out.toString();
	}

	private static void center(StringBuilder c, String font, int size, int y, String text) {
		String t = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
		double x = Math.max(40, (W - t.length() * size * 0.5) / 2);
		c.append("BT /").append(font).append(' ').append(size).append(" Tf ").append(x).append(' ').append(y)
				.append(" Td (").append(t).append(") Tj ET\n");
	}

	private static byte[] build(String content) {
		byte[] stream = content.getBytes(StandardCharsets.ISO_8859_1);
		List<String> objs = new ArrayList<>();
		objs.add("<< /Type /Catalog /Pages 2 0 R >>");
		objs.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
		objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) W + " " + (int) H
				+ "] /Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>");
		objs.add("<< /Length " + stream.length + " >>\nstream\n" + content + "endstream");
		objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
		objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, "%PDF-1.4\n");
		int[] offsets = new int[objs.size()];
		for (int i = 0; i < objs.size(); i++) {
			offsets[i] = out.size();
			write(out, (i + 1) + " 0 obj\n" + objs.get(i) + "\nendobj\n");
		}
		int xref = out.size();
		StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
		for (int off : offsets) {
			x.append(String.format("%010d 00000 n \n", off));
		}
		x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref)
				.append("\n%%EOF\n");
		write(out, x.toString());
		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, String s) {
		byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
		out.write(b, 0, b.length);
	}
}
