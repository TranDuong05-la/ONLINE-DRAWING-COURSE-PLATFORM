import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/network/api_client.dart';
import '../../core/network/api_exceptions.dart';
import '../../core/widgets/state_views.dart';
import 'course_purchase_config.dart';

/// ĐIỂM NỐI CHO D (giỏ hàng): các màn khóa học/combo chỉ gọi qua interface này.
///
/// Khi D làm xong giỏ hàng: viết một class implements [CartGateway] (gọi API/giỏ thật)
/// rồi đổi biến [cartGateway] bên dưới sang class đó. Không cần sửa màn hình nào của phần B.
abstract class CartGateway {
  Future<void> addCourse(
    BuildContext context, {
    required int courseId,
    required String title,
  });

  Future<void> addBundle(
    BuildContext context, {
    required int bundleId,
    required String title,
  });
}

/// Bản tạm: chỉ hiện thông báo.
class PlaceholderCartGateway implements CartGateway {
  const PlaceholderCartGateway();

  static const _message = 'Chức năng giỏ hàng do D phụ trách';

  @override
  Future<void> addCourse(
    BuildContext context, {
    required int courseId,
    required String title,
  }) async {
    showMessage(context, _message);
  }

  @override
  Future<void> addBundle(
    BuildContext context, {
    required int bundleId,
    required String title,
  }) async {
    showMessage(context, _message);
  }
}

/// Đổi bản cài đặt thật của D ở đây.
class ApiCourseCartGateway implements CartGateway {
  const ApiCourseCartGateway();

  @override
  Future<void> addCourse(
    BuildContext context, {
    required int courseId,
    required String title,
  }) async {
    if (!coursePurchaseUiEnabled) {
      showMessage(context, 'Course purchasing is disabled in this app build.');
      return;
    }
    final messenger = ScaffoldMessenger.of(context);
    final router = GoRouter.of(context);
    try {
      final response = await ApiClient().post<dynamic>(
        '/api/cart/items',
        data: {'itemType': 'COURSE', 'itemId': courseId, 'quantity': 1},
        fromJsonT: (json) => json,
      );
      if (!response.success) {
        if (messenger.mounted) {
          messenger.showSnackBar(
            SnackBar(
              content: Text(
                response.message.isEmpty
                    ? 'Could not add this Course.'
                    : response.message,
              ),
            ),
          );
        }
        return;
      }
      if (!messenger.mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: const Text('Course added to your cart.'),
          action: SnackBarAction(
            label: 'View Cart',
            onPressed: () => router.push('/cart'),
          ),
        ),
      );
    } on ApiException catch (error) {
      if (messenger.mounted) {
        messenger.showSnackBar(SnackBar(content: Text(error.displayMessage)));
      }
    } catch (_) {
      if (messenger.mounted) {
        messenger.showSnackBar(
          const SnackBar(
            content: Text('Could not add this Course. Please try again.'),
          ),
        );
      }
    }
  }

  @override
  Future<void> addBundle(
    BuildContext context, {
    required int bundleId,
    required String title,
  }) async {
    if (!coursePurchaseUiEnabled) {
      showMessage(context, 'Bản ứng dụng này chưa bật tính năng mua khóa học.');
      return;
    }
    final messenger = ScaffoldMessenger.of(context);
    final router = GoRouter.of(context);
    try {
      // A Combo is one cart line that grants every Course inside it; the server decides the price.
      final response = await ApiClient().post<dynamic>(
        '/api/cart/items',
        data: {'itemType': 'COMBO', 'itemId': bundleId, 'quantity': 1},
        fromJsonT: (json) => json,
      );
      if (!response.success) {
        if (messenger.mounted) {
          messenger.showSnackBar(
            SnackBar(
              content: Text(
                response.message.isEmpty
                    ? 'Không thể thêm combo này vào giỏ hàng.'
                    : response.message,
              ),
            ),
          );
        }
        return;
      }
      if (!messenger.mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: const Text('Đã thêm combo vào giỏ hàng.'),
          action: SnackBarAction(
            label: 'Xem giỏ hàng',
            onPressed: () => router.push('/cart'),
          ),
        ),
      );
    } on ConflictException catch (error) {
      // 409: server nêu rõ lý do (đã sở hữu khóa trong combo, có quà tặng kèm, không còn bán...)
      if (messenger.mounted) {
        messenger.showSnackBar(
          SnackBar(
            content: Text(
              error.message.isEmpty
                  ? 'Không thể mua combo này lúc này.'
                  : error.message,
            ),
          ),
        );
      }
    } on ApiException catch (error) {
      if (messenger.mounted) {
        messenger.showSnackBar(SnackBar(content: Text(error.displayMessage)));
      }
    } catch (_) {
      if (messenger.mounted) {
        messenger.showSnackBar(
          const SnackBar(
            content: Text('Không thể thêm combo. Vui lòng thử lại.'),
          ),
        );
      }
    }
  }
}

CartGateway cartGateway = const ApiCourseCartGateway();
