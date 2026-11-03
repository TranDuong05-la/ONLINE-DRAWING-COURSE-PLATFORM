package com.artacademy.supply.catalog;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.artacademy.product.Product;

/**
 * Repository của E cho bảng products (B có ProductRepository riêng để chọn quà, không đụng tới).
 */
public interface SupplyProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);

    /** Số tồn mới nhất, đọc thẳng từ DB. */
    @Query("select new com.artacademy.supply.catalog.StockSnapshot(p.stockOnHand, p.stockReserved) "
            + "from Product p where p.id = :id")
    Optional<StockSnapshot> findStock(@Param("id") Long id);

    /**
     * Giữ hàng. Điều kiện nằm trong cùng câu UPDATE nên khi hai người mua món cuối
     * cùng lúc, DB khóa dòng và chỉ một người thành công; không bao giờ âm kho.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.stockReserved = p.stockReserved + :q "
            + "where p.id = :id and p.stockOnHand - p.stockReserved >= :q")
    int tryReserve(@Param("id") Long id, @Param("q") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.stockReserved = p.stockReserved - :q "
            + "where p.id = :id and p.stockReserved >= :q")
    int releaseReserved(@Param("id") Long id, @Param("q") int quantity);

    /** Xuất kho: hàng đã giữ rời khỏi kho. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.stockOnHand = p.stockOnHand - :q, p.stockReserved = p.stockReserved - :q "
            + "where p.id = :id and p.stockReserved >= :q and p.stockOnHand >= :q")
    int exportReserved(@Param("id") Long id, @Param("q") int quantity);

    /** Nhập kho hoặc hàng trả về kho. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.stockOnHand = p.stockOnHand + :q where p.id = :id")
    int addOnHand(@Param("id") Long id, @Param("q") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.ratingAvg = :avg, p.ratingCount = :count where p.id = :id")
    int updateRating(@Param("id") Long id, @Param("avg") double avg, @Param("count") int count);
}
