package com.artacademy.payment.vnpay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.LinkedMultiValueMap;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class VnPayReturnControllerQueryDrTest {
    @Test
    void browserSuccessFieldsAreIgnoredAndOnlyMerchantReferenceStartsServerQuery() {
        ObjectProvider<VnPayQueryDrService> provider = mock();
        VnPayQueryDrService service = mock();
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.refreshAfterBrowserReturn("dca-known-reference"))
                .thenReturn(VnPayQueryDrService.Result.notConfirmed());
        var controller = new VnPayReturnController(provider);
        var params = new LinkedMultiValueMap<String, String>();
        params.add("vnp_TxnRef", "dca-known-reference");
        params.add("vnp_ResponseCode", "00");
        params.add("vnp_TransactionStatus", "00");
        params.add("vnp_Amount", "1");

        var response = controller.paymentReturnPage(params);

        assertThat(response.getBody()).contains("does not confirm your payment");
        verify(service).refreshAfterBrowserReturn("dca-known-reference");
    }

    @Test
    void duplicateReturnReferenceDoesNotTriggerQuery() {
        ObjectProvider<VnPayQueryDrService> provider = mock();
        VnPayQueryDrService service = mock();
        when(provider.getIfAvailable()).thenReturn(service);
        var controller = new VnPayReturnController(provider);
        var params = new LinkedMultiValueMap<String, String>();
        params.add("vnp_TxnRef", "first");
        params.add("vnp_TxnRef", "second");

        controller.paymentReturnPage(params);

        verifyNoInteractions(service);
    }
}
