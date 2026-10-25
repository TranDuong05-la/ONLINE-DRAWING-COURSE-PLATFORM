import 'package:flutter/foundation.dart';

import '../../../core/network/api_exceptions.dart';
import '../data/order_history_repository.dart';
import '../models/order_history_models.dart';

typedef SessionExpiryHandler = Future<void> Function();

class OrderHistoryController extends ChangeNotifier {
  OrderHistoryController({required this.repository, this.onSessionExpired});

  static const int pageSize = 20;
  final OrderHistoryRepository repository;
  final SessionExpiryHandler? onSessionExpired;
  final List<OrderSummary> _orders = [];
  bool _isLoading = false;
  bool _isLoadingMore = false;
  bool _hasLoaded = false;
  bool _hasMore = true;
  bool _sessionExpiryHandled = false;
  Object? _error;
  int _nextPage = 0;

  List<OrderSummary> get orders => List.unmodifiable(_orders);
  bool get isLoading => _isLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get hasLoaded => _hasLoaded;
  bool get hasMore => _hasMore;
  Object? get error => _error;
  bool get isEmpty => _hasLoaded && _orders.isEmpty && _error == null;

  Future<void> loadFirstPage() async {
    if (_isLoading || _isLoadingMore) return;
    _isLoading = true;
    _isLoadingMore = false;
    _error = null;
    _nextPage = 0;
    _hasMore = true;
    _orders.clear();
    notifyListeners();
    try {
      final result = await repository.getOrders(page: 0, size: pageSize);
      _replaceWith(result);
    } catch (error) {
      _error = error;
      await _synchronizeExpiredSession(error);
    } finally {
      _isLoading = false;
      _hasLoaded = true;
      notifyListeners();
    }
  }

  Future<void> loadMore() async {
    if (_isLoading ||
        _isLoadingMore ||
        !_hasLoaded ||
        !_hasMore ||
        _error != null) {
      return;
    }
    _isLoadingMore = true;
    notifyListeners();
    try {
      final result = await repository.getOrders(
        page: _nextPage,
        size: pageSize,
      );
      final knownIds = _orders.map((order) => order.orderId).toSet();
      for (final order in result.content) {
        if (knownIds.add(order.orderId)) _orders.add(order);
      }
      _nextPage = result.number + 1;
      _hasMore = !result.last && _nextPage < result.totalPages;
    } catch (error) {
      _error = error;
      await _synchronizeExpiredSession(error);
    } finally {
      _isLoadingMore = false;
      notifyListeners();
    }
  }

  Future<void> retry() async {
    if (_error == null) return;
    final hadOrders = _orders.isNotEmpty;
    _error = null;
    notifyListeners();
    if (hadOrders) {
      await loadMore();
    } else {
      await loadFirstPage();
    }
  }

  void _replaceWith(PaginatedOrderHistory result) {
    _orders
      ..clear()
      ..addAll(result.content);
    _nextPage = result.number + 1;
    _hasMore = !result.last && _nextPage < result.totalPages;
    _error = null;
  }

  Future<void> _synchronizeExpiredSession(Object error) async {
    if (error is UnauthorizedException && !_sessionExpiryHandled) {
      _sessionExpiryHandled = true;
      await onSessionExpired?.call();
    }
  }
}
