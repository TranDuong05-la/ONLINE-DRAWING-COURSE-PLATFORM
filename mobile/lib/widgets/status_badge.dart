import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../core/constants/app_colors.dart';
import '../models/user_model.dart';

class StatusBadge extends StatelessWidget {
  final bool isActive;

  const StatusBadge({super.key, required this.isActive});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: isActive ? AppColors.successLight : AppColors.errorLight,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: isActive ? AppColors.success.withOpacity(0.3) : AppColors.error.withOpacity(0.3),
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: isActive ? AppColors.success : AppColors.error,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 5),
          Text(
            isActive ? 'Đang hoạt động' : 'Đã khóa',
            style: GoogleFonts.plusJakartaSans(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: isActive ? AppColors.success : AppColors.error,
            ),
          ),
        ],
      ),
    );
  }
}

class RoleBadge extends StatelessWidget {
  final UserRole role;

  const RoleBadge({super.key, required this.role});

  @override
  Widget build(BuildContext context) {
    Color bg;
    Color border;
    Color text;
    String label;

    switch (role) {
      case UserRole.admin:
        label = 'ADMIN';
        bg = const Color(0xFFFDE8E1);
        border = const Color(0xFFE76F51).withOpacity(0.4);
        text = const Color(0xFFE76F51);
        break;
      case UserRole.instructor:
        label = 'GIẢNG VIÊN';
        bg = const Color(0xFFE0F2FE);
        border = const Color(0xFF0284C7).withOpacity(0.4);
        text = const Color(0xFF0369A1);
        break;
      case UserRole.staff:
        label = 'NHÂN VIÊN';
        bg = const Color(0xFFFEF3C7);
        border = const Color(0xFFD97706).withOpacity(0.4);
        text = const Color(0xFFB45309);
        break;
      case UserRole.user:
        label = 'HỌC VIÊN';
        bg = AppColors.pastelTeal;
        border = AppColors.primary.withOpacity(0.2);
        text = AppColors.primary;
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: border),
      ),
      child: Text(
        label,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: text,
          letterSpacing: 0.3,
        ),
      ),
    );
  }
}
