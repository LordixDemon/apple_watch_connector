package dev.applewatchandroid.bridge;

/**
 * Thrown on unrecoverable host HAL or protocol failures.
 */
public class HostException extends Exception {
    public HostException(String message) {
        super(message);
    }

    public HostException(String message, Throwable cause) {
        super(message, cause);
    }
}
