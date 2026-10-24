import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'core/router/app_router.dart';
import 'core/theme/app_theme.dart';
import 'providers/address_provider.dart';
import 'providers/admin_provider.dart';
import 'providers/auth_provider.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ArtNestApp());
}

class ArtNestApp extends StatefulWidget {
  const ArtNestApp({super.key});

  @override
  State<ArtNestApp> createState() => _ArtNestAppState();
}

class _ArtNestAppState extends State<ArtNestApp> {
  late final AuthProvider _authProvider;
  late final AddressProvider _addressProvider;
  late final AdminProvider _adminProvider;
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();
    _authProvider = AuthProvider();
    _addressProvider = AddressProvider();
    _adminProvider = AdminProvider();
    _router = AppRouter.createRouter(_authProvider);
  }

  @override
  void dispose() {
    _authProvider.dispose();
    _addressProvider.dispose();
    _adminProvider.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: _authProvider),
        ChangeNotifierProvider.value(value: _addressProvider),
        ChangeNotifierProvider.value(value: _adminProvider),
      ],
      child: MaterialApp.router(
        title: 'ArtNest - Học vẽ & Dụng cụ mỹ thuật',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.lightTheme,
        routerConfig: _router,
      ),
    );
  }
}
