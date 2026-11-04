import '../../core/constants/api_constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_exceptions.dart';
import 'api_course_cart_repository.dart';
import 'cart_item.dart';
import 'cart_repository.dart';

/// General Cart adapter enabled only in an explicit Product-capable app build.
/// It inherits the authenticated Cart endpoints but exposes Product quantity
/// changes separately so Course quantity-one behavior is unchanged.
class ApiProductCartRepository extends ApiCourseCartRepository
    implements ProductCartQuantityRepository {
  // The client is also retained locally for the Product-specific quantity route.
  // ignore: use_super_parameters
  ApiProductCartRepository({ApiClient? apiClient})
    : _productApi = apiClient ?? ApiClient(),
      super(apiClient: apiClient);

  final ApiClient _productApi;

  @override
  Future<CartSnapshot> setProductQuantity(int cartItemId, int quantity) async {
    if (cartItemId <= 0 || quantity < 1 || quantity > 99) {
      throw ArgumentError('Product cart item ID or quantity is invalid.');
    }
    final response = await _productApi.patch<dynamic>(
      ApiConstants.cartItem(cartItemId),
      data: {'quantity': quantity},
      fromJsonT: (json) => json,
    );
    if (!response.success || response.data == null) {
      throw ApiException(message: response.message, statusCode: 200);
    }
    return CartSnapshot.fromJson(response.data);
  }
}
