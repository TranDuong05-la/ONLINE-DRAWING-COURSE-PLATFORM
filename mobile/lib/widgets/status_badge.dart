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
    final roleColor = switch (role) {
      UserRole.admin => AppColors.accentPeach,
      UserRole.staff => AppColors.warning,
      UserRole.instructor => AppColors.primaryDark,
      UserRole.user => AppColors.primary,
    };
    final roleBackground = switch (role) {
      UserRole.admin => AppColors.accentPeachLight,
      UserRole.staff => AppColors.warningLight,
      UserRole.instructor => AppColors.primaryMint,
      UserRole.user => AppColors.pastelTeal,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: roleBackground,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: roleColor.withOpacity(0.3),
        ),
      ),
      child: Text(
        role.toServerString(),
        style: GoogleFonts.plusJakartaSans(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: roleColor,
          letterSpacing: 0.3,
        ),
      ),
    );
  }
}
