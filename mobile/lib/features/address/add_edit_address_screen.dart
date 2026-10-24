import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../models/address_model.dart';
import '../../providers/address_provider.dart';
import '../../widgets/custom_button.dart';
import '../../widgets/custom_text_field.dart';

class AddEditAddressScreen extends StatefulWidget {
  final AddressModel? addressToEdit;

  const AddEditAddressScreen({super.key, this.addressToEdit});

  @override
  State<AddEditAddressScreen> createState() => _AddEditAddressScreenState();
}

class _AddEditAddressScreenState extends State<AddEditAddressScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _receiverNameController;
  late final TextEditingController _phoneController;
  late final TextEditingController _provinceController;
  late final TextEditingController _districtController;
  late final TextEditingController _wardController;
  late final TextEditingController _streetController;
  late bool _isDefault;

  bool get _isEditing => widget.addressToEdit != null;

  @override
  void initState() {
    super.initState();
    final addr = widget.addressToEdit;
    _receiverNameController = TextEditingController(text: addr?.receiverName ?? '');
    _phoneController = TextEditingController(text: addr?.phone ?? '');
    _provinceController = TextEditingController(text: addr?.province ?? '');
    _districtController = TextEditingController(text: addr?.district ?? '');
    _wardController = TextEditingController(text: addr?.ward ?? '');
    _streetController = TextEditingController(text: addr?.streetAddress ?? '');
    _isDefault = addr?.isDefault ?? false;
  }

  @override
  void dispose() {
    _receiverNameController.dispose();
    _phoneController.dispose();
    _provinceController.dispose();
    _districtController.dispose();
    _wardController.dispose();
    _streetController.dispose();
    super.dispose();
  }

  Future<void> _handleSave() async {
    if (!_formKey.currentState!.validate()) return;

    final request = AddressRequest(
      receiverName: _receiverNameController.text.trim(),
      phone: _phoneController.text.trim(),
      province: _provinceController.text.trim(),
      district: _districtController.text.trim(),
      ward: _wardController.text.trim(),
      streetAddress: _streetController.text.trim(),
      isDefault: _isDefault,
    );

    final provider = context.read<AddressProvider>();
    bool success;

    if (_isEditing) {
      success = await provider.updateAddress(widget.addressToEdit!.id, request);
    } else {
      success = await provider.createAddress(request);
    }

    if (!mounted) return;
    if (success) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _isEditing
                ? 'Đã cập nhật địa chỉ thành công'
                : 'Đã thêm địa chỉ mới thành công',
          ),
          backgroundColor: AppColors.primary,
        ),
      );
      context.pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final addressProvider = context.watch<AddressProvider>();

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: Text(_isEditing ? 'Sửa địa chỉ giao hàng' : 'Thêm địa chỉ mới'),
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (addressProvider.errorMessage != null) ...[
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
                          addressProvider.errorMessage!,
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

              CustomTextField(
                controller: _receiverNameController,
                label: 'Tên người nhận',
                hint: 'vd: Nguyễn Văn A',
                prefixIcon: Icons.person_outline,
                validator: (val) {
                  if (val == null || val.trim().isEmpty) {
                    return 'Vui lòng nhập tên người nhận';
                  }
                  if (val.trim().length > 100) {
                    return 'Tên người nhận không được quá 100 ký tự';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 14),

              CustomTextField(
                controller: _phoneController,
                label: 'Số điện thoại nhận hàng',
                hint: 'vd: 0912345678',
                prefixIcon: Icons.phone_outlined,
                keyboardType: TextInputType.phone,
                validator: (val) {
                  if (val == null || val.trim().isEmpty) {
                    return 'Vui lòng nhập số điện thoại';
                  }
                  if (val.trim().length > 20) {
                    return 'Số điện thoại không được quá 20 ký tự';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 14),

              Row(
                children: [
                  Expanded(
                    child: CustomTextField(
                      controller: _provinceController,
                      label: 'Tỉnh / Thành phố',
                      hint: 'vd: Hà Nội',
                      validator: (val) {
                        if (val == null || val.trim().isEmpty) {
                          return 'Nhập Tỉnh/Thành';
                        }
                        return null;
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: CustomTextField(
                      controller: _districtController,
                      label: 'Quận / Huyện',
                      hint: 'vd: Cầu Giấy',
                      validator: (val) {
                        if (val == null || val.trim().isEmpty) {
                          return 'Nhập Quận/Huyện';
                        }
                        return null;
                      },
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),

              CustomTextField(
                controller: _wardController,
                label: 'Phường / Xã',
                hint: 'vd: Dịch Vọng Hậu',
                validator: (val) {
                  if (val == null || val.trim().isEmpty) {
                    return 'Vui lòng nhập Phường/Xã';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 14),

              CustomTextField(
                controller: _streetController,
                label: 'Địa chỉ cụ thể (Số nhà, tên đường, tòa nhà)',
                hint: 'vd: Số 12, ngõ 34, đường Duy Tân',
                prefixIcon: Icons.home_outlined,
                maxLines: 2,
                validator: (val) {
                  if (val == null || val.trim().isEmpty) {
                    return 'Vui lòng nhập địa chỉ cụ thể';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 14),

              SwitchListTile.adaptive(
                contentPadding: EdgeInsets.zero,
                activeColor: AppColors.primary,
                title: Text(
                  'Đặt làm địa chỉ mặc định',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textPrimary,
                  ),
                ),
                subtitle: Text(
                  'Địa chỉ này sẽ được ưu tiên khi thanh toán mua dụng cụ.',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 12,
                    color: AppColors.textSecondary,
                  ),
                ),
                value: _isDefault,
                onChanged: (val) {
                  setState(() {
                    _isDefault = val;
                  });
                },
              ),
              const SizedBox(height: 28),

              CustomButton(
                text: _isEditing ? 'Cập nhật địa chỉ' : 'Lưu địa chỉ',
                isLoading: addressProvider.isLoading,
                onPressed: _handleSave,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
