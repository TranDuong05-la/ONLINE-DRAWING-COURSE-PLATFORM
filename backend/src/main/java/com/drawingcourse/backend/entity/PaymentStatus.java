package com.drawingcourse.backend.entity;

/** Payment-attempt vocabulary. Refunds have a separate future lifecycle. */
public enum PaymentStatus { CREATED, PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, UNKNOWN }
