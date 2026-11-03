import '../../../core/network/api_client.dart';
import '../../../core/network/api_exceptions.dart';
import '../../../models/api_response.dart';
import '../../../models/page_response.dart';
import '../models/supply_models.dart';

/// Toàn bộ API phần E (học cụ, kho, giao hàng, đánh giá). Lỗi ném ApiException của core/network.
class SupplyRepository {
  SupplyRepository._();
  static final SupplyRepository instance = SupplyRepository._();

  final ApiClient _api = ApiClient();

  static const _shop = '/api/supplies';
  static const _admin = '/api/admin/supplies';
  static const _staff = '/api/staff/supplies';
  static const _staffShipments = '/api/staff/shipments';

  Future<T> _data<T>(Future<ApiResponse<T>> call) async {
    final res = await call;
    if (!res.success || res.data == null) {
      throw ApiException(message: res.firstErrorMessage.isEmpty ? 'Có lỗi xảy ra' : res.firstErrorMessage);
    }
    return res.data as T;
  }

  Future<void> _ok(Future<ApiResponse<dynamic>> call) async {
    final res = await call;
    if (!res.success) throw ApiException(message: res.firstErrorMessage);
  }

  List<T> Function(dynamic) _list<T>(T Function(dynamic) parse) => (json) => (json as List).map(parse).toList();

  PageResponse<T> Function(dynamic) _page<T>(T Function(dynamic) parse) =>
      (json) => PageResponse.fromJson(Map<String, dynamic>.from(json as Map), parse);

  // ---------- Học viên ----------

  Future<List<SupplyCategory>> categories() =>
      _data(_api.get('$_shop/categories', fromJsonT: _list(SupplyCategory.fromJson)));

  Future<PageResponse<SupplyProduct>> products({
    String? q,
    int? categoryId,
    bool inStockOnly = false,
    String sort = 'newest',
    int page = 0,
    int size = 20,
  }) =>
      _data(_api.get('$_shop/products',
          queryParameters: {
            if (q != null && q.isNotEmpty) 'q': q,
            'categoryId': ?categoryId,
            if (inStockOnly) 'inStock': true,
            'sort': sort,
            'page': page,
            'size': size,
          },
          fromJsonT: _page(SupplyProduct.fromJson)));

  Future<SupplyProduct> product(int id) =>
      _data(_api.get('$_shop/products/$id', fromJsonT: SupplyProduct.fromJson));

  Future<PageResponse<Review>> reviews(int productId, {int page = 0}) => _data(_api.get(
      '$_shop/products/$productId/reviews',
      queryParameters: {'page': page, 'size': 10},
      fromJsonT: _page(Review.fromJson)));

  Future<ReviewSummary> reviewSummary(int productId) =>
      _data(_api.get('$_shop/products/$productId/reviews/summary', fromJsonT: ReviewSummary.fromJson));

  Future<ReviewEligibility> reviewEligibility(int productId) => _data(
      _api.get('$_shop/products/$productId/reviews/eligibility', fromJsonT: ReviewEligibility.fromJson));

  Future<Review> createReview(int productId, int rating, String? comment) => _data(_api.post(
      '$_shop/products/$productId/reviews',
      data: {'rating': rating, 'comment': comment},
      fromJsonT: Review.fromJson));

  Future<Review> updateReview(int reviewId, int rating, String? comment) => _data(_api.put(
      '$_shop/reviews/$reviewId',
      data: {'rating': rating, 'comment': comment},
      fromJsonT: Review.fromJson));

  Future<List<Shipment>> myShipments() =>
      _data(_api.get('/api/shipments/me', fromJsonT: _list(Shipment.fromJson)));

  Future<Shipment> shipment(int id) => _data(_api.get('/api/shipments/$id', fromJsonT: Shipment.fromJson));

  // ---------- Admin: danh mục, tạo/sửa/ẩn sản phẩm ----------

