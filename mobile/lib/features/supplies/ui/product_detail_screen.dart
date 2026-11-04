import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../models/page_response.dart';
import '../data/cart_bridge.dart';
import '../../cart/course_purchase_config.dart';
import '../data/supply_repository.dart';
import '../models/supply_models.dart';
import 'dev_order_sheet.dart';
import 'review_form_sheet.dart';
import 'widgets/supply_format.dart';
import 'widgets/supply_states.dart';
import 'widgets/supply_widgets.dart';

class ProductDetailScreen extends StatefulWidget {
  final int productId;
  const ProductDetailScreen({super.key, required this.productId});

  @override
  State<ProductDetailScreen> createState() => _ProductDetailScreenState();
}

class _ProductDetailScreenState extends State<ProductDetailScreen> {
  final _repo = SupplyRepository.instance;

  SupplyProduct? _product;
  Object? _error;
  ReviewSummary? _summary;
  ReviewEligibility? _eligibility;
  final List<Review> _reviews = [];
  int _reviewPage = 0;
  bool _reviewHasMore = false;
  bool _loadingReviews = false;
  int _quantity = 1;
  bool _adding = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final p = await _repo.product(widget.productId);
      if (!mounted) return;
      setState(() {
        _product = p;
        _quantity = p.available == 0 ? 1 : _quantity.clamp(1, p.available);
      });
      _loadReviews(reset: true);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _loadReviews({bool reset = false}) async {
    if (_loadingReviews) return;
    setState(() => _loadingReviews = true);
    try {
      final page = reset ? 0 : _reviewPage;
      final results = await Future.wait([
        _repo.reviews(widget.productId, page: page),
        if (reset) _repo.reviewSummary(widget.productId),
      ]);
      ReviewEligibility? elig = _eligibility;
      if (reset) {
        try {
          elig = await _repo.reviewEligibility(widget.productId);
        } catch (_) {
          elig = null;
        }
      }
      if (!mounted) return;
      final pageResult = results[0] as PageResponse<Review>;
      setState(() {
        if (reset) {
          _reviews.clear();
          _summary = results[1] as ReviewSummary;
          _eligibility = elig;
        }
        _reviews.addAll(pageResult.content);
        _reviewPage = page + 1;
        _reviewHasMore = !pageResult.last;
      });
    } catch (e) {
      if (mounted) showMessage(context, 'Không tải được đánh giá: $e');
    } finally {
      if (mounted) setState(() => _loadingReviews = false);
    }
  }

  Future<void> _addToCart() async {
    final p = _product!;
    setState(() => _adding = true);
    try {
      await CartBridge.instance.addProduct(p.id, _quantity);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('Đã thêm $_quantity "${p.name}" vào giỏ'),
        // Giỏ hàng chung (khóa học + học cụ) chỉ có khi build với PRODUCT_PURCHASE_ENABLED=true
        action: productPurchaseUiEnabled
            ? SnackBarAction(label: 'Xem giỏ', onPressed: () => context.push('/cart'))
            : null,
      ));
    } on CartNotReadyException catch (e) {
      if (!mounted) return;
      if (kDebugMode) {
        _openDevOrder();
      } else {
        showMessage(context, e.toString());
      }
    } catch (e) {
      if (mounted) showMessage(context, e.toString());
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }

  Future<void> _openDevOrder() async {
    final p = _product!;
    final ok = await showDevOrderSheet(context, product: p, quantity: _quantity);
    if (ok && mounted) {
      showMessage(context, 'Đã đặt thử. Xem ở "Đơn giao hàng của tôi".');
      _load();
    }
  }

  Future<void> _writeReview() async {
    final p = _product!;
    final saved = await showReviewForm(
      context,
      productId: p.id,
      productName: p.name,
      existing: _eligibility?.myReview,
    );
    if (saved && mounted) {
      showMessage(context, 'Cảm ơn bạn đã đánh giá!');
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _product;
    return Scaffold(
      appBar: AppBar(title: Text(p?.name ?? 'Chi tiết học cụ')),
      body: p == null
          ? (_error != null ? ErrorView(error: _error!, onRetry: _load) : const LoadingView())
          : RefreshIndicator(onRefresh: _load, child: _buildContent(p)),
      bottomNavigationBar: p == null ? null : _buildBuyBar(p),
    );
  }

  Widget _buildContent(SupplyProduct p) {
    final text = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        AspectRatio(
          aspectRatio: 1.2,
          child: SupplyImage(url: p.thumbnailUrl, borderRadius: BorderRadius.zero),
        ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(p.categoryName ?? 'Học cụ', style: text.labelMedium?.copyWith(color: scheme.primary)),
              const SizedBox(height: 4),
              Text(p.name, style: text.headlineSmall),
              const SizedBox(height: 8),
              Row(
                children: [
                  Text(
                    formatVnd(p.price),
                    style: text.headlineSmall?.copyWith(color: scheme.primary, fontWeight: FontWeight.bold),
                  ),
                  const Spacer(),
                  StockBadge(available: p.available, lowStock: p.lowStock),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  RatingStars(rating: p.ratingAvg),
                  const SizedBox(width: 6),
                  Text(
                    p.ratingCount == 0
                        ? 'Chưa có đánh giá'
                        : '${p.ratingAvg.toStringAsFixed(1)} · ${p.ratingCount} đánh giá',
                  ),
                  const Spacer(),
                  Text('SKU: ${p.sku}', style: text.bodySmall),
                ],
              ),
              const Divider(height: 32),
              Text('Mô tả', style: text.titleMedium),
              const SizedBox(height: 6),
              Text(p.description?.isNotEmpty == true ? p.description! : 'Chưa có mô tả.'),
              const Divider(height: 32),
              _buildReviewSection(p),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildReviewSection(SupplyProduct p) {
    final text = Theme.of(context).textTheme;
    final elig = _eligibility;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Đánh giá', style: text.titleMedium),
        const SizedBox(height: 8),
        if (_summary != null && _summary!.count > 0) _ReviewSummaryView(summary: _summary!),
        const SizedBox(height: 8),
        if (elig != null && elig.canReview)
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _writeReview,
              icon: Icon(elig.myReview == null ? Icons.rate_review_outlined : Icons.edit_outlined),
              label: Text(elig.myReview == null ? 'Viết đánh giá' : 'Sửa đánh giá của bạn'),
            ),
          )
        else if (elig?.message != null)
          Row(
            children: [
              const Icon(Icons.info_outline, size: 16),
              const SizedBox(width: 6),
              Expanded(child: Text(elig!.message!, style: text.bodySmall)),
            ],
          ),
        const SizedBox(height: 8),
        if (_reviews.isEmpty && !_loadingReviews)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 16),
            child: Center(child: Text('Chưa có đánh giá nào.')),
          ),
        for (final r in _reviews) _ReviewTile(review: r),
        if (_loadingReviews) const Padding(padding: EdgeInsets.all(12), child: LoadingView()),
        if (_reviewHasMore && !_loadingReviews)
          Center(
            child: TextButton(onPressed: () => _loadReviews(), child: const Text('Xem thêm đánh giá')),
          ),
      ],
    );
  }

  Widget _buildBuyBar(SupplyProduct p) {
    final outOfStock = p.available <= 0;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
        child: Row(
          children: [
            _QuantityStepper(
              value: _quantity,
              max: outOfStock ? 1 : p.available,
              enabled: !outOfStock,
              onChanged: (v) => setState(() => _quantity = v),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton.icon(
                onPressed: outOfStock || _adding ? null : _addToCart,
                icon: _adding
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.add_shopping_cart),
                label: Text(outOfStock ? 'Hết hàng' : 'Thêm vào giỏ'),
              ),
            ),
          ],
        ),
      ),
    );
  }

}

