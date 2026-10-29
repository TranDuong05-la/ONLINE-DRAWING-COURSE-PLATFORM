import 'package:flutter/material.dart';

import 'admin_bundle_list_page.dart';
import 'admin_course_list_page.dart';

/// Khu vực Quản trị: tab Khóa học và tab Combo.
/// TODO(A): chỉ hiện khi đăng nhập bằng tài khoản ADMIN.
class CourseAdminHomePage extends StatelessWidget {
  const CourseAdminHomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Quản trị'),
          bottom: const TabBar(tabs: [Tab(text: 'Khóa học'), Tab(text: 'Combo')]),
        ),
        body: const TabBarView(
          children: [AdminCourseListPage(), AdminBundleListPage()],
        ),
      ),
    );
  }
}
