import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import '../../core/constants/app_colors.dart';
import '../../models/role_history_model.dart';
import '../../models/user_model.dart';
import '../../repositories/admin_repository.dart';
import '../../widgets/custom_button.dart';
import '../../widgets/status_badge.dart';

class AdminUserDetailScreen extends StatefulWidget {
  final int userId;
  final UserModel? initialUser;

  const AdminUserDetailScreen({
    super.key,
    required this.userId,
    this.initialUser,
  });

  @override
  State<AdminUserDetailScreen> createState() => _AdminUserDetailScreenState();
}

class _AdminUserDetailScreenState extends State<AdminUserDetailScreen> {
  final AdminRepository _adminRepository = AdminRepository();
  UserModel? _user;
  List<RoleHistoryModel> _roleHistory = [];
  bool _isLoading = false;
  bool _isHistoryLoading = false;
  String? _errorMessage;
  UserRole? _selectedRole;

  @override
  void initState() {
    super.initState();
    _user = widget.initialUser;
    _selectedRole = widget.initialUser?.role;
    _fetchUserDetail();
    _fetchRoleHistory();
  }

  Future<void> _fetchUserDetail() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final user = await _adminRepository.getUserById(widget.userId);
      if (mounted) {
        setState(() {
          _user = user;
          _selectedRole = user.role;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = e.toString().replaceFirst('Exception: ', '');
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _fetchRoleHistory() async {
    setState(() {
      _isHistoryLoading = true;
    });

    try {
      final history = await _adminRepository.getRoleHistory(widget.userId);
      if (mounted) {
        setState(() {
          _roleHistory = history;
        });
      }
    } catch (_) {
      // Non-critical: role history may be empty for newly created users
    } finally {
      if (mounted) {
        setState(() {
          _isHistoryLoading = false;
        });
      }
    }
  }

  Future<void> _handleUpdateRole() async {
    if (_user == null || _selectedRole == null || _selectedRole == _user!.role) return;

    final reasonController = TextEditingController();
    final newRoleStr = _selectedRole!.displayName;
    final oldRoleStr = _user!.role.displayName;

    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Xác nhận thay đổi vai trò'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Bạn đang thay đổi vai trò của tài khoản "${_user!.fullName}":',
              style: GoogleFonts.plusJakartaSans(fontSize: 13.5),
            ),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.surfaceMuted,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  Flexible(
                    child: Text(
                      oldRoleStr,
                      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 6),
                    child: Icon(Icons.arrow_forward, size: 16),
                  ),
                  Flexible(
                    child: Text(
                      newRoleStr,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        color: AppColors.primary,
                        fontSize: 13,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: reasonController,
              decoration: const InputDecoration(
                labelText: 'Lý do thay đổi vai trò (tùy chọn)',
                hintText: 'Nhập lý do để lưu vào lịch sử kiểm toán...',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Hủy'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.primary),
            child: const Text('Lưu thay đổi'),
          ),
        ],
      ),
    );

    if (confirm != true || !mounted) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final updated = await _adminRepository.updateUserRole(
        _user!.id,
        _selectedRole!.toServerString(),
        reason: reasonController.text.trim(),
      );

      if (mounted) {
        setState(() {
          _user = updated;
          _selectedRole = updated.role;
        });

        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Đã cập nhật vai trò người dùng thành ${_selectedRole!.displayName}'),
            backgroundColor: AppColors.primary,
          ),
        );

        // Refresh role history audit trail
        _fetchRoleHistory();
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = e.toString().replaceFirst('Exception: ', '');
          _selectedRole = _user!.role; // Revert dropdown selection
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _toggleStatus() async {
    if (_user == null) return;

    final newStatus = !_user!.isActive;
    final actionText = newStatus ? 'kích hoạt' : 'vô hiệu hóa (khóa)';

    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Xác nhận $actionText'),
        content: Text(
          'Bạn có chắc chắn muốn $actionText tài khoản của "${_user!.fullName}" không? Khi bị khóa, tài khoản sẽ bị đăng xuất ngay lập tức.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Hủy'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(
              backgroundColor: newStatus ? AppColors.success : AppColors.error,
            ),
            child: Text(newStatus ? 'Kích hoạt' : 'Khóa tài khoản'),
          ),
        ],
      ),
    );

    if (confirm != true || !mounted) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final updated = await _adminRepository.updateUserStatus(_user!.id, newStatus);
      if (mounted) {
        setState(() {
          _user = updated;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              newStatus ? 'Kích hoạt tài khoản thành công' : 'Đã vô hiệu hóa tài khoản người dùng',
            ),
            backgroundColor: AppColors.primary,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = e.toString().replaceFirst('Exception: ', '');
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      appBar: AppBar(
        title: const Text('Chi tiết tài khoản (Admin)'),
        elevation: 0,
        backgroundColor: Colors.white,
        actions: [
          IconButton(
            tooltip: 'Làm mới',
            icon: const Icon(Icons.refresh),
            onPressed: () {
              _fetchUserDetail();
              _fetchRoleHistory();
            },
          ),
        ],
      ),
      body: _isLoading && _user == null
          ? const Center(
              child: CircularProgressIndicator(
                valueColor: AlwaysStoppedAnimation<Color>(AppColors.primary),
              ),
            )
          : _user == null
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.error_outline, size: 48, color: AppColors.error),
                        const SizedBox(height: 12),
                        Text(
                          _errorMessage ?? 'Không thể tải thông tin tài khoản',
                          textAlign: TextAlign.center,
                          style: GoogleFonts.plusJakartaSans(fontSize: 14),
                        ),
                        const SizedBox(height: 16),
                        ElevatedButton(
                          onPressed: () {
                            _fetchUserDetail();
                            _fetchRoleHistory();
                          },
                          child: const Text('Thử lại'),
                        ),
                      ],
                    ),
                  ),
                )
              : SingleChildScrollView(
                  padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (_errorMessage != null) ...[
                        Container(
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: AppColors.errorLight,
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(color: AppColors.error.withOpacity(0.3)),
                          ),
                          child: Row(
                            children: [
                              const Icon(Icons.error_outline, color: AppColors.error, size: 20),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Text(
                                  _errorMessage!,
                                  style: GoogleFonts.plusJakartaSans(
                                    fontSize: 13,
                                    color: AppColors.error,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 16),
                      ],

                      // Profile Overview Card
                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(18),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: Row(
                          children: [
                            Container(
                              width: 68,
                              height: 68,
                              decoration: BoxDecoration(
                                color: AppColors.primaryMint,
                                shape: BoxShape.circle,
                                border: Border.all(color: AppColors.primary, width: 2),
                              ),
                              child: Center(
                                child: Text(
                                  _user!.fullName.isNotEmpty
                                      ? _user!.fullName.substring(0, 1).toUpperCase()
                                      : 'U',
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
                                    _user!.fullName,
                                    style: GoogleFonts.plusJakartaSans(
                                      fontSize: 18,
                                      fontWeight: FontWeight.w700,
                                      color: AppColors.textPrimary,
                                    ),
                                  ),
                                  const SizedBox(height: 3),
                                  Text(
                                    _user!.email,
                                    style: GoogleFonts.plusJakartaSans(
                                      fontSize: 13,
                                      color: AppColors.textSecondary,
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  Row(
                                    children: [
                                      RoleBadge(role: _user!.role),
                                      const SizedBox(width: 8),
                                      StatusBadge(isActive: _user!.isActive),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),

                      // Role Management Card
                      Text(
                        'Phân quyền tài khoản (RBAC)',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: AppColors.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Chọn vai trò mới cho tài khoản:',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 13,
                                color: AppColors.textSecondary,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                            const SizedBox(height: 10),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 14),
                              decoration: BoxDecoration(
                                color: AppColors.surfaceMuted,
                                borderRadius: BorderRadius.circular(12),
                                border: Border.all(color: AppColors.border),
                              ),
                              child: DropdownButtonHideUnderline(
                                child: DropdownButton<UserRole>(
                                  value: _selectedRole,
                                  isExpanded: true,
                                  style: GoogleFonts.plusJakartaSans(
                                    fontSize: 14,
                                    color: AppColors.textPrimary,
                                    fontWeight: FontWeight.w600,
                                  ),
                                  items: const [
                                    DropdownMenuItem(
                                      value: UserRole.user,
                                      child: Text('Học viên (USER) - Quyền học tập & mua sắm'),
                                    ),
                                    DropdownMenuItem(
                                      value: UserRole.instructor,
                                      child: Text('Giảng viên (INSTRUCTOR) - Quản lý khóa học & bài giảng'),
                                    ),
                                    DropdownMenuItem(
                                      value: UserRole.staff,
                                      child: Text('Nhân viên (STAFF) - Vận hành kho & xử lý đơn hàng'),
                                    ),
                                    DropdownMenuItem(
                                      value: UserRole.admin,
                                      child: Text('Quản trị viên (ADMIN) - Toàn quyền quản trị hệ thống'),
                                    ),
                                  ],
                                  onChanged: (role) {
                                    if (role != null) {
                                      setState(() {
                                        _selectedRole = role;
                                      });
                                    }
                                  },
                                ),
                              ),
                            ),
                            const SizedBox(height: 14),
                            CustomButton(
                              text: 'Lưu thay đổi vai trò',
                              type: CustomButtonType.primary,
                              icon: Icons.shield_outlined,
                              isLoading: _isLoading,
                              onPressed: (_selectedRole != null && _selectedRole != _user!.role)
                                  ? _handleUpdateRole
                                  : null,
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),

                      // User Info Details Table
                      Text(
                        'Thông tin tài khoản hệ thống',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: AppColors.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: Column(
                          children: [
                            _buildInfoTile('User ID', '#${_user!.id}'),
                            const Divider(height: 1),
                            _buildInfoTile('Họ và tên', _user!.fullName),
                            const Divider(height: 1),
                            _buildInfoTile('Email', _user!.email),
                            const Divider(height: 1),
                            _buildInfoTile('Số điện thoại', _user!.phone ?? 'Chưa cập nhật'),
                            const Divider(height: 1),
                            _buildInfoTile('Vai trò hiện tại', _user!.role.toServerString()),
                            const Divider(height: 1),
                            _buildInfoTile(
                              'Trạng thái',
                              _user!.isActive ? 'Đang hoạt động (Active)' : 'Đã khóa (Deactivated)',
                            ),
                            const Divider(height: 1),
                            _buildInfoTile(
                              'Ngày tạo',
                              _user!.createdAt != null
                                  ? DateFormat('HH:mm - dd/MM/yyyy').format(_user!.createdAt!)
                                  : 'Không xác định',
                            ),
                            if (_user!.updatedAt != null) ...[
                              const Divider(height: 1),
                              _buildInfoTile(
                                'Cập nhật lần cuối',
                                DateFormat('HH:mm - dd/MM/yyyy').format(_user!.updatedAt!),
                              ),
                            ],
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),

                      // Status Toggle Action
                      CustomButton(
                        text: _user!.isActive ? 'Khóa tài khoản người dùng' : 'Mở khóa / Kích hoạt tài khoản',
                        type: _user!.isActive ? CustomButtonType.danger : CustomButtonType.primary,
                        icon: _user!.isActive ? Icons.lock_outline : Icons.lock_open_outlined,
                        isLoading: _isLoading,
                        onPressed: _toggleStatus,
                      ),
                      const SizedBox(height: 24),

                      // Role Change Audit History Section
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            'Lịch sử thay đổi vai trò (Audit Trail)',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 15,
                              fontWeight: FontWeight.w700,
                              color: AppColors.textPrimary,
                            ),
                          ),
                          if (_isHistoryLoading)
                            const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                        ],
                      ),
                      const SizedBox(height: 8),

                      if (_roleHistory.isEmpty)
                        Container(
                          padding: const EdgeInsets.all(20),
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(16),
                            border: Border.all(color: AppColors.border),
                          ),
                          child: Center(
                            child: Text(
                              'Chưa có lịch sử thay đổi quyền nào cho tài khoản này.',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 13,
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
                            itemCount: _roleHistory.length,
                            separatorBuilder: (context, index) => const Divider(height: 1),
                            itemBuilder: (ctx, index) {
                              final h = _roleHistory[index];
                              return Padding(
                                padding: const EdgeInsets.all(14),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                      children: [
                                        Row(
                                          children: [
                                            RoleBadge(role: h.oldRole),
                                            const SizedBox(width: 6),
                                            const Icon(Icons.arrow_forward_rounded, size: 14, color: AppColors.textMuted),
                                            const SizedBox(width: 6),
                                            RoleBadge(role: h.newRole),
                                          ],
                                        ),
                                        Text(
                                          DateFormat('dd/MM/yyyy HH:mm').format(h.changedAt),
                                          style: GoogleFonts.plusJakartaSans(
                                            fontSize: 11.5,
                                            color: AppColors.textMuted,
                                          ),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 8),
                                    Text(
                                      'Người thực hiện: ${h.changedByFullName} (${h.changedByEmail})',
                                      style: GoogleFonts.plusJakartaSans(
                                        fontSize: 12.5,
                                        fontWeight: FontWeight.w600,
                                        color: AppColors.textPrimary,
                                      ),
                                    ),
                                    if (h.reason != null && h.reason!.isNotEmpty) ...[
                                      const SizedBox(height: 4),
                                      Text(
                                        'Lý do: ${h.reason}',
                                        style: GoogleFonts.plusJakartaSans(
                                          fontSize: 12,
                                          fontStyle: FontStyle.italic,
                                          color: AppColors.textSecondary,
                                        ),
                                      ),
                                    ],
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
    );
  }

  Widget _buildInfoTile(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 13,
              fontWeight: FontWeight.w500,
              color: AppColors.textSecondary,
            ),
          ),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: GoogleFonts.plusJakartaSans(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
