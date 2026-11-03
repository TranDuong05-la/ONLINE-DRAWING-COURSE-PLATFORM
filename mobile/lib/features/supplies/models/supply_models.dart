import 'package:flutter/material.dart';

int _int(dynamic v) => v == null ? 0 : (v as num).toInt();
double _double(dynamic v) => v == null ? 0 : (v as num).toDouble();
int? _intOrNull(dynamic v) => v == null ? null : (v as num).toInt();
Map<String, dynamic> _map(dynamic v) => Map<String, dynamic>.from(v as Map);

class SupplyCategory {
  final int id;
  final String name;
  final String? description;
  final bool active;
  final int sortOrder;

  SupplyCategory({
    required this.id,
    required this.name,
    this.description,
    required this.active,
    required this.sortOrder,
  });

  factory SupplyCategory.fromJson(dynamic json) {
    final j = _map(json);
    return SupplyCategory(
      id: _int(j['id']),
      name: j['name'] ?? '',
      description: j['description'],
      active: j['active'] ?? true,
      sortOrder: _int(j['sortOrder']),
    );
  }
}

/// Học cụ. Các trường kho chi tiết (stockOnHand, stockReserved, lowStockThreshold)
/// chỉ có khi gọi API quản lý (STAFF/ADMIN).
class SupplyProduct {
  final int id;
  final String sku;
  final String name;
  final String? description;
  final int price;
  final String? thumbnailUrl;
  final int? categoryId;
  final String? categoryName;
  final bool active;
  final int available;
  final bool inStock;
  final bool lowStock;
  final double ratingAvg;
  final int ratingCount;
  final int? stockOnHand;
  final int? stockReserved;
  final int? lowStockThreshold;

  SupplyProduct({
    required this.id,
    required this.sku,
    required this.name,
    this.description,
    required this.price,
    this.thumbnailUrl,
    this.categoryId,
    this.categoryName,
    required this.active,
    required this.available,
    required this.inStock,
    required this.lowStock,
    required this.ratingAvg,
    required this.ratingCount,
    this.stockOnHand,
    this.stockReserved,
    this.lowStockThreshold,
  });

  bool get hidden => !active;

  factory SupplyProduct.fromJson(dynamic json) {
    final j = _map(json);
    final available = _int(j['available']);
    return SupplyProduct(
      id: _int(j['id']),
      sku: j['sku'] ?? '',
      name: j['name'] ?? '',
      description: j['description'],
      price: _int(j['price']),
      thumbnailUrl: j['thumbnailUrl'],
      categoryId: _intOrNull(j['categoryId']),
      categoryName: j['categoryName'],
      active: j['active'] ?? true,
      available: available,
      inStock: j['inStock'] ?? available > 0,
      lowStock: j['lowStock'] ?? false,
      ratingAvg: _double(j['ratingAvg']),
      ratingCount: _int(j['ratingCount']),
      stockOnHand: _intOrNull(j['stockOnHand']),
      stockReserved: _intOrNull(j['stockReserved']),
      lowStockThreshold: _intOrNull(j['lowStockThreshold']),
    );
  }
}

class InventoryTx {
  final int id;
  final String type;
  final int quantity;
  final int? orderId;
  final int? shipmentId;
  final bool gift;
  final int onHandAfter;
  final int reservedAfter;
  final String? note;
  final String? createdAt;

  InventoryTx({
    required this.id,
    required this.type,
    required this.quantity,
    this.orderId,
    this.shipmentId,
    required this.gift,
    required this.onHandAfter,
    required this.reservedAfter,
    this.note,
    this.createdAt,
  });

  factory InventoryTx.fromJson(dynamic json) {
    final j = _map(json);
    return InventoryTx(
      id: _int(j['id']),
      type: j['type'] ?? '',
      quantity: _int(j['quantity']),
      orderId: _intOrNull(j['orderId']),
      shipmentId: _intOrNull(j['shipmentId']),
      gift: j['gift'] ?? false,
      onHandAfter: _int(j['onHandAfter']),
      reservedAfter: _int(j['reservedAfter']),
      note: j['note'],
      createdAt: j['createdAt'],
    );
  }

  String get label => switch (type) {
        'IMPORT' => 'Nhập kho',
        'RESERVE' => 'Giữ cho đơn',
        'RELEASE' => 'Trả giữ',
        'EXPORT' => 'Xuất kho',
        'RETURN' => 'Hàng về kho',
        _ => type,
      };

  IconData get icon => switch (type) {
        'IMPORT' => Icons.add_box_outlined,
        'RESERVE' => Icons.lock_clock_outlined,
        'RELEASE' => Icons.lock_open_outlined,
        'EXPORT' => Icons.local_shipping_outlined,
        'RETURN' => Icons.assignment_return_outlined,
        _ => Icons.circle_outlined,
      };
}

enum ShipmentStatus {
  preparing('PREPARING', 'Chuẩn bị', Icons.inventory_2_outlined, Colors.orange),
  shipping('SHIPPING', 'Đang giao', Icons.local_shipping_outlined, Colors.blue),
  delivered('DELIVERED', 'Giao thành công', Icons.check_circle_outline, Colors.green),
  failed('FAILED', 'Giao thất bại', Icons.error_outline, Colors.red),
  returned('RETURNED', 'Đã về kho', Icons.assignment_return_outlined, Colors.brown),
  cancelled('CANCELLED', 'Đã hủy', Icons.cancel_outlined, Colors.grey);

  final String code;
  final String label;
  final IconData icon;
  final MaterialColor color;
  const ShipmentStatus(this.code, this.label, this.icon, this.color);

