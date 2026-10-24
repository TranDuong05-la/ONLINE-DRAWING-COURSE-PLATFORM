import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import '../../core/constants/app_colors.dart';
import '../../models/address_model.dart';
import '../../providers/address_provider.dart';
import '../../widgets/empty_state_view.dart';

class AddressListScreen extends StatefulWidget {
  const AddressListScreen({super.key});

  @override
  State<AddressListScreen> createState() => _AddressListScreenState();
}

class _AddressListScreenState extends State<AddressListScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AddressProvider>().loadAddresses();
    });
  }

  Future<void> _handleDelete(AddressModel address) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Xóa địa chỉ'),
        content: Text('Bạn có chắc chắn muốn xóa địa chỉ của "${address.receiverName}" không?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Hủy'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.error),
            child: const Text('Xóa'),
          ),
        ],
      ),
    );

    if (confirm == true && mounted) {
      final success = await context.read<AddressProvider>().deleteAddress(address.id);
      if (success && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Đã xóa địa chỉ thành công'),
            backgroundColor: AppColors.primary,
          ),
        );
      }
    }
  }

  Future<void> _handleSetDefault(int id) async {
    final success = await context.read<AddressProvider>().setDefaultAddress(id);
    if (success && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Đã đặt làm địa chỉ giao hàng mặc định'),
          backgroundColor: AppColors.primary,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final addressProvider = context.watch<AddressProvider>();

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Sổ địa chỉ nhận hàng'),
        elevation: 0,
      ),
      body: addressProvider.isLoading && addressProvider.addresses.isEmpty
          ? const Center(
              child: CircularProgressIndicator(
                valueColor: AlwaysStoppedAnimation<Color>(AppColors.primary),
              ),
            )
          : addressProvider.errorMessage != null && addressProvider.addresses.isEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.error_outline, size: 48, color: AppColors.error),
                        const SizedBox(height: 12),
                        Text(
                          addressProvider.errorMessage!,
                          textAlign: TextAlign.center,
                          style: GoogleFonts.plusJakartaSans(fontSize: 14),
                        ),
                        const SizedBox(height: 16),
                        ElevatedButton(
                          onPressed: () => addressProvider.loadAddresses(),
                          child: const Text('Thử lại'),
                        ),
                      ],
                    ),
                  ),
                )
              : addressProvider.addresses.isEmpty
                  ? EmptyStateView(
                      icon: Icons.location_off_outlined,
                      title: 'Chưa có địa chỉ nào',
                      message: 'Thêm địa chỉ giao hàng để thuận tiện khi mua dụng cụ & họa cụ mỹ thuật.',
                      buttonText: 'Thêm địa chỉ ngay',
                      onButtonPressed: () => context.push('/profile/addresses/add'),
                    )
                  : RefreshIndicator(
                      onRefresh: () => addressProvider.loadAddresses(),
                      color: AppColors.primary,
                      child: ListView.builder(
                        padding: const EdgeInsets.all(16),
                        itemCount: addressProvider.addresses.length,
                        itemBuilder: (context, index) {
                          final item = addressProvider.addresses[index];
                          return Container(
                            margin: const EdgeInsets.only(bottom: 14),
                            padding: const EdgeInsets.all(16),
                            decoration: BoxDecoration(
                              color: Colors.white,
                              borderRadius: BorderRadius.circular(16),
                              border: Border.all(
                                color: item.isDefault
                                    ? AppColors.primary
                                    : AppColors.border,
                                width: item.isDefault ? 1.5 : 1,
                              ),
                              boxShadow: [
                                BoxShadow(
                                  color: item.isDefault
                                      ? AppColors.primary.withOpacity(0.06)
                                      : Colors.black.withOpacity(0.02),
                                  blurRadius: 10,
                                  offset: const Offset(0, 4),
                                ),
                              ],
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(
                                      item.receiverName,
                                      style: GoogleFonts.plusJakartaSans(
                                        fontSize: 15,
                                        fontWeight: FontWeight.w700,
                                        color: AppColors.textPrimary,
                                      ),
                                    ),
                                    const SizedBox(width: 8),
                                    Text(
                                      '•  ${item.phone}',
                                      style: GoogleFonts.plusJakartaSans(
                                        fontSize: 13,
                                        fontWeight: FontWeight.w500,
                                        color: AppColors.textSecondary,
                                      ),
                                    ),
                                    const Spacer(),
                                    if (item.isDefault)
                                      Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                        decoration: BoxDecoration(
                                          color: AppColors.primaryMint,
                                          borderRadius: BorderRadius.circular(6),
                                        ),
                                        child: Text(
                                          'Mặc định',
                                          style: GoogleFonts.plusJakartaSans(
                                            fontSize: 11,
                                            fontWeight: FontWeight.w700,
                                            color: AppColors.primaryDark,
                                          ),
                                        ),
                                      ),
                                  ],
                                ),
                                const SizedBox(height: 10),
                                Text(
                                  item.fullAddress,
                                  style: GoogleFonts.plusJakartaSans(
                                    fontSize: 13.5,
                                    height: 1.4,
                                    color: AppColors.textPrimary,
                                  ),
                                ),
                                const SizedBox(height: 12),
                                const Divider(height: 1),
                                const SizedBox(height: 8),
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.end,
                                  children: [
                                    if (!item.isDefault)
                                      TextButton(
                                        onPressed: () => _handleSetDefault(item.id),
                                        child: Text(
                                          'Thiết lập mặc định',
                                          style: GoogleFonts.plusJakartaSans(
                                            fontSize: 12.5,
                                            fontWeight: FontWeight.w600,
                                            color: AppColors.primary,
                                          ),
                                        ),
                                      ),
                                    TextButton.icon(
                                      icon: const Icon(Icons.edit_outlined, size: 16),
                                      label: const Text('Sửa'),
                                      onPressed: () => context.push('/profile/addresses/edit', extra: item),
                                    ),
                                    TextButton.icon(
                                      icon: const Icon(Icons.delete_outline, size: 16, color: AppColors.error),
                                      label: const Text('Xóa', style: TextStyle(color: AppColors.error)),
                                      onPressed: () => _handleDelete(item),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                    ),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        icon: const Icon(Icons.add_location_alt_outlined),
        label: Text(
          'Thêm địa chỉ mới',
          style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w600),
        ),
        onPressed: () => context.push('/profile/addresses/add'),
      ),
    );
  }
}
