package com.artacademy.learning.certificate;

/** Published inside the issuing transaction; email is sent only after that transaction commits. */
public record CertificateIssuedEvent(Long certificateId) {
}
