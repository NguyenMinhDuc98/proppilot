package dev.proppilot.actions;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Answers every refusal of the actions API with the same body: {@code {code, message}}. */
@RestControllerAdvice(assignableTypes = ActionController.class)
class ActionExceptionHandler {

    record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(ActionException.class)
    ResponseEntity<ErrorBody> refused(ActionException e) {
        return ResponseEntity.status(e.error().status()).body(new ErrorBody(e.error().code(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ErrorBody> unreadable() {
        var error = ActionError.INVALID_REQUEST;
        return ResponseEntity.status(error.status())
                .body(new ErrorBody(error.code(), "The request body must be a JSON object."));
    }
}
