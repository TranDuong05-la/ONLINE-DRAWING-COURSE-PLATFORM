import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/network/api_exceptions.dart';
import '../order_history/models/order_history_models.dart';
import 'cart_item.dart';
import 'cart_repository.dart';
import 'course_checkout_repository.dart';
import 'course_purchase_config.dart';

class ProductCheckoutScreen extends StatefulWidget {
  const ProductCheckoutScreen({super.key, required this.cart, required this.repository, this.cartRepository});
  final CartSnapshot cart;
  final ProductCheckoutRepository repository;
  /// Đặt hàng xong thì bỏ các dòng vừa đặt khỏi giỏ (backend không tự xóa).
  final CartRepository? cartRepository;

  @override
  State<ProductCheckoutScreen> createState() => _ProductCheckoutScreenState();
}

class _ProductCheckoutScreenState extends State<ProductCheckoutScreen> with WidgetsBindingObserver {
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _address = TextEditingController();
  final _locality = TextEditingController();
  final _postalCode = TextEditingController();
  final _voucher = TextEditingController();
  final _idempotencyKey = newCheckoutIdempotencyKey();
  int? _orderId;
  OrderDetail? _order;
  CoursePaymentSession? _session;
  bool _busy = false;
  bool _paymentLaunched = false;
  String? _error;
  String? _info;

