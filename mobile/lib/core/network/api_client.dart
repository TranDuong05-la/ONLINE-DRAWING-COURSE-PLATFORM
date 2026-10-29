import 'dart:async';
import 'package:dio/dio.dart';
import '../constants/api_constants.dart';
import '../../models/api_response.dart';
import '../storage/token_storage.dart';
import 'api_exceptions.dart';

class ApiClient {
  static ApiClient? _instance;
  late final Dio _dio;
  final TokenStorage _tokenStorage;
  String _baseUrl = ApiConstants.defaultBaseUrl;
  bool _isRefreshing = false;
  Completer<String?>? _refreshCompleter;

  factory ApiClient({TokenStorage? tokenStorage}) {
    return _instance ??= ApiClient._internal(tokenStorage: tokenStorage);
  }

  ApiClient._internal({TokenStorage? tokenStorage})
      : _tokenStorage = tokenStorage ?? TokenStorage() {
    _dio = Dio(
      BaseOptions(
        baseUrl: _baseUrl,
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 15),
        sendTimeout: const Duration(seconds: 15),
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
      ),
    );

    _setupInterceptors();
    _loadCustomBaseUrl();
  }

  Dio get dio => _dio;
  String get baseUrl => _baseUrl;

  Future<void> _loadCustomBaseUrl() async {
    final customUrl = await _tokenStorage.getBaseUrl();
    if (customUrl != null && customUrl.isNotEmpty) {
      setBaseUrl(customUrl);
    }
  }

  void setBaseUrl(String url) {
    _baseUrl = url.endsWith('/') ? url.substring(0, url.length - 1) : url;
    _dio.options.baseUrl = _baseUrl;
    _tokenStorage.saveBaseUrl(_baseUrl);
  }

  void _setupInterceptors() {
    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          // Check if request needs Bearer token
          final isPublicAuth = options.path.contains('/api/auth/login') ||
              options.path.contains('/api/auth/register') ||
              options.path.contains('/api/auth/refresh');

          if (!isPublicAuth) {
            final accessToken = await _tokenStorage.getAccessToken();
            if (accessToken != null && accessToken.isNotEmpty) {
              options.headers['Authorization'] = 'Bearer $accessToken';
            }
          }
          return handler.next(options);
        },
        onError: (DioException error, handler) async {
          final statusCode = error.response?.statusCode;
          final requestPath = error.requestOptions.path;

          // If 401 and not an auth attempt, try to refresh token
          final isAuthAttempt = requestPath.contains('/api/auth/login') ||
              requestPath.contains('/api/auth/register') ||
              requestPath.contains('/api/auth/refresh');

          if (statusCode == 401 && !isAuthAttempt) {
            try {
              final newAccessToken = await _handleTokenRefresh();
              if (newAccessToken != null) {
                // Retry original request with new token
                final options = error.requestOptions;
                options.headers['Authorization'] = 'Bearer $newAccessToken';
                final response = await _dio.fetch(options);
                return handler.resolve(response);
              }
            } catch (_) {
              await _tokenStorage.clearTokens();
            }
          }

          return handler.reject(error);
        },
      ),
    );
  }

  Future<String?> _handleTokenRefresh() async {
    if (_isRefreshing) {
      return _refreshCompleter?.future;
    }

    _isRefreshing = true;
    _refreshCompleter = Completer<String?>();

    try {
      final refreshToken = await _tokenStorage.getRefreshToken();
      if (refreshToken == null || refreshToken.isEmpty) {
        throw UnauthorizedException();
      }

      // Separate clean dio instance without interceptors to avoid loops
      final refreshDio = Dio(
        BaseOptions(
          baseUrl: _baseUrl,
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 10),
        ),
      );

      final response = await refreshDio.post(
        ApiConstants.refresh,
        data: {'refreshToken': refreshToken},
      );

      if (response.statusCode == 200 && response.data != null) {
        final data = response.data['data'];
        final newAccessToken = data['accessToken'] as String?;
        final newRefreshToken = data['refreshToken'] as String?;

        if (newAccessToken != null && newRefreshToken != null) {
          await _tokenStorage.saveTokens(
            accessToken: newAccessToken,
            refreshToken: newRefreshToken,
          );
          _refreshCompleter?.complete(newAccessToken);
          return newAccessToken;
        }
      }

      throw UnauthorizedException();
    } catch (e) {
      _refreshCompleter?.complete(null);
      await _tokenStorage.clearTokens();
      rethrow;
    } finally {
      _isRefreshing = false;
    }
  }

  // GET
  Future<ApiResponse<T>> get<T>(
    String path, {
    Map<String, dynamic>? queryParameters,
    T Function(dynamic json)? fromJsonT,
  }) async {
    try {
      final response = await _dio.get(
        path,
        queryParameters: queryParameters,
      );
      return _handleResponse<T>(response, fromJsonT);
    } on DioException catch (e) {
      throw _handleDioError(e);
    }
  }

  // POST
  Future<ApiResponse<T>> post<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    T Function(dynamic json)? fromJsonT,
  }) async {
    try {
      final response = await _dio.post(
        path,
        data: data,
        queryParameters: queryParameters,
      );
      return _handleResponse<T>(response, fromJsonT);
    } on DioException catch (e) {
      throw _handleDioError(e);
    }
  }

  // PUT
  Future<ApiResponse<T>> put<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    T Function(dynamic json)? fromJsonT,
  }) async {
    try {
      final response = await _dio.put(
        path,
        data: data,
        queryParameters: queryParameters,
      );
      return _handleResponse<T>(response, fromJsonT);
    } on DioException catch (e) {
      throw _handleDioError(e);
    }
  }

  // PATCH
  Future<ApiResponse<T>> patch<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    T Function(dynamic json)? fromJsonT,
  }) async {
    try {
      final response = await _dio.patch(
        path,
        data: data,
        queryParameters: queryParameters,
      );
      return _handleResponse<T>(response, fromJsonT);
    } on DioException catch (e) {
      throw _handleDioError(e);
    }
  }

  // DELETE
  Future<ApiResponse<T>> delete<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    T Function(dynamic json)? fromJsonT,
  }) async {
    try {
      final response = await _dio.delete(
        path,
        data: data,
        queryParameters: queryParameters,
      );
      return _handleResponse<T>(response, fromJsonT);
    } on DioException catch (e) {
      throw _handleDioError(e);
    }
  }

  // POST multipart/form-data (upload ảnh/video/PDF của phần khóa học).
  // Video có thể rất nặng nên cho thời gian gửi dài; onProgress nhận giá trị 0..1.
  Future<ApiResponse<T>> postMultipart<T>(
    String path, {
    required FormData formData,
    void Function(double progress)? onProgress,
    T Function(dynamic json)? fromJsonT,
  }) async {
    try {
      final response = await _dio.post(
        path,
        data: formData,
        options: Options(
          contentType: 'multipart/form-data',
          sendTimeout: const Duration(minutes: 10),
          receiveTimeout: const Duration(minutes: 2),
        ),
        onSendProgress: (sent, total) {
          if (total > 0) onProgress?.call(sent / total);
        },
      );
      return _handleResponse<T>(response, fromJsonT);
    } on DioException catch (e) {
      throw _handleDioError(e);
    }
  }

  ApiResponse<T> _handleResponse<T>(
    Response response,
    T Function(dynamic json)? fromJsonT,
  ) {
    if (response.data is Map<String, dynamic>) {
      return ApiResponse<T>.fromJson(
        response.data as Map<String, dynamic>,
        fromJsonT,
      );
    }
    return ApiResponse<T>(
      success: response.statusCode != null &&
          response.statusCode! >= 200 &&
          response.statusCode! < 300,
      message: response.statusMessage ?? 'Success',
    );
  }

  ApiException _handleDioError(DioException e) {
    if (e.type == DioExceptionType.connectionTimeout ||
        e.type == DioExceptionType.sendTimeout ||
        e.type == DioExceptionType.receiveTimeout ||
        e.type == DioExceptionType.connectionError) {
      return NetworkException();
    }

    final response = e.response;
    if (response != null && response.data is Map<String, dynamic>) {
      final json = response.data as Map<String, dynamic>;
      final message = json['message'] as String? ?? 'Đã xảy ra lỗi.';
      final errorsList = json['errors'] as List?;
      List<ApiFieldError>? fieldErrors;

      if (errorsList != null) {
        fieldErrors = errorsList
            .map((err) => ApiFieldError.fromJson(err as Map<String, dynamic>))
            .toList();
      }

      switch (response.statusCode) {
        case 400:
          return ValidationException(message: message, errors: fieldErrors);
        case 401:
          return UnauthorizedException(message: message);
        case 403:
          return ForbiddenException(message: message);
        case 404:
          return NotFoundException(message: message);
        case 409:
          return ConflictException(message: message);
        default:
          return ApiException(
            message: message,
            statusCode: response.statusCode,
            errors: fieldErrors,
          );
      }
    }

    return ApiException(
      message: e.message ?? 'Đã xảy ra lỗi không xác định',
      statusCode: response?.statusCode,
    );
  }
}
