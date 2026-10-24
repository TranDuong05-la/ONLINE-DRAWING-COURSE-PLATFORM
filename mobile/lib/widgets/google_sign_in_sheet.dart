import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:provider/provider.dart';
import '../core/constants/app_colors.dart';
import '../core/utils/google_web_wrapper/web_wrapper.dart' as web;
import '../providers/auth_provider.dart';
import 'google_icon.dart';

class GoogleSignInSheet extends StatefulWidget {
  const GoogleSignInSheet({super.key});

  static const String googleClientId =
      '4555221892-omq3ncgr2ib4frohuct3mhsv5rs02m1n.apps.googleusercontent.com';

  static bool _isGoogleInitialized = false;
  static Future<void>? _initFuture;

  /// Ensures GoogleSignIn is initialized once across the app lifecycle.
  /// On Web, serverClientId is not supported and MUST be null.
  /// On Android/iOS, serverClientId requests the ID token with audience matching our backend.
  static Future<void> ensureInitialized() {
    if (_isGoogleInitialized) return Future.value();
    return _initFuture ??= () async {
      try {
        await GoogleSignIn.instance.initialize(
          clientId: googleClientId,
          serverClientId: kIsWeb ? null : googleClientId,
        );
      } catch (e) {
        debugPrint('GoogleSignIn.initialize notice: $e');
      } finally {
        _isGoogleInitialized = true;
      }
    }();
  }

  static Future<void> show(BuildContext context) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) => const GoogleSignInSheet(),
    );
  }

  @override
  State<GoogleSignInSheet> createState() => _GoogleSignInSheetState();
}

class _GoogleSignInSheetState extends State<GoogleSignInSheet> {
  bool _isLoading = false;
  String? _errorMessage;
  StreamSubscription<GoogleSignInAuthenticationEvent>? _authSubscription;

  @override
  void initState() {
    super.initState();
    GoogleSignInSheet.ensureInitialized();
    _subscribeToAuthEvents();
  }

  @override
  void dispose() {
    _authSubscription?.cancel();
    super.dispose();
  }

  void _subscribeToAuthEvents() {
    _authSubscription = GoogleSignIn.instance.authenticationEvents.listen(
      (GoogleSignInAuthenticationEvent event) async {
        if (event is GoogleSignInAuthenticationEventSignIn) {
          await _processGoogleUser(event.user);
        }
      },
      onError: (err) {
        debugPrint('Google authenticationEvents error: $err');
        if (!mounted) return;
        setState(() {
          _isLoading = false;
          _errorMessage = 'Lỗi xác thực Google: $err';
        });
      },
    );
  }

