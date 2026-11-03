package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentEventAudit;

/** Does not contain raw callback material or a payment URL. */
public record IpnReconciliationResult(IpnClassification classification, Integer paymentAttemptId,
                                      Integer orderId, Integer eventId, Integer conflictId,
                                      PaymentEventAudit.Disposition disposition,
                                      com.drawingcourse.backend.entity.PaymentAttempt.ReviewReason reviewReason,
                                      boolean duplicate) { }
