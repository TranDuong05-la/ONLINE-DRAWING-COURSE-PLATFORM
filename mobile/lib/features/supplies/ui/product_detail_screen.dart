import 'package:flutter/material.dart';

import '../../../models/page_response.dart';
import '../data/supply_repository.dart';
import '../models/supply_models.dart';
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

  @override
  Widget build(BuildContext context) {
    final product = _product;
    return Scaffold(
      appBar: AppBar(title: Text(product?.name ?? 'Product')),
      body: _error != null && product == null
          ? ErrorView(error: _error!, onRetry: _load)
          : product == null
              ? const LoadingView()
              : ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    SupplyImage(url: product.thumbnailUrl, size: 280),
                    const SizedBox(height: 16),
                    Text(product.name, style: Theme.of(context).textTheme.headlineSmall),
                    Text(formatVnd(product.price), style: Theme.of(context).textTheme.titleLarge),
                    StockBadge(available: product.available, lowStock: product.lowStock),
                    if (product.description != null) ...[
                      const SizedBox(height: 12),
                      Text(product.description!),
                    ],
                    if (_error != null) Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_error.toString(), style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ),
                    const Divider(height: 32),
                    Text('Reviews', style: Theme.of(context).textTheme.titleLarge),
                    if (_summary != null) _ReviewSummaryView(summary: _summary!),
                    if (_eligibility?.canReview == true)
                      TextButton.icon(
                        onPressed: () async {
                          final saved = await showReviewForm(context, productId: product.id, productName: product.name);
                          if (saved && mounted) _loadReviews(reset: true);
                        },
                        icon: const Icon(Icons.rate_review_outlined),
                        label: const Text('Write a review'),
                      ),
                    if (_reviews.isEmpty && !_loadingReviews)
                      const Padding(padding: EdgeInsets.all(16), child: Text('No reviews yet.')),
                    for (final review in _reviews) _ReviewTile(review: review),
                    if (_loadingReviews) const Center(child: CircularProgressIndicator()),
                    if (_reviewHasMore && !_loadingReviews)
                      TextButton(onPressed: () => _loadReviews(), child: const Text('Load more reviews')),
                  ],
                ),
      bottomNavigationBar: product == null ? null : _buildBuyBar(product),
    );
  }

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
              enabled: false,
              onChanged: (v) => setState(() => _quantity = v),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton.icon(
                onPressed: null,
                icon: const Icon(Icons.add_shopping_cart),
                label: Text(outOfStock ? 'Out of stock' : 'Product checkout unavailable'),
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
