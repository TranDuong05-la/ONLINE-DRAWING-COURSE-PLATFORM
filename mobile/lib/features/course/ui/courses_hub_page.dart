import 'package:flutter/material.dart';

import '../../bundle/ui/bundle_list_page.dart';
import 'course_list_page.dart';

/// Nội dung tab "Khóa học" cho học viên: hai tab con là từng khóa lẻ và combo.
/// Được nhúng vào MainScreen qua CoursesTab (features/home/courses_tab.dart).
class CoursesHubPage extends StatelessWidget {
  const CoursesHubPage({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Khóa học'),
          bottom: const TabBar(tabs: [Tab(text: 'Từng khóa'), Tab(text: 'Combo')]),
        ),
        body: const TabBarView(children: [CourseListPage(), BundleListPage()]),
      ),
    );
  }
}
