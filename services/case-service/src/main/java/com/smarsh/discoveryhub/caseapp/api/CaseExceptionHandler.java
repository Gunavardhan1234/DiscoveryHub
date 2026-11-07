package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.DomainException;
import com.smarsh.discoveryhub.common.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Maps {@code common} module domain exceptions to the RFC-7807 {@link ApiError}
 * envelope. case-service depends on {@code discoveryhub-common} (not
 * {@code discoveryhub-events}), so the shared handler there does not apply.
 */
// Global Exception Handling: @RestControllerAdvice applies to every @RestController in this
// service without each controller needing its own try/catch — a cross-cutting concern factored
// into one place (Separation of Concerns), so CaseController/EvidenceController/etc. can assume
// any thrown DomainException is translated into the right HTTP status automatically.
@RestControllerAdvice
public class CaseExceptionHandler {

    // Polymorphism: one handler method covers every subclass of DomainException (including
    // CaseStateMachine.InvalidCaseTransitionException and ConflictException/NotFoundException from
    // `common`) by calling the overridden httpStatus()/code() — new domain exceptions automatically
    // get correct handling here with zero changes to this class (Open/Closed Principle).
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        // Builder Pattern: ApiError.builder() assembles the RFC-7807 error envelope through named,
        // chainable setter calls rather than a constructor with 7 positional arguments — safer to
        // read/extend than remembering the exact ordering of a huge constructor call.
        return ResponseEntity.status(ex.httpStatus())
                .body(ApiError.builder()
                        .status(ex.httpStatus())
                        .code(ex.code())
                        .title(titleFor(ex.httpStatus()))
                        .detail(ex.getMessage())
                        .build());
    }

    @ExceptionHandler({NoSuchElementException.class, NotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex) {
        String code = ex instanceof NotFoundException nfe ? nfe.code() : "NOT_FOUND";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.builder()
                        .status(404)
                        .code(code)
                        .title("Not Found")
                        .detail(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.builder()
                        .status(409)
                        .code(ex.code())
                        .title("Conflict")
                        .detail(ex.getMessage())
                        .build());
    }

    // Catches the SPECIFIC exception Spring throws for a failed @Valid bean-validation check
    // (training's "catch specific exceptions, not generic Exception") — this is what turns a
    // blank CaseCreateRequest.name into a 400 VALIDATION_ERROR instead of an unhandled 500.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .title("Bad Request")
                        .detail(detail)
                        .build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .title("Bad Request")
                        .detail(detail)
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("BAD_REQUEST")
                        .title("Bad Request")
                        .detail(ex.getMessage())
                        .build());
    }

    // Modern Java switch expression (Java 14+): returns a value directly with no fall-through risk
    // and no `break` statements, more concise than an equivalent switch statement or if/else chain.
    private static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            default -> "Error";
        };
    }
}
