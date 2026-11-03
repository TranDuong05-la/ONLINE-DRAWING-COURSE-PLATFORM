import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import '../../core/constants/app_colors.dart';

class StoreTab extends StatelessWidget {
  const StoreTab({super.key});

  final List<Map<String, dynamic>> _supplies = const [
    {
      'name': 'Bộ cọ vẽ chuyên nghiệp',
      'brand': 'ArtNest Studio',
      'price': '149.000đ',
      'rating': 4.9,
      'sold': 450,
      'category': 'Cọ vẽ',
      'icon': Icons.brush,
      'color': Color(0xFFFEF3C7),
    },
    {
      'name': 'Hộp màu nước 24 màu',
      'brand': 'Sakura Koi',
      'price': '320.000đ',
      'rating': 5.0,
      'sold': 820,
      'category': 'Màu vẽ',
      'icon': Icons.palette,
      'color': Color(0xFFFCE7F3),
    },
    {
      'name': 'Sổ vẽ màu nước A4 300gsm',
      'brand': 'Baohong Cotton',
      'price': '185.000đ',
      'rating': 4.8,
      'sold': 310,
      'category': 'Giấy vẽ',
      'icon': Icons.menu_book,
      'color': Color(0xFFE0E7FF),
    },
    {
      'name': 'Bộ bút chì than 12 cây',
      'brand': 'Faber-Castell',
      'price': '95.000đ',
      'rating': 4.9,
      'sold': 600,
      'category': 'Chì than',
      'icon': Icons.edit,
      'color': Color(0xFFDCFCE7),
    },
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Dụng cụ & Họa cụ'),
        elevation: 0,
        actions: [
          IconButton(
            key: const ValueKey('open-demo-cart'),
            tooltip: 'Open demo cart',
            onPressed: () => context.push('/cart'),
            icon: const Icon(Icons.shopping_cart_outlined),
          ),
        ],
      ),
      body: GridView.builder(
        padding: const EdgeInsets.all(16),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          childAspectRatio: 0.72,
          crossAxisSpacing: 14,
          mainAxisSpacing: 14,
        ),
        itemCount: _supplies.length,
        itemBuilder: (context, index) {
          final item = _supplies[index];
          return Container(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: AppColors.border),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withOpacity(0.03),
                  blurRadius: 8,
                  offset: const Offset(0, 3),
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  height: 110,
                  width: double.infinity,
                  decoration: BoxDecoration(
                    color: item['color'] as Color,
                    borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
                  ),
                  child: Center(
                    child: Icon(
                      item['icon'] as IconData,
                      size: 48,
                      color: AppColors.primaryDark.withOpacity(0.6),
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item['brand'] as String,
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 10.5,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textMuted,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        item['name'] as String,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w700,
                          color: AppColors.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            item['price'] as String,
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 13,
                              fontWeight: FontWeight.w800,
                              color: AppColors.primary,
                            ),
                          ),
                          Container(
                            padding: const EdgeInsets.all(6),
                            decoration: BoxDecoration(
                              color: AppColors.primaryMint,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: const Icon(
                              Icons.add_shopping_cart,
                              size: 15,
                              color: AppColors.primaryDark,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
