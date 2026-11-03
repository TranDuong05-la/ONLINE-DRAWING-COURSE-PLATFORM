import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../../providers/auth_provider.dart';
import 'admin_categories_tab.dart';
import 'admin_products_tab.dart';
import 'admin_shipments_tab.dart';

/// Màn quản lý phần E.
/// ADMIN: sản phẩm & kho, giao hàng, danh mục. STAFF: kho (nhập hàng) và giao hàng.
class AdminSupplyHome extends StatelessWidget {
  /// 0 = sản phẩm & kho, 1 = giao hàng
  final int initialTab;
  const AdminSupplyHome({super.key, this.initialTab = 0});

  @override
  Widget build(BuildContext context) {
    final isAdmin = context.watch<AuthProvider>().isAdmin;
    final tabs = [
      Tab(icon: const Icon(Icons.inventory_2_outlined), text: isAdmin ? 'Sản phẩm & kho' : 'Kho học cụ'),
      const Tab(icon: Icon(Icons.local_shipping_outlined), text: 'Giao hàng'),
      if (isAdmin) const Tab(icon: Icon(Icons.category_outlined), text: 'Danh mục'),
    ];
    return DefaultTabController(
      length: tabs.length,
      initialIndex: initialTab.clamp(0, tabs.length - 1),
      child: Scaffold(
        appBar: AppBar(
          title: Text(isAdmin ? 'Quản lý học cụ' : 'Kho & giao hàng'),
          bottom: TabBar(tabs: tabs),
        ),
        body: TabBarView(
          children: [
            AdminProductsTab(canEdit: isAdmin),
            const AdminShipmentsTab(),
            if (isAdmin) const AdminCategoriesTab(),
          ],
        ),
      ),
    );
  }
}
