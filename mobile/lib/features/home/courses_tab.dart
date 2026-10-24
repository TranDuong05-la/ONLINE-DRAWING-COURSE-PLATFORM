import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../../core/constants/app_colors.dart';

class CoursesTab extends StatefulWidget {
  const CoursesTab({super.key});

  @override
  State<CoursesTab> createState() => _CoursesTabState();
}

class _CoursesTabState extends State<CoursesTab> {
  String _selectedCategory = 'Tất cả';

  final List<String> _categories = [
    'Tất cả',
    'Màu nước',
    'Hình họa',
    'Sơn dầu',
    'Ký họa',
    'Digital Art',
  ];

  final List<Map<String, dynamic>> _courseList = [
    {
      'title': 'Vẽ màu nước cơ bản',
      'instructor': 'Giảng viên Thu Hà',
      'rating': 4.9,
      'students': 320,
      'duration': '8 buổi',
      'price': '399.000đ',
      'level': 'Người mới',
      'badgeColor': AppColors.pastelPeach,
      'icon': Icons.water_drop,
    },
    {
      'title': 'Nhập môn hình họa khối',
      'instructor': 'Thầy Quốc Huy',
      'rating': 4.8,
      'students': 210,
      'duration': '10 buổi',
      'price': '450.000đ',
      'level': 'Cơ bản',
      'badgeColor': AppColors.pastelLavender,
      'icon': Icons.crop_square,
    },
    {
      'title': 'Vẽ tĩnh vật & ánh sáng',
      'instructor': 'Cô Mai Lan',
      'rating': 5.0,
      'students': 185,
      'duration': '6 buổi',
      'price': '299.000đ',
      'level': 'Mọi cấp độ',
      'badgeColor': AppColors.pastelTeal,
      'icon': Icons.light_mode,
    },
    {
      'title': 'Ký họa phong cảnh đô thị',
      'instructor': 'Họa sĩ Minh Trí',
      'rating': 4.9,
      'students': 140,
      'duration': '8 buổi',
      'price': '520.000đ',
      'level': 'Trung cấp',
      'badgeColor': AppColors.pastelGreen,
      'icon': Icons.location_city,
    },
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Khóa học mỹ thuật'),
        elevation: 0,
      ),
      body: Column(
        children: [
          // Filter Chips
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Row(
              children: _categories.map((cat) {
                final isSelected = _selectedCategory == cat;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: FilterChip(
                    label: Text(cat),
                    selected: isSelected,
                    onSelected: (val) {
                      setState(() {
                        _selectedCategory = cat;
                      });
                    },
                    selectedColor: AppColors.primaryMint,
                    labelStyle: GoogleFonts.plusJakartaSans(
                      fontSize: 12.5,
                      fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                      color: isSelected ? AppColors.primaryDark : AppColors.textSecondary,
                    ),
                    backgroundColor: AppColors.surfaceMuted,
                    side: BorderSide.none,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                  ),
                );
              }).toList(),
            ),
          ),

          // Course List
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              itemCount: _courseList.length,
              itemBuilder: (context, index) {
                final course = _courseList[index];
                return Container(
                  margin: const EdgeInsets.only(bottom: 14),
                  padding: const EdgeInsets.all(14),
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
                  child: Row(
                    children: [
                      Container(
                        width: 72,
                        height: 72,
                        decoration: BoxDecoration(
                          color: course['badgeColor'] as Color,
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Center(
                          child: Icon(
                            course['icon'] as IconData,
                            size: 32,
                            color: AppColors.primaryDark,
                          ),
                        ),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              course['title'] as String,
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 14.5,
                                fontWeight: FontWeight.w700,
                                color: AppColors.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              course['instructor'] as String,
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 12,
                                color: AppColors.textSecondary,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Row(
                              children: [
                                const Icon(Icons.star, size: 14, color: AppColors.accentYellow),
                                const SizedBox(width: 3),
                                Text(
                                  '${course['rating']} • ${course['duration']}',
                                  style: GoogleFonts.plusJakartaSans(
                                    fontSize: 11.5,
                                    fontWeight: FontWeight.w600,
                                    color: AppColors.textSecondary,
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            course['price'] as String,
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 14,
                              fontWeight: FontWeight.w800,
                              color: AppColors.primary,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: AppColors.primaryMint,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              'Đăng ký',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 11,
                                fontWeight: FontWeight.w700,
                                color: AppColors.primaryDark,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
