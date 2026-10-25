package io.github.rj318.peerlens.web;

import org.springframework.http.HttpStatus;

/** An error the API reports to the caller as an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }
}
