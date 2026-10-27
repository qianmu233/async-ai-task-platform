package com.example.aitask.model;

public class MockModelClient implements ModelClient {
    @Override
    public String process(String input) {
        if (input.contains("[MOCK_FAIL]")) {
            throw new IllegalStateException("Simulated model failure");
        }
        return "[MOCK] " + input.strip();
    }
}
