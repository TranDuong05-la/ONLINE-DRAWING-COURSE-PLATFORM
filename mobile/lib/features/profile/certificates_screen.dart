import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../core/constants/app_colors.dart';
import '../../models/certificate_model.dart';
import '../../repositories/certificate_repository.dart';
import '../../widgets/empty_state_view.dart';

/// "Chứng chỉ của tôi": certificates earned by finishing courses (100% videos + approved assignments).
class CertificatesScreen extends StatefulWidget {
  const CertificatesScreen({super.key});

  @override
  State<CertificatesScreen> createState() => _CertificatesScreenState();
}

class _CertificatesScreenState extends State<CertificatesScreen> {
  final _repository = CertificateRepository();
  List<CertificateModel> _certificates = [];
  bool _isLoading = true;
  String? _errorMessage;
  final Set<int> _busyIds = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      final list = await _repository.getMyCertificates();
      if (!mounted) return;
      setState(() => _certificates = list);
    } catch (e) {
      if (!mounted) return;
      setState(() => _errorMessage = e.toString());
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _openPdf(CertificateModel cert) async {
    setState(() => _busyIds.add(cert.id));
    try {
      final link = await _repository.getDownloadLink(cert.id);
      final opened = await launchUrl(Uri.parse(link), mode: LaunchMode.externalApplication);
      if (!opened) _showMessage('Không mở được file PDF trên thiết bị này', isError: true);
    } catch (e) {
      _showMessage(e.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _busyIds.remove(cert.id));
    }
  }

  Future<void> _resendEmail(CertificateModel cert) async {
    setState(() => _busyIds.add(cert.id));
    try {
      final updated = await _repository.resendEmail(cert.id);
      if (!mounted) return;
      setState(() {
        final i = _certificates.indexWhere((c) => c.id == cert.id);
        if (i >= 0) _certificates[i] = updated;
      });
      _showMessage('Đã yêu cầu gửi lại chứng chỉ qua email');
    } catch (e) {
      _showMessage(e.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _busyIds.remove(cert.id));
    }
  }

  void _showMessage(String message, {bool isError = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: isError ? AppColors.error : AppColors.primary,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Chứng chỉ của tôi'),
        elevation: 0,
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_isLoading && _certificates.isEmpty) {
      return const Center(
        child: CircularProgressIndicator(
          valueColor: AlwaysStoppedAnimation<Color>(AppColors.primary),
        ),
      );
    }
    if (_errorMessage != null && _certificates.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.error_outline, size: 48, color: AppColors.error),
              const SizedBox(height: 12),
              Text(
                _errorMessage!,
                textAlign: TextAlign.center,
                style: GoogleFonts.plusJakartaSans(fontSize: 14),
              ),
              const SizedBox(height: 16),
              ElevatedButton(onPressed: _load, child: const Text('Thử lại')),
            ],
          ),
        ),
      );
    }
    if (_certificates.isEmpty) {
      return const EmptyStateView(
        icon: Icons.workspace_premium_outlined,
        title: 'Chưa có chứng chỉ nào',
        message: 'Hoàn thành 100% video và được chấm đạt mọi bài tập của một khóa học để nhận chứng chỉ.',
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      color: AppColors.primary,
      child: ListView.builder(
        padding: const EdgeInsets.all(16),
        itemCount: _certificates.length,
        itemBuilder: (context, index) => _buildCard(_certificates[index]),
      ),
    );
  }

  Widget _buildCard(CertificateModel cert) {
    final busy = _busyIds.contains(cert.id);
    final issued = cert.issuedAt != null ? DateFormat('dd/MM/yyyy').format(cert.issuedAt!) : '—';

    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.04),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: AppColors.accentYellowLight,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(Icons.workspace_premium_rounded, color: AppColors.accentYellow, size: 28),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      cert.courseTitle,
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: AppColors.primaryDark,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Mã: ${cert.code}',
                      style: GoogleFonts.plusJakartaSans(fontSize: 12, color: Colors.grey[700]),
                    ),
                    Text(
                      'Ngày cấp: $issued',
                      style: GoogleFonts.plusJakartaSans(fontSize: 12, color: Colors.grey[700]),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _buildEmailStatus(cert),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: busy ? null : () => _openPdf(cert),
                  icon: const Icon(Icons.picture_as_pdf_outlined, size: 18),
                  label: const Text('Xem PDF'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.primary,
                    foregroundColor: Colors.white,
                  ),
                ),
              ),
              if (cert.canResendEmail) ...[
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: busy ? null : () => _resendEmail(cert),
                    icon: const Icon(Icons.forward_to_inbox_outlined, size: 18),
                    label: const Text('Gửi lại email'),
                    style: OutlinedButton.styleFrom(foregroundColor: AppColors.primary),
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildEmailStatus(CertificateModel cert) {
    final (label, color, icon) = switch (cert.emailStatus) {
      'SENT' => ('Đã gửi qua email', AppColors.primary, Icons.mark_email_read_outlined),
      'FAILED' => ('Gửi email thất bại', AppColors.error, Icons.error_outline),
      _ => ('Đang chờ gửi email', AppColors.accentPeach, Icons.schedule_outlined),
    };
    return Row(
      children: [
        Icon(icon, size: 16, color: color),
        const SizedBox(width: 6),
        Text(label, style: GoogleFonts.plusJakartaSans(fontSize: 12, color: color, fontWeight: FontWeight.w600)),
      ],
    );
  }
}
