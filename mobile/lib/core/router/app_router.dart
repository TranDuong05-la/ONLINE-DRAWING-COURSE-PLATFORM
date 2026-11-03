import 'package:go_router/go_router.dart';

import '../../features/address/add_edit_address_screen.dart';
import '../../features/address/address_list_screen.dart';
import '../../features/admin/admin_dashboard_screen.dart';
import '../../features/admin/admin_user_detail_screen.dart';
import '../../features/admin/admin_user_list_screen.dart';
import '../../features/supplies/ui/supply_shop_screen.dart';
import '../../features/supplies/ui/product_detail_screen.dart';
import '../../features/supplies/ui/admin/admin_supply_home.dart';
import '../../features/supplies/ui/admin/admin_shipment_detail_screen.dart';
import '../../features/course_admin/ui/course_admin_home_page.dart';
import '../../features/learning/my_assignments_screen.dart';
import '../../features/learning_admin/admin_certificates_screen.dart';
import '../../features/learning_admin/learning_admin_home_page.dart';
import '../../features/auth/login_screen.dart';
import '../../features/auth/register_screen.dart';
import '../../features/cart/cart_screen.dart';
import '../../features/cart/api_course_cart_repository.dart';
import '../../features/cart/cart_item.dart';
import '../../features/cart/cart_repository.dart';
import '../../features/cart/course_checkout_repository.dart';
import '../../features/cart/course_checkout_screen.dart';
import '../../features/order_history/data/api_order_history_repository.dart';
import '../../features/order_history/data/order_history_repository.dart';
import '../../features/order_history/order_detail_screen.dart';
import '../../features/order_history/order_history_screen.dart';
import '../../features/home/main_screen.dart';
import '../../features/instructor/instructor_dashboard_screen.dart';
import '../../features/profile/certificates_screen.dart';
import '../../features/profile/change_password_screen.dart';
import '../../features/profile/edit_profile_screen.dart';
import '../../features/splash/splash_screen.dart';
import '../../features/staff/staff_dashboard_screen.dart';
import '../../models/address_model.dart';
import '../../models/user_model.dart';
import '../../providers/auth_provider.dart';

class AppRouter {
  static GoRouter createRouter(
    AuthProvider authProvider, {
    OrderHistoryRepository? orderHistoryRepository,
    CartRepository? cartRepository,
    CourseCheckoutRepository? courseCheckoutRepository,
  }) {
    final orders = orderHistoryRepository ?? ApiOrderHistoryRepository();
    final cart = cartRepository ?? ApiCourseCartRepository();
    final checkout = courseCheckoutRepository ?? ApiCourseCheckoutRepository();
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
        GoRoute(path: '/', builder: (context, state) => const MainScreen()),
        GoRoute(path: '/supplies', builder: (context, state) => const SupplyShopScreen()),
        GoRoute(
          path: '/supplies/products/:id',
          builder: (context, state) => ProductDetailScreen(
            productId: int.tryParse(state.pathParameters['id'] ?? '') ?? 0,
          ),
        ),
        GoRoute(path: '/admin/supplies', builder: (context, state) => const AdminSupplyHome()),
        GoRoute(path: '/staff/supplies', builder: (context, state) => const AdminSupplyHome()),
        GoRoute(
          path: '/staff/shipments/:id',
          builder: (context, state) => AdminShipmentDetailScreen(
            shipmentId: int.tryParse(state.pathParameters['id'] ?? '') ?? 0,
          ),
        ),
        GoRoute(
          path: '/cart',
          builder: (context, state) => CartScreen(repository: cart),
        ),
        GoRoute(
          path: '/checkout',
          builder: (context, state) => CourseCheckoutScreen(
            cart: state.extra is CartSnapshot
                ? state.extra! as CartSnapshot
                : const CartSnapshot(
                    revision: 0,
                    items: [],
                    subtotalVnd: 0,
                    canCheckout: false,
                    hasUnavailableItems: true,
                  ),
            repository: checkout,
          ),
        ),
        GoRoute(
          path: '/orders',
          builder: (context, state) => OrderHistoryScreen(
            repository: orders,
            onSessionExpired: authProvider.logout,
          ),
        ),
        GoRoute(
          path: '/orders/:orderId',
          builder: (context, state) => OrderDetailScreen(
            orderId: int.tryParse(state.pathParameters['orderId'] ?? ''),
            repository: orders,
            onSessionExpired: authProvider.logout,
          ),
        ),
        GoRoute(
          path: '/profile/edit',
          builder: (context, state) => const EditProfileScreen(),
        ),
        // C: chứng nhận hoàn thành của học viên
        GoRoute(
          path: '/profile/certificates',
          builder: (context, state) => const CertificatesScreen(),
        ),
        // C: bài tập của học viên (cần làm / chờ chấm / đã chấm)
        GoRoute(
          path: '/profile/assignments',
          builder: (context, state) => const MyAssignmentsScreen(),
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
        // Admin Routes
        GoRoute(
          path: '/admin/dashboard',
          builder: (context, state) => const AdminDashboardScreen(),
        ),
        GoRoute(
          path: '/admin',
          redirect: (context, state) => '/admin/dashboard',
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
        // B: quản trị khóa học & combo. Đường dẫn /admin đã được Admin guard bên dưới chặn với tài khoản
        // không phải ADMIN; backend cũng chặn lại bằng JWT (/api/admin/** chỉ cho ADMIN).
        GoRoute(
          path: '/admin/courses',
          builder: (context, state) => const CourseAdminHomePage(),
        ),
        // C: chấm bài và soạn đề bài tập
        GoRoute(
          path: '/admin/learning',
          builder: (context, state) => const LearningAdminHomePage(),
        ),
        // C: chứng nhận đã cấp cho học viên
        GoRoute(
          path: '/admin/certificates',
          builder: (context, state) => const AdminCertificatesScreen(),
        ),
        // Instructor Routes
        GoRoute(
          path: '/instructor/dashboard',
          builder: (context, state) => const InstructorDashboardScreen(),
        ),
        // Staff Routes
        GoRoute(
          path: '/staff/dashboard',
          builder: (context, state) => const StaffDashboardScreen(),
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

        // Admin role guard: /admin/** requires ADMIN
        if (location.startsWith('/admin') && !authProvider.isAdmin) {
          return '/';
        }
        if (location.startsWith('/staff') && !authProvider.isStaff && !authProvider.isAdmin) {
          return '/';
        }

        // Instructor role guard: /instructor/** requires INSTRUCTOR or ADMIN
        if (location.startsWith('/instructor') &&
            !authProvider.isInstructor &&
            !authProvider.isAdmin) {
          return '/';
        }

        // Staff role guard: /staff/** requires STAFF or ADMIN
        if (location.startsWith('/staff') &&
            !authProvider.isStaff &&
            !authProvider.isAdmin) {
          return '/';
        }

        return null;
      },
    );
  }
}
