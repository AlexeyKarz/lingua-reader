package com.example.reader.user;

public record ReviewStateDto(
        String status,
        long dueAt,
        int intervalDays,
        double ease,
        int reviewCount,
        Long lastReviewedAt
) {}
