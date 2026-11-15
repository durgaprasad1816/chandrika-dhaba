package com.chandrika.dhaba;

/** An error that should be shown to the caller as {"error": "..."} with an HTTP status. */
final class ApiException extends RuntimeException {
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    static ApiException bad(String message) { return new ApiException(400, message); }
    static ApiException notFound(String message) { return new ApiException(404, message); }
    static ApiException conflict(String message) { return new ApiException(409, message); }
}
