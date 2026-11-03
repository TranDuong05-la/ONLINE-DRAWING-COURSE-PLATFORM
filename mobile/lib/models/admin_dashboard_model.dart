class AdminDashboardModel {
  final int totalUsers;
  final int totalInstructors;
  final int totalStaff;
  final int totalAdmins;
  final int activeUsers;
  final int inactiveUsers;
  final int totalCourses;
  final int totalOrders;
  final double totalRevenue;

  AdminDashboardModel({
    required this.totalUsers,
    required this.totalInstructors,
    required this.totalStaff,
    required this.totalAdmins,
    required this.activeUsers,
    required this.inactiveUsers,
    required this.totalCourses,
    required this.totalOrders,
    required this.totalRevenue,
  });

  factory AdminDashboardModel.fromJson(Map<String, dynamic> json) {
    return AdminDashboardModel(
      totalUsers: (json['totalUsers'] as num?)?.toInt() ?? 0,
      totalInstructors: (json['totalInstructors'] as num?)?.toInt() ?? 0,
      totalStaff: (json['totalStaff'] as num?)?.toInt() ?? 0,
      totalAdmins: (json['totalAdmins'] as num?)?.toInt() ?? 0,
      activeUsers: (json['activeUsers'] as num?)?.toInt() ?? 0,
      inactiveUsers: (json['inactiveUsers'] as num?)?.toInt() ?? 0,
      totalCourses: (json['totalCourses'] as num?)?.toInt() ?? 0,
      totalOrders: (json['totalOrders'] as num?)?.toInt() ?? 0,
      totalRevenue: (json['totalRevenue'] as num?)?.toDouble() ?? 0.0,
    );
  }
}
