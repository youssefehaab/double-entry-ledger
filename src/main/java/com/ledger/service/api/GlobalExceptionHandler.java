package com.ledger.service.api;

import com.ledger.service.api.dto.ErrorResponse;
import com.ledger.service.service.exception.AccountNotFoundException;
import com.ledger.service.service.exception.IdempotencyKeyConflictException;
import com.ledger.service.service.exception.TransactionNotFoundException;
import com.ledger.service.service.exception.UnbalancedTransactionException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Uniform 4xx error handling for the API layer, kept independent of the
 * persistence layer so validation failures never leak entity/JPA details
 * into responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Validation Failed", messages));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "Malformed Request",
                        List.of("request body could not be parsed - check field types and enum values")));
    }

    /**
     * A path variable that could not be converted to its declared Java type -
     * in practice on this API, always a malformed (non-UUID) {@code {id}}
     * segment on {@code GET /transactions/{id}}, {@code GET
     * /accounts/{id}/balance}, or {@code GET /accounts/{id}/entries}. Without
     * this handler, Spring's conversion failure propagates as an unhandled
     * exception and falls through to the generic {@link #handleUnexpected}
     * 500 below - an undocumented 500 for what is, from the client's
     * perspective, simply a bad request. Mapped to 400 instead, with a
     * message naming the offending parameter and the value it could not
     * accept, so a client sees exactly what was wrong.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the expected type";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "Malformed Request",
                        List.of(ex.getName() + ": '" + ex.getValue() + "' is not a valid " + requiredType)));
    }

    /**
     * The request's {@code Content-Type} is not one this endpoint's {@code
     * @RequestBody} converters accept (e.g. {@code text/plain} against an
     * endpoint that only reads JSON). Spring's default behavior for this is
     * 415 Unsupported Media Type; without this handler it fell through to
     * the generic {@link #handleUnexpected} 500 below in this app because
     * the catch-all {@code Exception} handler intercepted it before
     * Spring's own {@code HandlerExceptionResolver} chain could apply its
     * default mapping. Deliberately mapped to 415 (not 400): 415 is the
     * semantically precise status for "the payload's media type is
     * unsupported" per RFC 9110 - unlike a 400, it lets a client
     * distinguish "fix your Content-Type header" from "fix your JSON body".
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                        "Unsupported Media Type",
                        List.of("Content-Type '" + ex.getContentType() + "' is not supported; use application/json")));
    }

    /** Missing {@code Idempotency-Key} header on POST /transactions: required, so this is a 400. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "Missing Required Header",
                        List.of(ex.getHeaderName() + " header is required")));
    }

    /** Blank/too-long {@code Idempotency-Key} header (validated via {@code @Validated} on the controller). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> messages = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Validation Failed", messages));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Account Not Found", List.of(ex.getMessage())));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Transaction Not Found", List.of(ex.getMessage())));
    }

    /** App-level pre-check failure: entries do not balance. The DB trigger (V5) is the ultimate backstop. */
    @ExceptionHandler(UnbalancedTransactionException.class)
    public ResponseEntity<ErrorResponse> handleUnbalanced(UnbalancedTransactionException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of(
                        HttpStatus.UNPROCESSABLE_ENTITY.value(), "Unbalanced Transaction", List.of(ex.getMessage())));
    }

    /** Same Idempotency-Key reused with a different request body. */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "Idempotency Key Conflict", List.of(ex.getMessage())));
    }

    /**
     * Last-resort backstop for a DB constraint violation that was not one of
     * the specific, precisely-handled cases above (e.g.
     * {@code TransactionService} already handles the idempotency-key
     * UNIQUE-violation race explicitly and never lets it reach here under
     * normal operation). Mapped to 409 rather than a raw 500 so a client
     * never sees a bare stack trace for what is, at root, a conflicting
     * write.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Unhandled DataIntegrityViolationException reached GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        HttpStatus.CONFLICT.value(),
                        "Conflict",
                        List.of("the request conflicts with a database constraint")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception reached GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        List.of("an unexpected error occurred")));
    }
}
