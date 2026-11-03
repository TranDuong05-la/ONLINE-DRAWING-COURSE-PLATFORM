import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import '../learning/learning_widgets.dart';

/// Admin: chứng nhận đã cấp cho học viên (tên học viên, email, khóa, mã, ngày cấp, trạng thái email).
/// Lọc theo khóa, tìm theo tên học viên hoặc mã; bấm để mở PDF.
class AdminCertificatesScreen extends StatefulWidget {
  const AdminCertificatesScreen({super.key});

  @override
  State<AdminCertificatesScreen> createState() => _AdminCertificatesScreenState();
}

class _AdminCertificatesScreenState extends State<AdminCertificatesScreen> {
  final _repo = LearningRepository();
  final _search = TextEditingController();
  List<CourseOption> _courses = const [];
  int? _courseId;
  int _reloadKey = 0;

  @override
  void initState() {
    super.initState();
    _repo.adminAllCourses().then((c) {
      if (mounted) setState(() => _courses = c);
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _openPdf(AdminCertificate c) async {
    try {
      final link = await _repo.adminCertificateLink(c.id);
      final ok = await launchUrl(Uri.parse(link), mode: LaunchMode.externalApplication);
      if (!ok && mounted) showSnack(context, 'Không mở được file PDF', isError: true);
    } catch (e) {
      if (mounted) showSnack(context, e.toString(), isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(title: const Text('Chứng nhận đã cấp')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => setState(() => _reloadKey++),
              decoration: InputDecoration(
                hintText: 'Tìm theo tên học viên hoặc mã chứng nhận',
                prefixIcon: const Icon(Icons.search),
                isDense: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 4),
            child: DropdownButtonFormField<int?>(
              initialValue: _courseId,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Khóa học', isDense: true),
              items: [
                const DropdownMenuItem(value: null, child: Text('Tất cả khóa')),
                for (final c in _courses)
                  DropdownMenuItem(value: c.id, child: Text(c.title, overflow: TextOverflow.ellipsis)),
              ],
              onChanged: (v) => setState(() {
                _courseId = v;
                _reloadKey++;
              }),
            ),
          ),
          Expanded(
            child: AsyncBody<List<AdminCertificate>>(
              key: ValueKey(_reloadKey),
              load: () => _repo.adminCertificates(courseId: _courseId, query: _search.text),
              builder: (context, items, reload) => RefreshIndicator(
                onRefresh: reload,
                child: items.isEmpty
                    ? ListView(children: [
                        const SizedBox(height: 100),
                        Text('Chưa có chứng nhận nào', textAlign: TextAlign.center,
                            style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                      ])
                    : ListView(
                        padding: const EdgeInsets.all(16),
                        children: [
                          Text('${items.length} chứng nhận',
                              style: GoogleFonts.plusJakartaSans(fontSize: 13, color: AppColors.textSecondary)),
                          const SizedBox(height: 8),
                          for (final c in items) _card(c),
                        ],
                      ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _card(AdminCertificate c) {
    final issued = c.issuedAt == null ? '' : DateFormat('dd/MM/yyyy').format(c.issuedAt!);
    final (label, color) = switch (c.emailStatus) {
      'SENT' => ('Đã gửi email', AppColors.success),
      'FAILED' => ('Gửi email lỗi', AppColors.error),
      _ => ('Đang gửi email', AppColors.info),
    };
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      shape: RoundedRectangleBorder(
          side: const BorderSide(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
      child: ListTile(
        onTap: () => _openPdf(c),
        leading: const CircleAvatar(
          backgroundColor: AppColors.successLight,
          child: Icon(Icons.workspace_premium_rounded, color: AppColors.success),
        ),
        title: Text(c.learnerName, style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w700, fontSize: 15)),
        subtitle: Text(
          '${c.learnerEmail ?? ''}\n${c.courseTitle}\nMã: ${c.code} • Cấp ngày $issued',
          style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary),
        ),
        isThreeLine: true,
        trailing: StatusPill(label: label, color: color),
      ),
    );
  }
}
