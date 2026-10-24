import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../core/constants/app_colors.dart';

class ArtNestLogo extends StatelessWidget {
  final double size;
  final bool showSubtitle;
  final Color? color;

  const ArtNestLogo({
    super.key,
    this.size = 36,
    this.showSubtitle = false,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    final effectiveColor = color ?? AppColors.primaryDark;

    return FittedBox(
      fit: BoxFit.scaleDown,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Container(
            width: size,
            height: size,
            decoration: BoxDecoration(
              color: AppColors.primaryMint,
              borderRadius: BorderRadius.circular(size * 0.3),
              boxShadow: [
                BoxShadow(
                  color: AppColors.primary.withOpacity(0.12),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: Center(
              child: Icon(
                Icons.auto_awesome,
                color: AppColors.primary,
                size: size * 0.55,
              ),
            ),
          ),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'artnest',
                style: GoogleFonts.plusJakartaSans(
                  fontSize: size * 0.65,
                  fontWeight: FontWeight.w800,
                  color: effectiveColor,
                  letterSpacing: -0.5,
                ),
              ),
              if (showSubtitle)
                Text(
                  'Học vẽ & Dụng cụ mỹ thuật',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: size * 0.28,
                    fontWeight: FontWeight.w500,
                    color: AppColors.textSecondary,
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
