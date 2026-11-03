import 'package:flutter/foundation.dart';

/// Notifies the retained My Courses tab to reload after server-confirmed payment.
class CourseOwnershipRefreshSignal {
  CourseOwnershipRefreshSignal._();

  static final ValueNotifier<int> revision = ValueNotifier<int>(0);

  static void notify() => revision.value++;
}
