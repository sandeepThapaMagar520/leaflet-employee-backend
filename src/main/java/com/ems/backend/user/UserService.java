package com.ems.backend.user;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.dto.CreateStaffDocumentRequest;
import com.ems.backend.user.dto.StaffDocumentResponse;
import com.ems.backend.user.dto.UpdateUserRequest;
import com.ems.backend.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final StaffDocumentRepository staffDocumentRepository;
    private final StaffAuditEventRepository staffAuditEventRepository;

    public UserService(
            UserRepository userRepository,
            SecurityUtils securityUtils,
            StaffDocumentRepository staffDocumentRepository,
            StaffAuditEventRepository staffAuditEventRepository
    ) {
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.staffDocumentRepository = staffDocumentRepository;
        this.staffAuditEventRepository = staffAuditEventRepository;
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(this::map)
                .toList();
    }

    public PageResponse<UserResponse> getUsersPaged(int page, int size, String search) {
        String query = search != null ? search.trim().toLowerCase() : "";
        List<UserResponse> filtered = getAllUsers().stream()
                .filter(user -> query.isEmpty()
                        || user.fullName().toLowerCase().contains(query)
                        || user.email().toLowerCase().contains(query)
                        || user.role().name().toLowerCase().contains(query))
                .toList();
        return PageResponse.of(filtered, page, size);
    }

    public UserResponse map(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getJobTitle(),
                user.getProfilePhotoUrl(),
                user.getEmployeeId(),
                user.getJoiningDate(),
                user.getEmploymentType(),
                user.getPhone(),
                user.getEmergencyContact(),
                user.getDepartment(),
                user.getLocation(),
                user.getTimezone(),
                accountStatus(user),
                user.getEmailVerified(),
                user.getMustChangePassword(),
                user.getLastLoginAt()
        );
    }

    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        User actor = securityUtils.getCurrentUser();

        if (actor.getId().equals(user.getId())) {
            if (Boolean.FALSE.equals(request.active())) {
                throw new ResponseStatusException(BAD_REQUEST, "You cannot deactivate your own admin account.");
            }
            if (request.role() != Role.ADMIN) {
                throw new ResponseStatusException(BAD_REQUEST, "You cannot remove your own admin role.");
            }
        }

        // Only check email uniqueness if it's changing
        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(CONFLICT, "Email already in use");
        }
        if (request.employeeId() != null && !request.employeeId().isBlank()) {
            userRepository.findAll().stream()
                    .filter(existing -> request.employeeId().trim().equalsIgnoreCase(existing.getEmployeeId()))
                    .filter(existing -> !existing.getId().equals(id))
                    .findAny()
                    .ifPresent(existing -> {
                        throw new ResponseStatusException(CONFLICT, "Employee ID already in use");
                    });
        }

        String before = summary(user);
        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setRole(request.role());
        user.setActive(request.active());
        user.setJobTitle(normalize(request.jobTitle()));
        user.setEmployeeId(normalize(request.employeeId()));
        user.setJoiningDate(request.joiningDate());
        user.setEmploymentType(request.employmentType());
        user.setPhone(normalize(request.phone()));
        user.setEmergencyContact(normalize(request.emergencyContact()));
        user.setDepartment(normalize(request.department()));
        user.setLocation(normalize(request.location()));
        user.setTimezone(normalize(request.timezone()) == null ? "Asia/Kathmandu" : normalize(request.timezone()));

        User updatedUser = userRepository.save(user);
        audit(updatedUser, actor, StaffAuditAction.UPDATED, "Updated staff profile. Before: " + before + ". After: " + summary(updatedUser));

        return map(updatedUser);
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        User actor = securityUtils.getCurrentUser();
        if (actor.getId().equals(user.getId())) {
            throw new ResponseStatusException(BAD_REQUEST, "You cannot deactivate your own admin account.");
        }
        // Soft delete: deactivate the user
        user.setActive(false);
        User saved = userRepository.save(user);
        audit(saved, actor, StaffAuditAction.DEACTIVATED, "Deactivated staff account.");
    }

    public StaffDocumentResponse addDocument(Long userId, CreateStaffDocumentRequest request) {
        User staffUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        User actor = securityUtils.getCurrentUser();
        StaffDocument document = new StaffDocument();
        document.setUser(staffUser);
        document.setDocumentType(request.documentType());
        document.setFileName(request.fileName().trim());
        document.setFileUrl(request.fileUrl().trim());
        document.setNote(normalize(request.note()));
        StaffDocument saved = staffDocumentRepository.save(document);
        audit(staffUser, actor, StaffAuditAction.DOCUMENT_ADDED, "Added document: " + saved.getFileName());
        return mapDocument(saved);
    }

    public void deleteDocument(Long userId, Long documentId) {
        StaffDocument document = staffDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found"));
        if (!document.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found");
        }
        User actor = securityUtils.getCurrentUser();
        String fileName = document.getFileName();
        User staffUser = document.getUser();
        staffDocumentRepository.delete(document);
        audit(staffUser, actor, StaffAuditAction.DOCUMENT_REMOVED, "Removed document: " + fileName);
    }

    public StaffDocumentResponse mapDocument(StaffDocument document) {
        return new StaffDocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getFileName(),
                document.getFileUrl(),
                document.getNote(),
                document.getCreatedAt()
        );
    }

    public void audit(User staffUser, User actor, StaffAuditAction action, String description) {
        StaffAuditEvent event = new StaffAuditEvent();
        event.setStaffUser(staffUser);
        event.setActor(actor);
        event.setAction(action);
        event.setDescription(description);
        staffAuditEventRepository.save(event);
    }

    public AccountStatus accountStatus(User user) {
        if (Boolean.TRUE.equals(user.getEmailVerified()) && !Boolean.TRUE.equals(user.getMustChangePassword())) {
            return AccountStatus.VERIFIED;
        }
        if (user.getPasswordOtp() != null || user.getPasswordResetToken() != null) {
            return AccountStatus.SETUP_PENDING;
        }
        return AccountStatus.INVITE_SENT;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String summary(User user) {
        return "name=%s, email=%s, role=%s, active=%s, employeeId=%s"
                .formatted(user.getFullName(), user.getEmail(), user.getRole(), user.getActive(), user.getEmployeeId());
    }
}
