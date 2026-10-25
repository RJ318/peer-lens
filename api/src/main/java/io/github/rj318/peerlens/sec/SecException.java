package io.github.rj318.peerlens.sec;

/** A request to SEC EDGAR failed in a way the caller should report rather than retry. */
public class SecException extends RuntimeException {

    public SecException(String message) {
        super(message);
    }

    public SecException(String message, Throwable cause) {
        super(message, cause);
    }
}
