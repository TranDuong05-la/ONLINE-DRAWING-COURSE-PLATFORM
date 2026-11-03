package com.artacademy.supply.inventory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.supply.catalog.StockSnapshot;
import com.artacademy.supply.catalog.SupplyProductRepository;
import com.artacademy.supply.common.SupplyException;

/**
 * Nơi duy nhất thay đổi số tồn. Mỗi thao tác: một câu UPDATE nguyên tử + ghi lịch sử.
 * Luôn chạy trong transaction của service gọi nó.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class StockLedger {

    private final SupplyProductRepository productRepository;
    private final InventoryTransactionRepository transactionRepository;

    public StockLedger(SupplyProductRepository productRepository,
            InventoryTransactionRepository transactionRepository) {
        this.productRepository = productRepository;
        this.transactionRepository = transactionRepository;
    }

    public InventoryTransaction importStock(Long productId, int quantity, String note, Integer actorUserId) {
        if (quantity <= 0) {
            throw SupplyException.badRequest("INVALID_QUANTITY", "Số lượng nhập phải lớn hơn 0");
        }
        if (productRepository.addOnHand(productId, quantity) == 0) {
            throw SupplyException.notFound("Học cụ");
        }
        return log(productId, InventoryTransactionType.IMPORT, quantity, null, null, false, note, actorUserId);
    }

    /** Trả false nếu không đủ hàng còn bán được. */
    public boolean tryReserve(Long productId, int quantity, Integer orderId, boolean gift) {
        if (productRepository.tryReserve(productId, quantity) == 0) {
            return false;
        }
        log(productId, InventoryTransactionType.RESERVE, quantity, orderId, null, gift, null, null);
        return true;
    }

    public void releaseReserved(Long productId, int quantity, Integer orderId, boolean gift, String reason) {
        if (productRepository.releaseReserved(productId, quantity) == 0) {
            throw new IllegalStateException("Số giữ của sản phẩm " + productId + " nhỏ hơn " + quantity);
        }
        log(productId, InventoryTransactionType.RELEASE, quantity, orderId, null, gift, reason, null);
    }

    public void exportReserved(Long productId, int quantity, Integer orderId, Long shipmentId, boolean gift,
            Integer actorUserId) {
        if (productRepository.exportReserved(productId, quantity) == 0) {
            throw new IllegalStateException("Không thể xuất " + quantity + " của sản phẩm " + productId);
        }
        log(productId, InventoryTransactionType.EXPORT, quantity, orderId, shipmentId, gift, null, actorUserId);
    }

    public void returnToStock(Long productId, int quantity, Integer orderId, Long shipmentId, boolean gift,
            String note, Integer actorUserId) {
        productRepository.addOnHand(productId, quantity);
        log(productId, InventoryTransactionType.RETURN, quantity, orderId, shipmentId, gift, note, actorUserId);
    }

    private InventoryTransaction log(Long productId, InventoryTransactionType type, int quantity, Integer orderId,
            Long shipmentId, boolean gift, String note, Integer actorUserId) {
        StockSnapshot stock = productRepository.findStock(productId)
                .orElseThrow(() -> SupplyException.notFound("Học cụ"));
        InventoryTransaction tx = new InventoryTransaction();
        tx.setProduct(productRepository.getReferenceById(productId));
        tx.setType(type);
        tx.setQuantity(quantity);
        tx.setOrderId(orderId);
        tx.setShipmentId(shipmentId);
        tx.setGift(gift);
        tx.setOnHandAfter(stock.onHand());
        tx.setReservedAfter(stock.reserved());
        tx.setNote(note);
        tx.setActorUserId(actorUserId);
        return transactionRepository.save(tx);
    }
}
