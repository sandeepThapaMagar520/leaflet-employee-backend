package com.ems.backend.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaContentInspectorTest {
    @TempDir
    Path temporaryDirectory;

    private final MediaContentInspector inspector = new MediaContentInspector();

    @Test
    void acceptsFullyDecodedJpegAndPng() throws Exception {
        Path jpeg = image("valid.jpg", "jpg", 128, 128);
        Path png = image("valid.png", "png", 128, 128);

        assertEquals(
                "jpeg",
                inspector.inspect(
                        jpeg, UploadPurpose.PROFILE_IMAGE, "image/jpeg", "valid.jpg"
                ).format()
        );
        assertEquals(
                "png",
                inspector.inspect(
                        png, UploadPurpose.PROFILE_IMAGE, "image/png", "valid.png"
                ).format()
        );
    }

    @Test
    void rejectsHtmlSvgAndExecutableRenamedAsAllowedFiles() throws Exception {
        Path html = write("attack.jpg", "<html><script>alert(1)</script></html>");
        Path svg = write("attack.png", "<svg><script>alert(1)</script></svg>");
        Path executable = write("attack.pdf", "MZ\u0090\u0000program");

        assertReason("UNRECOGNIZED_FILE_SIGNATURE", html, "image/jpeg", "attack.jpg");
        assertReason("UNRECOGNIZED_FILE_SIGNATURE", svg, "image/png", "attack.png");
        assertReason("UNRECOGNIZED_FILE_SIGNATURE", executable, "application/pdf", "attack.pdf");
    }

    @Test
    void rejectsTruncatedPdfActivePdfAndTrailingImageData() throws Exception {
        Path truncatedPdf = write(
                "truncated.pdf",
                "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\nxref\n"
        );
        Path activePdf = write(
                "active.pdf",
                "%PDF-1.4\n1 0 obj\n<< /JavaScript (alert) >>\nendobj\nxref\n%%EOF"
        );
        Path jpeg = image("trailing.jpg", "jpg", 128, 128);
        Files.write(jpeg, "MZ".getBytes(StandardCharsets.US_ASCII),
                java.nio.file.StandardOpenOption.APPEND);

        assertReason("PDF_TRAILING_OR_TRUNCATED", truncatedPdf, "application/pdf", "truncated.pdf");
        assertReason("PDF_ACTIVE_CONTENT", activePdf, "application/pdf", "active.pdf");
        assertReason("IMAGE_TRAILING_OR_TRUNCATED", jpeg, "image/jpeg", "trailing.jpg");
    }

    @Test
    void rejectsMimeExtensionAndDimensionMismatch() throws Exception {
        Path image = image("photo.jpg", "jpg", 128, 128);
        assertReason("MIME_MISMATCH", image, "image/png", "photo.jpg");
        assertReason("EXTENSION_MISMATCH", image, "image/jpeg", "photo.png");

        Path tooSmall = image("small.png", "png", 32, 32);
        assertReason("IMAGE_TOO_SMALL", tooSmall, "image/png", "small.png");
    }

    @Test
    void rejectsUnsupportedAndOversizedContent() throws Exception {
        Path gif = temporaryDirectory.resolve("animated.gif");
        Files.write(gif, "GIF89a".getBytes(StandardCharsets.US_ASCII));
        assertReason("UNRECOGNIZED_FILE_SIGNATURE", gif, "image/gif", "animated.gif");

        Path oversized = temporaryDirectory.resolve("oversized.jpg");
        Files.write(oversized, new byte[(5 * 1024 * 1024) + 1]);
        assertReason("FILE_TOO_LARGE", oversized, "image/jpeg", "oversized.jpg");
    }

    private Path image(String filename, String format, int width, int height) throws Exception {
        Path path = temporaryDirectory.resolve(filename);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, format, path.toFile());
        return path;
    }

    private Path write(String filename, String content) throws Exception {
        Path path = temporaryDirectory.resolve(filename);
        Files.writeString(path, content, StandardCharsets.ISO_8859_1);
        return path;
    }

    private void assertReason(
            String expected,
            Path path,
            String contentType,
            String filename
    ) {
        MediaValidationException exception = assertThrows(
                MediaValidationException.class,
                () -> inspector.inspect(
                        path,
                        filename.endsWith(".pdf")
                                ? UploadPurpose.HR_DOCUMENT
                                : UploadPurpose.PROFILE_IMAGE,
                        contentType,
                        filename
                )
        );
        assertEquals(expected, exception.reasonCode());
    }
}
