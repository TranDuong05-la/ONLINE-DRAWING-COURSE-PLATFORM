import '../models/order_history_models.dart';

abstract interface class OrderHistoryRepository {
  Future<PaginatedOrderHistory> getOrders({int page = 0, int size = 20});
  Future<OrderDetail> getOrderDetail(int orderId);
}
