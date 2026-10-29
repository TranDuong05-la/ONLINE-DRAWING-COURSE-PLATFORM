import 'package:flutter/material.dart';

import '../course/ui/courses_hub_page.dart';

/// Tab "Khóa học" của MainScreen.
/// Trước đây là danh sách khóa học mẫu cố định (dữ liệu giả); nay hiển thị khóa học và combo THẬT
/// lấy từ backend (GET /api/courses, /api/bundles) do phần B (khóa học) cung cấp.
class CoursesTab extends StatelessWidget {
  const CoursesTab({super.key});

  @override
  Widget build(BuildContext context) => const CoursesHubPage();
}
