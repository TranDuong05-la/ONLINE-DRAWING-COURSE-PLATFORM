import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../models/change_password_request.dart';
import '../../providers/auth_provider.dart';
import '../../repositories/user_repository.dart';
import '../../widgets/custom_button.dart';
import '../../widgets/custom_text_field.dart';

class ChangePasswordScreen extends StatefulWidget {
  const ChangePasswordScreen({super.key});

  @override
  State<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends State<ChangePasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _currentPasswordController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final UserRepository _userRepository = UserRepository();
  bool _isLoading = false;
  bool _autoValidate = false;
  String? _errorMessage;

  @override
  void dispose() {
    _currentPasswordController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  Future<void> _handleChangePassword() async {
    setState(() {
      _autoValidate = true;
    });

    if (!_formKey.currentState!.validate()) return;

    if (_newPasswordController.text == _currentPasswordController.text) {
      setState(() {
        _errorMessage = 'Mật khẩu mới phải khác mật khẩu hiện tại';
      });
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      await _userRepository.changePassword(
        ChangePasswordRequest(
          currentPassword: _currentPasswordController.text,
          newPassword: _newPasswordController.text,
          confirmPassword: _confirmPasswordController.text,
        ),
      );

      if (!mounted) return;

      // Backend revokes refresh tokens on password change
      await context.read<AuthProvider>().logout();

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Đổi mật khẩu thành công! Vui lòng đăng nhập lại với mật khẩu mới.'),
          backgroundColor: AppColors.primary,
          duration: Duration(seconds: 4),
        ),
      );
      context.go('/login');
    } catch (e) {
      setState(() {
        _errorMessage = e.toString().replaceFirst('Exception: ', '');
      });
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
        title: const Text('Đổi mật khẩu'),
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        child: Form(
          key: _formKey,
          autovalidateMode: _autoValidate
              ? AutovalidateMode.onUserInteraction
              : AutovalidateMode.disabled,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: AppColors.primaryMintLight,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: AppColors.primaryMint),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.info_outline, color: AppColors.primaryDark, size: 22),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Sau khi đổi mật khẩu thành công, toàn bộ phiên đăng nhập cũ sẽ bị thu hồi và bạn cần đăng nhập lại.',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 12.5,
                          height: 1.4,
                          color: AppColors.primaryDark,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

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
                const SizedBox(height: 18),
              ],

              CustomTextField(
                controller: _currentPasswordController,
                label: 'Mật khẩu hiện tại',
                hint: 'Nhập mật khẩu đang sử dụng',
                prefixIcon: Icons.lock_outline,
                isPassword: true,
                onChanged: (_) => setState(() {}),
                validator: (val) {
                  if (val == null || val.isEmpty) {
                    return 'Vui lòng nhập mật khẩu hiện tại';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              CustomTextField(
                controller: _newPasswordController,
                label: 'Mật khẩu mới',
                hint: 'Tối thiểu 8 ký tự, 1 chữ hoa, 1 chữ thường, 1 số',
                prefixIcon: Icons.lock_reset,
                isPassword: true,
                helperText: 'Từ 8-72 ký tự, bao gồm ít nhất 1 chữ hoa, 1 chữ thường và 1 số',
                onChanged: (_) => setState(() {}),
                validator: (val) {
                  if (val == null || val.isEmpty) {
                    return 'Vui lòng nhập mật khẩu mới';
                  }
                  if (val == _currentPasswordController.text && val.isNotEmpty) {
                    return 'Mật khẩu mới phải khác mật khẩu hiện tại';
                  }
                  if (val.length < 8 || val.length > 72) {
                    return 'Mật khẩu phải từ 8 đến 72 ký tự';
                  }
                  final regex = RegExp(r'^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$');
                  if (!regex.hasMatch(val)) {
                    return 'Mật khẩu phải chứa ít nhất 1 chữ hoa, 1 chữ thường và 1 chữ số';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              CustomTextField(
                controller: _confirmPasswordController,
                label: 'Xác nhận mật khẩu mới',
                hint: 'Nhập lại chính xác mật khẩu mới',
                prefixIcon: Icons.check_circle_outline,
                isPassword: true,
                onChanged: (_) => setState(() {}),
                validator: (val) {
                  if (val == null || val.isEmpty) {
                    return 'Vui lòng xác nhận mật khẩu mới';
                  }
                  if (val != _newPasswordController.text) {
                    return 'Mật khẩu xác nhận không khớp với mật khẩu mới';
                  }
                  return null;
                },
              ),

              if (_confirmPasswordController.text.isNotEmpty && _newPasswordController.text.isNotEmpty) ...[
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(
                      _confirmPasswordController.text == _newPasswordController.text
                          ? Icons.check_circle
                          : Icons.cancel,
                      size: 16,
                      color: _confirmPasswordController.text == _newPasswordController.text
                          ? AppColors.primary
                          : AppColors.error,
                    ),
                    const SizedBox(width: 6),
                    Text(
                      _confirmPasswordController.text == _newPasswordController.text
                          ? 'Mật khẩu xác nhận đã trùng khớp'
                          : 'Mật khẩu xác nhận chưa khớp với ô Mật khẩu mới',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                        color: _confirmPasswordController.text == _newPasswordController.text
                            ? AppColors.primary
                            : AppColors.error,
                      ),
                    ),
                  ],
                ),
              ],

              const SizedBox(height: 28),

              CustomButton(
                text: 'Xác nhận đổi mật khẩu',
                isLoading: _isLoading,
                onPressed: _handleChangePassword,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
