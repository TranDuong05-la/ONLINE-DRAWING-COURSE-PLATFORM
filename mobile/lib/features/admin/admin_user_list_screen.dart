import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../models/user_model.dart';
import '../../providers/admin_provider.dart';
import '../../widgets/empty_state_view.dart';
import '../../widgets/status_badge.dart';

class AdminUserListScreen extends StatefulWidget {
  const AdminUserListScreen({super.key});

  @override
  State<AdminUserListScreen> createState() => _AdminUserListScreenState();
}

class _AdminUserListScreenState extends State<AdminUserListScreen> {
  final _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AdminProvider>().loadUsers();
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _handleToggleStatus(UserModel user) async {
    final actionName = user.isActive ? 'khóa (vô hiệu hóa)' : 'kích hoạt';
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Xác nhận $actionName tài khoản'),
        content: Text(
          'Bạn có chắc chắn muốn $actionName tài khoản của "${user.fullName}" (${user.email}) không?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Hủy'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(
              backgroundColor: user.isActive ? AppColors.error : AppColors.success,
            ),
            child: Text(user.isActive ? 'Khóa tài khoản' : 'Kích hoạt'),
          ),
        ],
      ),
    );

    if (confirm == true && mounted) {
      final success = await context.read<AdminProvider>().toggleUserStatus(user.id, user.isActive);
      if (success && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              user.isActive
                  ? 'Đã vô hiệu hóa tài khoản "${user.fullName}"'
                  : 'Đã kích hoạt tài khoản "${user.fullName}"',
            ),
            backgroundColor: AppColors.primary,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final adminProvider = context.watch<AdminProvider>();

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Quản lý người dùng (Admin)'),
        elevation: 0,
      ),
      body: Column(
        children: [
          // Search & Filter Box
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: const BoxDecoration(
              color: Colors.white,
              border: Border(bottom: BorderSide(color: AppColors.border)),
            ),
            child: Column(
              children: [
                // Search Field
                TextField(
                  controller: _searchController,
                  onSubmitted: (query) => adminProvider.setSearch(query),
                  decoration: InputDecoration(
                    hintText: 'Tìm kiếm theo tên, email, sđt...',
                    prefixIcon: const Icon(Icons.search, size: 20),
                    suffixIcon: _searchController.text.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear, size: 18),
                            onPressed: () {
                              _searchController.clear();
                              adminProvider.setSearch('');
                            },
                          )
                        : null,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  ),
                ),
                const SizedBox(height: 10),

                // Filters Row
                Row(
                  children: [
                    // Role Filter Dropdown
                    Expanded(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                        decoration: BoxDecoration(
                          color: AppColors.surfaceMuted,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: DropdownButtonHideUnderline(
                          child: DropdownButton<String>(
                            value: adminProvider.roleFilter,
                            isExpanded: true,
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 12.5,
                              color: AppColors.textPrimary,
                              fontWeight: FontWeight.w600,
                            ),
                            items: const [
                              DropdownMenuItem(value: 'ALL', child: Text('Tất cả vai trò')),
                              DropdownMenuItem(value: 'USER', child: Text('Học viên (USER)')),
                              DropdownMenuItem(value: 'ADMIN', child: Text('Quản trị (ADMIN)')),
                            ],
                            onChanged: (val) {
                              if (val != null) adminProvider.setRoleFilter(val);
                            },
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),

                    // Status Filter Dropdown
                    Expanded(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                        decoration: BoxDecoration(
                          color: AppColors.surfaceMuted,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: DropdownButtonHideUnderline(
                          child: DropdownButton<bool?>(
                            value: adminProvider.statusFilter,
                            isExpanded: true,
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 12.5,
                              color: AppColors.textPrimary,
                              fontWeight: FontWeight.w600,
                            ),
                            items: const [
                              DropdownMenuItem(value: null, child: Text('Tất cả trạng thái')),
                              DropdownMenuItem(value: true, child: Text('Đang hoạt động')),
                              DropdownMenuItem(value: false, child: Text('Đã bị khóa')),
                            ],
                            onChanged: (val) {
                              adminProvider.setStatusFilter(val);
                            },
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // Total counts summary
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Tổng số: ${adminProvider.totalElements} tài khoản',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textSecondary,
                  ),
                ),
                Text(
                  'Trang ${adminProvider.currentPage + 1}/${adminProvider.totalPages == 0 ? 1 : adminProvider.totalPages}',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 12,
                    color: AppColors.textMuted,
                  ),
                ),
              ],
            ),
          ),

          // User List
          Expanded(
            child: adminProvider.isLoading
                ? const Center(
                    child: CircularProgressIndicator(
                      valueColor: AlwaysStoppedAnimation<Color>(AppColors.primary),
                    ),
                  )
                : adminProvider.errorMessage != null
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Icon(Icons.error_outline, size: 48, color: AppColors.error),
                              const SizedBox(height: 12),
                              Text(
                                adminProvider.errorMessage!,
                                textAlign: TextAlign.center,
                                style: GoogleFonts.plusJakartaSans(fontSize: 14),
                              ),
                              const SizedBox(height: 16),
                              ElevatedButton(
                                onPressed: () => adminProvider.loadUsers(),
                                child: const Text('Tải lại'),
                              ),
                            ],
                          ),
                        ),
                      )
                    : adminProvider.users.isEmpty
                        ? const EmptyStateView(
                            icon: Icons.people_outline,
                            title: 'Không tìm thấy người dùng',
                            message: 'Không có tài khoản nào phù hợp với bộ lọc tìm kiếm hiện tại.',
                          )
                        : RefreshIndicator(
                            onRefresh: () => adminProvider.loadUsers(),
                            color: AppColors.primary,
                            child: ListView.builder(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                              itemCount: adminProvider.users.length,
                              itemBuilder: (context, index) {
                                final user = adminProvider.users[index];
                                return Container(
                                  margin: const EdgeInsets.only(bottom: 12),
                                  padding: const EdgeInsets.all(14),
                                  decoration: BoxDecoration(
                                    color: Colors.white,
                                    borderRadius: BorderRadius.circular(16),
                                    border: Border.all(color: AppColors.border),
                                    boxShadow: [
                                      BoxShadow(
                                        color: Colors.black.withOpacity(0.02),
                                        blurRadius: 8,
                                        offset: const Offset(0, 3),
                                      ),
                                    ],
                                  ),
                                  child: Column(
                                    children: [
                                      Row(
                                        children: [
                                          Container(
                                            width: 46,
                                            height: 46,
                                            decoration: BoxDecoration(
                                              color: user.isAdmin
                                                  ? AppColors.accentPeachLight
                                                  : AppColors.primaryMint,
                                              shape: BoxShape.circle,
                                            ),
                                            child: Center(
                                              child: Text(
                                                user.fullName.isNotEmpty
                                                    ? user.fullName.substring(0, 1).toUpperCase()
                                                    : 'U',
                                                style: GoogleFonts.plusJakartaSans(
                                                  fontSize: 18,
                                                  fontWeight: FontWeight.w700,
                                                  color: user.isAdmin
                                                      ? AppColors.accentPeach
                                                      : AppColors.primaryDark,
                                                ),
                                              ),
                                            ),
                                          ),
                                          const SizedBox(width: 12),
                                          Expanded(
                                            child: Column(
                                              crossAxisAlignment: CrossAxisAlignment.start,
                                              children: [
                                                Text(
                                                  user.fullName,
                                                  style: GoogleFonts.plusJakartaSans(
                                                    fontSize: 14.5,
                                                    fontWeight: FontWeight.w700,
                                                    color: AppColors.textPrimary,
                                                  ),
                                                ),
                                                const SizedBox(height: 2),
                                                Text(
                                                  user.email,
                                                  style: GoogleFonts.plusJakartaSans(
                                                    fontSize: 12,
                                                    color: AppColors.textSecondary,
                                                  ),
                                                ),
                                                if (user.phone != null && user.phone!.isNotEmpty) ...[
                                                  const SizedBox(height: 2),
                                                  Text(
                                                    'SĐT: ${user.phone}',
                                                    style: GoogleFonts.plusJakartaSans(
                                                      fontSize: 11.5,
                                                      color: AppColors.textMuted,
                                                    ),
                                                  ),
                                                ],
                                              ],
                                            ),
                                          ),
                                          IconButton(
                                            icon: const Icon(Icons.arrow_forward_ios, size: 16),
                                            onPressed: () {
                                              context.push('/admin/users/${user.id}', extra: user);
                                            },
                                          ),
                                        ],
                                      ),
                                      const SizedBox(height: 10),
                                      const Divider(height: 1),
                                      const SizedBox(height: 8),
                                      Row(
                                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                        children: [
                                          Row(
                                            children: [
                                              RoleBadge(role: user.role),
                                              const SizedBox(width: 8),
                                              StatusBadge(isActive: user.isActive),
                                            ],
                                          ),
                                          TextButton.icon(
                                            icon: Icon(
                                              user.isActive
                                                  ? Icons.block_outlined
                                                  : Icons.check_circle_outline,
                                              size: 16,
                                              color: user.isActive ? AppColors.error : AppColors.success,
                                            ),
                                            label: Text(
                                              user.isActive ? 'Khóa' : 'Kích hoạt',
                                              style: TextStyle(
                                                color: user.isActive ? AppColors.error : AppColors.success,
                                                fontSize: 12,
                                                fontWeight: FontWeight.w600,
                                              ),
                                            ),
                                            onPressed: () => _handleToggleStatus(user),
                                          ),
                                        ],
                                      ),
                                    ],
                                  ),
                                );
                              },
                            ),
                          ),
          ),

          // Pagination Controls
          if (adminProvider.totalPages > 1)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              decoration: const BoxDecoration(
                color: Colors.white,
                border: Border(top: BorderSide(color: AppColors.border)),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    icon: const Icon(Icons.chevron_left),
                    onPressed: adminProvider.currentPage > 0
                        ? () => adminProvider.setPage(adminProvider.currentPage - 1)
                        : null,
                  ),
                  Text(
                    'Trang ${adminProvider.currentPage + 1} / ${adminProvider.totalPages}',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.chevron_right),
                    onPressed: adminProvider.currentPage < adminProvider.totalPages - 1
                        ? () => adminProvider.setPage(adminProvider.currentPage + 1)
                        : null,
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
