package com.ems.backend.user;

import com.ems.backend.common.PageResponse;
import com.ems.backend.common.Pagination;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.dto.CreateStaffDocumentRequest;
import com.ems.backend.user.dto.StaffDocumentResponse;
import com.ems.backend.user.dto.StaffDirectorySummaryResponse;
import com.ems.backend.user.dto.UpdateUserRequest;
import com.ems.backend.user.dto.UserResponse;
import com.ems.backend.user.dto.ManagerDirectoryResponse;
import com.ems.backend.security.SecurityAuditService;
import com.ems.backend.security.RequestMetadata;
import com.ems.backend.authorization.AuthorizationPolicyService;
import com.ems.backend.media.MediaAsset;
import com.ems.backend.media.MediaAttachmentService;
import com.ems.backend.media.UploadPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final StaffDocumentRepository staffDocumentRepository;
    private final StaffAuditEventRepository staffAuditEventRepository;
    private final SecurityAuditService securityAuditService;
    private final AuthorizationPolicyService authorizationPolicy;
    private final MediaAttachmentService mediaAttachmentService;

    public UserService(
            UserRepository userRepository,
            SecurityUtils securityUtils,
            StaffDocumentRepository staffDocumentRepository,
            StaffAuditEventRepository staffAuditEventRepository,
            SecurityAuditService securityAuditService,
            AuthorizationPolicyService authorizationPolicy,
            MediaAttachmentService mediaAttachmentService
    ) {
        this.userRepository = userRepository;
        this.securityUtils = securityUtils;
        this.staffDocumentRepository = staffDocumentRepository;
        this.staffAuditEventRepository = staffAuditEventRepository;
        this.securityAuditService = securityAuditService;
        this.authorizationPolicy = authorizationPolicy;
        this.mediaAttachmentService = mediaAttachmentService;
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll(org.springframework.data.domain.Sort.by("fullName").ascending())
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional(readOnly = true)
    public Object getVisibleUsers() {
        User actor = securityUtils.getCurrentUser();
        if (actor.getRole() == Role.ADMIN) {
            return getAllUsers();
        }
        if (actor.getRole() == Role.MANAGER) {
            List<ManagerDirectoryResponse> visible = new java.util.ArrayList<>();
            visible.add(mapManagerDirectory(actor));
            visible.addAll(
                    userRepository.findActiveManagedEmployees(actor.getId()).stream()
                            .map(this::mapManagerDirectory)
                            .toList()
            );
            return visible;
        }
        throw new ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "Directory access is not available."
        );
    }

    @Transactional(readOnly = true)
    public Object getVisibleUsersPaged(
            int page,
            int size,
            String sortBy,
            String sortDir,
            String search,
            Role role,
            Boolean active,
            AccountStatus accountStatus,
            EmploymentType employmentType,
            String department,
            boolean incompleteOnly
    ) {
        User actor = securityUtils.getCurrentUser();
        var pageable = Pagination.page(page, size, sortBy, sortDir,
                Set.of("fullName", "email", "employeeId", "department", "joiningDate", "lastLoginAt"));
        if (actor.getRole() == Role.ADMIN) {
            return getUsersPaged(
                    pageable, search, role, active, accountStatus,
                    employmentType, department, incompleteOnly
            );
        }
        if (actor.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Directory access is not available."
            );
        }
        return PageResponse.from(
                userRepository.findVisibleDirectoryToManager(actor.getId(), pageable),
                this::mapManagerDirectory
        );
    }

    public PageResponse<UserResponse> getUsersPaged(
            org.springframework.data.domain.Pageable pageable,
            String search,
            Role role,
            Boolean active,
            AccountStatus accountStatus,
            EmploymentType employmentType,
            String department,
            boolean incompleteOnly
    ) {
        String query = Pagination.filter(search, "search");
        String departmentFilter = Pagination.filter(department, "department");
        Specification<User> specification = Specification.where(null);
        if (query != null) {
            String like = "%" + query + "%";
            specification = specification.and((root, ignored, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("employeeId")), like),
                    cb.like(cb.lower(root.get("department")), like)
            ));
        }
        if (role != null) specification = specification.and((root, ignored, cb) -> cb.equal(root.get("role"), role));
        if (active != null) specification = specification.and((root, ignored, cb) -> cb.equal(root.get("active"), active));
        if (employmentType != null) specification = specification.and((root, ignored, cb) -> cb.equal(root.get("employmentType"), employmentType));
        if (departmentFilter != null) specification = specification.and((root, ignored, cb) ->
                cb.equal(cb.lower(root.get("department")), departmentFilter));
        if (accountStatus != null) specification = specification.and(accountStatusSpecification(accountStatus));
        if (incompleteOnly) specification = specification.and((root, ignored, cb) -> cb.or(
                cb.isNull(root.get("employeeId")), cb.isNull(root.get("joiningDate")),
                cb.isNull(root.get("jobTitle")), cb.isNull(root.get("phone")),
                cb.isNull(root.get("emergencyContact")), cb.isNull(root.get("department")),
                cb.isNull(root.get("location"))
        ));
        return PageResponse.from(userRepository.findAll(specification, pageable), this::map);
    }

    public PageResponse<UserResponse> getUsersPaged(
            int page,
            int size,
            String search,
            Role role,
            Boolean active,
            AccountStatus accountStatus,
            EmploymentType employmentType,
            String department,
            boolean incompleteOnly
    ) {
        return getUsersPaged(
                Pagination.page(page, size, "fullName", "asc", Set.of("fullName")),
                search, role, active, accountStatus, employmentType, department, incompleteOnly
        );
    }

    private Specification<User> accountStatusSpecification(AccountStatus status) {
        return (root, ignored, cb) -> switch (status) {
            case VERIFIED -> cb.and(cb.isTrue(root.get("emailVerified")), cb.isFalse(root.get("mustChangePassword")));
            case SETUP_PENDING -> cb.or(cb.isNotNull(root.get("passwordOtpHash")), cb.isNotNull(root.get("passwordResetTokenHash")));
            case INVITE_SENT -> cb.and(
                    cb.or(cb.isFalse(root.get("emailVerified")), cb.isTrue(root.get("mustChangePassword"))),
                    cb.isNull(root.get("passwordOtpHash")), cb.isNull(root.get("passwordResetTokenHash"))
            );
        };
    }

    public StaffDirectorySummaryResponse getStaffDirectorySummary() {
        List<UserResponse> users = getAllUsers();
        List<String> departments = users.stream()
                .map(UserResponse::department)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new StaffDirectorySummaryResponse(
                users.size(),
                users.stream().filter(user -> Boolean.TRUE.equals(user.active())).count(),
                users.stream().filter(user -> user.role() == Role.MANAGER).count(),
                users.stream().filter(user -> user.accountStatus() != AccountStatus.VERIFIED).count(),
                users.stream().filter(this::hasIncompleteRecord).count(),
                departments
        );
    }

    public UserResponse map(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getJobTitle(),
                user.getProfileMediaAsset() == null ? null : user.getProfilePhotoUrl(),
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

    @Transactional
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

        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        // Only check email uniqueness if it's changing
        if (!user.getEmail().equalsIgnoreCase(normalizedEmail)
                && userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResponseStatusException(CONFLICT, "Email already in use");
        }
        if (request.employeeId() != null && !request.employeeId().isBlank()) {
            userRepository.findByEmployeeIdIgnoreCase(request.employeeId().trim())
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new ResponseStatusException(CONFLICT, "Employee ID already in use");
                    });
        }

        String before = summary(user);
        boolean identityChanged = !user.getEmail().equalsIgnoreCase(request.email());
        boolean beingDeactivated = !Boolean.FALSE.equals(user.getActive())
                && Boolean.FALSE.equals(request.active());
        user.setFullName(request.fullName());
        user.setEmail(normalizedEmail);
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
        if (identityChanged || beingDeactivated) {
            user.setSecurityVersion(user.getSecurityVersion() + 1);
        }

        User updatedUser = userRepository.save(user);
        audit(updatedUser, actor, StaffAuditAction.UPDATED, "Updated staff profile. Before: " + before + ". After: " + summary(updatedUser));
        if (beingDeactivated) {
            securityAuditService.record(
                    actor.getId(), updatedUser.getId(), "ACCOUNT_DISABLED", "SUCCESS", "ADMIN_UPDATE",
                    updatedUser.getEmail(), null
            );
        }

        return map(updatedUser);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        User actor = securityUtils.getCurrentUser();
        if (actor.getId().equals(user.getId())) {
            throw new ResponseStatusException(BAD_REQUEST, "You cannot deactivate your own admin account.");
        }
        // Soft delete: deactivate the user
        user.setActive(false);
        user.setSecurityVersion(user.getSecurityVersion() + 1);
        User saved = userRepository.save(user);
        audit(saved, actor, StaffAuditAction.DEACTIVATED, "Deactivated staff account.");
        securityAuditService.record(
                actor.getId(), saved.getId(), "ACCOUNT_DISABLED", "SUCCESS", "ADMIN_DEACTIVATE",
                saved.getEmail(), null
        );
    }

    @Transactional
    public StaffDocumentResponse addDocument(Long userId, CreateStaffDocumentRequest request) {
        User staffUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        User actor = securityUtils.getCurrentUser();
        if (actor.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Administrator access is required."
            );
        }
        StaffDocument document = new StaffDocument();
        document.setUser(staffUser);
        document.setDocumentType(request.documentType());
        document.setFileName("pending");
        document.setNote(normalize(request.note()));
        StaffDocument saved = staffDocumentRepository.saveAndFlush(document);
        MediaAsset asset = mediaAttachmentService.attach(
                request.mediaAssetId(),
                UploadPurpose.HR_DOCUMENT,
                actor,
                staffUser,
                "STAFF_DOCUMENT",
                saved.getId().toString()
        );
        saved.setMediaAsset(asset);
        saved.setFileName(asset.getOriginalFilename());
        saved.setFileUrl(null);
        saved.setLegacyAssetStatus("NONE");
        saved = staffDocumentRepository.save(saved);
        audit(staffUser, actor, StaffAuditAction.DOCUMENT_ADDED, "Added document: " + saved.getFileName());
        securityAuditService.recordWithDetails(
                actor.getId(), staffUser.getId(), "HR_DOCUMENT_ADDED", "SUCCESS",
                "ADMIN_OPERATION", "documentId=" + saved.getId(), staffUser.getEmail(),
                RequestMetadata.current()
        );
        return mapDocument(saved);
    }

    public List<StaffDocumentResponse> getMyDocuments() {
        User currentUser = securityUtils.getCurrentUser();
        List<StaffDocumentResponse> documents =
                staffDocumentRepository.findByUserIdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(this::mapDocument)
                .toList();
        securityAuditService.record(
                currentUser.getId(), currentUser.getId(), "HR_DOCUMENTS_VIEWED", "SUCCESS",
                "SELF_ACCESS", currentUser.getEmail(), RequestMetadata.current()
        );
        return documents;
    }

    @Transactional
    public void deleteDocument(Long userId, Long documentId) {
        StaffDocument document = staffDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found"));
        if (!document.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found");
        }
        User actor = securityUtils.getCurrentUser();
        if (!authorizationPolicy.canManageHrDocument(actor, document.getUser(), document)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "You do not have permission to delete this document."
            );
        }
        String fileName = document.getFileName();
        User staffUser = document.getUser();
        mediaAttachmentService.deleteAttached(
                document.getMediaAsset(), actor, "HR_DOCUMENT_REMOVED"
        );
        staffDocumentRepository.delete(document);
        audit(staffUser, actor, StaffAuditAction.DOCUMENT_REMOVED, "Removed document: " + fileName);
        securityAuditService.recordWithDetails(
                actor.getId(), staffUser.getId(), "HR_DOCUMENT_DELETED", "SUCCESS",
                "ADMIN_OPERATION", "documentId=" + documentId, staffUser.getEmail(),
                RequestMetadata.current()
        );
    }

    public StaffDocumentResponse mapDocument(StaffDocument document) {
        return new StaffDocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getFileName(),
                document.getMediaAsset() == null ? null : document.getMediaAsset().getId(),
                document.getMediaAsset() == null
                        ? null : "/api/v1/media/assets/" + document.getMediaAsset().getId() + "/download",
                document.getLegacyAssetStatus(),
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
        if (user.getPasswordOtpHash() != null || user.getPasswordResetTokenHash() != null) {
            return AccountStatus.SETUP_PENDING;
        }
        return AccountStatus.INVITE_SENT;
    }

    public ManagerDirectoryResponse mapManagerDirectory(User user) {
        return new ManagerDirectoryResponse(
                user.getId(),
                user.getEmployeeId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getJobTitle(),
                user.getDepartment(),
                user.getActive(),
                user.getProfileMediaAsset() == null ? null : user.getProfilePhotoUrl()
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private boolean hasIncompleteRecord(UserResponse user) {
        return user.employeeId() == null
                || user.joiningDate() == null
                || user.jobTitle() == null
                || user.phone() == null
                || user.emergencyContact() == null
                || user.department() == null
                || user.location() == null;
    }

    private String summary(User user) {
        return "name=%s, email=%s, role=%s, active=%s, employeeId=%s"
                .formatted(user.getFullName(), user.getEmail(), user.getRole(), user.getActive(), user.getEmployeeId());
    }
}
