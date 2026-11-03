import '../../models/api_response.dart';

class ApiException implements Exception {
  final String message;
  final int? statusCode;
  final List<ApiFieldError>? errors;

  ApiException({
    required this.message,
    this.statusCode,
    this.errors,
  });

  @override
  String toString() {
    if (errors != null && errors!.isNotEmpty) {
      final details = errors!.map((e) => '${e.field}: ${e.message}').join('\n');
      return '$message\n$details';
    }
    return message;
  }
}

class UnauthorizedException extends ApiException {
  UnauthorizedException({super.message = 'Phiên đăng nhập đã hết hạn hoặc không hợp lệ. Vui lòng đăng nhập lại.'})
      : super(statusCode: 401);
}

class ForbiddenException extends ApiException {
  ForbiddenException({super.message = 'Bạn không có quyền truy cập chức năng này hoặc tài khoản đã bị khóa.'})
      : super(statusCode: 403);
}

class NotFoundException extends ApiException {
  NotFoundException({super.message = 'Không tìm thấy dữ liệu yêu cầu.'})
      : super(statusCode: 404);
}

class ConflictException extends ApiException {
  ConflictException({super.message = 'Dữ liệu đã tồn tại trong hệ thống (email hoặc thông tin bị trùng).'})
      : super(statusCode: 409);
}

class ValidationException extends ApiException {
  ValidationException({
    super.message = 'Dữ liệu nhập vào không hợp lệ.',
    super.errors,
  }) : super(statusCode: 400);
}

class NetworkException extends ApiException {
  NetworkException({
    super.message = 'Không thể kết nối đến máy chủ. Vui lòng kiểm tra kết nối mạng hoặc địa chỉ máy chủ.',
  });
}

/// Tiện ích hiển thị lỗi cho các màn của phần khóa học.
extension ApiExceptionDisplay on ApiException {
  /// Khi server báo lỗi nhập liệu, message chỉ là "Validation failed";
  /// lấy thông báo cụ thể của ô sai đầu tiên để hiện cho người dùng.
  String get displayMessage {
    final list = errors;
    if (list != null && list.isNotEmpty && list.first.message.isNotEmpty) {
      return list.first.message;
    }
    return message;
  }

  /// Lỗi theo từng ô nhập: tên field -> thông báo.
  Map<String, String> get fieldErrorMap => {
        for (final e in errors ?? const <ApiFieldError>[]) e.field: e.message,
      };
}