  static ShipmentStatus parse(String? code) =>
      values.firstWhere((s) => s.code == code, orElse: () => ShipmentStatus.preparing);

  /// Nhãn nút hành động cho nhân viên.
  String get actionLabel => switch (this) {
        ShipmentStatus.shipping => 'Chuyển sang đang giao',
        ShipmentStatus.delivered => 'Đã giao thành công',
        ShipmentStatus.failed => 'Giao thất bại',
        ShipmentStatus.returned => 'Hàng đã về kho',
        _ => label,
      };
}

class ShipmentItem {
  final int productId;
  final String sku;
  final String productName;
  final String? thumbnailUrl;
  final int quantity;
  final bool gift;

  ShipmentItem({
    required this.productId,
    required this.sku,
    required this.productName,
    this.thumbnailUrl,
    required this.quantity,
    required this.gift,
  });

  factory ShipmentItem.fromJson(dynamic json) {
    final j = _map(json);
    return ShipmentItem(
      productId: _int(j['productId']),
      sku: j['sku'] ?? '',
      productName: j['productName'] ?? '',
      thumbnailUrl: j['thumbnailUrl'],
      quantity: _int(j['quantity']),
      gift: j['gift'] ?? false,
    );
  }
}

class Shipment {
  final int id;
  final int orderId;
  final int userId;
  final String recipientName;
  final String recipientPhone;
  final String shippingAddress;
  final ShipmentStatus status;
  final List<ShipmentStatus> nextStatuses;
  final String? carrier;
  final String? trackingCode;
  final String? note;
  final int attempts;
  final String? createdAt;
  final String? shippedAt;
  final String? deliveredAt;
  final String? failedAt;
  final String? returnedAt;
  final String? cancelledAt;
  final int totalQuantity;
  final List<ShipmentItem> items;

  Shipment({
    required this.id,
    required this.orderId,
    required this.userId,
    required this.recipientName,
    required this.recipientPhone,
    required this.shippingAddress,
    required this.status,
    required this.nextStatuses,
    this.carrier,
    this.trackingCode,
    this.note,
    required this.attempts,
    this.createdAt,
    this.shippedAt,
    this.deliveredAt,
    this.failedAt,
    this.returnedAt,
    this.cancelledAt,
    required this.totalQuantity,
    required this.items,
  });

  bool get hasGift => items.any((i) => i.gift);

  factory Shipment.fromJson(dynamic json) {
    final j = _map(json);
    return Shipment(
      id: _int(j['id']),
      orderId: _int(j['orderId']),
      userId: _int(j['userId']),
      recipientName: j['recipientName'] ?? '',
      recipientPhone: j['recipientPhone'] ?? '',
      shippingAddress: j['shippingAddress'] ?? '',
      status: ShipmentStatus.parse(j['status']),
      nextStatuses:
          ((j['nextStatuses'] ?? []) as List).map((e) => ShipmentStatus.parse(e as String)).toList(),
      carrier: j['carrier'],
      trackingCode: j['trackingCode'],
      note: j['note'],
      attempts: _int(j['attempts']),
      createdAt: j['createdAt'],
      shippedAt: j['shippedAt'],
      deliveredAt: j['deliveredAt'],
      failedAt: j['failedAt'],
      returnedAt: j['returnedAt'],
      cancelledAt: j['cancelledAt'],
      totalQuantity: _int(j['totalQuantity']),
      items: ((j['items'] ?? []) as List).map(ShipmentItem.fromJson).toList(),
    );
  }
}

class ShipmentSummary {
  final Map<ShipmentStatus, int> counts;
  ShipmentSummary(this.counts);

  factory ShipmentSummary.fromJson(dynamic json) {
    final j = _map(json);
    return ShipmentSummary({for (final s in ShipmentStatus.values) s: _int(j[s.name])});
  }
}

class Review {
  final int id;
  final int productId;
  final String reviewerName;
  final String? reviewerAvatarUrl;
  final int rating;
  final String? comment;
  final bool mine;
  final String? createdAt;

  Review({
    required this.id,
    required this.productId,
    required this.reviewerName,
    this.reviewerAvatarUrl,
    required this.rating,
    this.comment,
    required this.mine,
    this.createdAt,
  });

  factory Review.fromJson(dynamic json) {
    final j = _map(json);
    return Review(
      id: _int(j['id']),
      productId: _int(j['productId']),
      reviewerName: j['reviewerName'] ?? 'Học viên',
      reviewerAvatarUrl: j['reviewerAvatarUrl'],
      rating: _int(j['rating']),
      comment: j['comment'],
      mine: j['mine'] ?? false,
      createdAt: j['createdAt'],
    );
  }
}

class ReviewEligibility {
  final bool canReview;
  final String reason;
  final String? message;
  final Review? myReview;

  ReviewEligibility({required this.canReview, required this.reason, this.message, this.myReview});

  factory ReviewEligibility.fromJson(dynamic json) {
    final j = _map(json);
    return ReviewEligibility(
      canReview: j['canReview'] ?? false,
      reason: j['reason'] ?? '',
      message: j['message'],
      myReview: j['myReview'] == null ? null : Review.fromJson(j['myReview']),
    );
  }
}

class ReviewSummary {
  final double average;
  final int count;
  final Map<int, int> histogram;

  ReviewSummary({required this.average, required this.count, required this.histogram});

  factory ReviewSummary.fromJson(dynamic json) {
    final j = _map(json);
    return ReviewSummary(
      average: _double(j['average']),
      count: _int(j['count']),
      histogram: {
        for (final e in Map<String, dynamic>.from(j['histogram'] ?? {}).entries) int.parse(e.key): _int(e.value),
      },
    );
  }
}
