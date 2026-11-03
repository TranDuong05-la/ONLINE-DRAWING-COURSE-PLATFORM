package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.OrderLineSnapshot;
import java.math.BigInteger;
import java.util.*;

/** Pure largest-remainder allocator. Returned amounts correspond to input positions. */
public final class OrderDiscountAllocator {
    private record Share(int index, BigInteger remainder) { }

    public List<Long> allocate(List<OrderLineSnapshot> input, long discountVnd) {
        List<OrderLineSnapshot> lines = List.copyOf(input);
        if (discountVnd < 0) throw new IllegalArgumentException("Negative discount");
        Set<String> references = new HashSet<>();
        long subtotal = 0;
        for (var line : lines) {
            if (!references.add(line.itemType().name() + ":" + line.itemId())) {
                throw new IllegalArgumentException("Duplicate item reference");
            }
            if (line.voucherEligible()) subtotal = Math.addExact(subtotal, line.grossAmountVnd());
        }
        if (discountVnd > subtotal) throw new IllegalArgumentException("Discount exceeds eligible subtotal");
        List<Long> amounts = new ArrayList<>(Collections.nCopies(lines.size(), 0L));
        if (discountVnd == 0) return List.copyOf(amounts);
        List<Share> shares = new ArrayList<>();
        long allocated = 0;
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (!line.voucherEligible() || line.grossAmountVnd() == 0) continue;
            var result = BigInteger.valueOf(discountVnd).multiply(BigInteger.valueOf(line.grossAmountVnd()))
                    .divideAndRemainder(BigInteger.valueOf(subtotal));
            long amount = result[0].longValueExact();
            amounts.set(index, amount);
            allocated = Math.addExact(allocated, amount);
            shares.add(new Share(index, result[1]));
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed()
                .thenComparing(s -> lines.get(s.index()).itemType().name())
                .thenComparing(s -> lines.get(s.index()).itemId()));
        long remaining = discountVnd - allocated;
        for (int i = 0; i < remaining; i++) {
            int index = shares.get(i).index();
            amounts.set(index, Math.addExact(amounts.get(index), 1));
        }
        return List.copyOf(amounts);
    }
}
