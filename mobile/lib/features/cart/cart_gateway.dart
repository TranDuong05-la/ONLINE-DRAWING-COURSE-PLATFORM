import 'package:flutter/material.dart';

import '../../core/widgets/state_views.dart';

/// ĐIỂM NỐI CHO D (giỏ hàng): các màn khóa học/combo chỉ gọi qua interface này.
///
/// Khi D làm xong giỏ hàng: viết một class implements [CartGateway] (gọi API/giỏ thật)
/// rồi đổi biến [cartGateway] bên dưới sang class đó. Không cần sửa màn hình nào của phần B.
abstract class CartGateway {
  Future<void> addCourse(BuildContext context, {required int courseId, required String title});

  Future<void> addBundle(BuildContext context, {required int bundleId, required String title});
}

/// Bản tạm: chỉ hiện thông báo.
class PlaceholderCartGateway implements CartGateway {
  const PlaceholderCartGateway();

  static const _message = 'Chức năng giỏ hàng do D phụ trách';

  @override
  Future<void> addCourse(BuildContext context, {required int courseId, required String title}) async {
    showMessage(context, _message);
  }

  @override
  Future<void> addBundle(BuildContext context, {required int bundleId, required String title}) async {
    showMessage(context, _message);
  }
}

/// Đổi bản cài đặt thật của D ở đây.
CartGateway cartGateway = const PlaceholderCartGateway();
