import 'dart:async';

import 'package:artnest_app/core/network/api_exceptions.dart';
import 'package:artnest_app/features/order_history/data/order_history_repository.dart';
import 'package:artnest_app/features/order_history/models/order_history_models.dart';
import 'package:artnest_app/features/order_history/state/order_history_controller.dart';
import 'package:flutter_test/flutter_test.dart';

import 'order_history_test_data.dart';

void main() {
  test('load more appends pages and removes duplicate Order IDs', () async {
    var pageOneCalls = 0;
    final repository = _FakeRepository((page, size) async {
      if (page == 0) {
        return parsedPage(
          [1, 2],
          number: 0,
          totalPages: 2,
          totalElements: 4,
          last: false,
        );
      }
      pageOneCalls++;
      return parsedPage(
        [2, 3, 4],
        number: 1,
        totalPages: 2,
        totalElements: 4,
        last: true,
      );
    });
    final controller = OrderHistoryController(repository: repository);
    await controller.loadFirstPage();
    await controller.loadMore();
    expect(controller.orders.map((order) => order.orderId), [1, 2, 3, 4]);
    expect(pageOneCalls, 1);
    expect(controller.hasMore, isFalse);
    controller.dispose();
  });

  test('concurrent load-more calls produce one request', () async {
    final pageOne = Completer<PaginatedOrderHistory>();
    var pageOneCalls = 0;
    final repository = _FakeRepository((page, size) async {
      if (page == 0) {
        return parsedPage(
          [1],
          number: 0,
          totalPages: 2,
          totalElements: 2,
          last: false,
        );
      }
      pageOneCalls++;
      return pageOne.future;
    });
    final controller = OrderHistoryController(repository: repository);
    await controller.loadFirstPage();
    final first = controller.loadMore();
    final duplicate = controller.loadMore();
    expect(pageOneCalls, 1);
    pageOne.complete(
      parsedPage([2], number: 1, totalPages: 2, totalElements: 2, last: true),
    );
    await Future.wait([first, duplicate]);
    expect(controller.orders.map((order) => order.orderId), [1, 2]);
    controller.dispose();
  });

  test('network retry reloads without invoking logout callback', () async {
    var calls = 0;
    var sessionExpiryCalls = 0;
    final repository = _FakeRepository((page, size) async {
      calls++;
      if (calls == 1) throw NetworkException();
      return parsedPage([8]);
    });
    final controller = OrderHistoryController(
      repository: repository,
      onSessionExpired: () async => sessionExpiryCalls++,
    );
    await controller.loadFirstPage();
    expect(controller.error, isA<NetworkException>());
    await controller.retry();
    expect(controller.orders.single.orderId, 8);
    expect(controller.error, isNull);
    expect(sessionExpiryCalls, 0);
    controller.dispose();
  });

  test('only unrecoverable unauthorized errors synchronize the existing auth state', () async {
    var sessionExpiryCalls = 0;
    final unauthorized = OrderHistoryController(
      repository: _FakeRepository(
        (page, size) async => throw UnauthorizedException(),
      ),
      onSessionExpired: () async {
        sessionExpiryCalls++;
      },
    );
    await unauthorized.loadFirstPage();
    expect(sessionExpiryCalls, 1);
    unauthorized.dispose();

    final forbidden = OrderHistoryController(
      repository: _FakeRepository(
        (page, size) async => throw ForbiddenException(),
      ),
      onSessionExpired: () async {
        sessionExpiryCalls++;
      },
    );
    await forbidden.loadFirstPage();
    expect(sessionExpiryCalls, 1);
    forbidden.dispose();
  });
}

class _FakeRepository implements OrderHistoryRepository {
  _FakeRepository(this.listHandler);
  final Future<PaginatedOrderHistory> Function(int page, int size) listHandler;
  @override
  Future<PaginatedOrderHistory> getOrders({int page = 0, int size = 20}) =>
      listHandler(page, size);
  @override
  Future<OrderDetail> getOrderDetail(int orderId) => throw UnimplementedError();
}
