package com.ems.backend.media;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Service
public class MediaContentInspector {
    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] PNG_END =
            {0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82};
    private static final Set<String> DANGEROUS_PDF_TOKENS = Set.of(
            "/javascript", "/js", "/launch", "/embeddedfile", "/richmedia",
            "/openaction", "/aa"
    );
    private final Semaphore imageDecoders = new Semaphore(2, true);

    public DetectedMedia inspect(
            Path path,
            UploadPurpose purpose,
            String submittedContentType,
            String submittedFilename
    ) {
        try {
            long size = Files.size(path);
            if (size <= 0) {
                throw invalid("EMPTY_FILE", "The uploaded file is empty.");
            }
            if (size > purpose.maximumBytes()) {
                throw invalid("FILE_TOO_LARGE", "The file exceeds the limit for this upload purpose.");
            }
            byte[] bytes = Files.readAllBytes(path);
            String format = detect(bytes);
            if (!purpose.formats().contains(format)) {
                throw invalid("UNSUPPORTED_FORMAT", "The actual file format is not allowed for this purpose.");
            }
            String mime = mime(format);
            validateClientHints(format, mime, submittedContentType, submittedFilename);
            String safeName = sanitizeFilename(submittedFilename, format);
            String checksum = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
            if ("pdf".equals(format)) {
                validatePdf(bytes);
                return new DetectedMedia(mime, format, size, checksum, null, null, null, safeName);
            }
            return inspectImage(path, bytes, purpose, mime, format, size, checksum, safeName);
        } catch (MediaValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("FILE_READ_FAILED", "The uploaded file could not be inspected.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw invalid("DECODER_BUSY", "Image validation was interrupted.");
        } catch (Exception exception) {
            throw invalid("INVALID_CONTENT", "The uploaded file content is invalid.");
        }
    }

    private DetectedMedia inspectImage(
            Path path,
            byte[] bytes,
            UploadPurpose purpose,
            String mime,
            String format,
            long size,
            String checksum,
            String safeName
    ) throws IOException, InterruptedException {
        validateImageEnding(bytes, format);
        if (!imageDecoders.tryAcquire()) {
            throw invalid("DECODER_BUSY", "Image validation capacity is temporarily exhausted.");
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(path.toFile())) {
            if (stream == null) {
                throw invalid("IMAGE_DECODE_FAILED", "The image cannot be decoded.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalid("IMAGE_DECODE_FAILED", "The image format is not supported.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, true);
                int frames;
                try {
                    frames = reader.getNumImages(true);
                } catch (IOException ignored) {
                    frames = 1;
                }
                if (!purpose.animationAllowed() && frames != 1) {
                    throw invalid("ANIMATION_NOT_ALLOWED", "Animated images are not permitted.");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < purpose.minimumDimension() || height < purpose.minimumDimension()) {
                    throw invalid("IMAGE_TOO_SMALL", "The image dimensions are below the required minimum.");
                }
                if (width > purpose.maximumDimension() || height > purpose.maximumDimension()) {
                    throw invalid("IMAGE_DIMENSIONS_EXCEEDED", "The image dimensions exceed the allowed maximum.");
                }
                long pixels = Math.multiplyExact((long) width, (long) height);
                if (pixels > purpose.maximumPixels()) {
                    throw invalid("IMAGE_PIXEL_LIMIT_EXCEEDED", "The image contains too many pixels.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid("IMAGE_DECODE_FAILED", "The image could not be fully decoded.");
                }
                return new DetectedMedia(
                        mime, format, size, checksum, width, height, frames, safeName
                );
            } finally {
                reader.dispose();
            }
        } catch (ArithmeticException exception) {
            throw invalid("IMAGE_PIXEL_LIMIT_EXCEEDED", "The image dimensions are invalid.");
        } finally {
            imageDecoders.release();
        }
    }

    private String detect(byte[] bytes) {
        if (startsWith(bytes, PNG_MAGIC)) return "png";
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "jpeg";
        if (bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-') return "pdf";
        throw invalid("UNRECOGNIZED_FILE_SIGNATURE", "The file signature is not recognized.");
    }

    private void validateImageEnding(byte[] bytes, String format) {
        if ("jpeg".equals(format)) {
            if (bytes.length < 2
                    || (bytes[bytes.length - 2] & 0xff) != 0xff
                    || (bytes[bytes.length - 1] & 0xff) != 0xd9) {
                throw invalid("IMAGE_TRAILING_OR_TRUNCATED", "The JPEG is truncated or contains trailing data.");
            }
        } else if ("png".equals(format) && !endsWith(bytes, PNG_END)) {
            throw invalid("IMAGE_TRAILING_OR_TRUNCATED", "The PNG is truncated or contains trailing data.");
        }
    }

    private void validatePdf(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        int eof = content.lastIndexOf("%%EOF");
        if (eof < 0 || !content.substring(eof + 5).trim().isEmpty()) {
            throw invalid("PDF_TRAILING_OR_TRUNCATED", "The PDF is truncated or contains trailing data.");
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (DANGEROUS_PDF_TOKENS.stream().anyMatch(lower::contains)) {
            throw invalid("PDF_ACTIVE_CONTENT", "PDF active or embedded content is not permitted.");
        }
        if (!lower.contains("xref") && !lower.contains("/type /xref")) {
            throw invalid("PDF_STRUCTURE_INVALID", "The PDF structure is invalid.");
        }
    }

    private void validateClientHints(
            String format,
            String mime,
            String submittedContentType,
            String submittedFilename
    ) {
        if (submittedContentType != null
                && !submittedContentType.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(submittedContentType)
                && !mime.equalsIgnoreCase(submittedContentType)) {
            throw invalid("MIME_MISMATCH", "The submitted MIME type does not match the actual file.");
        }
        String extension = extension(submittedFilename);
        if (!extension.isEmpty()) {
            boolean matches = switch (format) {
                case "jpeg" -> extension.equals("jpg") || extension.equals("jpeg");
                default -> extension.equals(format);
            };
            if (!matches) {
                throw invalid("EXTENSION_MISMATCH", "The filename extension does not match the actual file.");
            }
        }
    }

    private String sanitizeFilename(String original, String format) {
        String name = original == null ? "upload." + extensionFor(format)
                : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._ -]", "_")
                .replaceAll("\\.{2,}", ".")
                .trim();
        if (name.isBlank() || name.startsWith(".")) {
            name = "upload." + extensionFor(format);
        }
        return name.substring(0, Math.min(name.length(), 180));
    }

    private String extensionFor(String format) {
        return "jpeg".equals(format) ? "jpg" : format;
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String mime(String format) {
        return switch (format) {
            case "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            default -> throw invalid("UNSUPPORTED_FORMAT", "Unsupported file format.");
        };
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private boolean endsWith(byte[] value, byte[] suffix) {
        if (value.length < suffix.length) return false;
        int offset = value.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) if (value[offset + i] != suffix[i]) return false;
        return true;
    }

    private MediaValidationException invalid(String code, String message) {
        return new MediaValidationException(code, message);
    }
}
