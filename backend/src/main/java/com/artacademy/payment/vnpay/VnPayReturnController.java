package com.artacademy.payment.vnpay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;

/** Browser landing page only. Payment state is established exclusively by verified IPN reconciliation. */
@RestController
@RequestMapping("/api/payments/vnpay")
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
public final class VnPayReturnController {
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);
    private static final String RETURN_PAGE = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Payment status</title></head>
            <body><main><h1>Return to DrawingCourse</h1>
            <p>This redirect does not confirm your payment. Return to the Flutter app and check the Order status there.</p>
            </main></body></html>
            """;

    private final ObjectProvider<VnPayQueryDrService> queryDr;

    public VnPayReturnController(ObjectProvider<VnPayQueryDrService> queryDr) {
        this.queryDr = queryDr;
    }

    @GetMapping(path = "/return", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> paymentReturnPage(
            @RequestParam MultiValueMap<String, String> parameters) {
        // Return parameters are only an attempt locator; success and amount are established by
        // a fresh signed server-to-server QueryDR response when the explicitly local mode is active.
        List<String> references = parameters.get("vnp_TxnRef");
        VnPayQueryDrService service = queryDr.getIfAvailable();
        if (service != null && references != null && references.size() == 1) {
            try { service.refreshAfterBrowserReturn(references.getFirst()); }
            catch (RuntimeException ignored) {
                // The browser page remains neutral. The app must read persisted server status.
            }
        }
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(RETURN_PAGE);
    }
}
