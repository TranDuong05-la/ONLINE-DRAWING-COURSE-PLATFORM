import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../core/network/api_client.dart';
import '../../providers/auth_provider.dart';
import '../../widgets/custom_button.dart';
import '../../widgets/server_config_dialog.dart';
import '../../widgets/status_badge.dart';

class ProfileScreen extends StatelessWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final authProvider = context.watch<AuthProvider>();
    final user = authProvider.currentUser;

    if (user == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Tài khoản')),
        body: Center(
          child: CustomButton(
            text: 'Đăng nhập ngay',
            width: 180,
            onPressed: () => context.go('/login'),
          ),
        ),
      );
    }

    final createdAtStr = user.createdAt != null
        ? DateFormat('dd/MM/yyyy').format(user.createdAt!)
        : 'Mới gia nhập';

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Tài khoản của tôi'),
        elevation: 0,
        actions: [
          IconButton(
            tooltip: 'Cấu hình Server API',
            icon: const Icon(Icons.tune_rounded, color: AppColors.textPrimary),
            onPressed: () {
              ServerConfigDialog.show(context, ApiClient());
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        child: Column(
          children: [
            // User Profile Card
            Container(
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
                    color: const Color(0xFF1B4332).withOpacity(0.18),
                    blurRadius: 16,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Row(
                children: [
                  Container(
                    width: 64,
                    height: 64,
                    decoration: BoxDecoration(
                      color: AppColors.primaryMint,
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white, width: 2),
                    ),
                    child: Center(
                      child: Text(
                        user.fullName.isNotEmpty
                            ? user.fullName.substring(0, 1).toUpperCase()
                            : 'A',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: AppColors.primaryDark,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          user.fullName,
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 18,
                            fontWeight: FontWeight.w700,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(height: 3),
                        Text(
                          user.email,
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 12.5,
                            color: Colors.white.withOpacity(0.85),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            RoleBadge(role: user.role),
                            const SizedBox(width: 8),
                            Text(
                              'Gia nhập: $createdAtStr',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 11,
                                color: Colors.white.withOpacity(0.7),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),

            // Profile Actions Section
            _buildSectionHeader('Quản lý thông tin'),
            _buildMenuCard([
              _buildMenuItem(
                icon: Icons.person_outline_rounded,
                title: 'Chỉnh sửa hồ sơ',
                subtitle: 'Cập nhật họ tên, số điện thoại, avatar',
                onTap: () => context.push('/profile/edit'),
              ),
              const Divider(height: 1, indent: 56),
              _buildMenuItem(
                icon: Icons.location_on_outlined,
                title: 'Sổ địa chỉ nhận hàng',
                subtitle: 'Quản lý danh sách địa chỉ giao hàng',
                onTap: () => context.push('/profile/addresses'),
              ),
              const Divider(height: 1, indent: 56),
              _buildMenuItem(
                icon: Icons.lock_outline_rounded,
                title: 'Đổi mật khẩu',
                subtitle: 'Bảo mật và cập nhật mật khẩu mới',
                onTap: () => context.push('/profile/change-password'),
              ),
            ]),
            const SizedBox(height: 20),

            // Admin Section (Only for Admin role)
            if (user.isAdmin) ...[
              _buildSectionHeader('Khu vực Quản trị viên (Admin)'),
              _buildMenuCard([
                _buildMenuItem(
                  icon: Icons.admin_panel_settings_outlined,
                  iconColor: AppColors.accentPeach,
                  title: 'Quản lý người dùng',
                  subtitle: 'Xem danh sách, tìm kiếm & quản lý trạng thái tài khoản',
                  onTap: () => context.push('/admin/users'),
                ),
                _buildMenuItem(
                  icon: Icons.menu_book_outlined,
                  iconColor: AppColors.primaryMedium,
                  title: 'Quản lý khóa học & combo',
                  subtitle: 'Tạo khóa, module, bài học, quà tặng kèm, xuất bản và combo',
                  onTap: () => context.push('/admin/courses'),
                ),
              ]),
              const SizedBox(height: 20),
            ],

            // System and support section
            _buildSectionHeader('Hệ thống & Cài đặt'),
            _buildMenuCard([
              _buildMenuItem(
                icon: Icons.dns_outlined,
                title: 'Cấu hình Server API',
                subtitle: 'Địa chỉ máy chủ: ${ApiClient().baseUrl}',
                onTap: () => ServerConfigDialog.show(context, ApiClient()),
              ),
              const Divider(height: 1, indent: 56),
              _buildMenuItem(
                icon: Icons.help_outline_rounded,
                title: 'Hỗ trợ & Thông tin API',
                subtitle: 'Xem danh mục API backend Spring Boot 8080',
                onTap: () {
                  showAboutDialog(
                    context: context,
                    applicationName: 'ArtNest Mobile App',
                    applicationVersion: '1.0.0',
                    applicationLegalese: 'Kết nối Spring Boot API (com.artacademy)',
                    children: [
                      const SizedBox(height: 12),
                      const Text(
                        'Ứng dụng được xây dựng bởi Senior Flutter Developer, tích hợp đầy đủ các API Auth, User Profile, Address Management, Token Refresh và Admin Management.',
                      ),
                    ],
                  );
                },
              ),
            ]),
            const SizedBox(height: 28),

            // Logout Button
            CustomButton(
              text: 'Đăng xuất',
              type: CustomButtonType.outline,
              icon: Icons.logout_rounded,
              onPressed: () async {
                final confirm = await showDialog<bool>(
                  context: context,
                  builder: (ctx) => AlertDialog(
                    title: const Text('Đăng xuất'),
                    content: const Text('Bạn có chắc chắn muốn đăng xuất khỏi tài khoản không?'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(ctx, false),
                        child: const Text('Hủy'),
                      ),
                      ElevatedButton(
                        onPressed: () => Navigator.pop(ctx, true),
                        style: ElevatedButton.styleFrom(backgroundColor: AppColors.error),
                        child: const Text('Đăng xuất'),
                      ),
                    ],
                  ),
                );

                if (confirm == true && context.mounted) {
                  await authProvider.logout();
                  if (context.mounted) {
                    context.go('/login');
                  }
                }
              },
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Padding(
        padding: const EdgeInsets.only(left: 4, bottom: 8),
        child: Text(
          title,
          style: GoogleFonts.plusJakartaSans(
            fontSize: 13,
            fontWeight: FontWeight.w700,
            color: AppColors.textSecondary,
          ),
        ),
      ),
    );
  }

  Widget _buildMenuCard(List<Widget> children) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(children: children),
    );
  }

  Widget _buildMenuItem({
    required IconData icon,
    Color? iconColor,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return ListTile(
      onTap: onTap,
      leading: Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          color: (iconColor ?? AppColors.primary).withOpacity(0.1),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Center(
          child: Icon(icon, color: iconColor ?? AppColors.primary, size: 20),
        ),
      ),
      title: Text(
        title,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 14,
          fontWeight: FontWeight.w600,
          color: AppColors.textPrimary,
        ),
      ),
      subtitle: Text(
        subtitle,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 12,
          color: AppColors.textSecondary,
        ),
      ),
      trailing: const Icon(Icons.chevron_right, color: AppColors.textMuted, size: 20),
    );
  }
}
