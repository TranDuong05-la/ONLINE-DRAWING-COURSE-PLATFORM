import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import '../../core/constants/app_colors.dart';
import '../../models/admin_role_history.dart';
import '../../models/user_model.dart';
import '../../repositories/admin_repository.dart';
import '../../widgets/custom_button.dart';
import '../../widgets/status_badge.dart';

class AdminUserDetailScreen extends StatefulWidget {
  final int userId;
  final UserModel? initialUser;
  final AdminRepository? repository;

  const AdminUserDetailScreen({
    super.key,
    required this.userId,
    this.initialUser,
    this.repository,
  });

  @override
  State<AdminUserDetailScreen> createState() => _AdminUserDetailScreenState();
}

class _AdminUserDetailScreenState extends State<AdminUserDetailScreen> {
  late final AdminRepository _adminRepository =
      widget.repository ?? AdminRepository();
  UserModel? _user;
  List<AdminRoleHistory> _roleHistory = const [];
  bool _isLoading = false;
  bool _isHistoryLoading = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _user = widget.initialUser;
    _fetchUserDetail();
    _loadRoleHistory();
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

  Future<void> _loadRoleHistory() async {
    setState(() => _isHistoryLoading = true);
    try {
      final history = await _adminRepository.getUserRoleHistory(widget.userId);
      if (mounted) setState(() => _roleHistory = history);
    } catch (e) {
      if (mounted) {
        setState(() => _errorMessage = e.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) setState(() => _isHistoryLoading = false);
    }
  }

  Future<void> _changeRole() async {
    if (_user == null) return;
    final selectedRole = ValueNotifier<UserRole>(_user!.role);
    final reasonController = TextEditingController();
    final decision = await showDialog<({UserRole role, String reason})>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Change user role'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ValueListenableBuilder<UserRole>(
              valueListenable: selectedRole,
              builder: (context, role, _) => DropdownButtonFormField<UserRole>(
                value: role,
                decoration: const InputDecoration(labelText: 'Role'),
                items: UserRole.values
                    .map((value) => DropdownMenuItem(
                          value: value,
                          child: Text(value.displayName),
                        ))
                    .toList(),
                onChanged: (value) {
                  if (value != null) selectedRole.value = value;
                },
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: reasonController,
              maxLength: 255,
              decoration: const InputDecoration(
                labelText: 'Reason (optional)',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, (
              role: selectedRole.value,
              reason: reasonController.text.trim(),
            )),
            child: const Text('Save role'),
          ),
        ],
      ),
    );
    selectedRole.dispose();
    reasonController.dispose();
    if (decision == null || !mounted) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      final updated = await _adminRepository.updateUserRole(
        widget.userId,
        role: decision.role.toServerString(),
        reason: decision.reason,
      );
      if (mounted) {
        setState(() => _user = updated);
        await _loadRoleHistory();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('User role updated')),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() => _errorMessage = e.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
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
          'Bạn có chắc chắn muốn $actionText tài khoản của "${_user!.fullName}" không?',
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
              newStatus ? 'Kích hoạt tài khoản thành công' : 'Đã vô hiệu hóa tài khoản',
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
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Chi tiết tài khoản (Admin)'),
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _fetchUserDetail,
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
                          onPressed: _fetchUserDetail,
                          child: const Text('Thử lại'),
                        ),
                      ],
                    ),
                  ),
                )
              : SingleChildScrollView(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
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

                      // Profile Card
                      Center(
                        child: Column(
                          children: [
                            Container(
                              width: 80,
                              height: 80,
                              decoration: BoxDecoration(
                                color: _user!.isAdmin
                                    ? AppColors.accentPeachLight
                                    : AppColors.primaryMint,
                                shape: BoxShape.circle,
                                border: Border.all(
                                  color: _user!.isAdmin ? AppColors.accentPeach : AppColors.primary,
                                  width: 2,
                                ),
                              ),
                              child: Center(
                                child: Text(
                                  _user!.fullName.isNotEmpty
                                      ? _user!.fullName.substring(0, 1).toUpperCase()
                                      : 'U',
                                  style: GoogleFonts.plusJakartaSans(
                                    fontSize: 32,
                                    fontWeight: FontWeight.w800,
                                    color: _user!.isAdmin ? AppColors.accentPeach : AppColors.primaryDark,
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(height: 12),
                            Text(
                              _user!.fullName,
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 20,
                                fontWeight: FontWeight.w700,
                                color: AppColors.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              _user!.email,
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 13.5,
                                color: AppColors.textSecondary,
                              ),
                            ),
                            const SizedBox(height: 10),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                RoleBadge(role: _user!.role),
                                const SizedBox(width: 10),
                                StatusBadge(isActive: _user!.isActive),
                              ],
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 28),

                      Text(
                        'Thông tin chi tiết tài khoản',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: AppColors.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 12),

                      Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: Column(
                          children: [
                            _buildInfoTile('User ID (Hệ thống)', '#${_user!.id}'),
                            const Divider(height: 1),
                            _buildInfoTile('Họ và tên', _user!.fullName),
                            const Divider(height: 1),
                            _buildInfoTile('Email', _user!.email),
                            const Divider(height: 1),
                            _buildInfoTile('Số điện thoại', _user!.phone ?? 'Chưa cập nhật'),
                            const Divider(height: 1),
                            _buildInfoTile('Vai trò (Role)', _user!.role.toServerString()),
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
                      const SizedBox(height: 28),

                      CustomButton(
                        text: 'Change role',
                        type: CustomButtonType.primary,
                        icon: Icons.manage_accounts_outlined,
                        isLoading: _isLoading,
                        onPressed: _changeRole,
                      ),
                      const SizedBox(height: 16),
                      Row(
                        children: [
                          const Expanded(child: Text('Role history')),
                          IconButton(
                            tooltip: 'Refresh role history',
                            onPressed: _loadRoleHistory,
                            icon: const Icon(Icons.history),
                          ),
                        ],
                      ),
                      if (_isHistoryLoading)
                        const Center(child: CircularProgressIndicator())
                      else if (_roleHistory.isEmpty)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 8),
                          child: Text('No role history recorded.'),
                        )
                      else
                        ..._roleHistory.map(_buildRoleHistoryTile),
                      const SizedBox(height: 24),

                      // Status Toggle Action
                      CustomButton(
                        text: _user!.isActive ? 'Khóa tài khoản người dùng' : 'Mở khóa / Kích hoạt tài khoản',
                        type: _user!.isActive ? CustomButtonType.danger : CustomButtonType.primary,
                        icon: _user!.isActive ? Icons.lock_outline : Icons.lock_open_outlined,
                        isLoading: _isLoading,
                        onPressed: _toggleStatus,
                      ),
                      const SizedBox(height: 20),
                    ],
                  ),
                ),
    );
  }

  Widget _buildRoleHistoryTile(AdminRoleHistory entry) {
    final changedAt = entry.changedAt;
    return Card(
      key: ValueKey('role-history-${entry.id}'),
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: const Icon(Icons.history),
        title: Text('${entry.oldRole.toServerString()} → ${entry.newRole.toServerString()}'),
        subtitle: Text([
          'By ${entry.changedByFullName} (${entry.changedByEmail})',
          if (entry.reason != null && entry.reason!.isNotEmpty) entry.reason!,
          if (changedAt != null) DateFormat('HH:mm · dd/MM/yyyy').format(changedAt),
        ].join('\n')),
        isThreeLine: true,
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
