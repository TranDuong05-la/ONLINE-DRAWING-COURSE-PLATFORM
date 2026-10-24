package com.artacademy.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ITemplateEngine templateEngine;

    @Mock
    private MimeMessage mimeMessage;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, templateEngine);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    @Test
    @DisplayName("sendWelcomeEmail renders welcome template and sends email")
    void sendWelcomeEmail_success() {
        when(templateEngine.process(eq("email/welcome"), any(IContext.class)))
                .thenReturn("<html><body>Welcome</body></html>");

        emailService.sendWelcomeEmail("student@example.com", "Nguyen Van A", "http://localhost:3000/courses");

        verify(templateEngine).process(eq("email/welcome"), any(IContext.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendPasswordResetEmail renders reset-password template and sends email")
    void sendPasswordResetEmail_success() {
        when(templateEngine.process(eq("email/reset-password"), any(IContext.class)))
                .thenReturn("<html><body>Reset Password</body></html>");

        emailService.sendPasswordResetEmail("student@example.com", "Nguyen Van A", "http://localhost:3000/reset", "123456", 15);

        verify(templateEngine).process(eq("email/reset-password"), any(IContext.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendCertificateEmail renders certificate template and sends email")
    void sendCertificateEmail_success() {
        when(templateEngine.process(eq("email/certificate"), any(IContext.class)))
                .thenReturn("<html><body>Certificate</body></html>");

        emailService.sendCertificateEmail("student@example.com", "Nguyen Van A", "Hoi hoa co ban", "ART-2026", "http://cert.url", "24/10/2026");

        verify(templateEngine).process(eq("email/certificate"), any(IContext.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendTemplateEmail executes custom template with variables")
    void sendTemplateEmail_success() {
        when(templateEngine.process(eq("email/notification"), any(IContext.class)))
                .thenReturn("<html><body>Notification</body></html>");

        emailService.sendTemplateEmail("student@example.com", "Title", "email/notification", Map.of("key", "value"));

        verify(templateEngine).process(eq("email/notification"), any(IContext.class));
        verify(mailSender).send(any(MimeMessage.class));
    }
}