class _QuantityStepper extends StatelessWidget {
  final int value;
  final int max;
  final bool enabled;
  final ValueChanged<int> onChanged;
  const _QuantityStepper({
    required this.value,
    required this.max,
    required this.enabled,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(24),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            visualDensity: VisualDensity.compact,
            onPressed: enabled && value > 1 ? () => onChanged(value - 1) : null,
            icon: const Icon(Icons.remove),
          ),
          Text('$value', style: Theme.of(context).textTheme.titleMedium),
          IconButton(
            visualDensity: VisualDensity.compact,
            onPressed: enabled && value < max && value < 99 ? () => onChanged(value + 1) : null,
            icon: const Icon(Icons.add),
          ),
        ],
      ),
    );
  }
}

class _ReviewSummaryView extends StatelessWidget {
  final ReviewSummary summary;
  const _ReviewSummaryView({required this.summary});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Column(
          children: [
            Text(summary.average.toStringAsFixed(1), style: text.displaySmall),
            RatingStars(rating: summary.average),
            Text('${summary.count} đánh giá', style: text.bodySmall),
          ],
        ),
        const SizedBox(width: 20),
        Expanded(
          child: Column(
            children: [
              for (var star = 5; star >= 1; star--)
                Row(
                  children: [
                    SizedBox(width: 14, child: Text('$star', style: text.bodySmall)),
                    Icon(Icons.star_rounded, size: 12, color: Colors.amber.shade700),
                    const SizedBox(width: 6),
                    Expanded(
                      child: LinearProgressIndicator(
                        value: summary.count == 0 ? 0 : (summary.histogram[star] ?? 0) / summary.count,
                        minHeight: 6,
                        borderRadius: BorderRadius.circular(3),
                      ),
                    ),
                    SizedBox(
                      width: 28,
                      child: Text(
                        '${summary.histogram[star] ?? 0}',
                        textAlign: TextAlign.end,
                        style: text.bodySmall,
                      ),
                    ),
                  ],
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _ReviewTile extends StatelessWidget {
  final Review review;
  const _ReviewTile({required this.review});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(radius: 16, child: Text(review.reviewerName.characters.last)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(review.reviewerName, style: text.titleSmall),
                    if (review.mine) ...[const SizedBox(width: 6), Text('(bạn)', style: text.bodySmall)],
                    const Spacer(),
                    Text(formatDateTime(review.createdAt), style: text.bodySmall),
                  ],
                ),
                RatingStars(rating: review.rating.toDouble(), size: 14),
                if (review.comment != null) ...[const SizedBox(height: 4), Text(review.comment!)],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
