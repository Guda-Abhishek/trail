package com.AccountReceivableManagement.service_Imple.company_profile;

import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Backend validation of an uploaded signature: non-empty, within the size
 * limit, extension and declared MIME type both PNG/JPEG, real content (magic
 * bytes) agreeing with them, and a fully decodable image of sane dimensions.
 * The client's filename is only inspected for its extension - never stored.
 */
@Component
public class SignatureImageValidator {

    public record ValidatedImage(String contentType, byte[] content) {
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final long maxBytes;
    private final int maxDimension;

    public SignatureImageValidator(
            @Value("${app.signature.max-bytes:1048576}") long maxBytes,
            @Value("${app.signature.max-dimension-px:4000}") int maxDimension) {
        this.maxBytes = maxBytes;
        this.maxDimension = maxDimension;
    }

    public ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("The signature file is empty or missing.");
        }
        if (file.getSize() > maxBytes) {
            throw invalid("The signature file exceeds the maximum size of " + maxBytes + " bytes.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!extension.equals("png") && !extension.equals("jpg") && !extension.equals("jpeg")) {
            throw invalid("Only PNG and JPEG/JPG signature images are accepted.");
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!declared.equals("image/png") && !declared.equals("image/jpeg") && !declared.equals("image/jpg")) {
            throw invalid("Only PNG and JPEG/JPG signature images are accepted.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw invalid("The signature file could not be read.");
        }
        if (content.length > maxBytes) {
            throw invalid("The signature file exceeds the maximum size of " + maxBytes + " bytes.");
        }

        String detected = detectType(content);
        if (detected == null) {
            throw invalid("The file content is not a PNG or JPEG image.");
        }
        boolean detectedPng = detected.equals("image/png");
        if (detectedPng != declared.equals("image/png") || detectedPng != extension.equals("png")) {
            throw invalid("The file content does not match its declared type or extension.");
        }

        verifyDecodable(content);
        return new ValidatedImage(detected, content);
    }

    private void verifyDecodable(byte[] content) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw invalid("The file is not a valid image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > maxDimension || height > maxDimension) {
                    throw invalid("The signature image dimensions must be at most "
                            + maxDimension + "x" + maxDimension + " pixels.");
                }
                reader.read(0); // full decode: rejects truncated / corrupt data
            } finally {
                reader.dispose();
            }
        } catch (GlobalExceptionHandler.ValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("The file is not a valid image.");
        }
    }

    private static String detectType(byte[] c) {
        if (c.length >= PNG_MAGIC.length) {
            boolean png = true;
            for (int i = 0; i < PNG_MAGIC.length; i++) {
                if (c[i] != PNG_MAGIC[i]) {
                    png = false;
                    break;
                }
            }
            if (png) {
                return "image/png";
            }
        }
        if (c.length >= 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static GlobalExceptionHandler.ValidationException invalid(String message) {
        return new GlobalExceptionHandler.ValidationException(message);
    }
}