  bool get _enabled => productPurchaseUiEnabled;
  bool get _confirmed => _order?.paymentStatus == OrderPaymentStatus.succeeded && _order?.status == OrderStatus.confirmed;
  bool get _validCart => widget.cart.canCheckout &&
      widget.cart.payableItems.any((item) => item.type == CartItemType.product) &&
      !widget.cart.items.any((item) => item.blocksCheckout) &&
      // Courses and Combos are always one line of quantity one; only Products can have a larger quantity.
      widget.cart.payableItems.every((item) => item.type == CartItemType.product || item.quantity == 1);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    for (final c in [_name, _phone, _address, _locality, _postalCode, _voucher]) { c.dispose(); }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _paymentLaunched && _orderId != null && !_busy) {
      _refreshOrder();
    }
  }

  Future<void> _placeOrder() async {
    if (!_enabled || !_validCart || _busy) return;
    if ([_name, _phone, _address, _locality].any((c) => c.text.trim().isEmpty)) {
      setState(() => _error = 'Enter the recipient, phone, street address and locality.');
      return;
    }
    setState(() { _busy = true; _error = null; _info = null; });
    try {
      final id = await widget.repository.createProductOrder(
        cart: widget.cart,
        idempotencyKey: _idempotencyKey,
        voucherCode: _voucher.text,
        address: ProductShippingAddress(
          recipient: _name.text.trim(), phone: _phone.text.trim(),
          addressLine: _address.text.trim(), locality: _locality.text.trim(),
          postalCode: _postalCode.text.trim(), countryCode: 'VN',
        ),
      );
      if (!mounted) return;
      setState(() => _orderId = id);
      await _removeOrderedLines();
      await _refreshOrder(openPayment: true);
    } catch (e) {
      if (mounted) setState(() => _error = _friendly(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _removeOrderedLines() async {
    final carts = widget.cartRepository;
    if (carts == null) return;
    for (final item in widget.cart.items) {
      try {
        await carts.remove(item.id);
      } catch (_) {
        // Đơn đã tạo xong; dòng còn sót trong giỏ không ảnh hưởng thanh toán.
      }
    }
  }

  Future<void> _startPayment({bool allowWhileBusy = false}) async {
    final id = _orderId;
    if (id == null || (_busy && !allowWhileBusy) || _confirmed) return;
    final deadline = _order?.paymentExpiresAt;
    if (deadline != null && !DateTime.now().toUtc().isBefore(deadline.toUtc())) {
      setState(() => _error = 'The Order payment deadline has passed.');
      return;
    }
    setState(() { _busy = true; _error = null; });
    try {
      final session = await widget.repository.createPaymentSession(id);
      if (!mounted) return;
      setState(() => _session = session);
      final opened = await launchUrl(session.paymentUrl, mode: LaunchMode.externalApplication);
      if (opened && mounted) {
        setState(() {
          _paymentLaunched = true;
          _info = 'VNPay opened. A browser return is not payment confirmation; check the verified Order status here.';
        });
      } else if (mounted) {
        setState(() => _error = 'Could not open VNPay. Retry using the same Order.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = _friendly(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshOrder({bool openPayment = false}) async {
    final id = _orderId;
    if (id == null) return;
    final wasBusy = _busy;
    if (!wasBusy && mounted) setState(() { _busy = true; _error = null; });
    try {
      final order = await widget.repository.getOrder(id);
      if (!mounted) return;
      setState(() => _order = order);
      if (_confirmed) {
        setState(() => _info = 'Payment is confirmed by the server. The Order is ready for fulfillment.');
      } else if (openPayment) {
        await _startPayment(allowWhileBusy: true);
      } else {
        setState(() => _info = 'Status refreshed from the server. Browser return alone does not confirm payment.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = _friendly(e));
    } finally {
      if (!wasBusy && mounted) setState(() => _busy = false);
    }
  }

  String _friendly(Object e) {
    if (e is UnauthorizedException) return 'Your session expired. Sign in again.';
    if (e is NetworkException) return 'Could not reach the server. Retry safely with the same checkout key.';
    if (e is ApiException && e.message.isNotEmpty) return e.message;
    if (e is FormatException) return e.message;
    return 'Product checkout could not be completed.';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Product Checkout')),
      body: SafeArea(child: ListView(padding: const EdgeInsets.all(16), children: [
        if (!_enabled) const Text('Product purchasing is disabled in this app build.'),
        if (_enabled && !_validCart) const Text('This Cart has unavailable or unsupported items.'),
        if (_enabled) ...[
          for (final item in widget.cart.items)
            ListTile(title: Text(item.name), subtitle: Text('${item.quantity} × ${formatVnd(item.unitPriceVnd ?? 0)} VND')),
          Text('Current catalog subtotal: ${formatVnd(widget.cart.subtotalVnd)} VND'),
          if (_orderId == null) ...[
            TextField(controller: _name, decoration: const InputDecoration(labelText: 'Recipient')),
            TextField(controller: _phone, decoration: const InputDecoration(labelText: 'Phone'), keyboardType: TextInputType.phone),
            TextField(controller: _address, decoration: const InputDecoration(labelText: 'Street address')),
            TextField(controller: _locality, decoration: const InputDecoration(labelText: 'City / locality')),
            TextField(controller: _postalCode, decoration: const InputDecoration(labelText: 'Postal code (optional)')),
            TextField(controller: _voucher, decoration: const InputDecoration(labelText: 'Voucher (optional)')),
          ],
          if (_busy) const LinearProgressIndicator(),
          if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          if (_info != null) Text(_info!),
          if (_order != null) ...[
            Text('Order #${_order!.orderId} · ${_order!.status.wireName} · ${_order!.paymentStatus.wireName}'),
            Text('Persisted total: ${formatVnd(_order!.totalVnd)} VND'),
          ],
          if (_orderId == null)
            FilledButton(onPressed: _busy || !_validCart ? null : _placeOrder, child: const Text('Create Order and continue to VNPay'))
          else if (_confirmed)
            FilledButton(onPressed: () => context.go('/orders'), child: const Text('View Order History'))
          else ...[
            FilledButton(onPressed: _busy ? null : _startPayment, child: Text(_session == null ? 'Open VNPay payment' : 'Retry payment page')),
            OutlinedButton(onPressed: _busy ? null : () => _refreshOrder(), child: const Text('Check payment status')),
          ],
        ],
      ])),
    );
  }
}