  Future<List<SupplyCategory>> adminCategories() =>
      _data(_api.get('$_admin/categories', fromJsonT: _list(SupplyCategory.fromJson)));

  Future<SupplyCategory> saveCategory({
    int? id,
    required String name,
    String? description,
    required bool active,
    int sortOrder = 0,
  }) {
    final body = {'name': name, 'description': description, 'active': active, 'sortOrder': sortOrder};
    return _data(id == null
        ? _api.post('$_admin/categories', data: body, fromJsonT: SupplyCategory.fromJson)
        : _api.put('$_admin/categories/$id', data: body, fromJsonT: SupplyCategory.fromJson));
  }

  Future<SupplyProduct> saveProduct(int? id, Map<String, dynamic> body) => _data(id == null
      ? _api.post('$_admin/products', data: body, fromJsonT: SupplyProduct.fromJson)
      : _api.put('$_admin/products/$id', data: body, fromJsonT: SupplyProduct.fromJson));

  Future<SupplyProduct> setProductActive(int id, bool active) => _data(
      _api.patch('$_admin/products/$id/status', data: {'active': active}, fromJsonT: SupplyProduct.fromJson));

  Future<void> deleteReview(int reviewId) => _ok(_api.delete('$_admin/reviews/$reviewId'));

  // ---------- Staff/Admin: kho ----------

  Future<PageResponse<SupplyProduct>> managerProducts({
    String? q,
    bool? active,
    bool lowStockOnly = false,
    int page = 0,
  }) =>
      _data(_api.get('$_staff/products',
          queryParameters: {
            if (q != null && q.isNotEmpty) 'q': q,
            'active': ?active,
            if (lowStockOnly) 'lowStock': true,
            'page': page,
            'size': 20,
          },
          fromJsonT: _page(SupplyProduct.fromJson)));

  Future<SupplyProduct> managerProduct(int id) =>
      _data(_api.get('$_staff/products/$id', fromJsonT: SupplyProduct.fromJson));

  Future<SupplyProduct> importStock(int id, int quantity, String? note) => _data(_api.post(
      '$_staff/products/$id/stock-imports',
      data: {'quantity': quantity, 'note': note},
      fromJsonT: SupplyProduct.fromJson));

  Future<PageResponse<InventoryTx>> inventoryTransactions(int productId, {int page = 0}) => _data(_api.get(
      '$_staff/products/$productId/inventory-transactions',
      queryParameters: {'page': page, 'size': 30},
      fromJsonT: _page(InventoryTx.fromJson)));

  // ---------- Staff/Admin: giao hàng ----------

  Future<PageResponse<Shipment>> managerShipments(ShipmentStatus status, {int page = 0}) => _data(_api.get(
      _staffShipments,
      queryParameters: {'status': status.code, 'page': page, 'size': 20},
      fromJsonT: _page(Shipment.fromJson)));

  Future<ShipmentSummary> shipmentSummary() =>
      _data(_api.get('$_staffShipments/summary', fromJsonT: ShipmentSummary.fromJson));

  Future<Shipment> managerShipment(int id) =>
      _data(_api.get('$_staffShipments/$id', fromJsonT: Shipment.fromJson));

  Future<Shipment> updateShipmentStatus(
    int id,
    ShipmentStatus status, {
    String? carrier,
    String? trackingCode,
    String? note,
  }) =>
      _data(_api.patch('$_staffShipments/$id/status',
          data: {'status': status.code, 'carrier': carrier, 'trackingCode': trackingCode, 'note': note},
          fromJsonT: Shipment.fromJson));

  // ---------- Dev: giả lập đơn của D (backend không có ở prod) ----------

  Future<void> devOrder({
    required List<Map<String, dynamic>> lines,
    required String recipientName,
    required String phone,
    required String address,
  }) =>
      _ok(_api.post('/api/dev/supply/orders', data: {
        'lines': lines,
        'recipientName': recipientName,
        'phone': phone,
        'address': address,
        'pay': true,
      }));
}
