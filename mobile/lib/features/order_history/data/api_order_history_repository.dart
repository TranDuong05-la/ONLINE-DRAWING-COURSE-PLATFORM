import '../../../core/constants/api_constants.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_exceptions.dart';
import '../models/order_history_models.dart';
import 'order_history_repository.dart';

class ApiOrderHistoryRepository implements OrderHistoryRepository {
  ApiOrderHistoryRepository({ApiClient? apiClient})
    : _apiClient = apiClient ?? ApiClient();
  final ApiClient _apiClient;

  @override
  Future<PaginatedOrderHistory> getOrders({int page = 0, int size = 20}) async {
    if (page < 0 || size < 1 || size > 100) {
      throw ArgumentError(
        'page must be non-negative and size must be between 1 and 100.',
      );
    }
    try {
      final response = await _apiClient.get<PaginatedOrderHistory>(
        ApiConstants.orders,
        queryParameters: {'page': page, 'size': size},
        fromJsonT: PaginatedOrderHistory.fromJson,
      );
      if (!response.success) {
        throw ApiException(message: response.message, statusCode: 200);
      }
      final result = response.data;
      if (result == null) {
        throw const OrderHistoryFormatException(
          'Order list response has no data.',
        );
      }
      if (result.number != page || result.size != size) {
        throw const OrderHistoryFormatException(
          'Order list response page does not match the request.',
        );
      }
      return result;
    } on OrderHistoryFormatException {
      rethrow;
    } on FormatException catch (error) {
      throw OrderHistoryFormatException(
        'Order list response is malformed: ${error.message}',
      );
    } on TypeError {
      throw const OrderHistoryFormatException(
        'Order list response has invalid field types.',
      );
    } on RangeError {
      throw const OrderHistoryFormatException(
        'Order list response contains an out-of-range value.',
      );
    }
  }

  @override
  Future<OrderDetail> getOrderDetail(int orderId) async {
    if (orderId <= 0) {
      throw ArgumentError.value(orderId, 'orderId', 'must be positive');
    }
    try {
      final response = await _apiClient.get<OrderDetail>(
        ApiConstants.orderDetail(orderId),
        fromJsonT: OrderDetail.fromJson,
      );
      if (!response.success) {
        throw ApiException(message: response.message, statusCode: 200);
      }
      final result = response.data;
      if (result == null) {
        throw const OrderHistoryFormatException(
          'Order detail response has no data.',
        );
      }
      if (result.orderId != orderId) {
        throw const OrderHistoryFormatException(
          'Order detail ID does not match the request.',
        );
      }
      return result;
    } on OrderHistoryFormatException {
      rethrow;
    } on FormatException catch (error) {
      throw OrderHistoryFormatException(
        'Order detail response is malformed: ${error.message}',
      );
    } on TypeError {
      throw const OrderHistoryFormatException(
        'Order detail response has invalid field types.',
      );
    } on RangeError {
      throw const OrderHistoryFormatException(
        'Order detail response contains an out-of-range value.',
      );
    }
  }
}
