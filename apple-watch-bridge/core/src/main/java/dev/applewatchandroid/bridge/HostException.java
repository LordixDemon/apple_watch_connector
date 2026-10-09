package dev.applewatchandroid.bridge;

/**
 * Thrown on unrecoverable host HAL or protocol failures.
 */
class HostException extends Exception {
    HostException(String message) {
        super(message);
    }

    HostException(String message, Throwable cause) {
        super(message, cause);
    }
}
