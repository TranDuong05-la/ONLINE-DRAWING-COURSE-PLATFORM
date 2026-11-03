import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../core/constants/app_colors.dart';
import '../core/data/vietnam_divisions.dart';
import '../services/vietnam_address_service.dart';

class AddressSuggestionField extends StatefulWidget {
  final String label;
  final String hint;
  final TextEditingController controller;
  final IconData prefixIcon;
  final List<VietnamDivision> items;
  final bool isLoading;
  final bool enabled;
  final String? disabledHint;
  final ValueChanged<VietnamDivision> onSelected;
  final VoidCallback? onCleared;
  final String? Function(String?)? validator;

  const AddressSuggestionField({
    super.key,
    required this.label,
    required this.hint,
    required this.controller,
    required this.prefixIcon,
    required this.items,
    required this.onSelected,
    this.isLoading = false,
    this.enabled = true,
    this.disabledHint,
    this.onCleared,
    this.validator,
  });

  @override
  State<AddressSuggestionField> createState() => _AddressSuggestionFieldState();
}

class _AddressSuggestionFieldState extends State<AddressSuggestionField> {
  final FocusNode _focusNode = FocusNode();
  final LayerLink _layerLink = LayerLink();
  bool _isOpen = false;
  String _currentQuery = '';

  @override
  void initState() {
    super.initState();
    _currentQuery = widget.controller.text;
    widget.controller.addListener(_handleTextChange);
    _focusNode.addListener(_handleFocusChange);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_handleTextChange);
    _focusNode.removeListener(_handleFocusChange);
    _focusNode.dispose();
    super.dispose();
  }

  void _handleTextChange() {
    if (mounted) {
      setState(() {
        _currentQuery = widget.controller.text;
      });
    }
  }

  void _handleFocusChange() {
    if (!widget.enabled) return;
    if (_focusNode.hasFocus) {
      setState(() {
        _isOpen = true;
      });
    } else {
      // Đóng gợi ý sau độ trễ ngắn để tránh xung đột khi bấm chọn item
      Future.delayed(const Duration(milliseconds: 200), () {
        if (mounted && !_focusNode.hasFocus) {
          setState(() {
            _isOpen = false;
          });
        }
      });
    }
  }

  void _toggleDropdown() {
    if (!widget.enabled) return;
    setState(() {
      _isOpen = !_isOpen;
      if (_isOpen) {
        _focusNode.requestFocus();
      } else {
        _focusNode.unfocus();
      }
    });
  }

  void _selectItem(VietnamDivision item) {
    widget.controller.text = item.name;
    setState(() {
      _currentQuery = item.name;
      _isOpen = false;
    });
    _focusNode.unfocus();
    widget.onSelected(item);
  }

  void _handleClear() {
    widget.controller.clear();
    setState(() {
      _currentQuery = '';
      _isOpen = false;
    });
    widget.onCleared?.call();
  }

  @override
  Widget build(BuildContext context) {
    final filtered = VietnamAddressService.filterDivisions(
      widget.items,
      _currentQuery,
    );

    return CompositedTransformTarget(
      link: _layerLink,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Text(
                widget.label,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: widget.enabled
                      ? AppColors.textPrimary
                      : AppColors.textSecondary.withOpacity(0.6),
                ),
              ),
              const SizedBox(width: 4),
              const Text(
                '*',
                style: TextStyle(
                  color: AppColors.error,
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                ),
              ),
              if (widget.isLoading) ...[
                const SizedBox(width: 8),
                const SizedBox(
                  width: 12,
                  height: 12,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    valueColor: AlwaysStoppedAnimation<Color>(AppColors.primary),
                  ),
                ),
                const SizedBox(width: 6),
                Text(
                  'Đang tải...',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 11,
                    color: AppColors.textSecondary,
                  ),
                ),
              ],
            ],
          ),
          const SizedBox(height: 6),

          // Ô nhập liệu
          TextFormField(
            controller: widget.controller,
            focusNode: _focusNode,
            enabled: widget.enabled,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color: widget.enabled ? AppColors.textPrimary : Colors.grey[500],
            ),
            decoration: InputDecoration(
              hintText: !widget.enabled && widget.disabledHint != null
                  ? widget.disabledHint
                  : widget.hint,
              hintStyle: GoogleFonts.plusJakartaSans(
                fontSize: 13,
                color: widget.enabled
                    ? AppColors.textSecondary.withOpacity(0.7)
                    : Colors.grey[400],
              ),
              prefixIcon: Icon(
                widget.prefixIcon,
                color: widget.enabled ? AppColors.primary : Colors.grey[400],
                size: 20,
              ),
              suffixIcon: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (widget.enabled && widget.controller.text.isNotEmpty)
                    IconButton(
                      icon: const Icon(Icons.clear, size: 18, color: Colors.grey),
                      splashRadius: 18,
                      onPressed: _handleClear,
                      tooltip: 'Xóa',
                    ),
                  if (widget.enabled)
                    IconButton(
                      icon: Icon(
                        _isOpen
                            ? Icons.keyboard_arrow_up
                            : Icons.keyboard_arrow_down,
                        color: AppColors.primary,
                        size: 22,
                      ),
                      splashRadius: 18,
                      onPressed: _toggleDropdown,
                      tooltip: 'Danh sách gợi ý',
                    ),
                ],
              ),
              filled: true,
              fillColor: widget.enabled ? Colors.grey[50] : Colors.grey[100],
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 14,
                vertical: 14,
              ),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(color: Colors.grey[300]!),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(
                  color: _isOpen ? AppColors.primary : Colors.grey[300]!,
                  width: _isOpen ? 1.5 : 1,
                ),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(
                  color: AppColors.primary,
                  width: 1.8,
                ),
              ),
              disabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide(color: Colors.grey[200]!),
              ),
            ),
            validator: widget.validator,
          ),

          // Hộp danh sách gợi ý sổ xuống ngay bên dưới
          if (_isOpen && widget.enabled) ...[
            const SizedBox(height: 4),
            Container(
              constraints: const BoxConstraints(maxHeight: 220),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.primary.withOpacity(0.35)),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.08),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: filtered.isEmpty
                    ? Padding(
                        padding: const EdgeInsets.all(16),
                        child: Row(
                          children: [
                            const Icon(
                              Icons.info_outline,
                              size: 18,
                              color: AppColors.textSecondary,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'Không tìm thấy gợi ý phù hợp. Bạn có thể tự nhập tay địa chỉ này.',
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 12,
                                  color: AppColors.textSecondary,
                                ),
                              ),
                            ),
                          ],
                        ),
                      )
                    : Scrollbar(
                        thumbVisibility: true,
                        child: ListView.separated(
                          shrinkWrap: true,
                          padding: const EdgeInsets.symmetric(vertical: 4),
                          itemCount: filtered.length,
                          separatorBuilder: (_, _) => Divider(
                            height: 1,
                            thickness: 0.5,
                            color: Colors.grey[200],
                          ),
                          itemBuilder: (context, index) {
                            final item = filtered[index];
                            final isSelected = item.name == widget.controller.text;

                            return InkWell(
                              onTap: () => _selectItem(item),
                              child: Container(
                                color: isSelected
                                    ? AppColors.primaryMint.withOpacity(0.35)
                                    : Colors.transparent,
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 14,
                                  vertical: 10,
                                ),
                                child: Row(
                                  children: [
                                    Icon(
                                      Icons.location_on_outlined,
                                      size: 16,
                                      color: isSelected
                                          ? AppColors.primaryDark
                                          : Colors.grey[500],
                                    ),
                                    const SizedBox(width: 10),
                                    Expanded(
                                      child: Text(
                                        item.name,
                                        style: GoogleFonts.plusJakartaSans(
                                          fontSize: 13,
                                          fontWeight: isSelected
                                              ? FontWeight.w700
                                              : FontWeight.w500,
                                          color: isSelected
                                              ? AppColors.primaryDark
                                              : AppColors.textPrimary,
                                        ),
                                      ),
                                    ),
                                    if (isSelected)
                                      const Icon(
                                        Icons.check,
                                        size: 16,
                                        color: AppColors.primaryDark,
                                      ),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                      ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
