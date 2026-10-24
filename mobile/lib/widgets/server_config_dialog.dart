import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../../core/constants/api_constants.dart';
import '../../core/constants/app_colors.dart';
import '../../core/network/api_client.dart';

class ServerConfigDialog extends StatefulWidget {
  final ApiClient apiClient;

  const ServerConfigDialog({super.key, required this.apiClient});

  static Future<void> show(BuildContext context, ApiClient apiClient) {
    return showDialog(
      context: context,
      builder: (context) => ServerConfigDialog(apiClient: apiClient),
    );
  }

  @override
  State<ServerConfigDialog> createState() => _ServerConfigDialogState();
}

class _ServerConfigDialogState extends State<ServerConfigDialog> {
  late final TextEditingController _urlController;

  @override
  void initState() {
    super.initState();
    _urlController = TextEditingController(text: widget.apiClient.baseUrl);
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  void _applyUrl(String url) {
    widget.apiClient.setBaseUrl(url);
    Navigator.of(context).pop();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Đã cập nhật Base URL: $url'),
        backgroundColor: AppColors.primary,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      title: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: AppColors.primaryMint,
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(Icons.dns_outlined, color: AppColors.primary, size: 22),
          ),
          const SizedBox(width: 12),
          Text(
            'Cấu hình Server API',
            style: GoogleFonts.plusJakartaSans(
              fontSize: 17,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Thiết lập địa chỉ máy chủ backend Spring Boot (cổng 8080):',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 13,
                color: AppColors.textSecondary,
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: _urlController,
              decoration: InputDecoration(
                labelText: 'Base URL',
                hintText: 'http://10.0.2.2:8080',
                prefixIcon: const Icon(Icons.link, size: 20),
                contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              ),
            ),
            const SizedBox(height: 16),
            Text(
              'Gợi ý nhanh:',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
              ),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ActionChip(
                  label: const Text('Android Emulator (10.0.2.2)'),
                  onPressed: () => _urlController.text = 'http://10.0.2.2:8080',
                ),
                ActionChip(
                  label: const Text('Localhost (127.0.0.1)'),
                  onPressed: () => _urlController.text = 'http://127.0.0.1:8080',
                ),
                ActionChip(
                  label: const Text('Mặc định hệ thống'),
                  onPressed: () => _urlController.text = ApiConstants.defaultBaseUrl,
                ),
              ],
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        ElevatedButton(
          onPressed: () {
            final url = _urlController.text.trim();
            if (url.isNotEmpty) {
              _applyUrl(url);
            }
          },
          child: const Text('Lưu thay đổi'),
        ),
      ],
    );
  }
}
