import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../core/network/api_exceptions.dart';
import '../../core/network/media_url.dart';
import '../../core/utils/format.dart';
import '../../providers/auth_provider.dart';
import '../../widgets/artnest_logo.dart';
import '../course/data/course_repository.dart';
import '../course/models/course_models.dart';
import '../course/ui/course_detail_page.dart';

class HomeTab extends StatefulWidget {
  final Function(int)? onNavigateTab;

  const HomeTab({super.key, this.onNavigateTab});

  @override
  State<HomeTab> createState() => _HomeTabState();
}

class _HomeTabState extends State<HomeTab> {
  final Set<String> _favoriteItems = {};

  // Khóa học nổi bật: lấy 2 khóa mới nhất từ backend (GET /api/courses, công khai)
  final CourseRepository _courseRepository = CourseRepository();
  List<CourseSummary> _featured = [];
  bool _loadingFeatured = true;
  String? _featuredError;

  @override
  void initState() {
    super.initState();
    _loadFeatured();
  }

  Future<void> _loadFeatured() async {
    setState(() {
      _loadingFeatured = true;
      _featuredError = null;
    });
    try {
      final page = await _courseRepository.list(size: 2);
      if (!mounted) return;
      setState(() {
        _featured = page.items;
        _loadingFeatured = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _featuredError = e.displayMessage;
        _loadingFeatured = false;
      });
    }
  }

