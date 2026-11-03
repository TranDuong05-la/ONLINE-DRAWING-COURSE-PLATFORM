import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class AdminDashboardScreen extends StatelessWidget {
  const AdminDashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Admin dashboard')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Card(
            child: Semantics(
              enabled: false,
              label: 'Dashboard metrics unavailable',
              child: const ListTile(
                enabled: false,
                leading: Icon(Icons.bar_chart_outlined),
                title: Text('Dashboard metrics unavailable'),
                subtitle: Text(
                  'The backend does not currently provide an admin dashboard '
                  'aggregate endpoint. Metrics are disabled until real data is available.',
                ),
              ),
            ),
          ),
          const SizedBox(height: 12),
          _AdminDestination(
            title: 'User management',
            subtitle: 'Open the live paginated user list and status controls.',
            icon: Icons.people_outline,
            onTap: () => context.push('/admin/users'),
          ),
          _AdminDestination(
            title: 'Course and Combo management',
            subtitle: 'Open the Course catalog administration area.',
            icon: Icons.menu_book_outlined,
            onTap: () => context.push('/admin/courses'),
          ),
          _AdminDestination(
            title: 'Learning administration',
            subtitle: 'Open submission review and learning tools.',
            icon: Icons.fact_check_outlined,
            onTap: () => context.push('/admin/learning'),
          ),
          _AdminDestination(
            title: 'Product, stock and shipping administration',
            subtitle: 'Manage supplies and shipment operations using the live APIs.',
            icon: Icons.inventory_2_outlined,
            onTap: () => context.push('/admin/supplies'),
          ),
        ],
      ),
    );
  }
}

class _AdminDestination extends StatelessWidget {
  final String title;
  final String subtitle;
  final IconData icon;
  final VoidCallback onTap;

  const _AdminDestination({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}
