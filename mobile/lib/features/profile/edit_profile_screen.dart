import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../core/network/api_exceptions.dart';
import '../../models/update_profile_request.dart';
import '../../models/user_model.dart';
import '../../providers/auth_provider.dart';
import '../../repositories/user_repository.dart';
import '../../widgets/custom_button.dart';
import '../../widgets/custom_text_field.dart';

class EditProfileScreen extends StatefulWidget {
  const EditProfileScreen({super.key});

  @override
  State<EditProfileScreen> createState() => _EditProfileScreenState();
}

class _EditProfileScreenState extends State<EditProfileScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _phoneController;
  final UserRepository _userRepository = UserRepository();
  final ImagePicker _imagePicker = ImagePicker();

  XFile? _selectedImage;
  Uint8List? _selectedImageBytes;
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    final user = context.read<AuthProvider>().currentUser;
    _nameController = TextEditingController(text: user?.fullName ?? '');
    _phoneController = TextEditingController(text: user?.phone ?? '');
  }

  @override
  void dispose() {
    _nameController.dispose();
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _pickImage(ImageSource source) async {
    try {
      final XFile? picked = await _imagePicker.pickImage(
        source: source,
        maxWidth: 1024,
        maxHeight: 1024,
        imageQuality: 85,
      );
      if (picked != null) {
        final bytes = await picked.readAsBytes();
        setState(() {
          _selectedImage = picked;
          _selectedImageBytes = bytes;
          _errorMessage = null;
        });
      }
    } catch (e) {
      setState(() {
        _errorMessage = 'Không thể chọn ảnh: ${e.toString()}';
      });
    }
  }

  void _showImageSourceBottomSheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(
                  color: Colors.grey[300],
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              Text(
                'Chọn ảnh đại diện',
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                  color: AppColors.textPrimary,
                ),
              ),
              const SizedBox(height: 12),
              ListTile(
                leading: const Icon(Icons.camera_alt_outlined, color: AppColors.primary),
                title: Text(
                  'Chụp ảnh mới',
                  style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w500),
                ),
                onTap: () {
                  Navigator.pop(ctx);
                  _pickImage(ImageSource.camera);
                },
              ),
              ListTile(
                leading: const Icon(Icons.photo_library_outlined, color: AppColors.primary),
                title: Text(
                  'Chọn ảnh từ thư viện',
                  style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w500),
                ),
                onTap: () {
                  Navigator.pop(ctx);
                  _pickImage(ImageSource.gallery);
                },
              ),
              if (_selectedImage != null)
                ListTile(
                  leading: const Icon(Icons.delete_outline, color: AppColors.error),
                  title: Text(
                    'Hủy ảnh vừa chọn',
                    style: GoogleFonts.plusJakartaSans(
                      color: AppColors.error,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  onTap: () {
                    Navigator.pop(ctx);
                    setState(() {
                      _selectedImage = null;
                      _selectedImageBytes = null;
                    });
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAvatarPreview(UserModel? user) {
    if (_selectedImageBytes != null) {
      return Image.memory(
        _selectedImageBytes!,
        width: 104,
        height: 104,
        fit: BoxFit.cover,
      );
    }

    final currentUrl = user?.avatarUrl ?? '';
    if (currentUrl.isNotEmpty) {
      return Image.network(
        currentUrl,
        width: 104,
        height: 104,
        fit: BoxFit.cover,
        errorBuilder: (context, error, stackTrace) => _buildAvatarFallback(user),
      );
    }

    return _buildAvatarFallback(user);
  }

  Widget _buildAvatarFallback(UserModel? user) {
    final initial = (user != null && user.fullName.isNotEmpty)
        ? user.fullName.substring(0, 1).toUpperCase()
        : 'A';

    return Center(
      child: Text(
        initial,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 36,
          fontWeight: FontWeight.w800,
          color: AppColors.primaryDark,
        ),
      ),
    );
  }

  Future<void> _handleUpdate() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      UserModel? updatedUser;
      final currentUser = context.read<AuthProvider>().currentUser;

      // 1. Tải ảnh thật lên Cloudinary nếu người dùng đã chọn tệp ảnh
      if (_selectedImage != null) {
        updatedUser = await _userRepository.uploadAvatar(_selectedImage!);
      }

      // 2. Chỉ gọi updateProfile nếu có thay đổi về họ tên hoặc SĐT
      final newName = _nameController.text.trim();
      final newPhone = _phoneController.text.trim();

      final isNameChanged = newName != (currentUser?.fullName ?? '');
      final isPhoneChanged = (newPhone.isNotEmpty ? newPhone : null) != currentUser?.phone;

      if (_selectedImage == null && !isNameChanged && !isPhoneChanged) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Không có thông tin nào thay đổi.'),
            backgroundColor: AppColors.primary,
          ),
        );
        context.pop();
        return;
      }

      if (isNameChanged || isPhoneChanged) {
        final profileRequest = UpdateProfileRequest(
          fullName: newName,
          phone: newPhone.isNotEmpty ? newPhone : null,
          avatarUrl: updatedUser?.avatarUrl ?? currentUser?.avatarUrl,
        );
        updatedUser = await _userRepository.updateProfile(profileRequest);
      }

      if (!mounted) return;
      if (updatedUser != null) {
        context.read<AuthProvider>().updateCurrentUser(updatedUser);
      }

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Cập nhật hồ sơ thành công!'),
          backgroundColor: AppColors.primary,
        ),
      );
      context.pop();
    } catch (e) {
      final msg = e.toString().replaceFirst('Exception: ', '');
      if (e is UnauthorizedException || msg.contains('Authentication required') || msg.contains('Session expired')) {
        setState(() {
          _errorMessage = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
        });
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'),
              backgroundColor: AppColors.error,
            ),
          );
          Future.delayed(const Duration(seconds: 1), () {
            if (mounted) {
              context.read<AuthProvider>().logout();
              context.go('/login');
            }
          });
        }
      } else {
        setState(() {
          _errorMessage = msg;
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
    final user = context.watch<AuthProvider>().currentUser;

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Chỉnh sửa hồ sơ'),
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
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
                const SizedBox(height: 18),
              ],

              // Avatar preview & upload section
              Center(
                child: Stack(
                  children: [
                    Container(
                      width: 108,
                      height: 108,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: AppColors.primaryMint.withOpacity(0.35),
                        border: Border.all(color: AppColors.primary, width: 3),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withOpacity(0.08),
                            blurRadius: 10,
                            offset: const Offset(0, 4),
                          ),
                        ],
                      ),
                      child: ClipOval(
                        child: _buildAvatarPreview(user),
                      ),
                    ),
                    Positioned(
                      bottom: 0,
                      right: 0,
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: _showImageSourceBottomSheet,
                          borderRadius: BorderRadius.circular(20),
                          child: Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color: AppColors.primary,
                              shape: BoxShape.circle,
                              border: Border.all(color: Colors.white, width: 2),
                              boxShadow: [
                                BoxShadow(
                                  color: Colors.black.withOpacity(0.2),
                                  blurRadius: 4,
                                  offset: const Offset(0, 2),
                                ),
                              ],
                            ),
                            child: const Icon(
                              Icons.camera_alt,
                              color: Colors.white,
                              size: 18,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Center(
                child: TextButton.icon(
                  onPressed: _showImageSourceBottomSheet,
                  icon: const Icon(Icons.add_photo_alternate_outlined, size: 18, color: AppColors.primary),
                  label: Text(
                    _selectedImage != null ? 'Đã chọn ảnh (Bấm để đổi)' : 'Tải ảnh đại diện từ thiết bị',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: AppColors.primary,
                    ),
                  ),
                ),
              ),
              if (_selectedImage != null)
                Center(
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      'Tệp: ${_selectedImage!.name}',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12,
                        color: Colors.grey[600],
                        fontStyle: FontStyle.italic,
                      ),
                    ),
                  ),
                ),
              const SizedBox(height: 16),

              // Email readonly info
              CustomTextField(
                controller: TextEditingController(text: user?.email ?? ''),
                label: 'Địa chỉ Email (Không thể thay đổi)',
                prefixIcon: Icons.email_outlined,
                readOnly: true,
                helperText: 'Email gắn liền với định danh tài khoản học viên.',
              ),
              const SizedBox(height: 16),

              CustomTextField(
                controller: _nameController,
                label: 'Họ và tên',
                hint: 'Nhập họ và tên của bạn',
                prefixIcon: Icons.person_outline,
                validator: (val) {
                  if (val == null || val.trim().isEmpty) {
                    return 'Vui lòng nhập họ và tên';
                  }
                  if (val.trim().length > 100) {
                    return 'Họ tên không được vượt quá 100 ký tự';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              CustomTextField(
                controller: _phoneController,
                label: 'Số điện thoại',
                hint: 'Nhập số điện thoại',
                prefixIcon: Icons.phone_outlined,
                keyboardType: TextInputType.phone,
                validator: (val) {
                  if (val != null && val.trim().isNotEmpty && val.trim().length > 20) {
                    return 'Số điện thoại không được vượt quá 20 ký tự';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 32),

              CustomButton(
                text: 'Lưu thay đổi',
                isLoading: _isLoading,
                onPressed: _handleUpdate,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
