class ApiResponse<T> {
  final bool success;
  final String message;
  final T? data;
  final List<ApiFieldError>? errors;
  final String? timestamp;

  ApiResponse({
    required this.success,
    required this.message,
    this.data,
    this.errors,
    this.timestamp,
  });

  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(dynamic json)? fromJsonT,
  ) {
    return ApiResponse<T>(
      success: json['success'] as bool? ?? false,
      message: json['message'] as String? ?? '',
      data: json['data'] != null && fromJsonT != null
          ? fromJsonT(json['data'])
          : null,
      errors: json['errors'] != null
          ? (json['errors'] as List)
              .map((e) => ApiFieldError.fromJson(e as Map<String, dynamic>))
              .toList()
          : null,
      timestamp: json['timestamp'] as String?,
    );
  }

  String get firstErrorMessage {
    if (errors != null && errors!.isNotEmpty) {
      return errors!.map((e) => '${e.field}: ${e.message}').join('\n');
    }
    return message;
  }
}

class ApiFieldError {
  final String field;
  final String message;

  ApiFieldError({
    required this.field,
    required this.message,
  });

  factory ApiFieldError.fromJson(Map<String, dynamic> json) {
    return ApiFieldError(
      field: json['field'] as String? ?? '',
      message: json['message'] as String? ?? '',
    );
  }
}
