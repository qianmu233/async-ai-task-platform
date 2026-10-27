package com.example.aitask.model;

/** Safe diagnostics only. Never retains provider body, credentials, input, URL or original cause. */
public final class ModelCallException extends RuntimeException {
    public enum Kind {
        TIMEOUT, RATE_LIMITED, AUTH_FAILED, UPSTREAM_UNAVAILABLE, REQUEST_REJECTED,
        HTTP_ERROR, RESPONSE_INVALID, RESPONSE_TOO_LARGE, TRANSPORT_FAILED, INTERRUPTED
    }
    private final Kind kind;
    public ModelCallException(Kind kind) {
        super("Model call failed: MODEL_" + kind.name());
        this.kind = kind;
    }
    public Kind kind() { return kind; }
    public String errorCode() { return "MODEL_" + kind.name(); }
}
