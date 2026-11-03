import '../../core/constants/api_constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_exceptions.dart';
import 'cart_item.dart';
import 'cart_repository.dart';

class ApiCourseCartRepository implements CartRepository {
  ApiCourseCartRepository({ApiClient? apiClient})
    : _api = apiClient ?? ApiClient();

  final ApiClient _api;

  Future<CartSnapshot> _snapshot(Future<dynamic> call) async {
    final response = await call;
    if (!response.success) {
      throw ApiException(message: response.message, statusCode: 200);
    }
    final data = response.data;
    if (data == null) throw const FormatException('Cart response has no data.');
    return CartSnapshot.fromJson(data);
  }

  @override
  Future<CartSnapshot> load() => _snapshot(
    _api.get<dynamic>(ApiConstants.cart, fromJsonT: (json) => json),
  );

  @override
  Future<CartSnapshot> addCourse(int courseId) {
    if (courseId <= 0) throw ArgumentError.value(courseId, 'courseId');
    return _snapshot(
      _api.post<dynamic>(
        '${ApiConstants.cart}/items',
        data: {'itemType': 'COURSE', 'itemId': courseId, 'quantity': 1},
        fromJsonT: (json) => json,
      ),
    );
  }

  @override
  Future<CartSnapshot> setQuantity(int cartItemId, int quantity) {
    if (cartItemId <= 0 || quantity != 1) {
      throw ArgumentError(
        'Course cart lines require a valid ID and quantity 1.',
      );
    }
    return _snapshot(
      _api.patch<dynamic>(
        ApiConstants.cartItem(cartItemId),
        data: {'quantity': 1},
        fromJsonT: (json) => json,
      ),
    );
  }

  @override
  Future<CartSnapshot> remove(int cartItemId) {
    if (cartItemId <= 0) throw ArgumentError.value(cartItemId, 'cartItemId');
    return _snapshot(
      _api.delete<dynamic>(
        ApiConstants.cartItem(cartItemId),
        fromJsonT: (json) => json,
      ),
    );
  }

  @override
  Future<CartSnapshot> clear() => _snapshot(
    _api.delete<dynamic>(ApiConstants.cart, fromJsonT: (json) => json),
  );
}