  void _openCourse(int courseId) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => CourseDetailPage(courseId: courseId)));
  }

  /// Hàng 2 thẻ khóa học nổi bật: đang tải / lỗi (có nút thử lại) / chưa có khóa / danh sách.
  Widget _buildFeaturedCourses() {
    if (_loadingFeatured) {
      return const SizedBox(height: 200, child: Center(child: CircularProgressIndicator()));
    }
    if (_featuredError != null) {
      return Column(
        children: [
          Text(_featuredError!, textAlign: TextAlign.center),
          TextButton(onPressed: _loadFeatured, child: const Text('Thử lại')),
        ],
      );
    }
    if (_featured.isEmpty) {
      return Text(
        'Chưa có khóa học nào được mở bán.',
        style: GoogleFonts.plusJakartaSans(fontSize: 13, color: AppColors.textSecondary),
      );
    }
    final colors = [AppColors.pastelPeach, AppColors.pastelLavender];
    final cards = <Widget>[
      for (var i = 0; i < _featured.length; i++)
        Expanded(
          child: _buildCourseCard(
            course: _featured[i],
            cardColor: colors[i % colors.length],
            isFavorite: _favoriteItems.contains(_featured[i].title),
            onToggleFavorite: () => _toggleFavorite(_featured[i].title),
          ),
        ),
    ];
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < cards.length; i++) ...[
          if (i > 0) const SizedBox(width: 14),
          cards[i],
        ],
        // chỉ có 1 khóa thì chừa chỗ trống để thẻ không bị kéo giãn
        if (cards.length == 1) const Expanded(child: SizedBox()),
      ],
    );
  }

  void _toggleFavorite(String name) {
    setState(() {
      if (_favoriteItems.contains(name)) {
        _favoriteItems.remove(name);
      } else {
        _favoriteItems.add(name);
      }
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          _favoriteItems.contains(name)
              ? 'Đã thêm "$name" vào danh sách yêu thích'
              : 'Đã xóa "$name" khỏi danh sách yêu thích',
        ),
        duration: const Duration(seconds: 1),
        backgroundColor: AppColors.primary,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final authProvider = context.watch<AuthProvider>();
    final userName = authProvider.currentUser?.fullName ?? 'bạn';

    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Top Header Row
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const ArtNestLogo(size: 32),
                      const SizedBox(height: 4),
                      Text(
                        'Chào $userName, hôm nay mình vẽ gì nhỉ?',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    ],
                  ),
                  Stack(
                    children: [
                      Container(
                        width: 44,
                        height: 44,
                        decoration: BoxDecoration(
                          color: AppColors.surfaceMuted,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.notifications_none_rounded,
                          color: AppColors.textPrimary,
                          size: 24,
                        ),
                      ),
                      Positioned(
                        top: 4,
                        right: 4,
                        child: Container(
                          width: 18,
                          height: 18,
                          decoration: const BoxDecoration(
                            color: AppColors.accentPeach,
                            shape: BoxShape.circle,
                          ),
                          child: Center(
                            child: Text(
                              '2',
                              style: GoogleFonts.plusJakartaSans(
                                color: Colors.white,
                                fontSize: 10,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 16),

              // Search Bar
              Container(
                decoration: BoxDecoration(
                  color: AppColors.inputBackground,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: AppColors.border),
                ),
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    const Icon(
                      Icons.search_rounded,
                      color: AppColors.textMuted,
                      size: 22,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'Tìm khóa học, màu vẽ, cọ...',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 13.5,
                          color: AppColors.textMuted,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // Hero Green Banner
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(22),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFF1B4332), Color(0xFF2D6A4F)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(22),
                  boxShadow: [
                    BoxShadow(
                      color: const Color(0xFF1B4332).withOpacity(0.2),
                      blurRadius: 16,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.white.withOpacity(0.15),
                            borderRadius: BorderRadius.circular(20),
                          ),
                          child: Text(
                            'KHỞI NGUỒN SÁNG TẠO',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 10,
                              fontWeight: FontWeight.w700,
                              color: AppColors.primaryMint,
                              letterSpacing: 0.5,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'Mỗi người đều\ncó một sắc màu\nriêng',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 20,
                            fontWeight: FontWeight.w800,
                            color: Colors.white,
                            height: 1.25,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Khơi dậy niềm đam mê nghệ thuật\ntheo cách của riêng bạn.',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 12,
                            color: Colors.white.withOpacity(0.85),
                            height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 16),
                        GestureDetector(
                          onTap: () {
                            if (widget.onNavigateTab != null) {
                              widget.onNavigateTab!(1); // Go to Courses
                            }
                          },
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
                            decoration: BoxDecoration(
                              color: AppColors.accentYellow,
                              borderRadius: BorderRadius.circular(24),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  'Khám phá ngay',
                                  style: GoogleFonts.plusJakartaSans(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700,
                                    color: AppColors.primaryDark,
                                  ),
                                ),
                                const SizedBox(width: 6),
                                const Icon(
                                  Icons.arrow_forward_rounded,
                                  size: 14,
                                  color: AppColors.primaryDark,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                    Positioned(
                      right: -10,
                      bottom: 0,
                      child: Container(
                        width: 90,
                        height: 90,
                        decoration: BoxDecoration(
                          color: AppColors.accentPeach.withOpacity(0.2),
                          shape: BoxShape.circle,
                        ),
                        child: const Center(
                          child: Icon(
                            Icons.palette_rounded,
                            size: 58,
                            color: AppColors.accentYellow,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 28),

              // Categories Row: "Khám phá theo sở thích"
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Khám phá theo sở thích',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  Text(
                    'Xem tất cả ›',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: AppColors.primary,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  _buildCategoryItem(
                    label: 'Học vẽ',
                    color: AppColors.pastelTeal,
                    iconColor: const Color(0xFF0D9488),
                    icon: Icons.brush_rounded,
                    onTap: () => widget.onNavigateTab?.call(1),
                  ),
                  _buildCategoryItem(
                    label: 'Màu vẽ',
                    color: AppColors.pastelPeach,
                    iconColor: AppColors.accentPeach,
                    icon: Icons.color_lens_rounded,
                    onTap: () => widget.onNavigateTab?.call(2),
                  ),
                  _buildCategoryItem(
                    label: 'Dụng cụ',
                    color: AppColors.pastelGreen,
                    iconColor: AppColors.primaryMedium,
                    icon: Icons.draw_rounded,
                    onTap: () => widget.onNavigateTab?.call(2),
                  ),
                  _buildCategoryItem(
                    label: 'Sáng tạo',
                    color: AppColors.pastelLavender,
                    iconColor: const Color(0xFF8B5CF6),
                    icon: Icons.lightbulb_outline_rounded,
                    onTap: () => widget.onNavigateTab?.call(1),
                  ),
                ],
              ),
              const SizedBox(height: 28),

              // Section: "Lớp học được yêu thích"
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Lớp học được yêu thích',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  GestureDetector(
                    onTap: () => widget.onNavigateTab?.call(1),
                    child: Text(
                      'Xem tất cả ›',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.primary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              _buildFeaturedCourses(),
              const SizedBox(height: 28),

              // Section: "Góc họa cụ xinh"
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Góc họa cụ xinh',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  GestureDetector(
                    onTap: () => widget.onNavigateTab?.call(2),
                    child: Text(
                      'Xem tất cả ›',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.primary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  Expanded(
                    child: _buildProductCard(
                      title: 'Bộ cọ đa năng',
                      specs: '6 cây - Lông mềm',
                      price: '89.000đ',
                      icon: Icons.brush_outlined,
                      backgroundColor: const Color(0xFFFFF7ED),
                      onAdd: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Đã thêm "Bộ cọ đa năng" vào giỏ'),
                            backgroundColor: AppColors.primary,
                          ),
                        );
                      },
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: _buildProductCard(
                      title: 'Bút chì màu',
                      specs: '12 màu cao cấp',
                      price: '65.000đ',
                      icon: Icons.mode_edit_outline_rounded,
                      backgroundColor: const Color(0xFFF0FDF4),
                      onAdd: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Đã thêm "Bút chì màu" vào giỏ'),
                            backgroundColor: AppColors.primary,
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 28),

              // Warm Inspiring Banner
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  color: AppColors.accentYellowLight,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: AppColors.accentYellow.withOpacity(0.4)),
                ),
                child: Row(
                  children: [
                    Container(
                      width: 44,
                      height: 44,
                      decoration: const BoxDecoration(
                        color: AppColors.accentYellow,
                        shape: BoxShape.circle,
                      ),
                      child: const Center(
                        child: Icon(Icons.favorite_rounded, color: AppColors.primaryDark, size: 24),
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Sáng tạo không giới hạn tuổi',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: AppColors.textPrimary,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Dù bạn là người lớn hay trẻ nhỏ, mỗi người đều có nét vẽ phù hợp.',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                              height: 1.4,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCategoryItem({
    required String label,
    required Color color,
    required Color iconColor,
    required IconData icon,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        children: [
          Container(
            width: 58,
            height: 58,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              boxShadow: [
                BoxShadow(
                  color: color.withOpacity(0.4),
                  blurRadius: 8,
                  offset: const Offset(0, 3),
                ),
              ],
            ),
            child: Center(
              child: Icon(icon, color: iconColor, size: 26),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            label,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
        ],
      ),
    );
  }

  /// Thẻ khóa học nổi bật lấy dữ liệu thật; bấm vào để mở trang chi tiết.
  Widget _buildCourseCard({
    required CourseSummary course,
    required Color cardColor,
    required bool isFavorite,
    required VoidCallback onToggleFavorite,
  }) {
    final title = course.title;
    final subtitle = levelLabel(course.level);
    final meta = '${course.lessonCount} bài';
    final thumbnail = resolveMediaUrl(course.thumbnailUrl);
    return GestureDetector(
      onTap: () => _openCourse(course.id),
      child: _buildCourseCardBody(
        title: title,
        subtitle: subtitle,
        meta: meta,
        course: course,
        cardColor: cardColor,
        thumbnailUrl: thumbnail,
        isFavorite: isFavorite,
        onToggleFavorite: onToggleFavorite,
      ),
    );
  }

  Widget _buildCourseCardBody({
    required String title,
    required String subtitle,
    required String meta,
    required CourseSummary course,
    required Color cardColor,
    required String thumbnailUrl,
    required bool isFavorite,
    required VoidCallback onToggleFavorite,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.04),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Preview Container
          Container(
            height: 110,
            width: double.infinity,
            decoration: BoxDecoration(
              color: cardColor,
              borderRadius: const BorderRadius.vertical(top: Radius.circular(18)),
            ),
            child: Stack(
              children: [
                Center(
                  child: Icon(Icons.palette_outlined, size: 48, color: AppColors.primary.withOpacity(0.45)),
                ),
                // Ảnh bìa (nếu có) phủ lên biểu tượng; lỗi tải ảnh thì vẫn thấy biểu tượng bên dưới
                if (thumbnailUrl.isNotEmpty)
                  Positioned.fill(
                    child: ClipRRect(
                      borderRadius: const BorderRadius.vertical(top: Radius.circular(18)),
                      child: Image.network(
                        thumbnailUrl,
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) => const SizedBox.shrink(),
                      ),
                    ),
                  ),
                Positioned(
                  top: 8,
                  right: 8,
                  child: GestureDetector(
                    onTap: onToggleFavorite,
                    child: Container(
                      padding: const EdgeInsets.all(6),
                      decoration: const BoxDecoration(
                        color: Colors.white,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        isFavorite ? Icons.favorite : Icons.favorite_border,
                        size: 16,
                        color: isFavorite ? AppColors.accentPeach : AppColors.textMuted,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 11,
                    color: AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    const Icon(Icons.play_circle_outline, size: 14, color: AppColors.primaryMedium),
                    const SizedBox(width: 3),
                    Flexible(
                      child: Text(
                        '$meta • ${formatDuration(course.totalDurationSeconds)}',
                        overflow: TextOverflow.ellipsis,
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                // Giá thực bán (giá giảm nếu có); có giảm thì giá gốc gạch ngang bên dưới
                Text(
                  formatPrice(course.effectivePrice),
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                    color: AppColors.primary,
                  ),
                ),
                if (course.hasDiscount)
                  Text(
                    formatPrice(course.price),
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11,
                      color: AppColors.textMuted,
                      decoration: TextDecoration.lineThrough,
                    ),
                  ),
                if (course.hasKits) ...[
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      const Icon(Icons.card_giftcard, size: 13, color: AppColors.accentPeach),
                      const SizedBox(width: 4),
                      Flexible(
                        child: Text(
                          'Tặng kèm dụng cụ',
                          overflow: TextOverflow.ellipsis,
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 10.5,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accentPeach,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProductCard({
    required String title,
    required String specs,
    required String price,
    required IconData icon,
    required Color backgroundColor,
    required VoidCallback onAdd,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.04),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            height: 100,
            width: double.infinity,
            decoration: BoxDecoration(
              color: backgroundColor,
              borderRadius: const BorderRadius.vertical(top: Radius.circular(18)),
            ),
            child: Center(
              child: Icon(icon, size: 42, color: AppColors.primaryDark.withOpacity(0.5)),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  specs,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 11,
                    color: AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      price,
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w800,
                        color: AppColors.primary,
                      ),
                    ),
                    GestureDetector(
                      onTap: onAdd,
                      child: Container(
                        padding: const EdgeInsets.all(6),
                        decoration: BoxDecoration(
                          color: AppColors.primaryMint,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: const Icon(
                          Icons.add,
                          size: 16,
                          color: AppColors.primaryDark,
                        ),
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
  }
}
