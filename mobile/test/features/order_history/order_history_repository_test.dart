import 'package:artnest_app/core/constants/api_constants.dart';
import 'package:artnest_app/core/network/api_client.dart';
import 'package:artnest_app/core/network/api_exceptions.dart';
import 'package:artnest_app/models/api_response.dart';
import 'package:artnest_app/features/order_history/data/api_order_history_repository.dart';
import 'package:artnest_app/features/order_history/models/order_history_models.dart';
import 'package:flutter_test/flutter_test.dart';

import 'order_history_test_data.dart';

void main() {
  test(
    'list calls shared ApiClient path with only pagination parameters',
    () async {
      final client = _FakeApiClient(
        (path, query) async => {
          'success': true,
          'message': 'Orders retrieved successfully',
          'data': orderPageJson(
            [orderSummaryJson(41)],
            number: 2,
            totalPages: 3,
            totalElements: 45,
            size: 20,
            last: false,
          ),
        },
      );
      final repository = ApiOrderHistoryRepository(apiClient: client);
      final result = await repository.getOrders(page: 2, size: 20);
      expect(client.lastPath, ApiConstants.orders);
      expect(client.lastQuery, {'page': 2, 'size': 20});
      expect(client.lastQuery!.containsKey('userId'), isFalse);
      expect(result.content.single.orderId, 41);
    },
  );

  test('detail parses persisted snapshot through shared client', () async {
    final client = _FakeApiClient(
      (path, query) async => {
        'success': true,
        'message': 'Order retrieved successfully',
        'data': orderDetailJson(42),
      },
    );
    final result = await ApiOrderHistoryRepository(apiClient: client)
        .getOrderDetail(42);
    expect(client.lastPath, ApiConstants.orderDetail(42));
    expect(result.orderId, 42);
    expect(result.totalVnd, 192500);
  });

  test('empty backend page is valid and malformed page is rejected', () async {
    final emptyClient = _FakeApiClient(
      (path, query) async => {
        'success': true,
        'message': 'Orders retrieved successfully',
        'data': orderPageJson([], totalElements: 0),
      },
    );
    expect(
      (await ApiOrderHistoryRepository(
        apiClient: emptyClient,
      ).getOrders()).empty,
      isTrue,
    );

    final malformed = _FakeApiClient(
      (path, query) async => {
        'success': true,
        'message': 'Orders retrieved successfully',
        'data': {
          'totalElements': 0,
          'totalPages': 0,
          'size': 20,
          'number': 0,
          'first': true,
          'last': true,
          'empty': true,
        },
      },
    );
    await expectLater(
      ApiOrderHistoryRepository(apiClient: malformed).getOrders(),
      throwsA(isA<OrderHistoryFormatException>()),
    );
  });

  test(
    'HTTP errors and unsuccessful envelopes are not converted to empty data',
    () async {
      final notFound = _FakeApiClient(
        (path, query) async =>
            throw NotFoundException(message: 'Order not found'),
      );
      await expectLater(
        ApiOrderHistoryRepository(apiClient: notFound).getOrderDetail(4),
        throwsA(isA<NotFoundException>()),
      );
      final failure = _FakeApiClient(
        (path, query) async => {
          'success': false,
          'message': 'Request rejected',
        },
      );
      await expectLater(
        ApiOrderHistoryRepository(apiClient: failure).getOrders(),
        throwsA(isA<ApiException>()),
      );
    },
  );
}

class _FakeApiClient implements ApiClient {
  _FakeApiClient(this.handler);
  final Future<Map<String, dynamic>> Function(
    String path,
    Map<String, dynamic>? query,
  )
  handler;
  String? lastPath;
  Map<String, dynamic>? lastQuery;

  @override
  Future<ApiResponse<T>> get<T>(
    String path, {
    Map<String, dynamic>? queryParameters,
    T Function(dynamic json)? fromJsonT,
  }) async {
    lastPath = path;
    lastQuery = queryParameters;
    final envelope = await handler(path, queryParameters);
    final rawData = envelope['data'];
    final data = rawData == null || fromJsonT == null
        ? null
        : fromJsonT(rawData);
    return ApiResponse<T>(
      success: envelope['success'] == true,
      message: envelope['message'] as String? ?? '',
      data: data,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
