package com.ems.backend.media;

import com.ems.backend.user.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPurposeTest {
    @Test
    void roleAndAttachmentPoliciesArePurposeSpecific() {
        assertTrue(UploadPurpose.PROFILE_IMAGE.canUpload(Role.EMPLOYEE));
        assertTrue(UploadPurpose.PROFILE_IMAGE.attachmentTargets().contains("USER_PROFILE"));

        assertFalse(UploadPurpose.HR_DOCUMENT.canUpload(Role.MANAGER));
        assertFalse(UploadPurpose.HR_DOCUMENT.canUpload(Role.EMPLOYEE));
        assertTrue(UploadPurpose.HR_DOCUMENT.canUpload(Role.ADMIN));
        assertTrue(UploadPurpose.HR_DOCUMENT.attachmentTargets().contains("STAFF_DOCUMENT"));

        assertFalse(UploadPurpose.PAYMENT_ATTACHMENT.canUpload(Role.EMPLOYEE));
        assertTrue(UploadPurpose.PAYMENT_ATTACHMENT.canUpload(Role.MANAGER));
        assertTrue(UploadPurpose.PAYMENT_ATTACHMENT.canUpload(Role.ADMIN));
    }
}
