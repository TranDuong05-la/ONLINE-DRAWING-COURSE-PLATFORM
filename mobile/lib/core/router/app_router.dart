import 'package:go_router/go_router.dart';
import '../../features/address/add_edit_address_screen.dart';
import '../../features/address/address_list_screen.dart';
import '../../features/admin/admin_user_detail_screen.dart';
import '../../features/admin/admin_user_list_screen.dart';
import '../../features/auth/login_screen.dart';
import '../../features/course_admin/ui/course_admin_home_page.dart';
import '../../features/auth/register_screen.dart';
import '../../features/home/main_screen.dart';
import '../../features/profile/change_password_screen.dart';
import '../../features/profile/edit_profile_screen.dart';
import '../../features/splash/splash_screen.dart';
import '../../models/address_model.dart';
import '../../models/user_model.dart';
import '../../providers/auth_provider.dart';

class AppRouter {
  static GoRouter createRouter(AuthProvider authProvider) {
    return GoRouter(
      initialLocation: '/splash',
      refreshListenable: authProvider,
      routes: [
        GoRoute(
          path: '/splash',
          builder: (context, state) => const SplashScreen(),
        ),
        GoRoute(
          path: '/login',
          builder: (context, state) => const LoginScreen(),
        ),
        GoRoute(
          path: '/register',
          builder: (context, state) => const RegisterScreen(),
        ),
        GoRoute(
          path: '/',
          builder: (context, state) => const MainScreen(),
        ),
        GoRoute(
          path: '/profile/edit',
          builder: (context, state) => const EditProfileScreen(),
        ),
        GoRoute(
          path: '/profile/change-password',
          builder: (context, state) => const ChangePasswordScreen(),
        ),
        GoRoute(
          path: '/profile/addresses',
          builder: (context, state) => const AddressListScreen(),
        ),
        GoRoute(
          path: '/profile/addresses/add',
          builder: (context, state) => const AddEditAddressScreen(),
        ),
        GoRoute(
          path: '/profile/addresses/edit',
          builder: (context, state) {
            final address = state.extra as AddressModel?;
            return AddEditAddressScreen(addressToEdit: address);
          },
        ),
        // Quản trị khóa học & combo (phần B). Đường dẫn bắt đầu bằng /admin nên đã được Admin guard bên dưới chặn
        // với tài khoản không phải ADMIN; backend cũng chặn lại bằng JWT (/api/admin/** chỉ cho ADMIN).
        GoRoute(
          path: '/admin/courses',
          builder: (context, state) => const CourseAdminHomePage(),
        ),
        GoRoute(
          path: '/admin/users',
          builder: (context, state) => const AdminUserListScreen(),
        ),
        GoRoute(
          path: '/admin/users/:id',
          builder: (context, state) {
            final id = int.tryParse(state.pathParameters['id'] ?? '0') ?? 0;
            final user = state.extra as UserModel?;
            return AdminUserDetailScreen(userId: id, initialUser: user);
          },
        ),
      ],
      redirect: (context, state) {
        final location = state.uri.toString();
        final isSplash = location == '/splash';
        final isAuthRoute = location == '/login' || location == '/register';

        if (isSplash) return null;

        // If not authenticated and trying to access protected screen
        if (!authProvider.isAuthenticated && !isAuthRoute) {
          return '/login';
        }

        // If authenticated and trying to access login/register
        if (authProvider.isAuthenticated && isAuthRoute) {
          return '/';
        }

        // Admin guard: if trying to access /admin and not admin, redirect to home
        if (location.startsWith('/admin') && !authProvider.isAdmin) {
          return '/';
        }

        return null;
      },
    );
  }
}
