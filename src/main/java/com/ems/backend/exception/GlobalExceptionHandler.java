package com.ems.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.ems.backend.security.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        if (errors.isEmpty()) {
            return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Invalid request. Check your input and try again.", request);
        }
        FieldError first = errors.get(0);
        String field = first.getField();
        String defaultMessage = first.getDefaultMessage();
        String message = switch (field) {
            case "email" -> "Please enter a valid email address.";
            case "password" -> defaultMessage != null && defaultMessage.toLowerCase().contains("blank")
                    ? "Password is required."
                    : "Please check your password and try again.";
            default -> defaultMessage != null ? defaultMessage : "Invalid value for " + field;
        };
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_MALFORMED", "Request body is missing or not valid JSON.", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleUploadTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "UPLOAD_TOO_LARGE",
                "The upload exceeds the maximum allowed request size.",
                request
        );
    }

    @ExceptionHandler({
            MissingServletRequestPartException.class,
            MultipartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<?> handleMalformedMultipart(
            Exception exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "UPLOAD_REQUEST_INVALID",
                "The upload request must contain one valid purpose and one file.",
                request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "CONTENT_TYPE_UNSUPPORTED",
                "The request content type is not supported.",
                request
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatusException(
            ResponseStatusException e,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.valueOf(e.getStatusCode().value()),
                errorCode(e.getStatusCode().value()),
                e.getReason() != null ? e.getReason() : "The request could not be completed.",
                request
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> handleBadCredentialsException(
            BadCredentialsException e,
            HttpServletRequest request
    ) {
        return response(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_INVALID", "Invalid email or password.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDeniedException(
            AccessDeniedException e,
            HttpServletRequest request
    ) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission for this action.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        String constraint = constraintName(exception);
        log.warn("Database constraint rejected request constraint={} correlationId={}",
                constraint, request.getAttribute(RequestMetadata.CORRELATION_ATTRIBUTE));
        if ("uq_attendance_sessions_one_active_user".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "ATTENDANCE_SESSION_ALREADY_ACTIVE",
                    "This user already has an active attendance session.", request);
        }
        if ("uq_attendance_corrections_one_pending_session".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "ATTENDANCE_CORRECTION_ALREADY_PENDING",
                    "A correction for this attendance session is already pending.", request);
        }
        if ("ex_attendance_sessions_no_overlap".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "ATTENDANCE_SESSION_OVERLAP",
                    "This attendance period overlaps another session.", request);
        }
        if ("ex_leave_requests_no_active_overlap".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "LEAVE_REQUEST_OVERLAP",
                    "This leave request overlaps pending or approved leave.", request);
        }
        if ("uq_project_payments_project_idempotency".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "PAYMENT_DUPLICATE_SUBMISSION",
                    "This payment submission has already been processed.", request);
        }
        if ("uq_users_employee_id_ci".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "EMPLOYEE_ID_ALREADY_EXISTS",
                    "That employee ID is already in use.", request);
        }
        if (constraint != null && (
                constraint.startsWith("uq_users_")
                        || constraint.startsWith("uq_user_identity_")
                        || constraint.equals("user_identity_claims_pkey")
        )) {
            return response(HttpStatus.CONFLICT, "IDENTITY_ALREADY_EXISTS",
                    "That email address is already in use or awaiting verification.", request);
        }
        if ("uq_project_task_boards_name_ci".equals(constraint)
                || "uk_project_task_boards_project_key".equals(constraint)) {
            return response(HttpStatus.CONFLICT, "PROJECT_BOARD_NAME_CONFLICT",
                    "A board with that name already exists in this project.", request);
        }
        return response(HttpStatus.BAD_REQUEST, "DATABASE_CONSTRAINT_VIOLATION",
                "The requested change violates a data integrity rule.", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, "CONCURRENT_UPDATE_CONFLICT",
                "This record was changed by another request. Refresh and try again.", request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<?> handleDatabaseLockFailure(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        log.warn("Retryable database lock failure correlationId={}",
                request.getAttribute(RequestMetadata.CORRELATION_ATTRIBUTE));
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_RETRY_REQUIRED",
                "The record is busy. Please retry the request.", request);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<?> handleDatabaseUnavailable(
            DataAccessResourceFailureException exception,
            HttpServletRequest request
    ) {
        log.error("Database unavailable correlationId={}",
                request.getAttribute(RequestMetadata.CORRELATION_ATTRIBUTE));
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "The service is temporarily unavailable. Please try again later.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneralException(Exception e, HttpServletRequest request) {
        Object correlation = request.getAttribute(RequestMetadata.CORRELATION_ATTRIBUTE);
        log.error("Unhandled request failure correlationId={}", correlation, e);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Something went wrong on the server. Please try again later.",
                request
        );
    }

    private ResponseEntity<?> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        Object correlation = request.getAttribute(RequestMetadata.CORRELATION_ATTRIBUTE);
        if (correlation != null) {
            body.put("correlationId", correlation.toString());
        }
        return ResponseEntity.status(status).body(body);
    }

    private String errorCode(int status) {
        return switch (status) {
            case 400 -> "REQUEST_INVALID";
            case 401 -> "AUTHENTICATION_INVALID";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "RESOURCE_CONFLICT";
            case 413 -> "UPLOAD_TOO_LARGE";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "REQUEST_FAILED";
        };
    }

    private String constraintName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
