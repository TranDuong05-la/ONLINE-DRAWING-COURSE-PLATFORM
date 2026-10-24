package com.artacademy.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.UnsupportedEncodingException;
import java.util.Map;

/**
 * Shared email service for the Art Academy platform.
 * Formats emails with rich Thymeleaf templates for a polished, professional look.
 * Other modules (e.g., Auth, Certificate, Notification services) can inject this service
 * to send responsive, branded emails.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final ITemplateEngine templateEngine;

    @Value("${spring.mail.username:support@artacademy.com}")
    private String fromEmail = "support@artacademy.com";

    public EmailService(JavaMailSender mailSender, ITemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    /**
     * Send an email rendered from a Thymeleaf template.
     *
     * @param to           Recipient email address
     * @param subject      Email subject line
     * @param templateName Path to Thymeleaf template (e.g. "email/welcome" or "email/reset-password")
     * @param variables    Key-value pairs to populate inside the template
     */
    public void sendTemplateEmail(String to, String subject, String templateName, Map<String, Object> variables) {
        try {
            Context context = new Context();
            if (variables != null) {
                context.setVariables(variables);
            }
            context.setVariable("subject", subject);

            String htmlBody = templateEngine.process(templateName, context);
            sendHtmlEmail(to, subject, htmlBody);
            log.info("Template email [{}] sent successfully to: {}", templateName, to);
        } catch (Exception e) {
            log.error("Failed to render and send template email [{}] to: {}", templateName, to, e);
            throw new RuntimeException("Failed to send template email to: " + to, e);
        }
    }

    /**
     * Send a branded welcome email to newly registered students.
     *
     * @param to        Student email address
     * @param fullName  Student full name
     * @param actionUrl Link to dashboard or courses page
     */
    public void sendWelcomeEmail(String to, String fullName, String actionUrl) {
        Map<String, Object> variables = Map.of(
                "fullName", fullName != null ? fullName : "Học viên",
                "actionUrl", actionUrl != null ? actionUrl : "http://localhost:3000"
        );
        sendTemplateEmail(to, "Chào mừng bạn đến với Art Academy! 🎨", "email/welcome", variables);
    }

    /**
     * Send a secure password reset / OTP verification email.
     *
     * @param to            User email address
     * @param fullName      User full name
     * @param resetUrl      Password reset URL (optional, can be null if using OTP)
     * @param otpCode       OTP verification code (optional, can be null if using reset link)
     * @param expireMinutes Expiration time in minutes
     */
    public void sendPasswordResetEmail(String to, String fullName, String resetUrl, String otpCode, Integer expireMinutes) {
        Context context = new Context();
        context.setVariable("fullName", fullName != null ? fullName : "Học viên");
        if (resetUrl != null) context.setVariable("resetUrl", resetUrl);
        if (otpCode != null) context.setVariable("otpCode", otpCode);
        context.setVariable("expireMinutes", expireMinutes != null ? expireMinutes : 15);

        String htmlBody = templateEngine.process("email/reset-password", context);
        sendHtmlEmail(to, "[Art Academy] Yêu cầu đặt lại mật khẩu của bạn", htmlBody);
    }

    /**
     * Send course completion certificate notification email.
     *
     * @param to              Student email address
     * @param fullName        Student full name
     * @param courseName      Completed course name
     * @param certificateCode Official certificate code (e.g., ART-CERT-2026-001)
     * @param certificateUrl  Link to view or download certificate
     * @param issueDate       Date of issue (e.g., "24/10/2026")
     */
    public void sendCertificateEmail(String to, String fullName, String courseName,
                                     String certificateCode, String certificateUrl, String issueDate) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("courseName", courseName);
        context.setVariable("certificateCode", certificateCode);
        context.setVariable("certificateUrl", certificateUrl);
        if (issueDate != null) context.setVariable("issueDate", issueDate);

        String htmlBody = templateEngine.process("email/certificate", context);
        sendHtmlEmail(to, "🏆 Chúc mừng bạn đã hoàn thành khóa học: " + courseName, htmlBody);
    }

    /**
     * Send a general notification email with optional CTA button and details list.
     *
     * @param to         Recipient email address
     * @param fullName   Recipient name
     * @param subject    Subject line
     * @param title      Notification title
     * @param message    Main message body (HTML supported)
     * @param actionText Button label (optional)
     * @param actionUrl  Button URL (optional)
     * @param details    Key-value summary dictionary (optional)
     */
    public void sendNotificationEmail(String to, String fullName, String subject, String title,
                                      String message, String actionText, String actionUrl,
                                      Map<String, String> details) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("title", title);
        context.setVariable("message", message);
        if (actionText != null) context.setVariable("actionText", actionText);
        if (actionUrl != null) context.setVariable("actionUrl", actionUrl);
        if (details != null) context.setVariable("details", details);

        String htmlBody = templateEngine.process("email/notification", context);
        sendHtmlEmail(to, subject, htmlBody);
    }

    /**
     * Send raw HTML email.
     *
     * @param to       Recipient email address
     * @param subject  Email subject
     * @param htmlBody HTML email body
     */
    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            try {
                helper.setFrom(fromEmail, "Art Academy");
            } catch (UnsupportedEncodingException e) {
                helper.setFrom(fromEmail);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(mimeMessage);
            log.info("HTML email sent successfully to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send HTML email to: {}", to, e);
            throw new RuntimeException("Failed to send HTML email to: " + to, e);
        }
    }

    /**
     * Send plain text email (fallback).
     *
     * @param to      Recipient email address
     * @param subject Email subject
     * @param body    Plain text body
     */
    public void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("Art Academy <" + fromEmail + ">");
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Plain text email sent successfully to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to: {}", to, e);
            throw new RuntimeException("Failed to send email to: " + to, e);
        }
    }
}
