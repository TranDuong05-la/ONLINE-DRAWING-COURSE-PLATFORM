import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/network/api_exceptions.dart';
import '../order_history/models/order_history_models.dart';
import 'cart_item.dart';
import 'course_checkout_repository.dart';
import 'course_ownership_refresh_signal.dart';
import 'course_purchase_config.dart';

typedef PaymentUrlLauncher = Future<bool> Function(Uri url);

class CourseCheckoutScreen extends StatefulWidget {
  const CourseCheckoutScreen({
    super.key,
    required this.cart,
    required this.repository,
    this.launchPaymentUrl,
    this.purchaseEnabled,
  });

  final CartSnapshot cart;
  final CourseCheckoutRepository repository;
  final PaymentUrlLauncher? launchPaymentUrl;
  final bool? purchaseEnabled;

  @override
  State<CourseCheckoutScreen> createState() => _CourseCheckoutScreenState();
}

class _CourseCheckoutScreenState extends State<CourseCheckoutScreen>
    with WidgetsBindingObserver {
  final _voucher = TextEditingController();
  String _idempotencyKey = newCheckoutIdempotencyKey();
  int? _orderId;
  OrderDetail? _order;
  CoursePaymentSession? _session;
  Set<int> _ownedCourses = const {};
  bool _busy = false;
  bool _paymentLaunched = false;
  String? _error;
  String? _info;

  bool get _serverConfirmed =>
      _order?.paymentStatus == OrderPaymentStatus.succeeded &&
      _order?.status == OrderStatus.confirmed;
  bool get _enabled => widget.purchaseEnabled ?? coursePurchaseUiEnabled;
  bool get _validCart =>
      widget.cart.canCheckout &&
      widget.cart.payableItems.isNotEmpty &&
      !widget.cart.items.any((item) => item.blocksCheckout) &&
      widget.cart.payableItems.every(
        (item) => item.isCourseCheckoutItem && item.quantity == 1,
      );

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _voucher.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed &&
        _paymentLaunched &&
        _orderId != null &&
        !_busy) {
      _refreshFromServer();
    }
  }

  Future<void> _placeOrder() async {
    if (_busy || !_enabled) return;
    if (!_validCart) {
      setState(
        () => _error = 'Only available Course items can be checked out. Remove unsupported or unavailable items.',
      );
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _info = null;
    });
    try {
      final orderId = await widget.repository.createOrder(
        cart: widget.cart,
        idempotencyKey: _idempotencyKey,
        voucherCode: _voucher.text.trim().isEmpty ? null : _voucher.text.trim(),
      );
      if (!mounted) return;
      setState(() => _orderId = orderId);
      await _refreshFromServer(openPaymentAfterRefresh: true);
    } catch (error) {
      if (mounted) setState(() => _error = _friendlyError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _startPayment({bool allowWhileBusy = false}) async {
    final orderId = _orderId;
    if (orderId == null || (_busy && !allowWhileBusy) || _serverConfirmed) {
      return;
    }
    final deadline = _order?.paymentExpiresAt;
    if (deadline != null &&
        !DateTime.now().toUtc().isBefore(deadline.toUtc())) {
      setState(
        () => _error = 'This Order payment deadline has passed. Start a new checkout from your cart.',
      );
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final session = await widget.repository.createPaymentSession(orderId);
      if (!mounted) return;
      setState(() => _session = session);
      if (!DateTime.now().toUtc().isBefore(session.expiresAt.toUtc())) {
        setState(
          () => _error = 'This VNPay session has expired. Retry to obtain a current session before the Order deadline.',
        );
        return;
      }
      final launched = await (widget.launchPaymentUrl ?? _launchExternally)(
        session.paymentUrl,
      );
      if (!mounted) return;
      if (launched) {
        setState(() {
          _paymentLaunched = true;
          _info = 'VNPay opened. Returning from the provider does not confirm payment; this screen checks the verified server Order status.';
        });
      } else {
        setState(
          () => _error = 'Could not open the VNPay payment page. You can retry without creating another Order.',
        );
      }
    } catch (error) {
      if (mounted) setState(() => _error = _friendlyError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshFromServer({
    bool openPaymentAfterRefresh = false,
  }) async {
    final orderId = _orderId;
    if (orderId == null) return;
    final wasBusy = _busy;
    if (!wasBusy && mounted) {
      setState(() {
        _busy = true;
        _error = null;
      });
    }
    try {
      final order = await widget.repository.getOrder(orderId);
      if (!mounted) return;
      setState(() => _order = order);
      if (_serverConfirmed) {
        setState(
          () => _info = 'Payment is confirmed by the server. Refreshing your Course ownership.',
        );
        CourseOwnershipRefreshSignal.notify();
        try {
          final owned = await widget.repository.refreshOwnedCourses();
          if (!mounted) return;
          setState(() {
            _ownedCourses = owned;
            _info =
                widget.cart.items.every((item) => owned.contains(item.itemId))
                ? 'Payment is confirmed by the server and your Course ownership has refreshed.'
                : 'Payment is confirmed by the server. Course access is still updating; refresh your Courses shortly.';
          });
        } catch (_) {
          if (!mounted) return;
          setState(
            () => _info = 'Payment is confirmed by the server. Course ownership could not refresh yet; use Refresh in My Courses shortly.',
          );
        }
      } else if (openPaymentAfterRefresh) {
        await _startPayment(allowWhileBusy: true);
      } else {
        setState(
          () => _info = 'Current payment status was refreshed from the server. A browser return alone is not proof of payment.',
        );
      }
    } catch (error) {
      if (mounted) setState(() => _error = _friendlyError(error));
    } finally {
      if (!wasBusy && mounted) setState(() => _busy = false);
    }
  }

  String _friendlyError(Object error) {
    if (error is ConflictException) {
      return 'The Order or Cart changed, or the payment deadline expired. Reload the Cart before retrying checkout.';
    }
    if (error is UnauthorizedException) {
      return 'Your session expired. Sign in again to continue.';
    }
    if (error is NetworkException) {
      return 'The server could not be reached. Retry safely; the same checkout key is retained.';
    }
    if (error is ApiException && error.message.isNotEmpty) return error.message;
    if (error is FormatException) return error.message;
    return 'Checkout could not be completed. Please retry.';
  }

  Future<bool> _launchExternally(Uri url) =>
      launchUrl(url, mode: LaunchMode.externalApplication);

  @override
  Widget build(BuildContext context) {
    final order = _order;
    return Scaffold(
      appBar: AppBar(title: const Text('Course Checkout')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (!_enabled)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text(
                    'Course purchasing is disabled in this app build.',
                  ),
                ),
              )
            else ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Order items',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 8),
                      for (final item in widget.cart.items)
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(item.name),
                          subtitle: const Text('Course · quantity 1'),
                          trailing: Text(
                            item.unitPriceVnd == null
                                ? 'Unavailable'
                                : '${formatVnd(item.unitPriceVnd!)} VND',
                          ),
                        ),
                      const Divider(),
                      Text(
                        'Current subtotal: ${formatVnd(widget.cart.subtotalVnd)} VND',
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        key: const ValueKey('checkout-voucher-code'),
                        controller: _voucher,
                        enabled: _orderId == null && !_busy,
                        textCapitalization: TextCapitalization.characters,
                        decoration: const InputDecoration(
                          labelText: 'Voucher code (optional)',
                          helperText: 'The server validates the code and stores any eligible discount in the Order snapshot.',
                        ),
                        onChanged: (_) {
                          if (_orderId == null) {
                            _idempotencyKey = newCheckoutIdempotencyKey();
                          }
                        },
                      ),
                    ],
                  ),
                ),
              ),
              if (_busy) const LinearProgressIndicator(),
              if (_error != null)
                Card(
                  color: Theme.of(context).colorScheme.errorContainer,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(_error!, key: const ValueKey('checkout-error')),
                  ),
                ),
              if (_info != null)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(_info!, key: const ValueKey('checkout-info')),
                  ),
                ),
              if (order != null) _orderSnapshot(order),
              if (order == null)
                FilledButton(
                  key: const ValueKey('place-course-order'),
                  onPressed: _busy || !_validCart ? null : _placeOrder,
                  child: const Text('Create Order and continue to VNPay'),
                )
              else if (_serverConfirmed)
                FilledButton.icon(
                  onPressed: () => context.go('/orders'),
                  icon: const Icon(Icons.receipt_long),
                  label: const Text('View Order History'),
                )
              else ...[
                FilledButton.icon(
                  onPressed: _busy ? null : _startPayment,
                  icon: const Icon(Icons.open_in_new),
                  label: Text(
                    _session == null
                        ? 'Open VNPay payment'
                        : 'Retry payment page',
                  ),
                ),
                OutlinedButton.icon(
                  key: const ValueKey('check-payment-status'),
                  onPressed: _busy ? null : () => _refreshFromServer(),
                  icon: const Icon(Icons.refresh),
                  label: const Text('Check payment status'),
                ),
                TextButton(
                  onPressed: () => context.go('/orders'),
                  child: const Text('View Order History'),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  Widget _orderSnapshot(OrderDetail order) {
    final deadline = order.paymentExpiresAt;
    final expiredForDisplay =
        deadline != null && !DateTime.now().toUtc().isBefore(deadline.toUtc());
    return Card(
      key: const ValueKey('persisted-order-snapshot'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Order #${order.orderId}',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Text(
              'Status: ${order.status.wireName} · payment: ${order.paymentStatus.wireName}',
            ),
            const SizedBox(height: 8),
            Text('Voucher: ${order.voucher?.code ?? 'none'}'),
            Text(
              'Server-calculated discount: ${formatVnd(order.discountVnd)} VND',
            ),
            Text('Persisted total: ${formatVnd(order.totalVnd)} VND'),
            if (deadline != null)
              Text(
                'Exclusive payment deadline: ${deadline.toLocal()}${expiredForDisplay ? ' · expired' : ''}',
              ),
            if (_serverConfirmed)
              Text(
                'Verified Courses visible in ownership: ${_ownedCourses.length}',
              ),
          ],
        ),
      ),
    );
  }
}
