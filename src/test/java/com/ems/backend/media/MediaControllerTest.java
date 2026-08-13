package com.ems.backend.media;

import jakarta.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaControllerTest {
    private final MediaUploadService uploadService = mock(MediaUploadService.class);
    private final MediaController controller = new MediaController(
            uploadService, mock(MediaDeliveryService.class)
    );

    @BeforeEach
    void resetUploadService() {
        org.mockito.Mockito.reset(uploadService);
    }

    @Test
    void acceptsBrowserStylePurposeFormFieldWithoutPartContentType() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPart(new MockPart("purpose", "HR_DOCUMENT".getBytes()));
        request.addPart(new MockPart("file", "document.pdf", new byte[]{1}));
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", new byte[]{1}
        );

        controller.upload(UploadPurpose.HR_DOCUMENT, file, request);

        org.mockito.Mockito.verify(uploadService).upload(UploadPurpose.HR_DOCUMENT, file);
    }

    @Test
    void acceptsExactlyOnePurposeAndOneFile() {
        assertDoesNotThrow(() -> controller.validateMultipartShape(
                List.of(part("purpose"), part("file"))
        ));
    }

    @Test
    void rejectsMissingDuplicateMultipleAndUnknownParts() {
        assertBadRequest(List.of(part("purpose")));
        assertBadRequest(List.of(part("file")));
        assertBadRequest(List.of(part("purpose"), part("purpose"), part("file")));
        assertBadRequest(List.of(part("purpose"), part("file"), part("file")));
        assertBadRequest(List.of(part("purpose"), part("file"), part("owner")));
        assertBadRequest(List.of());
    }

    private Part part(String name) {
        Part part = mock(Part.class);
        when(part.getName()).thenReturn(name);
        return part;
    }

    private void assertBadRequest(List<Part> parts) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.validateMultipartShape(parts)
        );
        assertEquals(400, exception.getStatusCode().value());
    }
}
