package com.example.aitask.service;

import java.util.regex.Pattern;
import java.text.Normalizer;

public final class TaskInputRules {
    /** Optional operator-owned acceptance namespace, never an SQL pattern supplied by an API caller. */
    public static String validateAcceptancePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) { return ""; }
        if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,95}:")) {
            throw new IllegalArgumentException("Acceptance key prefix must be a literal ASCII namespace ending in ':' without LIKE wildcards");
        }
        return prefix;
    }
    public static final int MAX_INPUT_LENGTH = 5000;
    public static final String NORMALIZATION_VERSION = "NFC_LF_V1";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private TaskInputRules() { }

    /** Preserve spaces/case; normalize Unicode composition and platform line endings only. */
    public static String normalizeInput(String input) {
        return input == null ? null : Normalizer.normalize(
                input.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
    }

    public static void validate(String key, String input) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new TaskException(TaskException.Kind.INVALID_REQUEST,
                    "idempotencyKey must contain 1-128 ASCII letters, digits, '.', '_', ':', or '-'");
        }
        if (input == null || input.isBlank() || input.length() > MAX_INPUT_LENGTH
                || !isWellFormedUnicode(input)) {
            throw new TaskException(TaskException.Kind.INVALID_REQUEST,
                    "input must be valid Unicode, non-blank and at most 5000 UTF-16 code units after normalization");
        }
    }

    public static boolean isWellFormedUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(value)) {
                return false;
            }
        }
        return true;
    }
}