  Future<void> _processGoogleUser(GoogleSignInAccount account) async {
    final String? idToken = account.authentication.idToken;
    final String email = account.email;
    final String? displayName = account.displayName;
    final String? photoUrl = account.photoUrl;

    if (idToken == null || idToken.trim().isEmpty) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage =
            'Không nhận được mã xác thực (ID Token) từ Google. Vui lòng thử lại.';
      });
      return;
    }

    if (!mounted) return;
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final authProvider = context.read<AuthProvider>();
      final success = await authProvider.loginWithGoogle(
        email: email,
        fullName: displayName,
        avatarUrl: photoUrl,
        idToken: idToken,
      );

      if (!mounted) return;
      if (success) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Đăng nhập Google thành công! Chào mừng ${displayName ?? email}',
            ),
            backgroundColor: AppColors.primary,
          ),
        );
        context.go('/');
      } else {
        setState(() {
          _errorMessage =
              authProvider.errorMessage ?? 'Xác thực tài khoản Google thất bại';
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = 'Không thể kết nối đến máy chủ ArtNest: $e';
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _handleMobileGoogleSignIn() async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      await GoogleSignInSheet.ensureInitialized();

      if (kIsWeb) {
        // On Web, Google Identity Services uses the rendered button or lightweight attempt
        final account =
            await GoogleSignIn.instance.attemptLightweightAuthentication();
        if (account != null) {
          await _processGoogleUser(account);
        } else {
          setState(() {
            _isLoading = false;
            _errorMessage =
                'Vui lòng bấm trực tiếp vào nút Google hiển thị ở trên.';
          });
        }
        return;
      }

      // Mobile (Android / iOS): sign out first to allow account selection
      try {
        await GoogleSignIn.instance.signOut();
      } catch (_) {}

      final GoogleSignInAccount account =
          await GoogleSignIn.instance.authenticate(
        scopeHint: const ['email', 'profile', 'openid'],
      );

      await _processGoogleUser(account);
    } catch (e) {
      debugPrint('Google Sign-In error: $e');
      if (!mounted) return;

      final errStr = e.toString();
      if (errStr.contains('canceled') ||
          errStr.contains('cancelled') ||
          errStr.contains('12501')) {
        // User voluntarily dismissed the Google picker
        setState(() {
          _isLoading = false;
        });
        return;
      }

      setState(() {
        _errorMessage =
            'Đăng nhập Google không thành công: ${errStr.replaceFirst("Exception: ", "")}';
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return Padding(
      padding: EdgeInsets.only(
        left: 24,
        right: 24,
        top: 20,
        bottom: bottomInset + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.shade300,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 20),
          Row(
            children: [
              const GoogleIcon(size: 26),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Đăng nhập với Google',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    Text(
                      'Bảo mật danh tính qua chuẩn OpenID Connect',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),

          if (_errorMessage != null) ...[
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.errorLight,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.error.withOpacity(0.3)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.error_outline,
                      color: AppColors.error, size: 20),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _errorMessage!,
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12.5,
                        color: AppColors.error,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
          ],

          // Web vs Mobile Google Sign-In Button
          if (kIsWeb) ...[
            Container(
              alignment: Alignment.center,
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: SizedBox(
                height: 44,
                child: web.renderButton(),
              ),
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFFFEF3C7),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: const Color(0xFFFDE68A)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.warning_amber_rounded,
                          color: Color(0xFF92400E), size: 18),
                      const SizedBox(width: 8),
                      Text(
                        'Cấu hình Google Cloud cho Web',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w700,
                          color: const Color(0xFF92400E),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Origin hiện tại: ${Uri.base.origin}\n'
                    'Nếu gặp lỗi 403 "The given origin is not allowed", bạn cần:\n'
                    '• Thêm "${Uri.base.origin}" vào mục "Authorized JavaScript origins" của Web Client ID trên Google Cloud Console.\n'
                    '• HOẶC chạy app trên terminal với port 3000 cố định:\n'
                    '  flutter run -d chrome --web-port=3000',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11.5,
                      color: const Color(0xFF92400E),
                      height: 1.45,
                    ),
                  ),
                ],
              ),
            ),
          ] else ...[
            ElevatedButton(
              onPressed: _isLoading ? null : _handleMobileGoogleSignIn,
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: AppColors.textPrimary,
                elevation: 0,
                padding:
                    const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                  side: const BorderSide(color: Color(0xFFDADCE0), width: 1.2),
                ),
              ),
              child: _isLoading
                  ? const SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(
                        strokeWidth: 2.2,
                        valueColor:
                            AlwaysStoppedAnimation<Color>(AppColors.primary),
                      ),
                    )
                  : FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const GoogleIcon(size: 22),
                          const SizedBox(width: 12),
                          Text(
                            'Tiếp tục với tài khoản Google',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: const Color(0xFF3C4043),
                            ),
                          ),
                        ],
                      ),
                    ),
            ),
          ],
          const SizedBox(height: 16),

          // Security details
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: AppColors.primaryMintLight,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.primaryMint),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.shield_outlined,
                    color: AppColors.primaryDark, size: 18),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Đăng nhập bảo mật qua máy chủ Google. ArtNest chỉ nhận mã xác thực ID Token để cấp quyền truy cập mà không bao giờ lưu trữ mật khẩu của bạn.',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11.5,
                      height: 1.45,
                      color: AppColors.primaryDark,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
