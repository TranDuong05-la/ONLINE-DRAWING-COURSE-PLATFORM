/// Khu vực đang làm việc của các màn quản lý dùng chung giữa ADMIN và giảng viên (quản lý khóa học, chấm bài).
///
/// Giảng viên dùng lại đúng các màn của admin nhưng gọi /api/instructor/** (backend chỉ cho thao tác trên khóa
/// được phân công) và ẩn các nút chỉ ADMIN được làm: tạo khóa, sửa giá, xuất bản / ẩn khóa, quà tặng, combo,
/// cấp / thu hồi quyền học. Màn "Khu vực Giảng viên" bật cờ khi mở và tắt khi đóng.
class StaffArea {
  StaffArea._();

  static bool instructor = false;

  /// Tiền tố API: '/api/instructor' hoặc '/api/admin'.
  static String get api => instructor ? '/api/instructor' : '/api/admin';
}
