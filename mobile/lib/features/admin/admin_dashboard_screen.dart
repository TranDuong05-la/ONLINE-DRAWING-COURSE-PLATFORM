import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../core/network/api_client.dart';
import '../../models/user_model.dart';
import '../../providers/admin_provider.dart';
import '../../providers/auth_provider.dart';
import '../supplies/ui/admin/admin_supply_home.dart';
import '../../widgets/server_config_dialog.dart';
import '../../widgets/status_badge.dart';

class AdminDashboardScreen extends StatefulWidget {
  const AdminDashboardScreen({super.key});

  @override
  State<AdminDashboardScreen> createState() => _AdminDashboardScreenState();
}

class _AdminDashboardScreenState extends State<AdminDashboardScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AdminProvider>().loadDashboard();
      context.read<AdminProvider>().loadUsers();
    });
  }

  @override
  Widget build(BuildContext context) {
    final adminProvider = context.watch<AdminProvider>();
    final authProvider = context.watch<AuthProvider>();
    final dashboard = adminProvider.dashboard;
    final currentUser = authProvider.currentUser;

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      appBar: AppBar(
        title: Text(
          'ArtNest Admin Portal',
          style: GoogleFonts.plusJakartaSans(
            fontWeight: FontWeight.w700,
            fontSize: 18,
          ),
        ),
        elevation: 0,
        backgroundColor: Colors.white,
        actions: [
          IconButton(
            tooltip: 'Làm mới dữ liệu',
            icon: const Icon(Icons.refresh_rounded, color: AppColors.textPrimary),
            onPressed: () {
              adminProvider.loadDashboard();
              adminProvider.loadUsers();
            },
          ),
          IconButton(
            tooltip: 'Cấu hình Server API',
            icon: const Icon(Icons.tune_rounded, color: AppColors.textPrimary),
            onPressed: () => ServerConfigDialog.show(context, ApiClient()),
          ),
        ],
      ),
      body: adminProvider.isDashboardLoading && dashboard == null
          ? const Center(
              child: CircularProgressIndicator(
                valueColor: AlwaysStoppedAnimation<Color>(AppColors.primary),
              ),
            )
          : RefreshIndicator(
              onRefresh: () async {
                await Future.wait([
                  adminProvider.loadDashboard(),
                  adminProvider.loadUsers(),
                ]);
              },
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Admin Greeting Banner
                    _buildAdminBanner(currentUser),
                    const SizedBox(height: 20),

                    // Section Title: Metrics Overview
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Tổng quan hệ thống',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                            color: AppColors.textPrimary,
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: AppColors.primaryMint.withOpacity(0.3),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Text(
                            'Dữ liệu thời gian thực',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                              color: AppColors.primaryDark,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),

                    // Metrics Grid (4 Role Stats)
                    GridView.count(
                      crossAxisCount: 2,
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      crossAxisSpacing: 12,
                      mainAxisSpacing: 12,
                      childAspectRatio: 1.45,
                      children: [
                        _buildStatCard(
                          title: 'Tổng người dùng',
                          value: '${dashboard?.totalUsers ?? adminProvider.totalElements}',
                          icon: Icons.people_alt_outlined,
                          accentColor: const Color(0xFF2B2D42),
                          bgColor: const Color(0xFFEDF2F4),
                        ),
                        _buildStatCard(
                          title: 'Giảng viên',
                          value: '${dashboard?.totalInstructors ?? 0}',
                          icon: Icons.school_outlined,
                          accentColor: const Color(0xFF0284C7),
                          bgColor: const Color(0xFFE0F2FE),
                        ),
                        _buildStatCard(
                          title: 'Nhân viên vận hành',
                          value: '${dashboard?.totalStaff ?? 0}',
                          icon: Icons.badge_outlined,
                          accentColor: const Color(0xFFD97706),
                          bgColor: const Color(0xFFFEF3C7),
                        ),
                        _buildStatCard(
                          title: 'Quản trị viên (Admin)',
                          value: '${dashboard?.totalAdmins ?? 0}',
                          icon: Icons.admin_panel_settings_outlined,
                          accentColor: const Color(0xFFE76F51),
                          bgColor: const Color(0xFFFDE8E1),
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),

                    // Secondary Metrics (Active accounts & Catalog Modules)
                    Row(
                      children: [
                        Expanded(
                          child: _buildMiniStat(
                            title: 'Tài khoản hoạt động',
                            value: '${dashboard?.activeUsers ?? 0}',
                            icon: Icons.check_circle_outline,
                            color: AppColors.success,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: _buildMiniStat(
                            title: 'Tài khoản bị khóa',
                            value: '${dashboard?.inactiveUsers ?? 0}',
                            icon: Icons.lock_outline,
                            color: AppColors.error,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),

                    // Quick Management Actions
                    Text(
                      'Trung tâm điều hành',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 12),

                    _buildActionCard(
                      title: 'Quản lý tài khoản & Phân quyền RBAC',
                      subtitle: 'Xem danh sách toàn bộ người dùng, tìm kiếm, lọc theo vai trò, đổi role và kích hoạt / khóa tài khoản.',
                      icon: Icons.manage_accounts_rounded,
                      iconColor: AppColors.primary,
                      badgeText: '${dashboard?.totalUsers ?? adminProvider.totalElements} tài khoản',
                      onTap: () => context.push('/admin/users'),
                    ),
                    const SizedBox(height: 12),

                    _buildActionCard(
                      title: 'Khóa học & Bài giảng (Course Catalog)',
                      subtitle: 'Quản lý danh mục khóa học, phê duyệt bài giảng của giảng viên.',
                      icon: Icons.auto_stories_rounded,
                      iconColor: const Color(0xFF0284C7),
                      badgeText: 'Module đang phát triển',
                      badgeColor: Colors.grey.shade600,
                      onTap: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Module Quản lý Khóa học đang trong lộ trình tích hợp API backend.'),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 12),

                    _buildActionCard(
                      title: 'Đơn hàng & Giao hàng (Order & Delivery)',
                      subtitle: 'Theo dõi tiến độ đơn hàng dụng cụ vẽ và tình trạng thanh toán.',
                      icon: Icons.local_shipping_outlined,
                      iconColor: const Color(0xFFD97706),
                      badgeText: 'Module đang phát triển',
                      badgeColor: Colors.grey.shade600,
                      onTap: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Module Quản lý Đơn hàng & Vận chuyển đang trong lộ trình tích hợp API backend.'),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 12),

                    // Phần E: học cụ, tồn kho, giao hàng
                    _buildActionCard(
                      title: 'Học cụ, Kho & Giao hàng',
                      subtitle: 'Tạo/sửa/ẩn học cụ, nhập kho, đóng gói và cập nhật trạng thái giao hàng.',
                      icon: Icons.inventory_2_rounded,
                      iconColor: const Color(0xFF7C3AED),
                      badgeText: 'Sẵn sàng',
                      onTap: () => Navigator.push(
                        context,
                        MaterialPageRoute(builder: (_) => const AdminSupplyHome()),
                      ),
                    ),
                    const SizedBox(height: 24),

                    // Recent Users Section Preview
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Người dùng mới nhất',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                            color: AppColors.textPrimary,
                          ),
                        ),
                        TextButton(
                          onPressed: () => context.push('/admin/users'),
                          child: Text(
                            'Xem tất cả',
                            style: GoogleFonts.plusJakartaSans(
                              fontWeight: FontWeight.w600,
                              color: AppColors.primary,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),

                    if (adminProvider.users.isEmpty)
                      Container(
                        padding: const EdgeInsets.all(24),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: Center(
                          child: Text(
                            'Chưa có người dùng nào được tải',
                            style: GoogleFonts.plusJakartaSans(
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ),
                      )
                    else
                      Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: ListView.separated(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: adminProvider.users.take(5).length,
                          separatorBuilder: (context, index) => const Divider(height: 1),
                          itemBuilder: (ctx, index) {
                            final user = adminProvider.users[index];
                            return ListTile(
                              onTap: () => context.push('/admin/users/${user.id}', extra: user),
                              leading: CircleAvatar(
                                backgroundColor: AppColors.primaryMint,
                                child: Text(
                                  user.fullName.isNotEmpty ? user.fullName[0].toUpperCase() : 'U',
                                  style: GoogleFonts.plusJakartaSans(
                                    fontWeight: FontWeight.w700,
                                    color: AppColors.primaryDark,
                                  ),
                                ),
                              ),
                              title: Text(
                                user.fullName,
                                style: GoogleFonts.plusJakartaSans(
                                  fontWeight: FontWeight.w600,
                                  fontSize: 14,
                                ),
                              ),
                              subtitle: Text(
                                user.email,
                                style: GoogleFonts.plusJakartaSans(fontSize: 12),
                              ),
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  RoleBadge(role: user.role),
                                  const SizedBox(width: 8),
                                  const Icon(Icons.chevron_right, size: 18, color: AppColors.textMuted),
                                ],
                              ),
                            );
                          },
                        ),
                      ),
                    const SizedBox(height: 32),
                  ],
                ),
              ),
            ),
    );
  }

  Widget _buildAdminBanner(UserModel? currentUser) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF1B4332), Color(0xFF2D6A4F)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFF1B4332).withOpacity(0.2),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Row(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: AppColors.accentPeachLight,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 2),
            ),
            child: const Center(
              child: Icon(Icons.shield_outlined, color: Color(0xFFE76F51), size: 30),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      currentUser?.fullName ?? 'Administrator',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                        color: Colors.white,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: const Color(0xFFE76F51),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        'SUPER ADMIN',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 9,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  'Hệ thống quản trị và phân quyền RBAC ArtNest',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 12,
                    color: Colors.white.withOpacity(0.85),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatCard({
    required String title,
    required String value,
    required IconData icon,
    required Color accentColor,
    required Color bgColor,
  }) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.02),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: bgColor,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: accentColor, size: 20),
              ),
              Text(
                value,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: accentColor,
                ),
              ),
            ],
          ),
          Text(
            title,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: AppColors.textSecondary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMiniStat({
    required String title,
    required String value,
    required IconData icon,
    required Color color,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 11,
                    color: AppColors.textSecondary,
                  ),
                ),
                Text(
                  value,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionCard({
    required String title,
    required String subtitle,
    required IconData icon,
    required Color iconColor,
    required String badgeText,
    Color? badgeColor,
    required VoidCallback onTap,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border),
      ),
      child: ListTile(
        onTap: onTap,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: iconColor.withOpacity(0.12),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Icon(icon, color: iconColor, size: 24),
        ),
        title: Row(
          children: [
            Expanded(
              child: Text(
                title,
                style: GoogleFonts.plusJakartaSans(
                  fontWeight: FontWeight.w700,
                  fontSize: 14,
                  color: AppColors.textPrimary,
                ),
              ),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: (badgeColor ?? AppColors.primary).withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                badgeText,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                  color: badgeColor ?? AppColors.primary,
                ),
              ),
            ),
          ],
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            subtitle,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
        ),
        trailing: const Icon(Icons.arrow_forward_ios_rounded, size: 16, color: AppColors.textMuted),
      ),
    );
  }
}
